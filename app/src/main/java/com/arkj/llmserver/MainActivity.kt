// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llmserver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.arkj.llmserver.runtime.HostPrefs
import com.arkj.llmserver.runtime.LocalModelManager
import com.arkj.llmserver.runtime.LocalModelRuntime
import com.arkj.llmserver.runtime.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    companion object {
        private const val TAG = "LlmHostMainActivity"
        private const val REQUEST_PICK_MODEL = 41
    }

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var modelList: LinearLayout
    private lateinit var customUrlInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HostPrefs.init(this)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)
        modelList = findViewById(R.id.modelList)
        customUrlInput = findViewById(R.id.customUrlInput)

        findViewById<Button>(R.id.startServiceButton).setOnClickListener {
            startForegroundService(Intent(this, LlmHostService::class.java))
            Toast.makeText(this, "Hosting service started", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.downloadCustomButton).setOnClickListener { downloadCustom() }
        findViewById<Button>(R.id.linkLocalButton).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQUEST_PICK_MODEL)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        launch {
            val ok = withContext(Dispatchers.IO) { importFromUri(uri) }
            Toast.makeText(
                this@MainActivity,
                if (ok) "Model imported" else "Import failed (file must be a valid model)",
                Toast.LENGTH_SHORT
            ).show()
            refresh()
        }
    }

    private fun importFromUri(uri: Uri): Boolean {
        return try {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "custom-model.litertlm"
            val dir = LocalModelManager.getModelDir(this)
            val target = File(dir, "imported-$name")
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            if (target.length() < 1_048_576L) {
                target.delete()
                return false
            }
            // Register as custom model pointing at the local copy so catalog picks it up
            HostPrefs.setCustomModelUrl("file://${target.absolutePath}")
            XLog.i(TAG, "importFromUri: imported ${target.name} (${target.length()} bytes)")
            true
        } catch (e: Exception) {
            XLog.e(TAG, "importFromUri failed", e)
            false
        }
    }

    private fun downloadCustom() {
        val url = customUrlInput.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter a model URL first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!url.startsWith("http")) {
            Toast.makeText(this, "URL must start with http(s)://", Toast.LENGTH_SHORT).show()
            return
        }
        HostPrefs.setCustomModelUrl(url)
        download(LocalModelManager.customModel() ?: return)
    }

    private fun refresh() {
        val selected = LocalModelManager.selectedModel(this)
        val modelPath = selected?.let { LocalModelManager.getModelPath(this, it) }
        val backend = modelPath?.let { LocalModelRuntime.currentBackendLabel(it) }
        statusText.text = when {
            selected == null -> "No model available - download one below"
            modelPath == null -> "${selected.displayName} - file missing, re-download"
            else -> "Active: ${selected.displayName}"
        }
        detailText.text = buildString {
            if (modelPath != null) {
                append(modelPath.substringAfterLast('/'))
                append(" · backend: ${backend ?: "not loaded yet"}")
            }
            append("\nDevice RAM: ${LocalModelManager.getDeviceRamGb(this@MainActivity)}GB")
        }

        modelList.removeAllViews()
        LocalModelManager.catalog(this).forEach { entry ->
            modelList.addView(modelRow(entry.model, entry.isDownloaded, entry.isSupported))
        }
    }

    private fun modelRow(model: LocalModelManager.ModelInfo, downloaded: Boolean, supported: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
        }

        row.addView(TextView(this).apply {
            text = when {
                !supported -> "${model.displayName} (needs ${model.minRamGb}GB RAM)"
                downloaded -> "${model.displayName} - downloaded"
                else -> model.displayName
            }
            textSize = 15f
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (downloaded) {
            actions.addView(button("Select") {
                LocalModelManager.selectModel(model.id)
                XLog.i(TAG, "User selected model ${model.id}")
                refresh()
            })
            actions.addView(button("Delete") {
                LocalModelManager.deleteModel(this@MainActivity, model)
                refresh()
            })
        } else if (supported || model.isCustom) {
            actions.addView(button("Download") { download(model) })
        }
        row.addView(actions)
        return row
    }

    private fun download(model: LocalModelManager.ModelInfo) {
        Toast.makeText(this, "Downloading ${model.displayName}...", Toast.LENGTH_SHORT).show()
        launch {
            var lastToast = ""
            val result = withContext(Dispatchers.IO) {
                val error = arrayOfNulls<String>(1)
                LocalModelManager.downloadModel(
                    this@MainActivity,
                    model,
                    object : LocalModelManager.DownloadCallback {
                        override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) {
                            if (totalBytes <= 0) return
                            val pct = (bytesDownloaded * 100 / totalBytes).toInt()
                            val msg = "Downloading ${model.displayName}: $pct%"
                            if (msg != lastToast) {
                                lastToast = msg
                                runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
                            }
                        }

                        override fun onComplete(modelPath: String) {
                            LocalModelManager.selectModel(model.id)
                        }

                        override fun onError(errorMsg: String) {
                            error[0] = errorMsg
                        }
                    }
                )
                error[0]
            }
            Toast.makeText(
                this@MainActivity,
                result ?: "Download complete: ${model.displayName} is now active",
                Toast.LENGTH_LONG
            ).show()
            refresh()
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.START; setMargins(0, 0, 16, 0) }
            setOnClickListener { onClick() }
        }
    }
}
