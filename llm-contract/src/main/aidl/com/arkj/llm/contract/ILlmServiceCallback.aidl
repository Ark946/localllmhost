// ILlmServiceCallback.aidl - server -> client notifications.
package com.arkj.llm.contract;

interface ILlmServiceCallback {
    /** Partial generated text for a session (streaming; may be the whole answer in one shot). */
    void onPartialText(String handle, String text);

    /** Queue visibility: position 0 means actively generating. */
    void onQueueStateChanged(String handle, int position);

    /** Session was dropped by the server (engine reset, model change, error). */
    void onSessionInvalidated(String handle, String reason);
}
