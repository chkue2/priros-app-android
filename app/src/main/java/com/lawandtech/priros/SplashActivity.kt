package com.lawandtech.priros

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

class SplashActivity: AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.splash_screen)

        val url = intent.getStringExtra("url")

        Handler(Looper.getMainLooper()).postDelayed({
            val mainIntent = Intent(this, MainActivity::class.java)
            mainIntent.putExtra("url", url)
            startActivity(mainIntent)

            finish()
        }, 2000)
    }
}