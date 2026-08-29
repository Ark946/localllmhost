// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llmserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import androidx.core.app.NotificationCompat
import com.arkj.llm.contract.ChatMessageCodec
import com.arkj.llm.contract.ILlmService
import com.arkj.llm.contract.ILlmServiceCallback
import com.arkj.llmserver.runtime.HostPrefs
import com.arkj.llmserver.runtime.LocalModelManager
import com.arkj.llmserver.runtime.XLog

/**
 * Foreground service exposing the on-device LLM to agent apps over AIDL.
 * Binding is restricted to apps signed with the same key via the
 * BIND_LLM_SERVICE signature permission declared in the manifest.
 */
class LlmHostService : Service() {

    companion object {
        private const val TAG = "LlmHostService"
        private const val CHANNEL_ID = "llm_host_service"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var dispatcher: SessionDispatcher
    private val callbacks = RemoteCallbackList<ILlmServiceCallback>()

    override fun onCreate() {
        super.onCreate()
        HostPrefs.init(this)
        dispatcher = SessionDispatcher(applicationContext).also { d ->
            d.notifier = object : SessionDispatcher.Notifier {
                override fun onPartialText(handle: String, text: String) {
                    broadcast { it.onPartialText(handle, text) }
                }

                override fun onQueueStateChanged(handle: String, position: Int) {
                    broadcast { it.onQueueStateChanged(handle, position) }
                }

                override fun onSessionInvalidated(handle: String, reason: String) {
                    broadcast { it.onSessionInvalidated(handle, reason) }
                }
            }
        }
        startForegroundInternal()
        XLog.i(TAG, "onCreate: LLM host service ready (${LocalModelManager.selectedModel(this)?.displayName ?: "no model"})")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        dispatcher.invalidateAll("service destroyed")
        callbacks.kill()
        XLog.i(TAG, "onDestroy")
        super.onDestroy()
    }

    private val binder = object : ILlmService.Stub() {

        override fun openSession(
            clientId: String,
            sessionId: String,
            systemPrompt: String,
            toolsJson: String,
            temperature: Double,
        ): String {
            XLog.i(TAG, "openSession: client=$clientId session=$sessionId")
            return dispatcher.openSession(clientId, sessionId, systemPrompt, toolsJson, temperature)
        }

        override fun chat(handle: String, messagesJson: String): String {
            val messages = ChatMessageCodec.decode(messagesJson)
            XLog.d(TAG, "chat: handle=$handle messages=${messages.size}")
            val future = dispatcher.chat(handle, messages)
            return try {
                future.get().encode()
            } catch (e: Exception) {
                XLog.e(TAG, "chat failed for $handle", e)
                com.arkj.llm.contract.ChatResult.error("Inference failed: ${e.message}")
            }
        }

        override fun singleShot(systemPrompt: String, prompt: String, temperature: Double): String {
            XLog.d(TAG, "singleShot: prompt=${prompt.take(60)}...")
            val future = dispatcher.singleShot(systemPrompt, prompt, temperature)
            return try {
                future.get().encode()
            } catch (e: Exception) {
                XLog.e(TAG, "singleShot failed", e)
                com.arkj.llm.contract.ChatResult.error("Inference failed: ${e.message}")
            }
        }

        override fun closeSession(handle: String) {
            dispatcher.closeSession(handle)
        }

        override fun getModelStatus(): String = dispatcher.modelStatusJson()

        override fun debugAction(action: String, argsJson: String): String {
            return DebugActions.handle(action, argsJson, this@LlmHostService)
        }

        override fun registerCallback(callback: ILlmServiceCallback) {
            callbacks.register(callback)
            XLog.i(TAG, "registerCallback: ${callbacks.registeredCallbackCount} registered")
        }

        override fun unregisterCallback(callback: ILlmServiceCallback) {
            callbacks.unregister(callback)
        }
    }

    private fun broadcast(block: (ILlmServiceCallback) -> Unit) {
        val count = callbacks.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    block(callbacks.getBroadcastItem(i))
                } catch (e: Exception) {
                    XLog.w(TAG, "broadcast: callback failed", e)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun startForegroundInternal() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "LLM Host Service",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Keeps the on-device model available for agent apps"
                    setShowBadge(false)
                }
            )
        }

        val model = LocalModelManager.selectedModel(this)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ArkLlm Host")
            .setContentText("${model?.displayName ?: "No model selected"} · serving agent apps")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
