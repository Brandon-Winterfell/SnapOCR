package com.snapocr.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.snapocr.app.databinding.ActivityMainBinding
import com.snapocr.app.model.OcrItem
import com.snapocr.app.model.OcrStatus
import com.snapocr.app.ocr.OcrManager
import com.snapocr.app.service.FloatingCaptureService
import com.snapocr.app.ui.OcrResultAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val ocrItems = mutableListOf<OcrItem>()
    private lateinit var adapter: OcrResultAdapter

    private var screenCaptureResultCode = 0
    private var screenCaptureResultData: Intent? = null

    private val pickMultipleImagesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            processSelectedImages(uris)
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            screenCaptureResultCode = result.resultCode
            screenCaptureResultData = result.data
            startFloatingService()
        } else {
            Toast.makeText(this, "未授予截屏录屏权限，无法开启悬浮识别", Toast.LENGTH_SHORT).show()
            updateFloatingServiceButton()
        }
    }

    private val ocrBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FloatingCaptureService.ACTION_NEW_OCR_RESULT -> {
                    val recognizedText = intent.getStringExtra(FloatingCaptureService.EXTRA_OCR_TEXT) ?: return
                    val newItem = OcrItem(
                        title = "截屏识别 #${ocrItems.size + 1}",
                        recognizedText = recognizedText,
                        status = OcrStatus.SUCCESS
                    )
                    adapter.addItem(newItem)
                    updateUIState()
                }
                FloatingCaptureService.ACTION_SERVICE_STATE_CHANGED -> {
                    updateFloatingServiceButton()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
        initListeners()
        handleIncomingIntents(intent)
        registerBroadcastReceiver()
    }

    override fun onResume() {
        super.onResume()
        updateFloatingServiceButton()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingIntents(it) }
    }

    private fun initViews() {
        adapter = OcrResultAdapter(ocrItems) { itemToDelete ->
            adapter.removeItem(itemToDelete)
            updateUIState()
        }

        binding.recyclerViewResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewResults.adapter = adapter
        updateUIState()
        updateFloatingServiceButton()
    }

    private fun initListeners() {
        binding.btnPickImages.setOnClickListener {
            pickMultipleImagesLauncher.launch("image/*")
        }

        binding.btnToggleFloating.setOnClickListener {
            if (FloatingCaptureService.isRunning) {
                stopFloatingService()
            } else {
                checkOverlayPermissionAndStart()
            }
        }

        binding.btnCopyAll.setOnClickListener {
            val allText = adapter.getAllText().trim()
            if (allText.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("SnapOCR All Text", allText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(
                    this,
                    getString(R.string.toast_copy_all_success, ocrItems.count { it.status == OcrStatus.SUCCESS }),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(this, R.string.toast_nothing_to_copy, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearAll.setOnClickListener {
            if (ocrItems.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("确认清空")
                    .setMessage("是否确认清空当前所有的识别结果？")
                    .setPositiveButton("清空") { _, _ ->
                        adapter.clearAll()
                        updateUIState()
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }
        }
    }

    private fun processSelectedImages(uris: List<Uri>) {
        lifecycleScope.launch {
            val startIndex = ocrItems.size
            val newItems = uris.mapIndexed { index, uri ->
                val item = OcrItem(
                    title = "图片 #${startIndex + index + 1}",
                    sourceUri = uri,
                    status = OcrStatus.PROCESSING
                )
                adapter.addItem(item)
                item
            }
            updateUIState()

            newItems.map { item ->
                async(Dispatchers.IO) {
                    try {
                        val text = item.sourceUri?.let { OcrManager.recognizeUri(this@MainActivity, it) } ?: ""
                        withContext(Dispatchers.Main) {
                            item.recognizedText = text
                            item.status = if (text.isNotBlank()) OcrStatus.SUCCESS else OcrStatus.EMPTY
                            adapter.updateItem(item)
                            updateUIState()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            item.status = OcrStatus.ERROR
                            item.errorMessage = e.message
                            adapter.updateItem(item)
                            updateUIState()
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun checkOverlayPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.permission_overlay_title)
                .setMessage(R.string.permission_overlay_message)
                .setPositiveButton(R.string.btn_go_to_settings) { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        } else {
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startFloatingService() {
        FloatingCaptureService.isRunning = true
        updateFloatingServiceButton()

        val serviceIntent = Intent(this, FloatingCaptureService::class.java).apply {
            putExtra(FloatingCaptureService.EXTRA_RESULT_CODE, screenCaptureResultCode)
            putExtra(FloatingCaptureService.EXTRA_RESULT_DATA, screenCaptureResultData)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopFloatingService() {
        FloatingCaptureService.isRunning = false
        updateFloatingServiceButton()

        val serviceIntent = Intent(this, FloatingCaptureService::class.java).apply {
            action = FloatingCaptureService.ACTION_STOP_SERVICE
        }
        startService(serviceIntent)
    }

    private fun updateFloatingServiceButton() {
        val isRunning = FloatingCaptureService.isRunning
        binding.btnToggleFloating.text = getString(
            if (isRunning) R.string.btn_stop_floating else R.string.btn_start_floating
        )
        binding.btnToggleFloating.setBackgroundColor(
            ContextCompat.getColor(this, if (isRunning) R.color.danger else R.color.accent)
        )
    }

    private fun updateUIState() {
        val hasItems = ocrItems.isNotEmpty()
        binding.layoutEmpty.visibility = if (hasItems) View.GONE else View.VISIBLE
        binding.recyclerViewResults.visibility = if (hasItems) View.VISIBLE else View.GONE
        binding.layoutResultsHeader.visibility = if (hasItems) View.VISIBLE else View.GONE

        if (hasItems) {
            val successCount = ocrItems.count { it.status == OcrStatus.SUCCESS }
            binding.tvResultsCount.text = "已提取结果 ($successCount/${ocrItems.size})"
        }
    }

    private fun handleIncomingIntents(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") == true) {
                    val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    imageUri?.let { processSelectedImages(listOf(it)) }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (intent.type?.startsWith("image/") == true) {
                    val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                    }
                    if (!uris.isNullOrEmpty()) {
                        processSelectedImages(uris)
                    }
                }
            }
        }
    }

    private fun registerBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(FloatingCaptureService.ACTION_NEW_OCR_RESULT)
            addAction(FloatingCaptureService.ACTION_SERVICE_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ocrBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(ocrBroadcastReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(ocrBroadcastReceiver)
    }
}
