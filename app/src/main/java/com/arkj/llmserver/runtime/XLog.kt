// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llmserver.runtime

import android.util.Log

/** Logcat-only logging shim mirroring the PokeClaw XLog API surface. */
object XLog {
    fun i(tag: String, msg: String) { Log.i(tag, msg) }
    fun i(tag: String, msg: String, tr: Throwable?) { Log.i(tag, msg, tr) }
    fun d(tag: String, msg: String) { Log.d(tag, msg) }
    fun d(tag: String, msg: String, tr: Throwable?) { Log.d(tag, msg, tr) }
    fun e(tag: String, msg: String) { Log.e(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable?) { Log.e(tag, msg, tr) }
    fun w(tag: String, msg: String) { Log.w(tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable?) { Log.w(tag, msg, tr) }
}
