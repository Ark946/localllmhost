// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llmserver

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.arkj.llmserver.runtime.HostPrefs
import com.arkj.llmserver.runtime.LocalBackendHealth
import com.arkj.llmserver.runtime.LocalModelManager
import com.arkj.llmserver.runtime.XLog

/**
 * Server side of the AIDL `debugAction` method. The agent app forwards its
 * backend debug commands here because the engine and its health markers now
 * live in the host process (see QA section L / backend_action ADB commands).
 */
object DebugActions {

    private const val TAG = "DebugActions"

    fun handle(action: String, argsJson: String, context: Context): String {
        val actionLower = action.lowercase()
        return try {
            val args = runCatching { JsonParser.parseString(argsJson).asJsonObject }.getOrNull()
            val result = when (actionLower) {
                "status" -> JsonObject().apply {
                    addProperty("ok", true)
                    addProperty("detail", LocalBackendHealth.debugStateSummary())
                }
                "force_cpu_safe" -> {
                    val reason = args?.get("reason")
                        ?.takeIf { !it.isJsonNull }
                        ?.asString?.trim().orEmpty()
                        .ifEmpty { "debug" }
                    LocalBackendHealth.debugForceCpuSafe(reason)
                    JsonObject().apply {
                        addProperty("ok", true)
                        addProperty("detail", LocalBackendHealth.debugStateSummary())
                    }
                }
                "clear_cpu_safe" -> {
                    LocalBackendHealth.debugClearCpuSafeMode()
                    JsonObject().apply {
                        addProperty("ok", true)
                        addProperty("detail", LocalBackendHealth.debugStateSummary())
                    }
                }
                "mark_pending_gpu_init" -> {
                    val modelPath = args?.get("modelPath")
                        ?.takeIf { !it.isJsonNull }
                        ?.asString?.trim().orEmpty()
                        .ifEmpty { "/debug/model.litertlm" }
                    LocalBackendHealth.markGpuInitStarted(modelPath)
                    JsonObject().apply {
                        addProperty("ok", true)
                        addProperty("detail", LocalBackendHealth.debugStateSummary())
                    }
                }
                "clear_pending_gpu_init" -> {
                    LocalBackendHealth.markGpuInitFinished()
                    JsonObject().apply {
                        addProperty("ok", true)
                        addProperty("detail", LocalBackendHealth.debugStateSummary())
                    }
                }
                "recover_pending_gpu_crash" -> {
                    val recovered = LocalBackendHealth.recoverPendingGpuCrashIfNeeded()
                    JsonObject().apply {
                        addProperty("ok", true)
                        addProperty("recovered", recovered)
                        addProperty("detail", LocalBackendHealth.debugStateSummary())
                    }
                }
                "allow_gpu_low_mem" -> {
                    // Override the low-RAM (<=8GB) GPU guard so a tester can
                    // exercise the GPU backend on a device that would otherwise
                    // be forced to CPU to avoid LMK OOM kills. Default true = allow.
                    val enabled = args?.get("enabled")
                        ?.takeIf { !it.isJsonNull }
                        ?.asBoolean ?: true
                    HostPrefs.setAllowGpuLowMemory(enabled)
                    XLog.w(TAG, "allow_gpu_low_mem=$enabled (low-RAM GPU guard overridden)")
                    JsonObject().apply {
                        addProperty("ok", true)
                        addProperty("detail", LocalBackendHealth.debugStateSummary())
                    }
                }
                "storage_diagnostics" -> {
                    val s = LocalModelManager.storageDiagnostics(context)
                    JsonObject().apply {
                        addProperty("ok", true)
                        addProperty("selectedDir", s.selectedDir ?: "")
                        addProperty("selectedAvailableBytes", s.selectedAvailableBytes ?: -1L)
                        addProperty("selectedError", s.selectedError ?: "")
                        addProperty("externalDir", s.externalDir)
                        addProperty("externalStatus", s.externalStatus)
                        addProperty("internalDir", s.internalDir)
                        addProperty("internalStatus", s.internalStatus)
                    }
                }
                else -> JsonObject().apply {
                    addProperty("ok", false)
                    addProperty("detail", "Unknown debugAction=$action")
                }
            }
            result.addProperty("action", actionLower)
            XLog.i(TAG, "debugAction: $actionLower -> ok=${result.get("ok").asBoolean}")
            result.toString()
        } catch (e: Exception) {
            XLog.e(TAG, "debugAction failed: $action", e)
            JsonObject().apply {
                addProperty("action", actionLower)
                addProperty("ok", false)
                addProperty("detail", "${e.javaClass.simpleName}: ${e.message}")
            }.toString()
        }
    }
}
