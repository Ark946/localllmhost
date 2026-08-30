// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llmserver.runtime

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persistent per-app access decisions for the LLM host service.
 *
 * Each entry records a package that has requested to use the service, along
 * with the user's decision (allow / deny) or PENDING when not yet answered.
 * Uses the same SharedPreferences pattern as [HostPrefs] but in its own file.
 */
object AppAccessStore {

    enum class Status { ALLOWED, DENIED, PENDING }

    data class Entry(
        val id: Long,
        val packageName: String,
        val status: Status,
        val firstSeenAt: Long,
    )

    private const val FILE = "app_access"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_NEXT_ID = "next_id"

    private val gson = Gson()
    private val listType = object : TypeToken<List<Entry>>() {}.type

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        if (prefs == null) {
            prefs = appContext!!.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    /** Application context for notification / package-manager queries. Null before init(). */
    fun appContext(): Context? = appContext

    private fun p(): SharedPreferences = requireNotNull(prefs) {
        "AppAccessStore.init(context) must be called before use"
    }

    @Synchronized
    fun statusFor(pkg: String): Status? = loadEntries().firstOrNull { it.packageName == pkg }?.status

    /** Record a first request for [pkg]; no-op (returns existing) if already known. */
    @Synchronized
    fun recordRequest(pkg: String): Entry {
        val entries = loadEntries().toMutableList()
        entries.firstOrNull { it.packageName == pkg }?.let { return it }
        val entry = Entry(nextId(), pkg, Status.PENDING, System.currentTimeMillis())
        entries.add(entry)
        saveEntries(entries)
        return entry
    }

    @Synchronized
    fun setStatus(pkg: String, allow: Boolean): Entry {
        val entries = loadEntries().toMutableList()
        val status = if (allow) Status.ALLOWED else Status.DENIED
        val idx = entries.indexOfFirst { it.packageName == pkg }
        val entry = if (idx >= 0) {
            entries[idx].copy(status = status)
        } else {
            Entry(nextId(), pkg, status, System.currentTimeMillis())
        }
        if (idx >= 0) entries[idx] = entry else entries.add(entry)
        saveEntries(entries)
        return entry
    }

    @Synchronized
    fun entries(): List<Entry> = loadEntries()

    private fun nextId(): Long {
        val next = p().getLong(KEY_NEXT_ID, 1L)
        p().edit().putLong(KEY_NEXT_ID, next + 1).apply()
        return next
    }

    private fun loadEntries(): List<Entry> {
        val raw = p().getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            gson.fromJson(raw, listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveEntries(entries: List<Entry>) {
        p().edit().putString(KEY_ENTRIES, gson.toJson(entries, listType)).apply()
    }
}
