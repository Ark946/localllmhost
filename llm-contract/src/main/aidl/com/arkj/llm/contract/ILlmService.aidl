// ILlmService.aidl - contract between the LLM host app and agent apps.
package com.arkj.llm.contract;

import com.arkj.llm.contract.ILlmServiceCallback;

interface ILlmService {
    /**
     * Open (or reuse) a conversation session for a client.
     * @param clientId stable per-app identifier (e.g. package name)
     * @param sessionId client-chosen session id; reuse same id to continue a conversation
     * @param systemPrompt system instruction for the conversation
     * @param toolsJson JSON array of tool declarations [{name, description, parameters}]
     * @param temperature sampling temperature
     * @return session handle id (server-assigned), or empty string on error
     */
    String openSession(String clientId, String sessionId, String systemPrompt, String toolsJson, double temperature);

    /**
     * Send the full message history for a session; the server sends only the
     * incremental messages to the model (like the old in-process client).
     * @param handle session handle from openSession
     * @param messagesJson JSON array produced by ChatMessageCodec.encode
     * @return JSON ChatResult {text, toolCalls:[{name, arguments}], error}
     */
    String chat(String handle, String messagesJson);

    /** One-shot prompt without keeping a session. Returns JSON ChatResult. */
    String singleShot(String systemPrompt, String prompt, double temperature);

    /** Close a session; its conversation is released but the engine stays warm. */
    void closeSession(String handle);

    /** JSON ModelStatus {modelName, modelPath, backendLabel, activeClients, ready} */
    String getModelStatus();

    /**
     * Debug/support introspection. Actions: "status" (backend health summary),
     * "force_cpu_safe", "clear_cpu_safe", "mark_pending_gpu_init",
     * "clear_pending_gpu_init", "recover_pending_gpu_crash", "storage_diagnostics".
     * @param argsJson optional JSON args {modelPath, reason}
     * @return JSON object with {action, ok, detail}
     */
    String debugAction(String action, String argsJson);

    void registerCallback(ILlmServiceCallback callback);

    void unregisterCallback(ILlmServiceCallback callback);
}
