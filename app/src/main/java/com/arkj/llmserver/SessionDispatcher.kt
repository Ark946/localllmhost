// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llmserver

import android.content.Context
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.arkj.llm.contract.ChatMessageCodec
import com.arkj.llm.contract.ChatResult
import com.arkj.llm.contract.ToolCallParser
import com.arkj.llm.contract.ToolSpecCodec
import com.arkj.llmserver.runtime.LocalModelManager
import com.arkj.llmserver.runtime.LocalModelRuntime
import com.arkj.llmserver.runtime.XLog
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * Serializes inference across all bound agent clients.
 *
 * LiteRT-LM supports exactly one live session at a time, so all chat calls run
 * on a single executor. Each client session keeps its own message history; when
 * the active session switches, its conversation is closed and the new session's
 * history is replayed from scratch (the same incremental/rebuild strategy the
 * old in-process LocalLlmClient used, now per-session and host-side).
 */
class SessionDispatcher(private val context: Context) {

    companion object {
        private const val TAG = "SessionDispatcher"
        private const val REBUILD_SEND_COUNT = 8
        private const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant."
    }

    private val GSON = Gson()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llm-dispatch").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val sessions = ConcurrentHashMap<String, HostSession>()
    private val activeHandle = AtomicReference<String?>(null)

    /** Optional notifications forwarded to bound clients via ILlmServiceCallback. */
    @Volatile var notifier: Notifier? = null

    interface Notifier {
        fun onPartialText(handle: String, text: String)
        fun onQueueStateChanged(handle: String, position: Int)
        fun onSessionInvalidated(handle: String, reason: String)
    }

    private inner class HostSession(
        val handle: String,
        val clientId: String,
        val systemPrompt: String,
        val toolDecls: List<ToolSpecCodec.ToolDecl>,
        val temperature: Double,
    ) {
        var conversation: Conversation? = null
        var processedMessageCount = 0
        var sendCount = 0
        var gpuFailed = false
    }

    fun openSession(clientId: String, sessionId: String, systemPrompt: String, toolsJson: String, temperature: Double): String {
        val handle = "$clientId/$sessionId"
        val decls = ToolSpecCodec.decode(toolsJson)
        sessions[handle] = HostSession(handle, clientId, systemPrompt, decls, temperature)
        XLog.i(TAG, "openSession: $handle (tools=${decls.size}, temp=$temperature)")
        return handle
    }

    fun closeSession(handle: String) {
        val session = sessions.remove(handle)
        if (session == null) {
            XLog.w(TAG, "closeSession: unknown handle $handle")
            return
        }
        executor.submit {
            try {
                session.conversation?.close()
            } catch (e: Exception) {
                XLog.w(TAG, "closeSession: conversation close failed", e)
            }
            if (activeHandle.get() == handle) activeHandle.set(null)
            session.conversation = null
            XLog.i(TAG, "closeSession: $handle closed")
        }
    }

    fun chat(handle: String, messages: List<ChatMessage>): Future<ChatResult> {
        val session = sessions[handle] ?: return executor.submit(Callable<ChatResult> {
            ChatResult(null, emptyList(), "Unknown session handle: $handle")
        })
        notifyQueuePosition(handle, positionFor(handle))
        return executor.submit(Callable { runChat(session, messages) })
    }

    fun singleShot(systemPrompt: String, prompt: String, temperature: Double): Future<ChatResult> {
        return executor.submit(Callable {
            val modelPath = selectedModelPath()
                ?: return@Callable ChatResult(null, emptyList(), "No model selected or downloaded in LLM host app")
            val messages = listOfNotNull(
                SystemMessage.from(systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }),
                UserMessage.from(prompt),
            )
            // Single shots are stateless: run through a scratch session each time.
            val scratch = HostSession("singleShot", "internal", systemPrompt, emptyList(), temperature)
            runChat(scratch, messages)
        })
    }

    /** Drop every session (model changed / engine reset). Called on the executor. */
    fun invalidateAll(reason: String) {
        val snapshot = sessions.keys.toList()
        executor.submit {
            sessions.values.forEach { session ->
                try {
                    session.conversation?.close()
                } catch (_: Exception) {
                }
                notifier?.onSessionInvalidated(session.handle, reason)
            }
            sessions.clear()
            activeHandle.set(null)
            XLog.w(TAG, "invalidateAll: ${snapshot.size} sessions dropped ($reason)")
        }
    }

    fun activeClientCount(): Int = sessions.keys.map { it.substringBefore('/') }.toSet().size

    fun modelStatusJson(): String {
        val model = LocalModelManager.selectedModel(context)
        val modelPath = model?.let { LocalModelManager.getModelPath(context, it) }
        val backend = modelPath?.let { LocalModelRuntime.currentBackendLabel(it) }
        return JsonObject().apply {
            addProperty("modelName", model?.displayName ?: "none")
            addProperty("modelId", model?.id ?: "")
            addProperty("modelPath", modelPath ?: "")
            addProperty("backendLabel", backend ?: "not loaded")
            addProperty("activeClients", activeClientCount())
            addProperty("ready", modelPath != null)
        }.toString()
    }

    // ------------------------------------------------------------------ //

    private fun runChat(session: HostSession, messages: List<ChatMessage>): ChatResult {
        try {
            val modelPath = selectedModelPath()
                ?: return ChatResult(null, emptyList(), "No model selected or downloaded in LLM host app")

            activateSession(session, modelPath)

            // Rebuild conversation when history was truncated or it has gotten long
            if (session.processedMessageCount == 0 || messages.size < session.processedMessageCount || session.sendCount >= REBUILD_SEND_COUNT) {
                rebuildConversation(session, modelPath)
            }

            val newMessages = messages.subList(
                session.processedMessageCount.coerceAtMost(messages.size),
                messages.size
            )

            var lastResponse: Any? = null
            for (msg in newMessages) {
                when (msg) {
                    is SystemMessage -> { /* baked into ConversationConfig */ }
                    is UserMessage -> {
                        lastResponse = sendMessageSafely(session, msg.singleText())
                        session.sendCount++
                    }
                    is AiMessage -> { /* already reflected in conversation state */ }
                    is ToolExecutionResultMessage -> {
                        val truncated = msg.text().take(400)
                        lastResponse = sendMessageSafely(session, "[Tool ${msg.toolName()} result]: $truncated")
                        session.sendCount++
                    }
                }
            }

            session.processedMessageCount = messages.size
            val result = parseResponse(session, lastResponse)
            lastResponse?.let { resp ->
                (resp as? com.google.ai.edge.litertlm.Message)?.contents?.toString()?.let { text ->
                    notifier?.onPartialText(session.handle, text)
                }
            }
            notifyQueuePosition(session.handle, -1)
            return result
        } catch (e: Exception) {
            XLog.e(TAG, "runChat failed for ${session.handle}", e)
            notifyQueuePosition(session.handle, -1)
            return ChatResult(null, emptyList(), "Inference failed: ${e.message}")
        }
    }

    private fun activateSession(session: HostSession, modelPath: String) {
        val current = activeHandle.get()
        if (current == session.handle) return

        // Session switch under the single-session constraint: close the old
        // conversation and reset the new session so its history replays fully.
        val previous = if (current != null) sessions[current] else null
        if (previous != null) {
            try {
                previous.conversation?.close()
            } catch (e: Exception) {
                XLog.w(TAG, "activateSession: closing previous conversation failed", e)
            }
            previous.conversation = null
            previous.processedMessageCount = 0
            previous.sendCount = 0
        }
        session.conversation = null
        session.processedMessageCount = 0
        session.sendCount = 0
        activeHandle.set(session.handle)
        XLog.i(TAG, "activateSession: $current -> ${session.handle} (history will replay)")
    }

    private fun rebuildConversation(session: HostSession, modelPath: String) {
        try {
            session.conversation?.close()
        } catch (_: Exception) {
        }
        session.conversation = null

        val nativeTools = session.toolDecls.mapNotNull { decl ->
            try {
                val params = decl.parameters?.let { GSON.fromJson(it, Any::class.java) } ?: emptyMap<String, Any>()
                tool(object : OpenApiTool {
                    override fun getToolDescriptionJsonString(): String = GSON.toJson(mapOf(
                        "name" to decl.name,
                        "description" to decl.description,
                        "parameters" to params,
                    ))
                    override fun execute(params: String): String = "{}"
                })
            } catch (e: Exception) {
                XLog.w(TAG, "Failed to wrap tool ${decl.name}", e)
                null
            }
        }

        val convConfig = ConversationConfig(
            systemInstruction = Contents.of(session.systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }),
            tools = nativeTools,
            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = session.temperature),
            automaticToolCalling = false, // tool execution stays in the agent app
        )

        val lease = LocalModelRuntime.openConversation(
            context = context,
            modelPath = modelPath,
            conversationConfig = convConfig,
            preferCpu = session.gpuFailed,
        )
        session.conversation = lease.conversation
        session.processedMessageCount = 0
        session.sendCount = 0
        XLog.i(TAG, "rebuildConversation: ${session.handle} ready (${nativeTools.size} tools, ${lease.backendLabel})")
    }

    private fun sendMessageSafely(session: HostSession, text: String): Any? {
        val conv = session.conversation
            ?: throw IllegalStateException("Conversation not initialized - engine may have failed to load the model")
        return try {
            conv.sendMessage(text)
        } catch (e: Exception) {
            val message = e.message ?: ""
            val salvaged = ToolCallParser.extractFromSdkParseError(message)
            if (salvaged != null) {
                XLog.w(TAG, "SDK tool call parse failed, salvaging raw output: ${message.take(200)}")
                salvaged
            } else if (!session.gpuFailed && LocalModelRuntime.isGpuBackendFailure(e)) {
                XLog.w(TAG, "sendMessage: GPU inference failed, degrading to CPU: ${e.message}")
                session.gpuFailed = true
                rebuildConversation(session, selectedModelPath() ?: throw e)
                session.conversation?.sendMessage(text)
            } else {
                throw e
            }
        }
    }

    private fun parseResponse(session: HostSession, response: Any?): ChatResult {
        // 1. Native structured tool calls from the SDK
        if (response is com.google.ai.edge.litertlm.Message) {
            val nativeCalls = response.toolCalls
            if (!nativeCalls.isNullOrEmpty()) {
                val calls = nativeCalls.mapNotNull { tc ->
                    try {
                        ChatResult.ToolCall(tc.name, GSON.toJson(tc.arguments))
                    } catch (e: Exception) {
                        XLog.w(TAG, "Failed to convert native ToolCall: ${tc.name}", e)
                        null
                    }
                }
                if (calls.isNotEmpty()) {
                    XLog.i(TAG, "parseResponse: ${calls.size} native tool calls from SDK")
                    val text = response.contents?.toString()?.trim()?.ifEmpty { null }
                    return ChatResult(text, calls)
                }
            }
            return ChatResult(response.contents?.toString()?.trim(), emptyList())
        }

        // 2. Salvaged raw string (SDK parse error path) or plain text
        val text = response?.toString() ?: ""
        val calls = ToolCallParser.extract(text).map { ChatResult.ToolCall(it.name, it.argumentsJson) }
        if (calls.isNotEmpty()) {
            val thinking = text
                .replace(Regex("""<tool_call>(.*?)</tool_call>""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""<\|tool_call>(.*?)<tool_call\|>""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""```tool_call\s*\n(.*?)\n\s*```""", RegexOption.DOT_MATCHES_ALL), "")
                .trim()
                .ifEmpty { null }
            return ChatResult(thinking, calls)
        }
        return ChatResult(text.ifEmpty { null }, emptyList())
    }

    private fun selectedModelPath(): String? {
        val model = LocalModelManager.selectedModel(context) ?: return null
        return LocalModelManager.getModelPath(context, model)
    }

    private fun positionFor(handle: String): Int {
        val active = activeHandle.get() ?: return 0
        return if (active == handle) 0 else 1
    }

    private fun notifyQueuePosition(handle: String, position: Int) {
        try {
            notifier?.onQueueStateChanged(handle, position)
        } catch (e: Exception) {
            XLog.w(TAG, "notifyQueuePosition failed", e)
        }
    }
}
