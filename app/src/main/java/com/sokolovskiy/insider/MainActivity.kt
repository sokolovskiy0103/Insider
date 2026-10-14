package com.sokolovskiy.insider

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#0E1320")
        window.navigationBarColor = Color.parseColor("#0E1320")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0E1320"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            }
        }
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0E1320"))
            addView(web, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)

        // Android 15 draws apps edge-to-edge: keep the page clear of the status bar,
        // navigation bar and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        startForegroundService(Intent(this, GameService::class.java))

        // Starting the server touches the network: do it off the UI thread.
        Thread {
            val server = InsiderHost.ensure(this)
            runOnUiThread {
                web.loadUrl("http://127.0.0.1:${server.port}/host#${server.hostToken}")
            }
        }.start()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Keep the game running; leave via the notification's "Завершити гру".
                moveTaskToBack(true)
            }
        })
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
