// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llmserver.runtime

import com.arkj.llmserver.runtime.XLog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig

/**
 * Process-wide singleton that keeps a single LiteRT-LM Engine alive.
 * Ported from the PokeClaw agent app when local inference moved into the
 * LLM host app (one engine serves all bound agent clients).
 *
 * Thread safety: all mutations are @Synchronized - every dispatcher worker
 * and the single-shot path can both call getOrCreate() safely.
 */
object EngineHolder {

    private const val TAG = "EngineHolder"

    private var engine: Engine? = null
    private var currentModelPath: String? = null
    private var currentBackendLabel: String? = null

    private fun backendLabel(backend: Backend): String =
        if (backend is Backend.CPU) "CPU" else if (backend is Backend.GPU) "GPU" else backend.javaClass.simpleName

    @Synchronized
    @JvmOverloads
    fun getOrCreate(modelPath: String, cacheDir: String, backend: Backend = Backend.CPU()): Engine {
        val existing = engine
        val requestedBackendLabel = backendLabel(backend)
        if (existing != null && currentModelPath == modelPath && currentBackendLabel == requestedBackendLabel) {
            XLog.d(TAG, "getOrCreate: reusing engine for $modelPath (${currentBackendLabel ?: "unknown"})")
            return existing
        }

        // Different model or first call - close old engine first
        if (existing != null) {
            XLog.i(
                TAG,
                "getOrCreate: runtime changed (model=$currentModelPath/${currentBackendLabel ?: "?"} -> $modelPath/$requestedBackendLabel), closing old engine"
            )
            try {
                existing.close()
            } catch (e: Exception) {
                XLog.w(TAG, "getOrCreate: error closing old engine", e)
            }
            engine = null
            currentModelPath = null
        }

        XLog.i(TAG, "getOrCreate: creating new engine for $modelPath with $requestedBackendLabel")
        return try {
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = 8192,
                cacheDir = cacheDir
            )
            if (backend is Backend.GPU) {
                LocalBackendHealth.markGpuInitStarted(modelPath)
            }
            val newEngine = Engine(engineConfig).also { it.initialize() }
            if (backend is Backend.GPU) {
                LocalBackendHealth.markGpuInitFinished()
                LocalBackendHealth.noteGpuInitSuccess(modelPath)
            }
            engine = newEngine
            currentModelPath = modelPath
            currentBackendLabel = requestedBackendLabel
            XLog.i(TAG, "getOrCreate: engine ready for $modelPath ($currentBackendLabel)")
            newEngine
        } catch (e: Exception) {
            if (backend is Backend.GPU) {
                LocalBackendHealth.noteRecoverableGpuFailure(modelPath, e)
            } else {
                LocalBackendHealth.markGpuInitFinished()
            }
            XLog.e(TAG, "getOrCreate: failed to create engine for $modelPath", e)
            throw e
        }
    }

    /** Explicitly close and release the engine (model unloaded entirely). */
    @Synchronized
    fun close() {
        XLog.i(TAG, "close: releasing engine for $currentModelPath")
        try {
            engine?.close()
        } catch (e: Exception) {
            XLog.w(TAG, "close: error closing engine", e)
        }
        engine = null
        currentModelPath = null
        currentBackendLabel = null
        XLog.i(TAG, "close: done")
    }

    @Synchronized
    fun isReady(modelPath: String): Boolean = engine != null && currentModelPath == modelPath

    @Synchronized
    fun getBackendLabel(modelPath: String? = null): String? {
        return if (modelPath == null || currentModelPath == modelPath) currentBackendLabel else null
    }
}
