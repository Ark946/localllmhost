// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llmserver.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * GPU/CPU backend health state machine for the LLM host app.
 * Ported from the PokeClaw agent app; quarantine markers now live in the
 * host app's own HostPrefs (the process that owns the engine).
 */
object LocalBackendHealth {

    private const val TAG = "LocalBackendHealth"
    private const val CRASH_MARKER_MAX_AGE_MS = 1000L * 60L * 60L * 24L * 30L
    private const val VERIFIED_GPU_CPU_SAFE_RETRY_COOLDOWN_MS = 1000L * 60L * 60L * 24L

    /**
     * Devices with this much RAM or less cannot afford the GPU double-memory
     * footprint (OpenCL buffers + CPU weight copy) on top of a 2.6GB+ model.
     * Measured on HUAWEI NOH-AN00 (8GB): GPU load spikes to ~4.1GB PSS and LMK
     * kills the host (reason=3 LOW_MEMORY); CPU mode holds at ~2.6GB and survives.
     */
    private const val LOW_MEMORY_GPU_UNSAFE_RAM_GB = 8
    private val CONSERVATIVE_CPU_MANUFACTURERS = setOf("xiaomi", "redmi", "poco")
    private val CONSERVATIVE_CPU_MODELS = listOf(
        "xiaomi 15",
        "mi 15",
        "galaxy z fold4",
        "sm-f936",
        "z flip7",
        "sm-f766",
    )
    private val CONSERVATIVE_CPU_HARDWARE_HINTS = listOf(
        "mt",
        "mediatek",
        "dimensity",
    )

    fun currentDeviceKey(): String {
        val fingerprint = Build.FINGERPRINT?.trim().orEmpty()
        if (fingerprint.isNotEmpty()) return fingerprint
        return listOf(Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.HARDWARE)
            .filter { !it.isNullOrBlank() }
            .joinToString("|")
    }

    fun shouldForceCpu(preferCpu: Boolean): Boolean {
        recoverPendingGpuCrashIfNeeded()
        maybeRearmVerifiedGpu()
        val lowMemGpuUnsafe = isLowMemoryGpuUnsafe()
        val forceCpu = preferCpu ||
            HostPrefs.getLocalBackendPreference().equals("CPU", ignoreCase = true) ||
            isCpuSafeModeEnabled() ||
            shouldStartCpuConservatively() ||
            lowMemGpuUnsafe
        if (forceCpu && shouldStartCpuConservatively()) {
            XLog.w(TAG, "Using conservative CPU-first mode on ${deviceDescriptor()}")
        }
        if (lowMemGpuUnsafe && !isCpuSafeModeEnabled()) {
            XLog.w(TAG, "Low-RAM device (<=${LOW_MEMORY_GPU_UNSAFE_RAM_GB}GB): forcing CPU to avoid GPU OOM kill")
        }
        return forceCpu
    }

    fun isCpuSafeModeEnabled(): Boolean {
        return HostPrefs.getLocalCpuSafeDevice() == currentDeviceKey()
    }

    /**
     * GPU backend keeps a second copy of weights (OpenCL buffers) on top of the
     * CPU-mapped model. On 8GB-class devices that double footprint spikes PSS to
     * ~4GB during load and LMK kills the process mid-load (Huawei NOH-AN00 QA,
     * 2026-08-28: reason=3 LOW_MEMORY, 5 kills). This is an unconditional guard:
     * it beats the gpu_verified marker because the verified GPU still OOMs on
     * low-RAM devices. debugAction `allow_gpu_low_mem` overrides it for GPU testing.
     */
    fun isLowMemoryGpuUnsafe(): Boolean {
        if (HostPrefs.getAllowGpuLowMemory()) return false
        return isLowMemoryGpuUnsafe(deviceRamGb())
    }

    internal fun isLowMemoryGpuUnsafe(ramGb: Int?): Boolean {
        if (ramGb == null) return false
        return ramGb <= LOW_MEMORY_GPU_UNSAFE_RAM_GB
    }

    /** Total device RAM in GB (rounded up), cached. Null when HostPrefs not initialized yet. */
    private var cachedDeviceRamGb: Int? = null

    private fun deviceRamGb(): Int? {
        cachedDeviceRamGb?.let { return it }
        val context = HostPrefs.appContext() ?: return null
        val gb = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            (info.totalMem / (1024L * 1024L * 1024L)).toInt() + 1
        } catch (e: Exception) {
            XLog.w(TAG, "deviceRamGb: could not read RAM", e)
            null
        }
        cachedDeviceRamGb = gb
        return gb
    }

    fun cpuSafeReason(): String = HostPrefs.getLocalCpuSafeReason()

    fun hasVerifiedGpuSuccess(): Boolean {
        return HostPrefs.getLocalGpuVerifiedDevice() == currentDeviceKey() &&
            HostPrefs.getLocalGpuVerifiedAt() > 0L
    }

    fun debugStateSummary(): String {
        val pendingDevice = HostPrefs.getPendingLocalGpuInitDevice().ifBlank { "-" }
        val pendingModel = HostPrefs.getPendingLocalGpuInitModel().ifBlank { "-" }
        val pendingAt = HostPrefs.getPendingLocalGpuInitAt()
        val pendingPid = HostPrefs.getPendingLocalGpuInitPid()
        val cpuSafeDevice = HostPrefs.getLocalCpuSafeDevice().ifBlank { "-" }
        val gpuVerifiedDevice = HostPrefs.getLocalGpuVerifiedDevice().ifBlank { "-" }
        val gpuVerifiedAt = HostPrefs.getLocalGpuVerifiedAt()
        val backendPreference = HostPrefs.getLocalBackendPreference().ifBlank { "-" }
        val reason = cpuSafeReason().ifBlank { "-" }
        val cpuSafeAt = HostPrefs.getLocalCpuSafeAt()
        return buildString {
            append("device=").append(currentDeviceKey())
            append(", cpuSafe=").append(isCpuSafeModeEnabled())
            append(", cpuSafeDevice=").append(cpuSafeDevice)
            append(", backendPreference=").append(backendPreference)
            append(", reason=").append(reason)
            append(", cpuSafeAt=").append(cpuSafeAt)
            append(", gpuVerified=").append(hasVerifiedGpuSuccess())
            append(", gpuVerifiedDevice=").append(gpuVerifiedDevice)
            append(", gpuVerifiedAt=").append(gpuVerifiedAt)
            append(", conservativeCpu=").append(shouldStartCpuConservatively())
            append(", lowMemGpuUnsafe=").append(isLowMemoryGpuUnsafe())
            append(", allowGpuLowMem=").append(HostPrefs.getAllowGpuLowMemory())
            append(", pendingDevice=").append(pendingDevice)
            append(", pendingModel=").append(pendingModel)
            append(", pendingAt=").append(pendingAt)
            append(", pendingPid=").append(pendingPid)
        }
    }

    fun debugForceCpuSafe(reason: String = "debug") {
        enableCpuSafeMode(reason)
    }

    fun debugClearCpuSafeMode() {
        HostPrefs.clearLocalCpuSafeMode()
        if (HostPrefs.getLocalBackendPreference().equals("CPU", ignoreCase = true)) {
            HostPrefs.setLocalBackendPreference("")
        }
    }

    fun debugClearGpuVerified() {
        HostPrefs.clearLocalGpuVerified()
    }

    fun noteRecoverableGpuFailure(modelPath: String, error: Throwable?) {
        val reason = buildReason("gpu_failure", modelPath, error?.message)
        enableCpuSafeMode(reason)
        HostPrefs.clearPendingLocalGpuInit()
        XLog.w(TAG, "GPU backend marked unsafe for this device: $reason")
    }

    fun noteGpuInitSuccess(modelPath: String) {
        HostPrefs.setLocalGpuVerifiedDevice(currentDeviceKey())
        HostPrefs.setLocalGpuVerifiedAt(System.currentTimeMillis())
        HostPrefs.clearPendingLocalGpuInit()
        XLog.i(TAG, "GPU backend verified healthy for ${modelPath.substringAfterLast('/')}")
    }

    fun markGpuInitStarted(modelPath: String) {
        HostPrefs.setPendingLocalGpuInitDevice(currentDeviceKey())
        HostPrefs.setPendingLocalGpuInitModel(modelPath)
        HostPrefs.setPendingLocalGpuInitAt(System.currentTimeMillis())
        HostPrefs.setPendingLocalGpuInitPid(Process.myPid())
        XLog.i(TAG, "Marked GPU init pending for ${modelPath.substringAfterLast('/')}")
    }

    fun markGpuInitFinished() {
        HostPrefs.clearPendingLocalGpuInit()
    }

    fun recoverPendingGpuCrashIfNeeded(): Boolean {
        val pendingDevice = HostPrefs.getPendingLocalGpuInitDevice()
        val pendingAt = HostPrefs.getPendingLocalGpuInitAt()
        val pendingPid = HostPrefs.getPendingLocalGpuInitPid()
        if (!shouldPromotePendingGpuCrash(currentDeviceKey(), pendingDevice, pendingAt, pendingPid, System.currentTimeMillis())) {
            return false
        }

        val modelPath = HostPrefs.getPendingLocalGpuInitModel()
        val reason = buildReason("gpu_init_crash", modelPath, "previous GPU engine init died before cleanup")
        enableCpuSafeMode(reason)
        HostPrefs.clearPendingLocalGpuInit()
        XLog.w(TAG, "Recovered pending GPU init crash; forcing CPU-safe mode for this device")
        return true
    }

    internal fun shouldPromotePendingGpuCrash(
        currentDeviceKey: String,
        pendingDeviceKey: String?,
        pendingAtMs: Long,
        pendingPid: Int,
        nowMs: Long,
        maxAgeMs: Long = CRASH_MARKER_MAX_AGE_MS,
    ): Boolean {
        if (pendingDeviceKey.isNullOrBlank()) return false
        if (pendingDeviceKey != currentDeviceKey) return false
        if (pendingAtMs <= 0L) return false
        if (pendingPid > 0 && pendingPid == Process.myPid()) return false
        return nowMs - pendingAtMs <= maxAgeMs
    }

    private fun enableCpuSafeMode(reason: String) {
        val now = System.currentTimeMillis()
        HostPrefs.setLocalCpuSafeDevice(currentDeviceKey())
        HostPrefs.setLocalCpuSafeReason(reason)
        HostPrefs.setLocalCpuSafeAt(now)
        HostPrefs.setLocalBackendPreference("CPU")
    }

    private fun maybeRearmVerifiedGpu(nowMs: Long = System.currentTimeMillis()) {
        if (!shouldRearmVerifiedGpu(
                isCpuSafeModeEnabled = isCpuSafeModeEnabled(),
                hasVerifiedGpuSuccess = hasVerifiedGpuSuccess(),
                hasPendingGpuInitMarker = hasPendingGpuInitMarker(),
                cpuSafeReason = cpuSafeReason(),
                cpuSafeAtMs = HostPrefs.getLocalCpuSafeAt(),
                nowMs = nowMs,
            )
        ) {
            return
        }

        XLog.w(TAG, "Re-arming verified GPU backend after stale CPU-safe quarantine on ${deviceDescriptor()}")
        HostPrefs.clearLocalCpuSafeMode()
        if (HostPrefs.getLocalBackendPreference().equals("CPU", ignoreCase = true)) {
            HostPrefs.setLocalBackendPreference("")
        }
    }

    private fun shouldStartCpuConservatively(): Boolean {
        val manufacturer = Build.MANUFACTURER?.trim()?.lowercase().orEmpty()
        val model = Build.MODEL?.trim()?.lowercase().orEmpty()
        val hardware = Build.HARDWARE?.trim()?.lowercase().orEmpty()
        return shouldConservativelyForceCpu(
            manufacturer = manufacturer,
            model = model,
            hardware = hardware,
            hasVerifiedGpuSuccess = hasVerifiedGpuSuccess(),
            isCpuSafeModeEnabled = isCpuSafeModeEnabled(),
        )
    }

    private fun deviceDescriptor(): String {
        return listOf(Build.MANUFACTURER, Build.MODEL, Build.HARDWARE)
            .filter { !it.isNullOrBlank() }
            .joinToString(" / ")
    }

    fun isConservativeCpuModeSuggested(): Boolean = shouldStartCpuConservatively()

    fun hasPendingGpuInitMarker(): Boolean {
        return shouldPromotePendingGpuCrash(
            currentDeviceKey = currentDeviceKey(),
            pendingDeviceKey = HostPrefs.getPendingLocalGpuInitDevice(),
            pendingAtMs = HostPrefs.getPendingLocalGpuInitAt(),
            pendingPid = HostPrefs.getPendingLocalGpuInitPid(),
            nowMs = System.currentTimeMillis(),
        )
    }

    internal fun shouldConservativelyForceCpu(
        manufacturer: String,
        model: String,
        hardware: String,
        hasVerifiedGpuSuccess: Boolean,
        isCpuSafeModeEnabled: Boolean,
    ): Boolean {
        if (hasVerifiedGpuSuccess) return false
        if (isCpuSafeModeEnabled) return false
        if (manufacturer in CONSERVATIVE_CPU_MANUFACTURERS) return true
        if (CONSERVATIVE_CPU_MODELS.any { model.contains(it) }) return true
        return CONSERVATIVE_CPU_HARDWARE_HINTS.any { hint ->
            hardware.contains(hint) || model.contains(hint)
        }
    }

    private fun buildReason(prefix: String, modelPath: String, detail: String?): String {
        val modelName = modelPath.substringAfterLast('/')
        return listOf(prefix, modelName, detail?.take(120))
            .filter { !it.isNullOrBlank() }
            .joinToString(": ")
    }

    internal fun shouldRearmVerifiedGpu(
        isCpuSafeModeEnabled: Boolean,
        hasVerifiedGpuSuccess: Boolean,
        hasPendingGpuInitMarker: Boolean,
        cpuSafeReason: String,
        cpuSafeAtMs: Long,
        nowMs: Long,
        cooldownMs: Long = VERIFIED_GPU_CPU_SAFE_RETRY_COOLDOWN_MS,
    ): Boolean {
        if (!isCpuSafeModeEnabled) return false
        if (!hasVerifiedGpuSuccess) return false
        if (hasPendingGpuInitMarker) return false
        if (!cpuSafeReason.startsWith("gpu_init_crash")) return false
        if (cpuSafeAtMs <= 0L) return false
        return nowMs - cpuSafeAtMs >= cooldownMs
    }
}
