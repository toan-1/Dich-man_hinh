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
import android.widget.TextView

class MainActivity : Activity() {
    private val reqCapture = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        val info = TextView(this).apply {
            text = "1) Cấp quyền \"Hiển thị trên ứng dụng khác\"\n2) Bấm Bắt đầu (lần đầu cần mạng để tải model dịch)\n3) Mở game, bấm nút Dịch để dịch, bấm lần nữa để xoá bản dịch"
            textSize = 16f
            setPadding(48, 96, 48, 48)
        }
        val btn = Button(this).apply { text = "Bắt đầu"; setOnClickListener { start() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(info); addView(btn)
        })
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, OverlayService::class.java).putExtra("code", resultCode).putExtra("data", data)
            )
            moveTaskToBack(true)
        }
    }
}
