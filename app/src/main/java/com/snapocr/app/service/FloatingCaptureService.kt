package com.snapocr.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Selection
import android.text.Spannable
import android.util.DisplayMetrics
import android.util.Log
import android.view.ActionMode
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.snapocr.app.MainActivity
import com.snapocr.app.R
import com.snapocr.app.ocr.OcrManager
import com.snapocr.app.ui.FloatingResultActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class FloatingCaptureService : Service() {

    companion object {
        private const val TAG = "FloatingCaptureService"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val ACTION_STOP_SERVICE = "action_stop_service"
        const val ACTION_NEW_OCR_RESULT = "com.snapocr.app.NEW_OCR_RESULT"
        const val ACTION_SERVICE_STATE_CHANGED = "com.snapocr.app.SERVICE_STATE_CHANGED"
        const val EXTRA_IS_RUNNING = "extra_is_running"
        const val EXTRA_OCR_TEXT = "extra_ocr_text"

        const val CHANNEL_ID = "snap_ocr_capture_channel"
        const val NOTIFICATION_ID = 2001

        @Volatile
        var isRunning = false
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var floatingBubbleView: View? = null
    private var floatingDialogView: View? = null

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private var bubbleLayoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        computeScreenMetrics()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            isRunning = false
            notifyStateChanged(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != 0 && resultData != null) {
            startForegroundServiceNotification()
            setupMediaProjection(resultCode, resultData)
            showFloatingBubble()
            isRunning = true
            notifyStateChanged(true)
        } else {
            isRunning = false
            notifyStateChanged(false)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun computeScreenMetrics() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_text)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification() {
        val stopIntent = Intent(this, FloatingCaptureService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val appIntent = Intent(this, MainActivity::class.java)
        val appPendingIntent = PendingIntent.getActivity(
            this,
            0,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(appPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_stop_floating), stopPendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cleanupCapturePipeline()
                isRunning = false
                notifyStateChanged(false)
            }
        }, mainHandler)

        setupVirtualDisplay()
    }

    private fun setupVirtualDisplay() {
        try {
            cleanupCapturePipeline()
            computeScreenMetrics()

            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                3
            )

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "SnapOCR_Capture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                mainHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay setup failed", e)
            Toast.makeText(this, "截屏初始化异常: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanupCapturePipeline() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        } catch (_: Exception) {}
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showFloatingBubble() {
        if (floatingBubbleView != null) return

        val inflater = LayoutInflater.from(this)
        floatingBubbleView = inflater.inflate(R.layout.floating_bubble_layout, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = screenHeight / 3
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDrag = false
        var downTime = 0L

        floatingBubbleView?.setOnTouchListener { _, event ->
            val params = bubbleLayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDrag = false
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        isDrag = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(floatingBubbleView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - downTime
                    if (!isDrag && duration < 550) {
                        captureScreenAndOcr()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(floatingBubbleView, bubbleLayoutParams)
        Toast.makeText(this, R.string.toast_service_started, Toast.LENGTH_SHORT).show()
    }

    /**
     * Captures current frame from ImageReader safely handling GPU stride and buffer padding
     */
    private fun captureCurrentFrame(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquireLatestImage exception: ${e.message}")
            null
        } ?: return null

        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rawWidth = rowStride / pixelStride

            buffer.rewind()

            val bitmap = Bitmap.createBitmap(rawWidth, screenHeight, Bitmap.Config.ARGB_8888)
            val requiredBytes = bitmap.byteCount
            val remainingBytes = buffer.remaining()

            if (remainingBytes >= requiredBytes) {
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                // Some Android GPU HALs omit the final row padding bytes in the buffer.
                // Allocate a direct buffer with exact capacity to avoid BufferUnderflowException.
                val directBuffer = ByteBuffer.allocateDirect(requiredBytes)
                directBuffer.put(buffer)
                while (directBuffer.hasRemaining()) {
                    directBuffer.put(0.toByte())
                }
                directBuffer.rewind()
                bitmap.copyPixelsFromBuffer(directBuffer)
            }

            if (rawWidth == screenWidth) {
                bitmap
            } else {
                val clean = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                bitmap.recycle()
                clean
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureCurrentFrame decode error", e)
            null
        } finally {
            image.close()
        }
    }

    /**
     * Temporarily hides floating bubble, captures screen, runs OCR, auto-copies to clipboard,
     * and shows the interactive result popup.
     */
    private fun captureScreenAndOcr() {
        Toast.makeText(this, "正在截屏提取文字...", Toast.LENGTH_SHORT).show()

        // Temporarily hide bubble so it's not captured in screenshot
        floatingBubbleView?.visibility = View.INVISIBLE
        removeResultDialog()

        // Wait 150ms for display to refresh without bubble
        mainHandler.postDelayed({
            serviceScope.launch {
                var bitmap: Bitmap? = null
                // Retry frame acquisition up to 10 times (total 500ms)
                for (i in 0 until 10) {
                    bitmap = captureCurrentFrame()
                    if (bitmap != null) break
                    delay(50)
                }

                // Restore bubble visibility immediately
                withContext(Dispatchers.Main) {
                    floatingBubbleView?.visibility = View.VISIBLE
                }

                if (bitmap == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@FloatingCaptureService,
                            "未能获取到屏幕画面，正在重置通道，请重试",
                            Toast.LENGTH_LONG
                        ).show()
                        setupVirtualDisplay()
                    }
                    return@launch
                }

                try {
                    val recognizedText = withContext(Dispatchers.Default) {
                        OcrManager.recognizeBitmap(bitmap)
                    }

                    withContext(Dispatchers.Main) {
                        if (recognizedText.isNotBlank()) {
                            // 1. Immediate Auto-Copy to Clipboard so user can immediately paste into other apps!
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("SnapOCR Screen Text", recognizedText)
                            clipboard.setPrimaryClip(clip)

                            Toast.makeText(
                                this@FloatingCaptureService,
                                "文字提取成功（${recognizedText.length} 字），已自动复制到剪贴板！",
                                Toast.LENGTH_LONG
                            ).show()

                            // 2. Broadcast result to MainActivity if open
                            val broadcastIntent = Intent(ACTION_NEW_OCR_RESULT).apply {
                                putExtra(EXTRA_OCR_TEXT, recognizedText)
                                setPackage(packageName)
                            }
                            sendBroadcast(broadcastIntent)

                            // 3. Launch translucent FloatingResultActivity to provide native Editor selection handles & ActionMode
                            try {
                                val dialogIntent = Intent(this@FloatingCaptureService, FloatingResultActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    putExtra(EXTRA_OCR_TEXT, recognizedText)
                                }
                                startActivity(dialogIntent)
                            } catch (e: Exception) {
                                Log.e(TAG, "FloatingResultActivity launch failed, fallback to overlay dialog", e)
                                showResultDialog(recognizedText)
                            }
                        } else {
                            Toast.makeText(this@FloatingCaptureService, "当前屏幕未识别到文字，请换个界面重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "OCR recognition error", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FloatingCaptureService, "文字识别异常: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, 150)
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showResultDialog(text: String) {
        removeResultDialog()

        try {
            val themedContext = ContextThemeWrapper(this, R.style.Theme_SnapOCR)
            val inflater = LayoutInflater.from(themedContext)
            floatingDialogView = inflater.inflate(R.layout.dialog_ocr_result, null)

            val tvDialogText = floatingDialogView?.findViewById<TextView>(R.id.tvDialogText)
            val tvCharCount = floatingDialogView?.findViewById<TextView>(R.id.tvCharCount)
            val tvSelectionStatus = floatingDialogView?.findViewById<TextView>(R.id.tvSelectionStatus)
            val btnSelectAllText = floatingDialogView?.findViewById<Button>(R.id.btnSelectAllText)
            val btnCopySelected = floatingDialogView?.findViewById<Button>(R.id.btnCopySelected)
            val btnDialogCopyAll = floatingDialogView?.findViewById<Button>(R.id.btnDialogCopyAll)
            val btnDialogClose = floatingDialogView?.findViewById<ImageButton>(R.id.btnDialogClose)
            val btnDialogViewInApp = floatingDialogView?.findViewById<Button>(R.id.btnDialogViewInApp)

            tvCharCount?.text = "共 ${text.length} 字"
            tvDialogText?.setText(text, TextView.BufferType.SPANNABLE)
            tvDialogText?.setTextIsSelectable(true)

            fun updateSelectionUI() {
                val start = tvDialogText?.selectionStart ?: -1
                val end = tvDialogText?.selectionEnd ?: -1
                if (start in 0..end && start != end) {
                    val len = end - start
                    btnCopySelected?.visibility = View.VISIBLE
                    btnCopySelected?.text = "复制选中 (${len}字)"
                    tvSelectionStatus?.text = "已选 $len 字，可拖动两端手柄调整"
                    tvSelectionStatus?.setTextColor(ContextCompat.getColor(this, R.color.primary))
                } else {
                    btnCopySelected?.visibility = View.GONE
                    tvSelectionStatus?.text = getString(R.string.dialog_tip_selection)
                    tvSelectionStatus?.setTextColor(ContextCompat.getColor(this, R.color.text_hint))
                }
            }

            // Quick select all action: highlights entire text with start/end drag handles visible
            btnSelectAllText?.setOnClickListener {
                val spannable = tvDialogText?.text as? Spannable
                if (spannable != null && spannable.isNotEmpty()) {
                    Selection.setSelection(spannable, 0, spannable.length)
                    tvDialogText?.requestFocus()
                    updateSelectionUI()
                }
            }

            // Quick copy currently selected text
            btnCopySelected?.setOnClickListener {
                val start = tvDialogText?.selectionStart ?: -1
                val end = tvDialogText?.selectionEnd ?: -1
                if (start in 0..end && start != end) {
                    val clipText = tvDialogText?.text?.substring(start, end) ?: ""
                    if (clipText.isNotEmpty()) {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("SnapOCR Selected", clipText)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, getString(R.string.toast_copied_selection, clipText.length), Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // Standard ActionMode for selection floating menu (选中/复制/全选)
            tvDialogText?.customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                    updateSelectionUI()
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                    updateSelectionUI()
                    return true
                }

                override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                    if (item?.itemId == android.R.id.copy) {
                        val start = tvDialogText?.selectionStart ?: -1
                        val end = tvDialogText?.selectionEnd ?: -1
                        if (start in 0..end && start != end) {
                            val clipText = tvDialogText?.text?.substring(start, end) ?: ""
                            if (clipText.isNotEmpty()) {
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("SnapOCR Selected", clipText)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(this@FloatingCaptureService, getString(R.string.toast_copied_selection, clipText.length), Toast.LENGTH_SHORT).show()
                            }
                        }
                        mode?.finish()
                        return true
                    }
                    return false
                }

                override fun onDestroyActionMode(mode: ActionMode?) {
                    mainHandler.postDelayed({ updateSelectionUI() }, 100)
                }
            }

            // Touch events tracking to update selection handle changes
            tvDialogText?.setOnTouchListener { v, event ->
                v.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    v.postDelayed({ updateSelectionUI() }, 60)
                }
                true
            }

            btnDialogCopyAll?.setOnClickListener {
                val clipText = tvDialogText?.text?.toString()?.trim() ?: ""
                if (clipText.isNotEmpty()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("SnapOCR Screen Text", clipText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
                }
            }

            btnDialogClose?.setOnClickListener {
                removeResultDialog()
            }

            btnDialogViewInApp?.setOnClickListener {
                removeResultDialog()
                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(mainIntent)
            }

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val dialogWidth = (screenWidth * 0.92f).toInt()
            val dialogParams = WindowManager.LayoutParams(
                dialogWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            }

            floatingDialogView?.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    removeResultDialog()
                    true
                } else {
                    false
                }
            }

            windowManager?.addView(floatingDialogView, dialogParams)
        } catch (e: Exception) {
            Log.e(TAG, "showResultDialog error", e)
            Toast.makeText(this, "提取文字已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeResultDialog() {
        if (floatingDialogView != null) {
            try {
                windowManager?.removeView(floatingDialogView)
            } catch (_: Exception) {}
            floatingDialogView = null
        }
    }

    private fun notifyStateChanged(running: Boolean) {
        val stateIntent = Intent(ACTION_SERVICE_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_RUNNING, running)
            setPackage(packageName)
        }
        sendBroadcast(stateIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        notifyStateChanged(false)
        serviceScope.cancel()

        removeResultDialog()

        if (floatingBubbleView != null) {
            try {
                windowManager?.removeView(floatingBubbleView)
            } catch (_: Exception) {}
            floatingBubbleView = null
        }

        cleanupCapturePipeline()
        mediaProjection?.stop()
        mediaProjection = null

        Toast.makeText(this, R.string.toast_service_stopped, Toast.LENGTH_SHORT).show()
    }
}
