package com.mytv.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var server: LocalServer? = null
    @Volatile private var inInput = false

    private val focusJs =
        "if(!window.__ftk){window.__ftk=1;" +
            "document.addEventListener('focusin',function(e){try{AndroidTV.inp(/^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName||''))}catch(x){}},true);" +
            "document.addEventListener('focusout',function(){try{AndroidTV.inp(false)}catch(x){}},true)}"

    private val okJs =
        "(function(){var a=document.activeElement||document.body;" +
            "var ev=new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true});" +
            "a.dispatchEvent(ev);" +
            "if(ev.defaultPrevented||a===document.body)return;" +
            "if(a.tagName==='LABEL'||a.classList.contains('cv'))return;" +
            "if(a.tagName==='BUTTON'||a.tagName==='A'||a.tagName==='SUMMARY'||a.hasAttribute('tabindex'))a.click()})()"

    private fun needStoragePermission(): Boolean =
        Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        web = WebView(this)
        web.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(web)

        if (needStoragePermission()) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 2)
        }
        try {
            server = LocalServer(this, 8765).also { it.start() }
        } catch (e: Exception) {
            Toast.makeText(this, "Ο τοπικός server δεν ξεκίνησε: $e", Toast.LENGTH_LONG).show()
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                v.evaluateJavascript(focusJs, null)
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidTV")
        web.setBackgroundColor(0xFF121214.toInt())
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.loadUrl("http://localhost:8765/")
        web.requestFocus()
    }

    inner class Bridge {
        @JavascriptInterface
        fun ready() {
        }

        @JavascriptInterface
        fun inp(b: Boolean) {
            inInput = b
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
        KeyEvent.KEYCODE_MENU -> "ContextMenu"
        else -> null
    }

    private fun isOk(code: Int): Boolean =
        code == KeyEvent.KEYCODE_DPAD_CENTER ||
            code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            code == KeyEvent.KEYCODE_BUTTON_A

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val code = ev.keyCode
        if (isOk(code) && !inInput) {
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                web.evaluateJavascript(okJs, null)
            }
            return true
        }
        val k = mapped(code)
        if (k != null) {
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) key(k, code)
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
