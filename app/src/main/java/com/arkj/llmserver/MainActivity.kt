// Copyright 2026 ArkLlm. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.arkj.llmserver

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.arkj.llmserver.runtime.HfModelCatalog
import com.arkj.llmserver.runtime.HostPrefs
import com.arkj.llmserver.runtime.LocalModelManager
import com.arkj.llmserver.runtime.LocalModelRuntime
import com.arkj.llmserver.runtime.XLog
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
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

        private val BUTTON_FILLED = com.google.android.material.R.attr.materialButtonStyle
        private val BUTTON_OUTLINED = com.google.android.material.R.attr.materialButtonOutlinedStyle
    }

    private lateinit var root: LinearLayout
    private lateinit var contentContainer: FrameLayout
    private lateinit var marketplaceLayout: LinearLayout
    private lateinit var mineLayout: View
    private lateinit var marketplaceList: LinearLayout
    private lateinit var bottomNav: BottomNavigationView

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var modelSpinner: Spinner
    private lateinit var startStopServiceButton: MaterialButton

    private lateinit var customUrlInput: TextInputEditText
    private lateinit var downloadProgress: LinearProgressIndicator
    private lateinit var downloadProgressLabel: TextView

    private var onSurface = 0
    private var onSurfaceVariant = 0
    private var errorColor = 0

    private var marketplaceModels: List<HfModelCatalog.MarketplaceModel> = emptyList()
    private var suppressSpinnerCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Material You: on Android 12+ this recolors the app from the user's wallpaper.
        DynamicColors.applyToActivitiesIfAvailable(application)
        HostPrefs.init(this)
        setContentView(R.layout.activity_main)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        root = findViewById(R.id.root)
        contentContainer = findViewById(R.id.contentContainer)
        marketplaceLayout = findViewById(R.id.marketplaceLayout)
        mineLayout = findViewById(R.id.mineLayout)
        marketplaceList = findViewById(R.id.marketplaceList)
        bottomNav = findViewById(R.id.bottomNav)
        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)
        modelSpinner = findViewById(R.id.modelSpinner)
        startStopServiceButton = findViewById(R.id.startStopServiceButton)
        customUrlInput = findViewById(R.id.customUrlInput)
        downloadProgress = findViewById(R.id.downloadProgress)
        downloadProgressLabel = findViewById(R.id.downloadProgressLabel)

        resolveThemeColors()
        applyEdgeToEdge()
        setUpBottomNav()
        setUpModelSpinner()

        startStopServiceButton.setOnClickListener {
            val running = LlmHostService.isRunning
            if (running) {
                stopService(Intent(this, LlmHostService::class.java))
            } else {
                startForegroundService(Intent(this, LlmHostService::class.java))
            }
            setServiceButtons(running = !running)
        }
        findViewById<MaterialButton>(R.id.downloadCustomButton).setOnClickListener { downloadCustom() }
        findViewById<MaterialButton>(R.id.linkLocalButton).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQUEST_PICK_MODEL)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshMine()
        loadMarketplace()
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
            refreshMine()
        }
    }

    private fun resolveThemeColors() {
        onSurface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface)
        onSurfaceVariant = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        errorColor = MaterialColors.getColor(root, com.google.android.material.R.attr.colorError)
    }

    private fun applyEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            contentContainer.updatePadding(top = bars.top)
            bottomNav.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    private fun setUpBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_marketplace -> showTab(marketplace = true)
                R.id.nav_mine -> showTab(marketplace = false)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        bottomNav.selectedItemId = R.id.nav_marketplace
    }

    private fun showTab(marketplace: Boolean) {
        marketplaceLayout.visibility = if (marketplace) View.VISIBLE else View.GONE
        mineLayout.visibility = if (marketplace) View.GONE else View.VISIBLE
    }

    private fun setUpModelSpinner() {
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinnerCallback) return
                val downloaded = LocalModelManager.downloadedModels(this@MainActivity)
                if (position !in downloaded.indices) return
                LocalModelManager.selectModel(downloaded[position].id)
                updateStatusCard()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    // ---- Marketplace --------------------------------------------------- //

    private fun loadMarketplace() {
        if (marketplaceModels.isNotEmpty()) {
            renderMarketplace()
            return
        }
        marketplaceList.removeAllViews()
        marketplaceList.addView(statusRow("Loading models…"))
        launch {
            try {
                marketplaceModels = HfModelCatalog.fetch()
                renderMarketplace()
            } catch (e: Exception) {
                XLog.e(TAG, "loadMarketplace failed", e)
                renderMarketplaceError(e)
            }
        }
    }

    private fun renderMarketplace() {
        val downloadedNames = LocalModelManager.downloadedModels(this).map { it.fileName }.toSet()
        marketplaceList.removeAllViews()
        if (marketplaceModels.isEmpty()) {
            marketplaceList.addView(statusRow("No litert-lm models found"))
            return
        }
        marketplaceModels.forEach { m ->
            marketplaceList.addView(marketplaceCard(m, m.fileName in downloadedNames))
        }
    }

    private fun renderMarketplaceError(e: Exception) {
        marketplaceList.removeAllViews()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(24), dp(4), dp(24))
        }
        container.addView(TextView(this).apply {
            text = "Couldn't load models: ${e.message}"
            textSize = 14f
            setTextColor(errorColor)
        })
        container.addView(materialButton("Retry", BUTTON_FILLED) {
            marketplaceModels = emptyList()
            HfModelCatalog.clearCache()
            loadMarketplace()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        })
        marketplaceList.addView(container)
    }

    private fun marketplaceCard(m: HfModelCatalog.MarketplaceModel, downloaded: Boolean): View {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        content.addView(TextView(this).apply {
            text = m.name
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(onSurface)
        })

        content.addView(TextView(this).apply {
            text = "${formatCount(m.downloads)} downloads · litert-lm"
            textSize = 13f
            setTextColor(onSurfaceVariant)
            setPadding(0, dp(4), 0, 0)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        if (downloaded) {
            actions.addView(materialButton("Select", BUTTON_FILLED) {
                LocalModelManager.selectModel(m.fileName)
                Toast.makeText(this@MainActivity, "Selected ${m.name}", Toast.LENGTH_SHORT).show()
                refreshMine()
            })
        } else {
            actions.addView(materialButton("Download", BUTTON_FILLED) { downloadMarketplace(m) })
        }
        content.addView(actions)

        card.addView(content)
        return card
    }

    private fun downloadMarketplace(m: HfModelCatalog.MarketplaceModel) {
        download(LocalModelManager.toModelInfo(m))
    }

    private fun statusRow(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(onSurfaceVariant)
        setPadding(dp(4), dp(24), dp(4), dp(24))
    }

    // ---- Mine ---------------------------------------------------------- //

    private fun refreshMine() {
        rebuildSpinner()
        updateStatusCard()
        setServiceButtons(LlmHostService.isRunning)
    }

    private fun rebuildSpinner() {
        val downloaded = LocalModelManager.downloadedModels(this)
        val selected = LocalModelManager.selectedModel(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, downloaded.map { it.displayName })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        suppressSpinnerCallback = true
        modelSpinner.adapter = adapter
        val idx = downloaded.indexOfFirst { it.id == selected?.id }
        if (idx >= 0) modelSpinner.setSelection(idx, false)
        suppressSpinnerCallback = false
    }

    private fun updateStatusCard() {
        val selected = LocalModelManager.selectedModel(this)
        val modelPath = selected?.let { LocalModelManager.getModelPath(this, it) }
        val backend = modelPath?.let { LocalModelRuntime.currentBackendLabel(it) }
        statusText.text = when {
            selected == null -> "No model available"
            modelPath == null -> "${selected.displayName} — file missing, re-download"
            else -> "Active: ${selected.displayName}"
        }
        detailText.text = buildString {
            if (modelPath != null) {
                append(modelPath.substringAfterLast('/'))
                append(" · backend: ${backend ?: "not loaded yet"}")
            } else {
                append("Download a model from the marketplace")
            }
            append("\nDevice RAM: ${LocalModelManager.getDeviceRamGb(this@MainActivity)} GB")
        }
    }

    private fun setServiceButtons(running: Boolean) {
        // The button always shows the *next* action: start when idle, stop when running.
        startStopServiceButton.text = if (running) "Stop hosting service" else "Start hosting service"
        startStopServiceButton.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play_arrow)
    }

    // ---- Download / import -------------------------------------------- //

    private fun download(model: LocalModelManager.ModelInfo) {
        Toast.makeText(this, "Downloading ${model.displayName}…", Toast.LENGTH_SHORT).show()
        downloadProgress.visibility = View.VISIBLE
        downloadProgressLabel.visibility = View.VISIBLE
        downloadProgress.progress = 0
        downloadProgressLabel.text = "0%"
        launch {
            val result = withContext(Dispatchers.IO) {
                val error = arrayOfNulls<String>(1)
                LocalModelManager.downloadModel(
                    this@MainActivity,
                    model,
                    object : LocalModelManager.DownloadCallback {
                        override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) {
                            if (totalBytes <= 0) return
                            val pct = (bytesDownloaded * 100 / totalBytes).toInt().coerceIn(0, 100)
                            val label = "$pct% · ${formatSize(bytesDownloaded)} / ${formatSize(totalBytes)}"
                            runOnUiThread {
                                downloadProgress.progress = pct
                                downloadProgressLabel.text = label
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
            downloadProgress.visibility = View.GONE
            downloadProgressLabel.visibility = View.GONE
            Toast.makeText(
                this@MainActivity,
                result ?: "Download complete: ${model.displayName} is now active",
                Toast.LENGTH_LONG
            ).show()
            refreshMine()
            renderMarketplace()
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

    // ---- Helpers ------------------------------------------------------- //

    private fun materialButton(label: String, styleAttr: Int, onClick: () -> Unit): MaterialButton {
        return MaterialButton(this, null, styleAttr).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(12), 0) }
            setOnClickListener { onClick() }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "size unknown"
        if (bytes >= 1_000_000_000) return String.format("%.1f GB", bytes / 1_000_000_000.0)
        if (bytes >= 1_000_000) return String.format("%.0f MB", bytes / 1_000_000.0)
        return "${bytes / 1000} KB"
    }

    private fun formatCount(n: Long): String = when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format("%.1fk", n / 1_000.0)
        else -> "$n"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
