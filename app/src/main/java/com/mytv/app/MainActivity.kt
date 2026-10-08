package com.mytv.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var dbg: TextView
    private lateinit var np: NativePlayer
    private var server: LocalServer? = null
    private var needResume = false
    private val log = ArrayList<String>()

    private fun note(s: String) {
        runOnUiThread {
            log.add(s)
            while (log.size > 12) log.removeAt(0)
            dbg.text = log.joinToString("\n")
        }
    }

    private fun needStoragePermission(): Boolean =
        Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = FrameLayout(this)
        web = WebView(this)
        dbg = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            textSize = 14f
            setPadding(16, 8, 16, 8)
        }
        root.setBackgroundColor(0xFF121214.toInt())
        np = NativePlayer(this, root) { js -> runOnUiThread { web.evaluateJavascript(js, null) } }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            dbg,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )
        setContentView(root)

        note("v1.5 Android API " + Build.VERSION.SDK_INT)
        note("index.html στην εφαρμογή: " + (assets.list("")?.contains("index.html") == true))
        note("άδεια αποθήκευσης: " + !needStoragePermission())
        if (needStoragePermission()) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 2)
        }
        try {
            server = LocalServer(this, 8765).also { it.start() }
            note("server OK")
        } catch (e: Exception) {
            note("server ΣΦΑΛΜΑ: $e")
        }

        WebView.setWebContentsDebuggingEnabled(true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    note("JS: " + m.message() + " @" + m.lineNumber())
                }
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                note("φορτώθηκε: $url")
            }

            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) note("ΣΦΑΛΜΑ φόρτωσης: " + e.description + " " + r.url)
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidTV")
        web.setBackgroundColor(Color.TRANSPARENT)
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.loadUrl("http://localhost:8765/")
        web.requestFocus()
    }

    inner class Bridge {
        @JavascriptInterface
        fun play(url: String, startMs: Double, live: Boolean) {
            runOnUiThread { np.play(url, startMs.toLong(), live) }
        }

        @JavascriptInterface
        fun stop() {
            runOnUiThread { np.stop() }
        }

        @JavascriptInterface
        fun pause() {
            runOnUiThread { np.pause() }
        }

        @JavascriptInterface
        fun resume() {
            runOnUiThread { np.resume() }
        }

        @JavascriptInterface
        fun seekBy(ms: Double) {
            runOnUiThread { np.seekBy(ms.toLong()) }
        }

        @JavascriptInterface
        fun nextAudio() {
            runOnUiThread {
                val l = np.nextAudio()
                web.evaluateJavascript("window.onAudio&&window.onAudio(" + JSONObject.quote(l) + ")", null)
            }
        }

        @JavascriptInterface
        fun saveState(json: String) {
            try {
                File(filesDir, "state.json").writeText(json)
            } catch (e: Exception) {
            }
        }

        @JavascriptInterface
        fun loadState(): String =
            try {
                File(filesDir, "state.json").readText()
            } catch (e: Exception) {
                ""
            }

        @JavascriptInterface
        fun ready() {
            runOnUiThread { dbg.visibility = View.GONE }
        }

        @JavascriptInterface
        fun pickFolder() {
            runOnUiThread {
                if (needStoragePermission()) {
                    requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 2)
                }
                web.evaluateJavascript("window.browse&&window.browse('')", null)
            }
        }

        @JavascriptInterface
        fun pickSystem() {
            runOnUiThread {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
                if (i.resolveActivity(packageManager) == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "Η συσκευή δεν έχει επιλογέα αρχείων.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                try {
                    startActivityForResult(i, 1)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Σφάλμα: $e", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            note("άδεια αποθήκευσης: " + !needStoragePermission())
            if (!needStoragePermission()) {
                server?.rescan()
                web.evaluateJavascript("window.onFolder&&window.onFolder()", null)
            } else {
                web.evaluateJavascript("window.browse&&window.browse('')", null)
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
        KeyEvent.KEYCODE_GUIDE -> "Guide"
        KeyEvent.KEYCODE_INFO -> "Info"
        else -> null
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
            note("πλήκτρο " + KeyEvent.keyCodeToString(ev.keyCode))
        }
        if (ev.keyCode == KeyEvent.KEYCODE_MENU) {
            if (ev.action == KeyEvent.ACTION_DOWN) {
                dbg.visibility = if (dbg.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            return true
        }
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
        if (hasFocus && needResume) {
            needResume = false
            web.evaluateJavascript("window.onResumeApp&&window.onResumeApp()", null)
        }
        if (hasFocus) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun onStop() {
        np.stop()
        needResume = true
        web.evaluateJavascript("window.onNativeStopped&&window.onNativeStopped()", null)
        super.onStop()
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }
}