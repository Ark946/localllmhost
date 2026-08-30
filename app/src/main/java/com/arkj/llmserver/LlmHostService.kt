// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llmserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import androidx.core.app.NotificationCompat
import com.arkj.llm.contract.ChatMessageCodec
import com.arkj.llm.contract.ChatResult
import com.arkj.llm.contract.ILlmService
import com.arkj.llm.contract.ILlmServiceCallback
import com.arkj.llmserver.runtime.AppAccessStore
import com.arkj.llmserver.runtime.HostPrefs
import com.arkj.llmserver.runtime.LocalModelManager
import com.arkj.llmserver.runtime.XLog
import com.google.gson.JsonObject

/**
 * Foreground service exposing the on-device LLM to agent apps over AIDL.
 * Binding is open to any app holding the BIND_LLM_SERVICE permission; per-app
 * access is enforced per-call via [AppAccessStore] and prompted for consent.
 */
class LlmHostService : Service() {

    companion object {
        private const val TAG = "LlmHostService"
        private const val CHANNEL_ID = "llm_host_service"
        private const val NOTIFICATION_ID = 1001

        /** Whether the foreground service is currently alive (read by MainActivity for UI state). */
        @Volatile var isRunning = false
    }

    private lateinit var dispatcher: SessionDispatcher
    private val callbacks = RemoteCallbackList<ILlmServiceCallback>()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        HostPrefs.init(this)
        AppAccessStore.init(this)
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
        isRunning = false
        dispatcher.invalidateAll("service destroyed")
        callbacks.kill()
        XLog.i(TAG, "onDestroy")
        super.onDestroy()
    }

    private sealed interface Access {
        object Allowed : Access
        object Denied : Access
        object Pending : Access
    }

    /**
     * Resolve the calling package and its access state. Must be called
     * synchronously at the top of each Binder method, before any async work,
     * so that [Binder.getCallingUid] still sees the live transaction.
     */
    private fun callerAccess(): Pair<String, Access> {
        val uid = Binder.getCallingUid()
        val pkg = packageManager.getPackagesForUid(uid)?.firstOrNull()
            ?: return "" to Access.Denied
        if (pkg == packageName) return pkg to Access.Allowed // trust self
        return when (AppAccessStore.statusFor(pkg)) {
            AppAccessStore.Status.ALLOWED -> pkg to Access.Allowed
            AppAccessStore.Status.DENIED -> pkg to Access.Denied
            else -> {
                AppAccessStore.recordRequest(pkg)
                BindPrompt.requestAccess(pkg)
                pkg to Access.Pending
            }
        }
    }

    private val binder = object : ILlmService.Stub() {

        override fun openSession(
            clientId: String,
            sessionId: String,
            systemPrompt: String,
            toolsJson: String,
            temperature: Double,
        ): String {
            val (pkg, access) = callerAccess()
            if (access != Access.Allowed) {
                XLog.w(TAG, "openSession: access $access for $pkg")
                return ""
            }
            XLog.i(TAG, "openSession: client=$clientId session=$sessionId")
            return dispatcher.openSession(clientId, sessionId, systemPrompt, toolsJson, temperature)
        }

        override fun chat(handle: String, messagesJson: String): String {
            val (pkg, access) = callerAccess()
            if (access != Access.Allowed) {
                return ChatResult.error("LLM host access denied or awaiting approval for $pkg")
            }
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
            val (pkg, access) = callerAccess()
            if (access != Access.Allowed) {
                return ChatResult.error("LLM host access denied or awaiting approval for $pkg")
            }
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
            val (_, access) = callerAccess()
            if (access != Access.Allowed) return
            dispatcher.closeSession(handle)
        }

        override fun getModelStatus(): String {
            val (_, access) = callerAccess()
            if (access != Access.Allowed) {
                return JsonObject().apply {
                    addProperty("modelName", "none")
                    addProperty("modelId", "")
                    addProperty("modelPath", "")
                    addProperty("backendLabel", "not loaded")
                    addProperty("activeClients", 0)
                    addProperty("ready", false)
                }.toString()
            }
            return dispatcher.modelStatusJson()
        }

        override fun debugAction(action: String, argsJson: String): String {
            val (pkg, access) = callerAccess()
            if (access != Access.Allowed) {
                return JsonObject().apply {
                    addProperty("ok", false)
                    addProperty("detail", "access denied for $pkg")
                    addProperty("action", action)
                }.toString()
            }
            return DebugActions.handle(action, argsJson, this@LlmHostService)
        }

        override fun registerCallback(callback: ILlmServiceCallback) {
            val (_, access) = callerAccess()
            if (access != Access.Allowed) return
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
