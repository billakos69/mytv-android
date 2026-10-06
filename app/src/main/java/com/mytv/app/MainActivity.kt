package com.mytv.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var server: LocalServer? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try {
            server = LocalServer(this, 8765).also { it.start() }
        } catch (e: Exception) {
            Toast.makeText(this, "Server: $e", Toast.LENGTH_LONG).show()
        }
        web = WebView(this)
        setContentView(web)
        WebView.setWebContentsDebuggingEnabled(true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "AndroidTV")
        web.setBackgroundColor(0xFF121214.toInt())
        web.loadUrl("http://localhost:8765/")
        web.requestFocus()
    }

    inner class Bridge {
        @JavascriptInterface
        fun pickFolder() {
            runOnUiThread {
                try {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        ), 1
                    )
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(
                        this@MainActivity,
                        "Δεν βρέθηκε επιλογέας αρχείων. Εγκατάστησε μια εφαρμογή αρχείων (π.χ. Files by Google ή X-plore).",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val u = data?.data
        if (req == 1 && res == RESULT_OK && u != null) {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            getSharedPreferences("p", 0).edit().putString("tree", u.toString()).apply()
            server?.setTree(u)
            web.evaluateJavascript("window.onFolder&&window.onFolder()", null)
        }
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        when (code) {
            KeyEvent.KEYCODE_BACK -> {
                web.evaluateJavascript("(window.__back&&window.__back())+''") { v ->
                    if (v != "\"true\"") finish()
                }
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> { key("ChannelUp"); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { key("ChannelDown"); return true }
        }
        return super.onKeyDown(code, e)
    }

    private fun key(k: String) {
        web.evaluateJavascript(
            "document.dispatchEvent(new KeyboardEvent('keydown',{key:'$k'}))", null
        )
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }
}
