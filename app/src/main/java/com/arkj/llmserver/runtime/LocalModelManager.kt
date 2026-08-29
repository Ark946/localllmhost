// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llmserver.runtime

import android.content.Context
import android.os.StatFs
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages on-device LLM model downloads and storage for the LLM host app.
 * Ported from the agent app; the user picks one active model in the host UI
 * and all bound agent clients are served with it.
 */
object LocalModelManager {

    private const val TAG = "LocalModelManager"
    private const val SIZE_TOLERANCE_BYTES = 32L * 1024L * 1024L

    data class ModelInfo(
        val id: String,
        val displayName: String,
        val url: String,
        val fileName: String,
        val sizeBytes: Long,
        val minRamGb: Int,
        /** True when this model came from a user-supplied custom URL. */
        val isCustom: Boolean = false
    )

    data class DeviceSupport(
        val deviceRamGb: Int,
        val minimumBuiltInRamGb: Int,
        val bestSupportedModel: ModelInfo?,
    )

    data class CatalogEntry(
        val model: ModelInfo,
        val isDownloaded: Boolean,
        val isSupported: Boolean,
        val path: String?,
    )

    val AVAILABLE_MODELS = listOf(
        ModelInfo(
            id = "gemma4-e2b",
            displayName = "Gemma 4 E2B - 2.6GB",
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_580_000_000L,
            minRamGb = 8
        ),
        ModelInfo(
            id = "gemma4-e4b",
            displayName = "Gemma 4 E4B - 3.6GB",
            url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            fileName = "gemma-4-E4B-it.litertlm",
            sizeBytes = 3_650_000_000L,
            minRamGb = 10
        ),
    )

    fun recommendedModel(context: Context): ModelInfo {
        val totalRamGb = getDeviceRamGb(context)
        return if (totalRamGb >= 12) {
            AVAILABLE_MODELS.first { it.id == "gemma4-e4b" }
        } else {
            AVAILABLE_MODELS.first { it.id == "gemma4-e2b" }
        }
    }

    fun getDeviceRamGb(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return (memInfo.totalMem / (1024L * 1024L * 1024L)).toInt() + 1
    }

    fun deviceSupport(context: Context): DeviceSupport {
        val deviceRamGb = getDeviceRamGb(context)
        return DeviceSupport(
            deviceRamGb = deviceRamGb,
            minimumBuiltInRamGb = AVAILABLE_MODELS.minOf { it.minRamGb },
            bestSupportedModel = AVAILABLE_MODELS
                .filter { it.minRamGb <= deviceRamGb }
                .maxByOrNull { it.minRamGb }
        )
    }

    fun isModelSupportedOnDevice(context: Context, model: ModelInfo): Boolean {
        return deviceSupport(context).deviceRamGb >= model.minRamGb
    }

    fun catalog(context: Context): List<CatalogEntry> {
        val support = deviceSupport(context)
        val builtIns = AVAILABLE_MODELS.map { model ->
            CatalogEntry(
                model = model,
                isDownloaded = isModelDownloaded(context, model),
                isSupported = model.minRamGb <= support.deviceRamGb,
                path = getModelPath(context, model),
            )
        }
        val custom = customModel()?.let { model ->
            listOf(
                CatalogEntry(
                    model = model,
                    isDownloaded = isModelDownloaded(context, model),
                    isSupported = true, // user opted in - don't gate on RAM heuristic
                    path = getModelPath(context, model),
                )
            )
        } ?: emptyList()
        return builtIns + custom
    }

    /** The model the host is currently configured to serve. */
    fun selectedModel(context: Context): ModelInfo? {
        val selectedId = HostPrefs.getSelectedModelId()
        catalog(context).firstOrNull { it.model.id == selectedId && it.isDownloaded }?.let { return it.model }
        // Fallback: the recommended model if already downloaded, else any downloaded entry
        val recommended = recommendedModel(context)
        val entries = catalog(context).filter { it.isDownloaded }
        return entries.firstOrNull { it.model.id == recommended.id }?.model
            ?: entries.firstOrNull()?.model
    }

    fun selectModel(modelId: String) {
        HostPrefs.setSelectedModelId(modelId)
        XLog.i(TAG, "selectModel: $modelId")
    }

    /** Synthetic ModelInfo for the user's custom URL, or null if not set. */
    fun customModel(): ModelInfo? {
        val url = HostPrefs.getCustomModelUrl()
        if (url.isBlank()) return null
        val fileName = url.substringAfterLast('/').ifBlank { "custom-model.bin" }
            .let { name ->
                val q = name.indexOf('?')
                if (q > 0) name.substring(0, q) else name
            }
        return ModelInfo(
            id = "custom-local",
            displayName = "Custom: $fileName",
            url = url,
            fileName = fileName,
            sizeBytes = 0L,
            minRamGb = 0,
            isCustom = true,
        )
    }

    interface DownloadCallback {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long)
        fun onComplete(modelPath: String)
        fun onError(error: String)
    }

    data class ModelStorageDiagnostics(
        val selectedDir: String?,
        val selectedAvailableBytes: Long?,
        val selectedError: String?,
        val externalDir: String,
        val externalStatus: String,
        val internalDir: String,
        val internalStatus: String,
    )

    fun getModelDir(context: Context): File {
        return resolveUsableModelDir(
            externalRoot = context.getExternalFilesDir(null),
            internalRoot = context.filesDir,
        )
    }

    internal fun resolveUsableModelDir(
        externalRoot: File?,
        internalRoot: File,
        canWriteDirectory: (File) -> Boolean = ::canWriteToDirectory,
    ): File {
        val externalDir = externalRoot?.let { File(it, "models") }
        if (externalDir != null && prepareModelDirectory(externalDir, canWriteDirectory)) {
            return externalDir
        }

        val internalDir = File(internalRoot, "models")
        if (prepareModelDirectory(internalDir, canWriteDirectory)) {
            return internalDir
        }

        throw IllegalStateException(
            "Could not create model storage directory at ${externalDir?.absolutePath ?: "(no external dir)"} or ${internalDir.absolutePath}"
        )
    }

    fun storageDiagnostics(context: Context): ModelStorageDiagnostics {
        val externalDir = context.getExternalFilesDir(null)?.let { File(it, "models") }
        val internalDir = File(context.filesDir, "models")
        val selected = runCatching { getModelDir(context) }

        return ModelStorageDiagnostics(
            selectedDir = selected.getOrNull()?.absolutePath,
            selectedAvailableBytes = selected.getOrNull()?.let { availableBytes(it) },
            selectedError = selected.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" },
            externalDir = externalDir?.absolutePath ?: "(no external files dir)",
            externalStatus = describeModelDirectory(externalDir),
            internalDir = internalDir.absolutePath,
            internalStatus = describeModelDirectory(internalDir),
        )
    }

    private fun prepareModelDirectory(dir: File, canWriteDirectory: (File) -> Boolean): Boolean {
        if (!ensureDirectory(dir)) {
            XLog.w(TAG, "Model directory is not usable: could not create ${dir.absolutePath}")
            return false
        }
        if (!canWriteDirectory(dir)) {
            XLog.w(TAG, "Model directory is not usable: write probe failed for ${dir.absolutePath}")
            return false
        }
        return true
    }

    private fun ensureDirectory(dir: File): Boolean {
        return dir.isDirectory || dir.mkdirs() || dir.isDirectory
    }

    private fun canWriteToDirectory(dir: File): Boolean {
        val probe = File(dir, ".llmserver-write-probe")
        return runCatching {
            FileOutputStream(probe, false).use { output ->
                output.write(1)
            }
            if (probe.exists() && !probe.delete()) {
                XLog.w(TAG, "Could not delete model storage probe: ${probe.absolutePath}")
            }
            true
        }.getOrElse { e ->
            XLog.w(TAG, "Model storage write probe failed for ${dir.absolutePath}", e)
            false
        }
    }

    private fun describeModelDirectory(dir: File?): String {
        if (dir == null) return "unavailable"
        val stat = runCatching { StatFs(dir.absolutePath).availableBytes }
        return listOf(
            "exists=${dir.exists()}",
            "isDirectory=${dir.isDirectory}",
            "canRead=${dir.canRead()}",
            "canWrite=${dir.canWrite()}",
            "availableBytes=${stat.getOrNull() ?: "(unknown)"}",
        ).joinToString(", ")
    }

    private fun availableBytes(dir: File): Long? {
        return runCatching { StatFs(dir.absolutePath).availableBytes }.getOrNull()
    }

    fun isModelDownloaded(context: Context, model: ModelInfo): Boolean {
        val file = File(getModelDir(context), model.fileName)
        return isValidModelFile(file, model)
    }

    fun getModelPath(context: Context, model: ModelInfo): String? {
        val file = File(getModelDir(context), model.fileName)
        return if (isValidModelFile(file, model)) file.absolutePath else null
    }

    /** Resolve a model file the user picked via SAF/absolute path into the model dir. */
    fun linkLocalModel(context: Context, sourcePath: String): Boolean {
        val source = File(sourcePath)
        if (!source.exists() || !source.canRead() || source.length() < 1_048_576L) {
            XLog.w(TAG, "linkLocalModel: not a usable model file: $sourcePath")
            return false
        }
        val dir = try {
            getModelDir(context)
        } catch (e: Exception) {
            XLog.e(TAG, "linkLocalModel: no usable model dir", e)
            return false
        }
        val target = File(dir, source.name)
        if (target.absolutePath == source.absolutePath) return true
        return try {
            source.copyTo(target, overwrite = true)
            XLog.i(TAG, "linkLocalModel: copied ${source.name} (${source.length()} bytes) into model dir")
            true
        } catch (e: Exception) {
            XLog.e(TAG, "linkLocalModel: copy failed", e)
            false
        }
    }

    /**
     * Download a model from HuggingFace with progress reporting.
     * Supports resume via HTTP Range headers for partial downloads.
     * Must be called from a background thread.
     */
    fun downloadModel(
        context: Context,
        model: ModelInfo,
        callback: DownloadCallback
    ) {
        val modelDir = try {
            getModelDir(context)
        } catch (e: Exception) {
            XLog.e(TAG, "Could not prepare model storage", e)
            callback.onError("Could not prepare model storage: ${e.message}")
            return
        }
        val targetFile = File(modelDir, model.fileName)
        val tempFile = File(modelDir, "${model.fileName}.downloading")
        cleanupInvalidFiles(model, targetFile, tempFile)

        try {
            val stat = StatFs(modelDir.absolutePath)
            val availableBytes = stat.availableBytes
            val existingTempBytes = if (tempFile.exists()) tempFile.length() else 0L
            val bytesNeeded = if (model.sizeBytes > 0) model.sizeBytes - existingTempBytes else 0L
            if (bytesNeeded > 0 && availableBytes < bytesNeeded) {
                val needGb = String.format("%.1f", bytesNeeded / 1_000_000_000.0)
                val haveGb = String.format("%.1f", availableBytes / 1_000_000_000.0)
                XLog.e(TAG, "Not enough storage: need ${needGb}GB, have ${haveGb}GB available")
                callback.onError("Not enough storage: need $needGb GB free, only $haveGb GB available")
                return
            }
            XLog.d(TAG, "Storage check passed: need ${bytesNeeded / 1_000_000}MB, have ${availableBytes / 1_000_000}MB")
        } catch (e: Exception) {
            XLog.w(TAG, "Could not check storage, proceeding anyway", e)
        }

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

            val requestBuilder = Request.Builder().url(model.url)
            if (existingBytes > 0) {
                requestBuilder.addHeader("Range", "bytes=$existingBytes-")
                XLog.i(TAG, "Resuming download from byte $existingBytes")
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful && response.code != 206) {
                callback.onError("Download failed: HTTP ${response.code}")
                return
            }

            val isResumedResponse = existingBytes > 0 && response.code == 206
            if (existingBytes > 0 && !isResumedResponse) {
                XLog.w(TAG, "Server ignored Range request for ${model.fileName}; restarting download from scratch")
                tempFile.delete()
            }

            val totalBytes = if (isResumedResponse) {
                val contentRange = response.header("Content-Range")
                contentRange?.substringAfterLast("/")?.toLongOrNull() ?: model.sizeBytes
            } else {
                response.body?.contentLength() ?: model.sizeBytes
            }

            val body = response.body ?: run {
                callback.onError("Empty response body")
                return
            }

            val startingBytes = if (isResumedResponse) existingBytes else 0L
            val outputStream = FileOutputStream(tempFile, isResumedResponse)
            val buffer = ByteArray(8192)
            var downloadedBytes = startingBytes
            var lastReportTime = System.currentTimeMillis()
            var lastReportedBytes = startingBytes

            body.byteStream().use { input ->
                outputStream.use { output ->
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastReportTime >= 200) {
                            val elapsed = (now - lastReportTime) / 1000.0
                            val speed = ((downloadedBytes - lastReportedBytes) / elapsed).toLong()
                            callback.onProgress(downloadedBytes, totalBytes, speed)
                            lastReportTime = now
                            lastReportedBytes = downloadedBytes
                        }
                    }
                }
            }

            if (!isValidModelFile(tempFile, model)) {
                tempFile.delete()
                callback.onError("Downloaded file looks incomplete or corrupted. Please retry.")
                return
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                callback.onError("Download finished but the model could not be moved into place")
                return
            }

            XLog.i(TAG, "Model downloaded: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            callback.onComplete(targetFile.absolutePath)

        } catch (e: Exception) {
            XLog.e(TAG, "Download failed", e)
            callback.onError("Download failed: ${e.message}")
        }
    }

    fun deleteModel(context: Context, model: ModelInfo): Boolean {
        val file = File(getModelDir(context), model.fileName)
        val tempFile = File(getModelDir(context), "${model.fileName}.downloading")
        tempFile.delete()
        return if (file.exists()) file.delete() else true
    }

    private fun cleanupInvalidFiles(model: ModelInfo, targetFile: File, tempFile: File) {
        if (targetFile.exists() && !isValidModelFile(targetFile, model)) {
            XLog.w(TAG, "Removing invalid completed model file: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            targetFile.delete()
        }
        if (tempFile.exists() && tempFile.length() > expectedUpperBound(model)) {
            XLog.w(TAG, "Removing oversized partial download: ${tempFile.absolutePath} (${tempFile.length()} bytes)")
            tempFile.delete()
        }
    }

    private fun isValidModelFile(file: File, model: ModelInfo): Boolean {
        if (!file.exists()) return false
        val length = file.length()
        if (length <= 0L) return false
        if (model.isCustom) return length >= 1_048_576L
        return length in expectedLowerBound(model)..expectedUpperBound(model)
    }

    private fun expectedLowerBound(model: ModelInfo): Long {
        return (model.sizeBytes - maxOf(SIZE_TOLERANCE_BYTES, model.sizeBytes / 20)).coerceAtLeast(1L)
    }

    private fun expectedUpperBound(model: ModelInfo): Long {
        return model.sizeBytes + maxOf(SIZE_TOLERANCE_BYTES, model.sizeBytes / 20)
    }
}
