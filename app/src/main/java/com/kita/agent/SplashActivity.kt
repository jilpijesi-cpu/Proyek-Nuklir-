package com.kita.agent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.ImageView

/** Layar pembuka: tampil sebentar saat aplikasi pertama dibuka, lalu lanjut ke MainActivity. */
class SplashActivity : Activity() {
    companion object {
        private const val DURATION_MS = 1500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)

        val iv = ImageView(this).apply {
            setImageResource(R.drawable.splash_art)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        setContentView(iv)

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }, DURATION_MS)
    }

    override fun onBackPressed() {
        // Jangan biarkan pengguna "kembali" ke splash kosong setelah muncul lagi dari recents.
        super.onBackPressed()
        finish()
    }
}
