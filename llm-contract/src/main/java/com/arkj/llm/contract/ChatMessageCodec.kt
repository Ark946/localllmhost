// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llm.contract

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage

/**
 * Wire codec shared by the LLM host app and agent apps.
 *
 * LangChain4j [ChatMessage]s cross the AIDL boundary as a JSON array of
 * plain objects; the host replays them into a LiteRT-LM Conversation.
 * Only fields the host needs are encoded (type + text + tool metadata).
 */
object ChatMessageCodec {

    private val GSON = Gson()

    fun encode(messages: List<ChatMessage>): String {
        return GSON.toJson(messages.map { encodeOne(it) })
    }

    fun decode(json: String): List<ChatMessage> {
        val raw: List<Map<String, Any?>> = try {
            GSON.fromJson(json, object : TypeToken<List<Map<String, Any?>>>() {}.type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        return raw.mapNotNull { decodeOne(it) }
    }

    private fun encodeOne(msg: ChatMessage): Map<String, Any?> {
        return when (msg) {
            is SystemMessage -> mapOf("type" to "system", "text" to msg.text())
            is UserMessage -> mapOf("type" to "user", "text" to msg.singleText())
            is AiMessage -> mapOf(
                "type" to "ai",
                "text" to msg.text(),
                "toolRequests" to msg.toolExecutionRequests().map {
                    mapOf("id" to it.id(), "name" to it.name(), "arguments" to it.arguments())
                }
            )
            is ToolExecutionResultMessage -> mapOf(
                "type" to "toolResult",
                "toolName" to msg.toolName(),
                "toolRequestId" to msg.id(),
                "text" to msg.text()
            )
            else -> mapOf("type" to "other", "text" to msg.toString())
        }
    }

    private fun decodeOne(raw: Map<String, Any?>): ChatMessage? {
        return when (raw["type"]) {
            "system" -> SystemMessage.from(raw["text"] as? String ?: return null)
            "user" -> UserMessage.from(raw["text"] as? String ?: return null)
            "ai" -> {
                val text = raw["text"] as? String ?: ""
                val requests = (raw["toolRequests"] as? List<*>)?.mapNotNull { r ->
                    val m = r as? Map<*, *> ?: return@mapNotNull null
                    val name = m["name"] as? String ?: return@mapNotNull null
                    ToolExecutionRequest.builder()
                        .id(m["id"] as? String ?: "remote_${System.nanoTime()}")
                        .name(name)
                        .arguments(m["arguments"] as? String ?: "{}")
                        .build()
                }.orEmpty()
                if (requests.isNotEmpty()) AiMessage.from(text, requests) else AiMessage.from(text)
            }
            "toolResult" -> {
                val toolName = raw["toolName"] as? String ?: return null
                ToolExecutionResultMessage.from(
                    raw["toolRequestId"] as? String ?: "remote_${System.nanoTime()}",
                    toolName,
                    raw["text"] as? String ?: ""
                )
            }
            else -> null
        }
    }
}

/**
 * Flattens a LangChain4j [ToolSpecification] into the simple JSON shape the
 * host hands to LiteRT-LM's OpenApiTool (name / description / parameters).
 */
object ToolSpecCodec {

    private val GSON = Gson()

    fun encode(specs: List<ToolSpecification>): String {
        return GSON.toJson(specs.map { encodeOne(it) })
    }

    data class ToolDecl(val name: String, val description: String, val parameters: JsonObject?)

    private fun encodeOne(spec: ToolSpecification): ToolDecl {
        val params = spec.parameters()
        val parameters = params?.let { schema ->
            JsonObject().apply {
                addProperty("type", "object")
                val properties = JsonObject()
                schema.properties()?.forEach { (name, sub) ->
                    properties.add(name, JsonObject().apply {
                        addProperty("description", sub.description() ?: "")
                        addProperty(
                            "type",
                            when (sub.javaClass.simpleName) {
                                "JsonIntegerSchema" -> "integer"
                                "JsonNumberSchema" -> "number"
                                "JsonBooleanSchema" -> "boolean"
                                else -> "string"
                            }
                        )
                    })
                }
                add("properties", properties)
                schema.required()?.let { required ->
                    add("required", GSON.toJsonTree(required))
                }
            }
        }
        return ToolDecl(
            name = spec.name(),
            description = spec.description() ?: "",
            parameters = parameters
        )
    }

    fun decode(json: String): List<ToolDecl> {
        return try {
            GSON.fromJson(json, object : TypeToken<List<ToolDecl>>() {}.type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** Result of a chat / singleShot call, JSON-encoded across the boundary. */
data class ChatResult(
    val text: String?,
    val toolCalls: List<ToolCall>,
    val error: String? = null,
) {
    data class ToolCall(val name: String, val argumentsJson: String)

    fun encode(): String {
        val obj = JsonObject().apply {
            addProperty("text", text)
            add("toolCalls", GSON.toJsonTree(toolCalls.map {
                JsonObject().apply {
                    addProperty("name", it.name)
                    addProperty("arguments", it.argumentsJson)
                }
            }))
            addProperty("error", error)
        }
        return GSON.toJson(obj)
    }

    companion object {
        private val GSON = Gson()

        fun decode(json: String): ChatResult {
            return try {
                val obj = GSON.fromJson(json, JsonObject::class.java)
                    ?: return ChatResult(null, emptyList(), "empty result")
                val text = obj.get("text")?.takeIf { !it.isJsonNull }?.asString
                val error = obj.get("error")?.takeIf { !it.isJsonNull }?.asString
                val callList = if (obj.get("toolCalls")?.isJsonArray == true) {
                    obj.getAsJsonArray("toolCalls").mapNotNull { el ->
                        val o = el.asJsonObject
                        val name = o.get("name")?.takeIf { !it.isJsonNull }?.asString
                            ?: return@mapNotNull null
                        ChatResult.ToolCall(
                            name,
                            o.get("arguments")?.takeIf { !it.isJsonNull }?.asString ?: "{}"
                        )
                    }
                } else emptyList()
                ChatResult(text, callList, error)
            } catch (e: Exception) {
                ChatResult(null, emptyList(), "decode failed: ${e.message}")
            }
        }

        fun error(message: String): String = ChatResult(null, emptyList(), message).encode()
    }
}
