package com.example.gametranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {
    private class Seg(val box: Rect, val text: String, val lineH: Int)

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

    private var lang = "zh"
    private var recognizer: TextRecognizer? = null
    private var translator: Translator? = null
    private var script = Regex("\\p{IsHan}")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        wm = getSystemService(WindowManager::class.java)
        if (intent?.action == ACTION_TOGGLE) {
            if (projection != null) setLanguage(if (lang == "zh") "ja" else "zh")
            startForegroundNotification()
            return START_NOT_STICKY
        }
        if (projection == null && intent != null) {
            lang = intent.getStringExtra("lang") ?: "zh"
            startForegroundNotification()
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra("code", 0), data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
            setupCapture()
            addButton()
            setLanguage(lang)
        } else {
            startForegroundNotification()
        }
        return START_NOT_STICKY
    }

    private fun langName() = if (lang == "ja") "Nhật" else "Trung"

    private fun setLanguage(l: String) {
        lang = l
        gen++; busy = false; lastSig = ""
        cache.clear()
        clearOverlay()
        recognizer?.close(); translator?.close()
        recognizer = if (l == "ja") TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        else TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        script = if (l == "ja") Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]") else Regex("\\p{IsHan}")
        val src = if (l == "ja") TranslateLanguage.JAPANESE else TranslateLanguage.CHINESE
        val tr = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.VIETNAMESE).build()
        )
        translator = tr
        tr.downloadModelIfNeeded()
            .addOnFailureListener { toast("Không tải được model dịch, hãy bật mạng rồi thử lại") }
        toast("Đang dịch: ${langName()} → Việt")
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ov", "Dịch màn hình", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val act = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_rotate), "Đổi Trung ↔ Nhật", pi
        ).build()
        val n = Notification.Builder(this, "ov")
            .setContentTitle("DichManhinh đang chạy")
            .setContentText("Đang dịch: ${langName()} → Việt")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(act)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    // Kích thước thật của màn hình theo hướng hiện tại (dọc hoặc ngang)
    private fun realSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    // Android 14+: mỗi MediaProjection chỉ tạo VirtualDisplay 1 lần -> giữ lại, khi xoay màn hình thì resize
    private fun setupCapture() {
        val (w, h) = realSize()
        if (vd != null && w == capW && h == capH) return
        capW = w; capH = h
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
        // Bản dịch cũ không còn đúng vị trí khi đổi hướng màn hình
        gen++; lastSig = ""; clearOverlay()
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
        val rec = recognizer ?: return
        busy = true
        val g = gen
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { showTranslations(it, g) }
            .addOnFailureListener { busy = false; toast("OCR lỗi") }
    }

    // Gộp các dòng liền nhau thành 1 đoạn; danh sách dọc (menu) thì mỗi mục là 1 đoạn riêng.
    // lineH = cỡ chữ gốc (chiều cao 1 dòng) để chữ dịch có cỡ tỉ lệ theo.
    private fun groupLines(text: Text): List<Seg> {
        val out = ArrayList<Seg>()
        for (blk in text.textBlocks) {
            var cur: Rect? = null
            var last: Rect? = null
            var hSum = 0
            var n = 0
            val sb = StringBuilder()
            fun flush() {
                val c = cur
                if (c != null && sb.isNotEmpty() && script.containsMatchIn(sb)) {
                    out.add(Seg(c, sb.toString(), max(1, hSum / max(n, 1))))
                }
                cur = null; last = null; hSum = 0; n = 0; sb.setLength(0)
            }
            for (ln in blk.lines) {
                val r = ln.boundingBox ?: continue
                if (!script.containsMatchIn(ln.text)) { flush(); continue }
                val lh = min(r.width(), r.height())
                val l = last
                val join = l != null &&
                    (r.top - l.bottom) < lh * 0.6 && (r.top - l.bottom) > -lh * 0.5 &&
                    abs(r.left - l.left) < lh * 2
                if (!join) flush()
                val c = cur
                if (c == null) cur = Rect(r) else c.union(r)
                sb.append(ln.text)
                hSum += lh; n++
                last = r
            }
            flush()
        }
        return out
    }

    private fun showTranslations(text: Text, g: Int) {
        if (g != gen) { busy = false; return }
        val tr = translator
        if (tr == null) { busy = false; return }
        val groups = groupLines(text)
        if (groups.isEmpty()) {
            busy = false
            if (auto) { clearOverlay(); lastSig = "" }
            else toast("Không thấy chữ ${langName()}")
            return
        }
        val sig = groups.joinToString("|") { "${it.box.left / 20},${it.box.top / 20}:${it.text}" }
        if (auto && sig == lastSig && overlay != null) { busy = false; return }
        lastSig = sig

        // Dịch xong TẤT CẢ các đoạn rồi mới sắp xếp
        val results = ArrayList<Pair<Seg, String>>()
        var remaining = groups.size
        val done = {
            remaining--
            if (remaining == 0) layoutAll(results, g)
        }
        for (seg in groups) {
            val cached = cache[seg.text]
            if (cached != null) {
                results.add(Pair(seg, cached)); done()
            } else {
                tr.translate(seg.text)
                    .addOnSuccessListener { cache[seg.text] = it; results.add(Pair(seg, it)) }
                    .addOnCompleteListener { done() }
            }
        }
    }

    private fun measure(tv: TextView, w: Int, px: Float) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
    }

    private fun layoutAll(items: List<Pair<Seg, String>>, g: Int) {
        busy = false
        if (g != gen) return
        val view = FrameLayout(this)
        val placed = ArrayList<Rect>()
        for ((seg, t) in items.sortedWith(compareBy({ it.first.box.top }, { it.first.box.left }))) {
            val box = seg.box
            val tv = TextView(this).apply {
                text = t
                setTextColor(Color.WHITE)
                setBackgroundColor(0xE6000000.toInt())
                gravity = Gravity.CENTER_VERTICAL
                minHeight = box.height()      // phủ kín khung chữ gốc
                setPadding(max(3, seg.lineH / 6), max(1, seg.lineH / 16), max(3, seg.lineH / 6), max(1, seg.lineH / 16))
            }
            // Cỡ chữ tỉ lệ theo chữ gốc: 0.85 x chiều cao dòng gốc, giảm dần tới 0.5 x nếu chưa vừa khung
            val ideal = max(seg.lineH * 0.85f, 14f)
            val minPx = max(seg.lineH * 0.5f, 12f)
            // Mở rộng ngang khi cần, nhưng không lấn sang chữ gốc của khối khác
            val others = items.map { it.first.box }.filter { it !== box }
            val maxW = max(capW - box.left, 80)
            val widths = listOf(1.0, 1.4, 2.0, 3.0)
                .map { min(max((box.width() * it).toInt(), 80), maxW) }
                .distinct()
                .filterIndexed { i, cw ->
                    i == 0 || others.none {
                        Rect.intersects(Rect(box.left, box.top, box.left + cw, box.top + box.height()), it)
                    }
                }
            var px = ideal
            var w = widths.last()
            var h = 0
            var fitted = false
            while (px >= minPx && !fitted) {
                for (cw in widths) {
                    measure(tv, cw, px)
                    if (tv.measuredHeight <= box.height() * 1.15) {
                        w = cw; h = tv.measuredHeight; fitted = true
                        break
                    }
                }
                if (!fitted) px *= 0.92f
            }
            if (!fitted) { w = widths.last(); measure(tv, w, minPx); h = tv.measuredHeight }

            val left = max(0, min(box.left, capW - w))
            val r = Rect(left, box.top, left + w, box.top + h)
            // Nếu đè lên bản dịch đã đặt thì đẩy xuống dưới
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
        translator?.close(); recognizer?.close()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_TOGGLE = "com.example.gametranslator.TOGGLE_LANG"
        private const val COLOR_MANUAL = 0xCC1565C0.toInt()
        private const val COLOR_AUTO = 0xCC2E7D32.toInt()
    }
}
