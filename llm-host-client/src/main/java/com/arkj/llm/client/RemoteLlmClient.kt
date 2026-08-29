// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llm.client

import android.content.Context
import android.os.RemoteException
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import com.arkj.llm.contract.ChatMessageCodec
import com.arkj.llm.contract.ChatResult
import com.arkj.llm.contract.ILlmService
import com.arkj.llm.contract.ILlmServiceCallback
import com.arkj.llm.contract.ToolSpecCodec
import java.util.UUID

/**
 * [LlmClient] implementation backed by the LLM Host app over AIDL.
 *
 * Each instance owns one server-side session (handle). The host keeps the
 * LiteRT-LM engine warm and replays per-session history across session
 * switches, so the agent keeps sending its full message list - identical to
 * the old in-process client contract.
 *
 * The active model is chosen in the host app's UI; the client only provides
 * the system prompt and temperature used to open a session.
 */
class RemoteLlmClient(
    private val context: Context,
    private val systemPrompt: String,
    temperature: Double,
) : LlmClient {

    private companion object {
        private const val TAG = "RemoteLlmClient"
        private const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant."
    }

    private val clientId: String = context.packageName
    private val sessionId: String = "agent-${UUID.randomUUID().toString().substring(0, 8)}"
    private val temperature: Double = temperature
    private val callbacks = RemoteCallback()
    private var handle: String? = null
    private var callbackRegistered = false

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>): LlmResponse {
        val host = host() ?: throw IllegalStateException(hostMissingMessage())

        val h = handle ?: openRemoteSession(host, toolSpecs)
        if (h.isBlank()) throw IllegalStateException("LLM host refused to open a session")

        val resultJson = try {
            host.chat(h, ChatMessageCodec.encode(messages))
        } catch (e: RemoteException) {
            XLog.w(TAG, "chat: remote call failed, resetting connection: ${e.message}")
            handle = null
            throw IllegalStateException("Lost connection to the LLM host app: ${e.message}")
        }
        val result = ChatResult.decode(resultJson)
        if (result.error != null) {
            throw IllegalStateException(result.error)
        }
        return toLlmResponse(result)
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>,
        listener: StreamingListener,
    ): LlmResponse {
        callbacks.streamingListener = listener
        val handleForStream = handle
        if (handleForStream == null) {
            val host = host() ?: run {
                listener.onError(IllegalStateException(hostMissingMessage()))
                throw IllegalStateException(hostMissingMessage())
            }
            handle = openRemoteSession(host, toolSpecs)
        }
        registerCallbackIfNeeded()
        return try {
            chat(messages, toolSpecs)
        } catch (e: Exception) {
            listener.onError(e)
            throw e
        }
    }

    override fun close() {
        val h = handle
        if (h != null) {
            try {
                host()?.closeSession(h)
            } catch (_: Exception) {
            }
        }
        unregisterCallback()
        handle = null
        callbacks.streamingListener = null
        XLog.i(TAG, "close: session closed")
    }

    // ------------------------------------------------------------------ //

    private fun host(): ILlmService? {
        return LlmHostConnection.ensureConnected(context)
    }

    private fun hostMissingMessage(): String =
        "Local LLM host app is not available. Install ArkLlm Host, select a model, then retry."

    private fun openRemoteSession(host: ILlmService, toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>): String {
        val system = systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }
        val toolsJson = ToolSpecCodec.encode(toolSpecs)
        val h = host.openSession(clientId, sessionId, system, toolsJson, temperature)
        if (h.isBlank()) {
            throw IllegalStateException("LLM host refused session - is a model selected in the host app?")
        }
        handle = h
        registerCallbackIfNeeded()
        XLog.i(TAG, "openRemoteSession: handle=$h tools=${toolSpecs.size}")
        return h
    }

    private fun registerCallbackIfNeeded() {
        if (callbackRegistered) return
        val host = host() ?: return
        try {
            host.registerCallback(callbacks)
            callbackRegistered = true
        } catch (e: Exception) {
            XLog.w(TAG, "registerCallback failed", e)
        }
    }

    private fun unregisterCallback() {
        if (!callbackRegistered) return
        try {
            host()?.unregisterCallback(callbacks)
        } catch (_: Exception) {
        }
        callbackRegistered = false
    }

    private fun toLlmResponse(result: ChatResult): LlmResponse {
        val requests = result.toolCalls.map {
            ToolExecutionRequest.builder()
                .id("remote_${System.nanoTime()}")
                .name(it.name)
                .arguments(it.argumentsJson)
                .build()
        }
        return LlmResponse(text = result.text, toolExecutionRequests = requests)
    }

    private inner class RemoteCallback : ILlmServiceCallback.Stub() {
        @Volatile
        var streamingListener: StreamingListener? = null

        override fun onPartialText(handle: String, text: String) {
            if (handle == this@RemoteLlmClient.handle) {
                streamingListener?.onPartialText(text)
            }
        }

        override fun onQueueStateChanged(handle: String, position: Int) {
            // Not surfaced to the chat UI yet - position 0 means actively generating.
        }

        override fun onSessionInvalidated(handle: String, reason: String) {
            if (handle == this@RemoteLlmClient.handle) {
                XLog.w(TAG, "session invalidated by host: $reason")
                this@RemoteLlmClient.handle = null
            }
        }
    }
}
