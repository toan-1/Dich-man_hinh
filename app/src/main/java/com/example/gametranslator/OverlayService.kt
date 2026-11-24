package com.example.gametranslator

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
import kotlin.math.min

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var capW = 0
    private var capH = 0
    private var latest: Image? = null
    private var frameSeq = 0
    private var button: TextView? = null
    private var overlay: FrameLayout? = null
    private var busy = false
    private var auto = false
    private var gen = 0          // tăng lên để huỷ các kết quả dịch đang chạy dở
    private var lastSig = ""
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
            if (img != null) { latest?.close(); latest = img; frameSeq++ }
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
        gen++
        clearOverlay()
        lastSig = ""
        handler.postDelayed({ setupCapture() }, 400)
    }

    // ---------- Nút nổi: chạm = dịch/xoá, nhấn giữ = bật/tắt tự động dịch ----------
    private fun addButton() {
        val lp = WindowManager.LayoutParams(
            140, 140, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }
        val btn = TextView(this).apply {
            text = "Dịch"; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setBackgroundColor(COLOR_MANUAL)
        }
        var downX = 0f; var downY = 0f; var offX = 0f; var offY = 0f
        var moved = false; var longFired = false
        val longPress = Runnable { longFired = true; setAuto(!auto) }
        btn.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; offX = e.rawX - lp.x; offY = e.rawY - lp.y
                    moved = false; longFired = false
                    handler.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > 15 || abs(e.rawY - downY) > 15) {
                        moved = true; handler.removeCallbacks(longPress)
                    }
                    if (moved) { lp.x = (e.rawX - offX).toInt(); lp.y = (e.rawY - offY).toInt(); wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (!moved && !longFired) onTap()
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            }
            true
        }
        wm.addView(btn, lp)
        button = btn
    }

    private fun onTap() {
        if (auto) { setAuto(false); return }
        if (overlay != null) { gen++; clearOverlay(); return }
        if (busy) return
        val bmp = grabBitmap(false) ?: return
        recognize(bmp)
    }

    private fun setAuto(on: Boolean) {
        auto = on
        gen++
        lastSig = ""
        handler.removeCallbacks(autoRunnable)
        clearOverlay()
        button?.setBackgroundColor(if (on) COLOR_AUTO else COLOR_MANUAL)
        if (on) {
            toast("Tự động dịch: BẬT (chạm nút để tắt)")
            handler.postDelayed(autoRunnable, 300)
        } else {
            toast("Tự động dịch: TẮT")
        }
    }

    // ---------- Tự động dịch ----------
    // Overlay cũng bị chụp vào ảnh nên phải ẩn đi một chút trước khi chụp
    private val autoRunnable = object : Runnable {
        override fun run() {
            if (!auto) return
            if (busy) { handler.postDelayed(this, 500); return }
            val ov = overlay
            if (ov == null) {
                grabBitmap(true)?.let { recognize(it) }
                handler.postDelayed(this, 1500)
            } else {
                val seq = frameSeq
                ov.visibility = View.INVISIBLE
                waitFrame(seq, 0L) {
                    val bmp = grabBitmap(true)
                    ov.visibility = View.VISIBLE
                    if (bmp != null) recognize(bmp)
                    handler.postDelayed(this, 1500)
                }
            }
        }
    }

    private fun waitFrame(seq: Int, waited: Long, then: () -> Unit) {
        if ((frameSeq > seq && waited >= 150) || waited >= 700) then()
        else handler.postDelayed({ waitFrame(seq, waited + 50, then) }, 50)
    }

    // ---------- Chụp + OCR ----------
    private fun grabBitmap(quiet: Boolean): Bitmap? {
        setupCapture()
        val img = latest
        if (img == null) {
            if (!quiet) toast("Chưa có khung hình, bấm lại lần nữa")
            return null
        }
        val p = img.planes[0]
        p.buffer.rewind()
        val raw = Bitmap.createBitmap(p.rowStride / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(p.buffer)
        return Bitmap.createBitmap(raw, 0, 0, img.width, img.height)
    }

    private fun recognize(bmp: Bitmap) {
        busy = true
        val g = gen
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { showTranslations(it, g) }
            .addOnFailureListener { busy = false; toast("OCR lỗi") }
    }

    // Gộp các dòng liền nhau thành 1 đoạn; danh sách dọc (menu) thì mỗi mục là 1 đoạn riêng
    private fun groupLines(text: Text): List<Pair<Rect, String>> {
        val out = ArrayList<Pair<Rect, String>>()
        for (blk in text.textBlocks) {
            var cur: Rect? = null
            var last: Rect? = null
            val sb = StringBuilder()
            fun flush() {
                val c = cur
                if (c != null && sb.isNotEmpty() && han.containsMatchIn(sb)) out.add(Pair(c, sb.toString()))
                cur = null; last = null; sb.setLength(0)
            }
            for (ln in blk.lines) {
                val r = ln.boundingBox ?: continue
                if (!han.containsMatchIn(ln.text)) { flush(); continue }
                val l = last
                val h = r.height()
                val join = l != null &&
                    (r.top - l.bottom) < h * 0.6 && (r.top - l.bottom) > -h * 0.5 &&
                    abs(r.left - l.left) < h * 2
                if (!join) flush()
                val c = cur
                if (c == null) cur = Rect(r) else c.union(r)
                sb.append(ln.text)
                last = r
            }
            flush()
        }
        return out
    }

    private fun showTranslations(text: Text, g: Int) {
        if (g != gen) { busy = false; return }
        val groups = groupLines(text)
        if (groups.isEmpty()) {
            busy = false
            if (auto) { clearOverlay(); lastSig = "" } else toast("Không thấy chữ Trung")
            return
        }
        val sig = groups.joinToString("|") { "${it.first.left / 20},${it.first.top / 20}:${it.second}" }
        if (auto && sig == lastSig && overlay != null) { busy = false; return }
        lastSig = sig

        // Dịch xong TẤT CẢ các đoạn rồi mới sắp xếp
        val results = ArrayList<Pair<Rect, String>>()
        var remaining = groups.size
        val done = {
            remaining--
            if (remaining == 0) layoutAll(results, g)
        }
        for ((box, src) in groups) {
            val cached = cache[src]
            if (cached != null) {
                results.add(Pair(box, cached)); done()
            } else {
                translator.translate(src)
                    .addOnSuccessListener { cache[src] = it; results.add(Pair(box, it)) }
                    .addOnCompleteListener { done() }
            }
        }
    }

    private fun measure(tv: TextView, w: Int, sp: Float) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
    }

    private fun layoutAll(items: List<Pair<Rect, String>>, g: Int) {
        busy = false
        if (g != gen) return
        val view = FrameLayout(this)
        val placed = ArrayList<Rect>()
        for ((box, t) in items.sortedWith(compareBy({ it.first.top }, { it.first.left }))) {
            val tv = TextView(this).apply {
                text = t
                setTextColor(Color.WHITE)
                setBackgroundColor(0xE6000000.toInt())
                setPadding(6, 2, 6, 2)
            }
            // Thử các độ rộng tăng dần và cỡ chữ giảm dần (16sp -> 9sp) cho vừa khung chữ gốc
            val maxW = max(capW - box.left, 100)
            val widths = listOf(1.0, 1.6, 2.4)
                .map { min(max((box.width() * it).toInt(), 160), maxW) }
                .distinct()
            var w = widths.last()
            var h = 0
            var fitted = false
            loop@ for (cw in widths) {
                var size = 16f
                while (size >= 9f) {
                    measure(tv, cw, size)
                    if (tv.measuredHeight <= box.height() * 1.2) {
                        w = cw; h = tv.measuredHeight; fitted = true
                        break@loop
                    }
                    size -= 1f
                }
            }
            if (!fitted) { measure(tv, w, 9f); h = tv.measuredHeight }

            val left = max(0, min(box.left, capW - w))
            val r = Rect(left, box.top, left + w, box.top + h)
            // Nếu đè lên khối đã đặt thì đẩy xuống dưới
            var moved = true
            var guard = 0
            while (moved && guard++ < 50) {
                moved = false
                for (p in placed) {
                    if (Rect.intersects(r, p)) { r.offsetTo(r.left, p.bottom + 2); moved = true }
                }
            }
            if (r.bottom > capH) r.offsetTo(r.left, max(0, capH - h))
            placed.add(Rect(r))
            view.addView(tv, FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = r.left; topMargin = r.top
            })
        }
        val old = overlay
        wm.addView(view, overlayParams())
        overlay = view
        old?.let { runCatching { wm.removeView(it) } }
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun clearOverlay() { overlay?.let { runCatching { wm.removeView(it) } }; overlay = null }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        auto = false
        handler.removeCallbacksAndMessages(null)
        clearOverlay()
        button?.let { runCatching { wm.removeView(it) } }
        latest?.close(); vd?.release(); reader?.close(); projection?.stop()
        translator.close(); recognizer.close()
        super.onDestroy()
    }

    companion object {
        private const val COLOR_MANUAL = 0xCC1565C0.toInt()
        private const val COLOR_AUTO = 0xCC2E7D32.toInt()
    }
}
