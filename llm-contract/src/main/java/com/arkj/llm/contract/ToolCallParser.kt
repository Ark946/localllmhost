// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llm.contract

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Extracts tool calls from raw local-model output text.
 *
 * Ported from the old in-process LocalLlmClient so both the host (native
 * response fallback) and any client (paranoia re-parse) share one parser.
 * Gemma 4 may emit several formats - see the pattern list below.
 */
object ToolCallParser {

    private val GSON = Gson()

    data class ParsedCall(val name: String, val argumentsJson: String)

    // Pattern 1: Standard <tool_call>...</tool_call> tags (preferred format)
    private val TOOL_CALL_PATTERN = Regex("""<tool_call>(.*?)</tool_call>""", RegexOption.DOT_MATCHES_ALL)

    // Pattern 2: Gemma 4 native trained token format: <|tool_call>call:name{key:<|"|>value<|"|>}<tool_call|>
    private val GEMMA4_NATIVE_PATTERN = Regex("""<\|tool_call>(.*?)<tool_call\|>""", RegexOption.DOT_MATCHES_ALL)

    // Pattern 2b: Gemma 4 native WITHOUT closing tag
    private val GEMMA4_NO_CLOSE_PATTERN = Regex("""<\|tool_call>(call:\w+[\(\{].*)""")

    // Pattern 3: Fenced code block format
    private val TOOL_CALL_BLOCK_PATTERN = Regex("""```tool_call\s*\n(.*?)\n\s*```""", RegexOption.DOT_MATCHES_ALL)

    // Pattern 4: Legacy functioncall/function_call prefix format
    private val FUNCTION_CALL_PATTERN = Regex("""(?:functioncall|function_call|tool_call)\s*:\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL)

    fun extract(text: String): List<ParsedCall> {
        val calls = mutableListOf<ParsedCall>()

        TOOL_CALL_PATTERN.findAll(text).forEach { match ->
            val content = match.groupValues[1].trim()
            if (content.startsWith("{")) {
                parseToolCallJson(content)?.let { calls.add(it) }
            } else {
                // tool_name{...} format - extract name and treat rest as arguments
                val nameEnd = content.indexOf('{')
                if (nameEnd > 0) {
                    val name = content.substring(0, nameEnd).trim()
                    var fixed = content.substring(nameEnd)
                    val open = fixed.count { it == '{' }
                    val close = fixed.count { it == '}' }
                    repeat(open - close) { fixed += "}" }
                    try {
                        val args = GSON.fromJson(fixed, Map::class.java) as Map<*, *>
                        calls.add(ParsedCall(name, GSON.toJson(args)))
                    } catch (_: Exception) {
                    }
                }
            }
        }
        if (calls.isNotEmpty()) return calls

        GEMMA4_NATIVE_PATTERN.findAll(text).forEach { match ->
            parseGemma4NativeCall(match.groupValues[1])?.let { calls.add(it) }
        }
        if (calls.isNotEmpty()) return calls

        GEMMA4_NO_CLOSE_PATTERN.findAll(text).forEach { match ->
            parseGemma4NativeCall(match.groupValues[1].trim())?.let { calls.add(it) }
        }
        if (calls.isNotEmpty()) return calls

        TOOL_CALL_BLOCK_PATTERN.findAll(text).forEach { match ->
            parseToolCallJson(match.groupValues[1])?.let { calls.add(it) }
        }
        if (calls.isNotEmpty()) return calls

        FUNCTION_CALL_PATTERN.findAll(text).forEach { match ->
            parseToolCallJson(match.groupValues[1], argsKey = "args")?.let { calls.add(it) }
        }
        return calls
    }

    /**
     * Parse Gemma 4's native token format: call:tool_name{key:<|"|>value<|"|>}
     * The <|"|> tokens are Gemma's quote markers - stripped and rebuilt as JSON.
     */
    fun parseGemma4NativeCall(rawContent: String): ParsedCall? {
        return try {
            val content = rawContent.trim()
            val nameMatch = Regex("""^call:(\w+)[\(\{]""").find(content) ?: return parseToolCallJson(content)
            val name = nameMatch.groupValues[1]

            val openChar = content[nameMatch.range.last]
            val closeChar = if (openChar == '{') '}' else ')'
            val paramsStart = content.indexOf(openChar)
            val paramsEnd = content.lastIndexOf(closeChar)
            if (paramsStart < 0 || paramsEnd <= paramsStart) return null
            val paramsRaw = content.substring(paramsStart + 1, paramsEnd)

            // Simple string arg like ("WhatsApp") - broadcast to common arg keys
            if (openChar == '(' && !paramsRaw.contains(':') && !paramsRaw.contains('=')) {
                val cleanVal = paramsRaw.trim().removeSurrounding("\"").removeSurrounding("<|\"", "\"|>")
                val args = JsonObject().apply {
                    listOf("app_name", "package_name", "text", "key", "summary", "contact", "message").forEach {
                        addProperty(it, cleanVal)
                    }
                }
                return ParsedCall(name, GSON.toJson(args))
            }

            val argsMap = mutableMapOf<String, String>()
            Regex("""(\w+):<\|"\|>(.*?)<\|"\|>""").findAll(paramsRaw).forEach {
                argsMap[it.groupValues[1]] = it.groupValues[2]
            }
            Regex("""(\w+)[=:]"([^"]*?)"""").findAll(paramsRaw).forEach {
                if (!argsMap.containsKey(it.groupValues[1])) argsMap[it.groupValues[1]] = it.groupValues[2]
            }
            Regex("""(\w+):([^,<}"=\s]+)""").findAll(paramsRaw).forEach {
                if (!argsMap.containsKey(it.groupValues[1])) argsMap[it.groupValues[1]] = it.groupValues[2]
            }
            ParsedCall(name, GSON.toJson(argsMap))
        } catch (_: Exception) {
            null
        }
    }

    fun parseToolCallJson(json: String, argsKey: String = "arguments"): ParsedCall? {
        return try {
            val trimmed = json.trim()
            // Multiple calls separated by commas: take only the FIRST (one tool per turn)
            val firstJson = if (trimmed.startsWith("{") && trimmed.contains("},{")) {
                var depth = 0
                var endIdx = 0
                for (i in trimmed.indices) {
                    when (trimmed[i]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                endIdx = i
                                break
                            }
                        }
                    }
                }
                trimmed.substring(0, endIdx + 1)
            } else {
                trimmed
            }

            var fixedJson = firstJson
            val openBraces = fixedJson.count { it == '{' }
            val closeBraces = fixedJson.count { it == '}' }
            repeat(openBraces - closeBraces) { fixedJson += "}" }

            val map = try {
                GSON.fromJson(fixedJson, Map::class.java) as Map<*, *>
            } catch (_: Exception) {
                // Regex fallback for malformed JSON
                val nameRegex = Regex(""""name"\s*:\s*"(\w+)"""")
                val n = nameRegex.find(fixedJson)?.groupValues?.get(1) ?: return null
                val argsRaw = Regex(""""arguments"\s*:\s*\{([^}]*)\}"""").find(fixedJson)?.groupValues?.get(1) ?: ""
                val argsMap = mutableMapOf<String, Any>()
                Regex(""""(\w+)"\s*:\s*"([^"]*?)"""").findAll(argsRaw).forEach {
                    argsMap[it.groupValues[1]] = it.groupValues[2]
                }
                mapOf("name" to n, "arguments" to argsMap)
            }
            val name = map["name"]?.toString() ?: return null
            val args = map[argsKey]
            val argsJson = if (args is Map<*, *>) GSON.toJson(args) else args?.toString() ?: "{}"
            ParsedCall(name, argsJson)
        } catch (_: Exception) {
            null
        }
    }

    /** Raw model output sometimes only reachable from inside an SDK parse-error message. */
    fun extractFromSdkParseError(errorMessage: String): String? {
        if (!errorMessage.contains("Failed to parse tool calls") || !errorMessage.contains("tool_call")) return null
        val rawOutput = errorMessage.substringAfter("from response: ").substringBefore("code block:")
            .ifEmpty { errorMessage.substringAfter("from response: ") }
        return rawOutput.trim().ifEmpty { null }
    }
}
