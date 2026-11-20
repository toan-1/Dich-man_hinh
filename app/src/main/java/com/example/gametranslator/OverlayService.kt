package com.example.gametranslator

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var capW = 0
    private var capH = 0
    private var latest: Image? = null
    private var button: TextView? = null
    private var overlay: FrameLayout? = null
    private var busy = false
    private val cache = HashMap<String, String>()
    private val han = Regex("\\p{IsHan}")

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.CHINESE)
            .setTargetLanguage(TranslateLanguage.VIETNAMESE)
            .build()
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        wm = getSystemService(WindowManager::class.java)
        startForegroundNotification()
        if (projection == null && intent != null) {
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra("code", 0), data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
            setupCapture()
            addButton()
            translator.downloadModelIfNeeded()
                .addOnFailureListener { toast("Không tải được model dịch, hãy bật mạng rồi mở lại") }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ov", "Dịch màn hình", NotificationManager.IMPORTANCE_LOW))
        val n = android.app.Notification.Builder(this, "ov")
            .setContentTitle("Đang chạy dịch màn hình")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    // Android 14+: mỗi MediaProjection chỉ tạo VirtualDisplay 1 lần -> giữ lại, khi xoay màn hình thì resize
    private fun setupCapture() {
        val b = wm.currentWindowMetrics.bounds
        if (vd != null && b.width() == capW && b.height() == capH) return
        capW = b.width(); capH = b.height()
        latest?.close(); latest = null
        val old = reader
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage()
            if (img != null) { latest?.close(); latest = img }
        }, handler)
        reader = r
        val dpi = resources.displayMetrics.densityDpi
        val v = vd
        if (v == null) {
            vd = projection?.createVirtualDisplay(
                "translator", capW, capH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
            )
        } else {
            v.resize(capW, capH, dpi)
            v.surface = r.surface
        }
        old?.close()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        clearOverlay()
        handler.postDelayed({ setupCapture() }, 400)
    }

    private fun addButton() {
        val lp = WindowManager.LayoutParams(
            140, 140, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }
        val btn = TextView(this).apply {
            text = "译"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setBackgroundColor(0xCC1565C0.toInt())
        }
        var downX = 0f; var downY = 0f; var offX = 0f; var offY = 0f; var moved = false
        btn.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; offX = e.rawX - lp.x; offY = e.rawY - lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > 15 || abs(e.rawY - downY) > 15) moved = true
                    if (moved) { lp.x = (e.rawX - offX).toInt(); lp.y = (e.rawY - offY).toInt(); wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            true
        }
        wm.addView(btn, lp)
        button = btn
    }

    private fun onTap() {
        if (overlay != null) { clearOverlay(); return }
        if (busy) return
        setupCapture()
        val img = latest ?: return toast("Chưa có khung hình, bấm lại lần nữa")
        val p = img.planes[0]
        p.buffer.rewind()
        val raw = Bitmap.createBitmap(p.rowStride / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(p.buffer)
        val bmp = Bitmap.createBitmap(raw, 0, 0, img.width, img.height)
        busy = true
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { showTranslations(it) }
            .addOnFailureListener { toast("OCR lỗi") }
            .addOnCompleteListener { busy = false }
    }

    private fun showTranslations(text: Text) {
        val view = FrameLayout(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(view, lp)
        overlay = view
        val blocks = text.textBlocks.filter { han.containsMatchIn(it.text) && it.boundingBox != null }
        if (blocks.isEmpty()) { toast("Không thấy chữ Trung"); clearOverlay(); return }
        for (blk in blocks) {
            val box = blk.boundingBox!!
            val src = blk.text.replace("\n", "")
            val add = { t: String ->
                view.addView(TextView(this).apply {
                    this.text = t; textSize = 12f
                    setTextColor(Color.WHITE); setBackgroundColor(0xCC000000.toInt()); setPadding(8, 4, 8, 4)
                }, FrameLayout.LayoutParams(max(box.width(), 300), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = box.left; topMargin = box.top
                })
            }
            val cached = cache[src]
            if (cached != null) add(cached)
            else translator.translate(src).addOnSuccessListener { cache[src] = it; add(it) }
        }
    }

    private fun clearOverlay() { overlay?.let { runCatching { wm.removeView(it) } }; overlay = null }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        clearOverlay()
        button?.let { runCatching { wm.removeView(it) } }
        latest?.close(); vd?.release(); reader?.close(); projection?.stop()
        translator.close(); recognizer.close()
        super.onDestroy()
    }
}
