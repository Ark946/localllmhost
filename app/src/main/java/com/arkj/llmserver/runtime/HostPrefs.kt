// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llmserver.runtime

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent state for the LLM host app (selected model, custom URL, and the
 * backend-health quarantine markers previously stored in the agent app's KVUtils).
 */
object HostPrefs {

    private const val FILE = "llm_host_prefs"
    private const val KEY_SELECTED_MODEL_ID = "selected_model_id"
    private const val KEY_CUSTOM_MODEL_URL = "custom_model_url"
    private const val KEY_LOCAL_BACKEND_PREFERENCE = "local_backend_preference"
    private const val KEY_ALLOW_GPU_LOW_MEMORY = "allow_gpu_low_memory"
    private const val KEY_LOCAL_CPU_SAFE_DEVICE = "local_cpu_safe_device"
    private const val KEY_LOCAL_CPU_SAFE_REASON = "local_cpu_safe_reason"
    private const val KEY_LOCAL_CPU_SAFE_AT = "local_cpu_safe_at"
    private const val KEY_LOCAL_GPU_VERIFIED_DEVICE = "local_gpu_verified_device"
    private const val KEY_LOCAL_GPU_VERIFIED_AT = "local_gpu_verified_at"
    private const val KEY_PENDING_GPU_INIT_DEVICE = "pending_gpu_init_device"
    private const val KEY_PENDING_GPU_INIT_MODEL = "pending_gpu_init_model"
    private const val KEY_PENDING_GPU_INIT_AT = "pending_gpu_init_at"
    private const val KEY_PENDING_GPU_INIT_PID = "pending_gpu_init_pid"

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        if (prefs == null) {
            prefs = appContext!!.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    /** Application context for host-side runtime queries (RAM, storage). Null before init(). */
    fun appContext(): Context? = appContext

    private fun p(): SharedPreferences = requireNotNull(prefs) {
        "HostPrefs.init(context) must be called before use"
    }

    fun getSelectedModelId(): String = p().getString(KEY_SELECTED_MODEL_ID, "") ?: ""
    fun setSelectedModelId(value: String) = p().edit().putString(KEY_SELECTED_MODEL_ID, value).apply()

    fun getCustomModelUrl(): String = p().getString(KEY_CUSTOM_MODEL_URL, "") ?: ""
    fun setCustomModelUrl(value: String) = p().edit().putString(KEY_CUSTOM_MODEL_URL, value).apply()

    fun getLocalBackendPreference(): String = p().getString(KEY_LOCAL_BACKEND_PREFERENCE, "") ?: ""
    fun setLocalBackendPreference(value: String) = p().edit().putString(KEY_LOCAL_BACKEND_PREFERENCE, value).apply()

    /** Debug override: allow GPU even on low-RAM devices (default false = GPU unsafe there). */
    fun getAllowGpuLowMemory(): Boolean = p().getBoolean(KEY_ALLOW_GPU_LOW_MEMORY, false)
    fun setAllowGpuLowMemory(value: Boolean) = p().edit().putBoolean(KEY_ALLOW_GPU_LOW_MEMORY, value).apply()

    fun getLocalCpuSafeDevice(): String = p().getString(KEY_LOCAL_CPU_SAFE_DEVICE, "") ?: ""
    fun setLocalCpuSafeDevice(value: String) = p().edit().putString(KEY_LOCAL_CPU_SAFE_DEVICE, value).apply()

    fun getLocalCpuSafeReason(): String = p().getString(KEY_LOCAL_CPU_SAFE_REASON, "") ?: ""
    fun setLocalCpuSafeReason(value: String) = p().edit().putString(KEY_LOCAL_CPU_SAFE_REASON, value).apply()

    fun getLocalCpuSafeAt(): Long = p().getLong(KEY_LOCAL_CPU_SAFE_AT, 0L)
    fun setLocalCpuSafeAt(value: Long) = p().edit().putLong(KEY_LOCAL_CPU_SAFE_AT, value).apply()

    fun getLocalGpuVerifiedDevice(): String = p().getString(KEY_LOCAL_GPU_VERIFIED_DEVICE, "") ?: ""
    fun setLocalGpuVerifiedDevice(value: String) = p().edit().putString(KEY_LOCAL_GPU_VERIFIED_DEVICE, value).apply()

    fun getLocalGpuVerifiedAt(): Long = p().getLong(KEY_LOCAL_GPU_VERIFIED_AT, 0L)
    fun setLocalGpuVerifiedAt(value: Long) = p().edit().putLong(KEY_LOCAL_GPU_VERIFIED_AT, value).apply()

    fun getPendingLocalGpuInitDevice(): String = p().getString(KEY_PENDING_GPU_INIT_DEVICE, "") ?: ""
    fun setPendingLocalGpuInitDevice(value: String) = p().edit().putString(KEY_PENDING_GPU_INIT_DEVICE, value).apply()

    fun getPendingLocalGpuInitModel(): String = p().getString(KEY_PENDING_GPU_INIT_MODEL, "") ?: ""
    fun setPendingLocalGpuInitModel(value: String) = p().edit().putString(KEY_PENDING_GPU_INIT_MODEL, value).apply()

    fun getPendingLocalGpuInitAt(): Long = p().getLong(KEY_PENDING_GPU_INIT_AT, 0L)
    fun setPendingLocalGpuInitAt(value: Long) = p().edit().putLong(KEY_PENDING_GPU_INIT_AT, value).apply()

    fun getPendingLocalGpuInitPid(): Int = p().getInt(KEY_PENDING_GPU_INIT_PID, 0)
    fun setPendingLocalGpuInitPid(value: Int) = p().edit().putInt(KEY_PENDING_GPU_INIT_PID, value).apply()

    fun clearLocalCpuSafeMode() {
        p().edit()
            .remove(KEY_LOCAL_CPU_SAFE_DEVICE)
            .remove(KEY_LOCAL_CPU_SAFE_REASON)
            .remove(KEY_LOCAL_CPU_SAFE_AT)
            .apply()
    }

    fun clearLocalGpuVerified() {
        p().edit()
            .remove(KEY_LOCAL_GPU_VERIFIED_DEVICE)
            .remove(KEY_LOCAL_GPU_VERIFIED_AT)
            .apply()
    }

    fun clearPendingLocalGpuInit() {
        p().edit()
            .remove(KEY_PENDING_GPU_INIT_DEVICE)
            .remove(KEY_PENDING_GPU_INIT_MODEL)
            .remove(KEY_PENDING_GPU_INIT_AT)
            .remove(KEY_PENDING_GPU_INIT_PID)
            .apply()
    }
}
