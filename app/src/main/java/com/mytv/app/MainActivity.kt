package com.mytv.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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

    private fun needStoragePermission(): Boolean =
        Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (needStoragePermission()) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 2)
        }
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
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.loadUrl("http://localhost:8765/")
        web.requestFocus()
    }

    inner class Bridge {
        @JavascriptInterface
        fun pickFolder() {
            runOnUiThread {
                if (needStoragePermission()) {
                    requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 2)
                }
                try {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        ), 1
                    )
                } catch (e: ActivityNotFoundException) {
                    web.evaluateJavascript("window.browse&&window.browse('')", null)
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
            server?.setTree(u)
            web.evaluateJavascript("window.onFolder&&window.onFolder()", null)
        }
    }

    private fun mapped(code: Int): String? = when (code) {
        KeyEvent.KEYCODE_CHANNEL_UP -> "ChannelUp"
        KeyEvent.KEYCODE_CHANNEL_DOWN -> "ChannelDown"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "MediaPlayPause"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "MediaRewind"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "MediaFastForward"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "MediaTrackNext"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "MediaTrackPrevious"
        KeyEvent.KEYCODE_MENU -> "ContextMenu"
        KeyEvent.KEYCODE_GUIDE -> "Guide"
        KeyEvent.KEYCODE_INFO -> "Info"
        else -> null
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val k = mapped(ev.keyCode)
        if (k != null) {
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) key(k, ev.keyCode)
            return true
        }
        if (!web.hasFocus()) web.requestFocus()
        return super.dispatchKeyEvent(ev)
    }

    private fun key(k: String, code: Int) {
        web.evaluateJavascript(
            "document.dispatchEvent(new KeyboardEvent('keydown',{key:'$k',keyCode:$code,bubbles:true}))",
            null
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("(window.__back&&window.__back())+''") { v ->
            if (v != "\"true\"") finish()
        }
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
