// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llmserver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arkj.llmserver.runtime.AppAccessStore
import com.arkj.llmserver.runtime.XLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates the first-time access consent prompt for the LLM host service.
 *
 * When an unknown package first calls into the service, we either hand the
 * request to the foreground activity (in-app dialog) or post a system
 * notification (host in background). The decision is persisted by [resolve].
 */
object BindPrompt {

    private const val TAG = "BindPrompt"
    private const val CHANNEL_CONSENT = "llm_host_consent"
    private const val NOTIFICATION_BASE = 2000
    private const val PROMPT_COOLDOWN_MS = 10_000L

    /** True while the host activity is visible (set/cleared in MainActivity onStart/onStop). */
    @Volatile var isAppForeground = false

    /** Foreground dialog callback, set/cleared by MainActivity. */
    @Volatile var dialogListener: ((pkg: String, label: String) -> Unit)? = null

    /** Settings-list refresh callback, set/cleared by MainActivity. */
    @Volatile var onStateChanged: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastPrompt = ConcurrentHashMap<String, Long>()

    /** Called from a Binder thread on a first-time request from an unknown package. */
    fun requestAccess(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        val last = lastPrompt[pkg]
        if (last != null && now - last < PROMPT_COOLDOWN_MS) return
        lastPrompt[pkg] = now

        val context = AppAccessStore.appContext() ?: return
        val label = resolveLabel(context, pkg)

        mainHandler.post {
            val listener = if (isAppForeground) dialogListener else null
            if (listener != null) {
                listener(pkg, label)
            } else {
                notify(context, pkg, label)
            }
        }
    }

    /** Persist the user's decision and refresh UI. Runs on the main thread. */
    fun resolve(pkg: String, allow: Boolean) {
        val context = AppAccessStore.appContext() ?: return
        val entry = AppAccessStore.setStatus(pkg, allow)
        cancelNotification(context, entry.id)
        XLog.i(TAG, "access ${if (allow) "granted" else "denied"}: $pkg")
        if (isAppForeground) {
            onStateChanged?.invoke()
        }
    }

    private fun resolveLabel(context: Context, pkg: String): String {
        return runCatching {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrNull() ?: pkg
    }

    private fun notify(context: Context, pkg: String, label: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            XLog.w(TAG, "POST_NOTIFICATIONS not granted; skipping consent notification for $pkg")
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_CONSENT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_CONSENT, "应用访问请求", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "其他应用请求访问 LLM 服务时的授权请求"
                }
            )
        }

        val entry = AppAccessStore.entries().firstOrNull { it.packageName == pkg } ?: return
        val notificationId = NOTIFICATION_BASE + entry.id.toInt()

        val allowIntent = PendingIntent.getBroadcast(
            context,
            entry.id.toInt() * 2,
            Intent(context, AccessActionReceiver::class.java)
                .putExtra(AccessActionReceiver.EXTRA_PKG, pkg)
                .putExtra(AccessActionReceiver.EXTRA_ALLOW, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val denyIntent = PendingIntent.getBroadcast(
            context,
            entry.id.toInt() * 2 + 1,
            Intent(context, AccessActionReceiver::class.java)
                .putExtra(AccessActionReceiver.EXTRA_PKG, pkg)
                .putExtra(AccessActionReceiver.EXTRA_ALLOW, false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CONSENT)
            .setContentTitle("应用请求访问 LLM 服务")
            .setContentText("$label ($pkg)")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setAutoCancel(true)
            .addAction(0, "允许", allowIntent)
            .addAction(0, "拒绝", denyIntent)
            .build()

        manager.notify(notificationId, notification)
    }

    private fun cancelNotification(context: Context, entryId: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_BASE + entryId.toInt())
    }
}
