// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llm.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.arkj.llm.contract.ILlmService

/**
 * Binds to the LLM Host app's AIDL service and hands out a connected
 * [ILlmService] proxy. Reconnects automatically after the host process dies.
 *
 * The host is protected by a signature-level permission (BIND_LLM_SERVICE), so
 * only agent apps signed with the same key can bind.
 */
object LlmHostConnection {

    private const val TAG = "LlmHostConnection"
    const val HOST_PACKAGE = "com.arkj.llmserver"
    const val HOST_ACTION = "com.arkj.llm.contract.ILlmService"
    const val HOST_PERMISSION = "com.arkj.llmserver.permission.BIND_LLM_SERVICE"
    private const val CONNECT_TIMEOUT_MS = 6000L
    private const val CONNECT_RETRY_MS = 400L

    @Volatile
    private var service: ILlmService? = null

    @Volatile
    private var connecting = false

    private var bound = false
    private var appContext: Context? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { ILlmService.Stub.asInterface(it) }
            connecting = false
            XLog.i(TAG, "connected to LLM host: ${service != null}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Host process died - the binding will be re-established lazily on next call.
            XLog.w(TAG, "LLM host disconnected (process died)")
            service = null
        }

        override fun onBindingDied(name: ComponentName?) {
            XLog.w(TAG, "LLM host binding died")
            service = null
            connecting = false
        }
    }

    fun isConnected(): Boolean = service != null

    fun hostInstalled(context: Context): Boolean {
        return context.packageManager.getLaunchIntentForPackage(HOST_PACKAGE) != null
    }

    /**
     * Ensure the host service is bound and return the proxy.
     * Blocks until connected or the timeout elapses. Safe to call from any thread.
     */
    @Synchronized
    fun ensureConnected(context: Context): ILlmService? {
        service?.let { return it }

        if (!bound) {
            appContext = context.applicationContext
            bound = true
        }
        bind()

        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (service == null && System.currentTimeMillis() < deadline) {
            if (bound && !connecting) {
                bind() // previous binding died - retry
            }
            try {
                Thread.sleep(CONNECT_RETRY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        if (service == null) {
            XLog.e(TAG, "Timed out waiting for LLM host connection")
        }
        return service
    }

    private fun bind() {
        val intent = Intent(HOST_ACTION).setPackage(HOST_PACKAGE)
        connecting = true
        val ok = appContext?.bindService(intent, connection, Context.BIND_AUTO_CREATE) ?: false
        if (!ok) {
            XLog.e(TAG, "bindService to LLM host returned false (host not installed?)")
            connecting = false
        }
    }

    fun unbind() {
        if (bound && appContext != null) {
            try {
                appContext!!.unbindService(connection)
            } catch (_: Exception) {
            }
        }
        bound = false
        service = null
    }

    /** Call a remote method, reconnecting if the host died meanwhile. */
    fun <T> withHost(context: Context, fallback: String, block: (ILlmService) -> T): T? {
        val host = ensureConnected(context)
            ?: return null
        return try {
            block(host)
        } catch (e: RemoteException) {
            XLog.w(TAG, "Remote call failed ($fallback): ${e.message}")
            service = null
            null
        } catch (e: Exception) {
            XLog.w(TAG, "Remote call failed ($fallback): ${e.message}")
            null
        }
    }
}
