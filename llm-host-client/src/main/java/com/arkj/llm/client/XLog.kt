// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llm.client

import android.util.Log

/**
 * Minimal logcat logger for the LLM host client library.
 *
 * Kept dependency-free (no AppLogStore / debug-report plumbing) so the
 * library stays self-contained. Agents that want the host-client logs in
 * their own AppLogStore can wrap this at the app layer.
 */
object XLog {

    fun v(tag: String, msg: String) = Log.v(tag, msg)

    fun d(tag: String, msg: String) = Log.d(tag, msg)

    fun i(tag: String, msg: String) = Log.i(tag, msg)

    fun w(tag: String, msg: String) = Log.w(tag, msg)
    fun w(tag: String, msg: String, tr: Throwable?) = Log.w(tag, msg, tr)

    fun e(tag: String, msg: String) = Log.e(tag, msg)
    fun e(tag: String, msg: String, tr: Throwable?) = Log.e(tag, msg, tr)
}
