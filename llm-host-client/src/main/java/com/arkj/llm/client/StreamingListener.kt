// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llm.client

interface StreamingListener {
    fun onPartialText(token: String)
    fun onComplete(response: LlmResponse)
    fun onError(error: Throwable)
}
