package com.example.gametranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

class MainActivity : Activity() {
    private val reqCapture = 1
    private lateinit var rg: RadioGroup
    private var lang = "zh"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        val prefs = getSharedPreferences("s", MODE_PRIVATE)
        val info = TextView(this).apply {
            text = "1) Chọn ngôn ngữ game\n2) Cấp quyền \"Hiển thị trên ứng dụng khác\"\n3) Bấm Bắt đầu (lần đầu cần mạng để tải model dịch)\n4) Mở game: chạm nút Dịch để dịch / xoá, nhấn giữ nút để bật/tắt tự động dịch\n5) Đổi ngôn ngữ khi đang chơi: kéo thanh thông báo, bấm \"Đổi Trung ↔ Nhật\""
            textSize = 16f
            setPadding(48, 96, 48, 24)
        }
        rg = RadioGroup(this).apply { setPadding(48, 0, 48, 24) }
        rg.addView(RadioButton(this).apply { id = 101; text = "Tiếng Trung → Việt" })
        rg.addView(RadioButton(this).apply { id = 102; text = "Tiếng Nhật → Việt" })
        rg.check(if (prefs.getString("lang", "zh") == "ja") 102 else 101)
        val btn = Button(this).apply { text = "Bắt đầu"; setOnClickListener { start() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(info); addView(rg); addView(btn)
        })
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        lang = if (rg.checkedRadioButtonId == 102) "ja" else "zh"
        getSharedPreferences("s", MODE_PRIVATE).edit().putString("lang", lang).apply()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, OverlayService::class.java)
                    .putExtra("code", resultCode).putExtra("data", data).putExtra("lang", lang)
            )
            moveTaskToBack(true)
        }
    }
}
