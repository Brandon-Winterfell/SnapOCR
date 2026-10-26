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
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.material.button.MaterialButton
import com.snapocr.app.MainActivity
import com.snapocr.app.R
import com.snapocr.app.ocr.OcrManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingCaptureService : Service() {

    companion object {
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
            e.printStackTrace()
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

    private fun captureCurrentFrame(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) {
                bitmap
            } else {
                val clean = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                bitmap.recycle()
                clean
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            image.close()
        }
    }

    private fun captureScreenAndOcr() {
        Toast.makeText(this, "正在截屏提取文字...", Toast.LENGTH_SHORT).show()

        floatingBubbleView?.visibility = View.INVISIBLE
        removeResultDialog()

        mainHandler.postDelayed({
            serviceScope.launch {
                var bitmap: Bitmap? = null
                for (i in 0 until 8) {
                    bitmap = captureCurrentFrame()
                    if (bitmap != null) break
                    delay(50)
                }

                withContext(Dispatchers.Main) {
                    floatingBubbleView?.visibility = View.VISIBLE
                }

                if (bitmap == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@FloatingCaptureService,
                            "未捕获到屏幕画面，正在重置通道...",
                            Toast.LENGTH_SHORT
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
                            showResultDialog(recognizedText)

                            val broadcastIntent = Intent(ACTION_NEW_OCR_RESULT).apply {
                                putExtra(EXTRA_OCR_TEXT, recognizedText)
                                setPackage(packageName)
                            }
                            sendBroadcast(broadcastIntent)
                        } else {
                            Toast.makeText(this@FloatingCaptureService, R.string.ocr_empty, Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FloatingCaptureService, "识别错误: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }, 120)
    }

    @SuppressLint("InflateParams")
    private fun showResultDialog(text: String) {
        removeResultDialog()

        val inflater = LayoutInflater.from(this)
        floatingDialogView = inflater.inflate(R.layout.dialog_ocr_result, null)

        val etDialogText = floatingDialogView?.findViewById<EditText>(R.id.etDialogText)
        val btnDialogCopy = floatingDialogView?.findViewById<MaterialButton>(R.id.btnDialogCopy)
        val btnDialogClose = floatingDialogView?.findViewById<ImageButton>(R.id.btnDialogClose)
        val btnDialogViewInApp = floatingDialogView?.findViewById<MaterialButton>(R.id.btnDialogViewInApp)

        etDialogText?.setText(text)
        etDialogText?.setTextIsSelectable(true)

        btnDialogCopy?.setOnClickListener {
            val clipText = etDialogText?.text?.toString()?.trim() ?: ""
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

        val dialogParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager?.addView(floatingDialogView, dialogParams)
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
