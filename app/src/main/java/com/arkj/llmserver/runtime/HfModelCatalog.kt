// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llmserver.runtime

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Lists the litert-lm text models published under the `litert-community` HuggingFace
 * organization. Uses the HF REST API (not the HTML page) and picks the generic
 * `.litertlm` file out of each repo's device-specific variants.
 */
object HfModelCatalog {

    private const val TAG = "HfModelCatalog"
    private const val LIST_URL = "https://huggingface.co/api/models?author=litert-community&full=true&limit=100"
    private const val RESOLVE_BASE = "https://huggingface.co"

    data class MarketplaceModel(
        val id: String,
        val name: String,
        val fileName: String,
        val fileUrl: String,
        val downloads: Long,
        val likes: Long,
    )

    private val gson = Gson()

    @Volatile
    private var cache: List<MarketplaceModel>? = null

    suspend fun fetch(forceRefresh: Boolean = false): List<MarketplaceModel> {
        cache?.takeUnless { forceRefresh }?.let { return it }
        val result = withContext(Dispatchers.IO) { fetchRemote() }
        cache = result
        return result
    }

    fun clearCache() {
        cache = null
    }

    private fun fetchRemote(): List<MarketplaceModel> {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(LIST_URL).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HuggingFace API returned HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IllegalStateException("Empty HuggingFace response")
            val array = gson.fromJson(body, JsonArray::class.java)
            return array.mapNotNull { parse(it.asJsonObject) }
        }
    }

    private fun parse(obj: JsonObject): MarketplaceModel? {
        if (obj.get("library_name")?.asString != "litert-lm") return null
        val id = obj.get("id")?.asString ?: return null
        val name = id.substringAfter('/').ifBlank { id }
        val fileName = pickModelFile(obj.getAsJsonArray("siblings")) ?: return null
        return MarketplaceModel(
            id = id,
            name = name,
            fileName = fileName,
            fileUrl = "$RESOLVE_BASE/$id/resolve/main/$fileName",
            downloads = obj.get("downloads")?.asLong ?: 0L,
            likes = obj.get("likes")?.asLong ?: 0L,
        )
    }

    /**
     * Pick the generic `.litertlm` file. Device/backend-specific builds (gpu, web,
     * mediatek, qualcomm, intel, Google Tensor, and bare SoC ids like mt6991/sm8550)
     * are excluded; large unquantized builds (f32/fp16) are avoided when a quantized
     * alternative exists; the shortest remaining name is the plainest, most portable build.
     */
    private fun pickModelFile(siblings: JsonArray?): String? {
        if (siblings == null || siblings.size() == 0) return null
        val files = siblings.mapNotNull { it.asJsonObject.get("rfilename")?.asString }
            .filter { it.endsWith(".litertlm") }
        if (files.isEmpty()) return null

        val deviceMarkers = listOf("gpu", "web", "mediatek", "qualcomm", "intel", "google", "tensor", "mt6", "mt7", "sm8")
        val heavyMarkers = listOf("f32", "fp32", "fp16")

        fun clean(f: String) = f.lowercase().let { lower ->
            deviceMarkers.none { lower.contains(it) }
        }

        val generic = files.filter(::clean).ifEmpty { files }
        val light = generic.filter { f -> heavyMarkers.none { f.lowercase().contains(it) } }
        return (light.ifEmpty { generic }).minByOrNull { it.length }
    }
}
