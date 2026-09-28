package com.example.numberhint

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var window: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var ring: View? = null
    private var label: TextView? = null
    private var target = 1
    private var screenW = 0
    private var screenH = 0
    private var stopped = false
    private val tick = object : Runnable { override fun run() { if (!stopped) { scan(); handler.postDelayed(this, 450) } } }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY
        val channel = NotificationChannel("capture", "屏幕识别", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val notification = Notification.Builder(this, "capture").setContentTitle("数字提示器正在识别屏幕")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()
        startForeground(1, notification)
        @Suppress("DEPRECATION")
        val consent = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("projectionData", Intent::class.java) else intent?.getParcelableExtra("projectionData")
        if (consent == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val metrics = resources.displayMetrics
            screenW = metrics.widthPixels; screenH = metrics.heightPixels
            window = getSystemService(WINDOW_SERVICE) as WindowManager
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(intent.getIntExtra("resultCode", Activity.RESULT_CANCELED), consent)
            projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, handler)
            reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
            display = projection?.createVirtualDisplay("NumberHint", screenW, screenH, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
            createOverlay()
            handler.postDelayed(tick, 700)
        } catch (e: Exception) { Toast.makeText(this, "截屏启动失败：${e.message}", Toast.LENGTH_LONG).show(); stopSelf() }
        return START_NOT_STICKY
    }
    private fun params(w: Int, h: Int, gravity: Int, x: Int = 0, y: Int = 0) = WindowManager.LayoutParams(w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply { this.gravity = gravity; this.x = x; this.y = y }
    private fun createOverlay() {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xE9232345.toInt()); gravity = Gravity.CENTER_VERTICAL }
        fun button(text: String, click: () -> Unit) = Button(this).apply { this.text = text; textSize = 15f; minWidth = 0; minimumWidth = 0; setOnClickListener { click() } }
        label = TextView(this).apply { text = "找 1 · 识别中"; setTextColor(-1); textSize = 17f; setPadding(12, 0, 12, 0) }
        bar.addView(button("−") { target = (target - 1).coerceAtLeast(1); updateLabel("识别中") })
        bar.addView(label); bar.addView(button("+") { target = (target + 1).coerceAtMost(49); updateLabel("识别中") })
        bar.addView(button("×") { stopSelf() })
        overlay = bar
        window?.addView(bar, params(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, (screenH * .13).toInt()))
        ring = object : View(this) {
            val paint = Paint(3).apply { color = 0xFFFFF000.toInt(); style = Paint.Style.STROKE; strokeWidth = 8f }
            override fun onDraw(canvas: Canvas) { super.onDraw(canvas); canvas.drawRoundRect(5f, 5f, width - 5f, height - 5f, 13f, 13f, paint) }
        }.also { it.visibility = View.GONE; window?.addView(it, params(90, 90, Gravity.TOP or Gravity.LEFT)) }
    }
    private fun updateLabel(status: String) { label?.text = "找 $target · $status"; ring?.visibility = View.GONE }
    private fun scan() {
        if (!busy.compareAndSet(false, true)) return
        val image = reader?.acquireLatestImage()
        if (image == null) { busy.set(false); return }
        var full: Bitmap? = null
        try {
            val plane = image.planes[0]
            val width = image.width; val height = image.height
            val padded = width + (plane.rowStride - plane.pixelStride * width) / plane.pixelStride
            val bitmap = Bitmap.createBitmap(padded, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(plane.buffer)
            full = bitmap
        } catch (_: Exception) { busy.set(false) } finally { image.close() }
        val bitmap = full ?: return
        val prefs = getSharedPreferences("board", MODE_PRIVATE)
        val left = (bitmap.width * prefs.getInt("b0", 15) / 100f).toInt()
        val top = (bitmap.height * prefs.getInt("b1", 34) / 100f).toInt()
        val right = (bitmap.width * prefs.getInt("b2", 85) / 100f).toInt()
        val bottom = (bitmap.height * prefs.getInt("b3", 77) / 100f).toInt()
        if (right <= left || bottom <= top || right > bitmap.width || bottom > bitmap.height) { bitmap.recycle(); busy.set(false); return }
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        bitmap.recycle()
        val observedTarget = target
        recognizer.process(InputImage.fromBitmap(crop, 0)).addOnSuccessListener { result ->
            if (observedTarget != target || stopped) return@addOnSuccessListener
            val candidates = result.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { el ->
                val n = el.text.trim().toIntOrNull()
                val r = el.boundingBox
                if (n == observedTarget && r != null) r else null
            }
            if (candidates.size == 1) {
                val r = candidates[0]
                val cx = left + r.centerX(); val cy = top + r.centerY()
                // A valid target must sit near a 7×7 cell centre, away from the board border.
                val col = ((cx - left).toFloat() / (right - left) * 7).toInt()
                val row = ((cy - top).toFloat() / (bottom - top) * 7).toInt()
                if (col in 0..6 && row in 0..6) {
                    val cellW = (right - left) / 7f; val cellH = (bottom - top) / 7f
                    val w = (cellW * .82f).toInt(); val h = (cellH * .82f).toInt()
                    val x = (left + (col + .5f) * cellW - w / 2).toInt()
                    val y = (top + (row + .5f) * cellH - h / 2).toInt()
                    val view = ring!!; val p = view.layoutParams as WindowManager.LayoutParams
                    p.width = w; p.height = h; p.x = x; p.y = y
                    window?.updateViewLayout(view, p); view.visibility = View.VISIBLE
                    label?.text = "找 $target ✓"
                }
            } else { updateLabel("识别中") }
        }.addOnFailureListener { updateLabel("重试中") }.addOnCompleteListener { crop.recycle(); busy.set(false) }
    }
    override fun onDestroy() {
        stopped = true; handler.removeCallbacks(tick)
        try { overlay?.let { window?.removeView(it) }; ring?.let { window?.removeView(it) } } catch (_: Exception) {}
        display?.release(); reader?.close(); projection?.stop(); recognizer.close()
        super.onDestroy()
    }
}
