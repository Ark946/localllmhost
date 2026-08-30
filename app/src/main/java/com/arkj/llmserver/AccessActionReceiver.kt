// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llmserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arkj.llmserver.runtime.AppAccessStore

/**
 * Handles the Allow / Deny actions on the access-request notification.
 *
 * Registered as a non-exported receiver; the PendingIntents target it
 * explicitly, so the broadcast arrives even if the process was cold-started
 * (the foreground service normally keeps it alive). The store must be
 * initialized here for that cold-start path.
 */
class AccessActionReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_ALLOW = "allow"
    }

    override fun onReceive(context: Context, intent: Intent) {
        AppAccessStore.init(context.applicationContext)
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
        BindPrompt.resolve(pkg, allow)
    }
}
