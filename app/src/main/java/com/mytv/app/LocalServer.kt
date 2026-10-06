package com.mytv.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

class LocalServer(private val ctx: Context, port: Int) : NanoHTTPD("127.0.0.1", port) {
    @Volatile private var files: Map<String, DocumentFile> = emptyMap()
    @Volatile private var scan: Thread? = null

    init {
        ctx.getSharedPreferences("p", 0).getString("tree", null)?.let { setTree(Uri.parse(it)) }
    }

    fun setTree(u: Uri) {
        val t = Thread {
            try {
                val root = DocumentFile.fromTreeUri(ctx, u) ?: return@Thread
                val m = HashMap<String, DocumentFile>()
                walk(root, root.name ?: "Disk", m)
                files = m
            } catch (e: Exception) {
            }
        }
        scan = t
        t.start()
    }

    private fun walk(d: DocumentFile, path: String, m: MutableMap<String, DocumentFile>) {
        for (f in d.listFiles()) {
            val n = f.name ?: continue
            val p = "$path/$n"
            if (f.isDirectory) walk(f, p, m) else m[p] = f
        }
    }

    private fun mime(n: String): String = when (n.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")

    override fun serve(s: IHTTPSession): Response {
        val uri = s.uri
        try {
            if (uri == "/" || uri == "/index.html") {
                val r = newChunkedResponse(
                    Response.Status.OK, "text/html; charset=utf-8", ctx.assets.open("index.html")
                )
                r.addHeader("Cache-Control", "no-store")
                return r
            }
            if (uri == "/api/list") {
                scan?.join(120000)
                val arr = JSONArray()
                for (p in files.keys) arr.put(JSONObject().put("path", p))
                return newFixedLengthResponse(
                    Response.Status.OK, "application/json; charset=utf-8",
                    JSONObject().put("files", arr).toString()
                )
            }
            if (uri.startsWith("/f/")) {
                val f = files[uri.substring(3)] ?: return notFound()
                return stream(f, s)
            }
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.toString())
        }
        return notFound()
    }

    private fun stream(f: DocumentFile, s: IHTTPSession): Response {
        val len = f.length()
        val rh = s.headers["range"]
        var start = 0L
        var end = len - 1
        var part = false
        if (rh != null && rh.startsWith("bytes=")) {
            val a = rh.substring(6).split("-")
            start = a[0].toLongOrNull() ?: 0L
            if (a.size > 1 && a[1].isNotEmpty()) end = minOf(a[1].toLong(), len - 1)
            part = true
        }
        if (start >= len) {
            val r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
            r.addHeader("Content-Range", "bytes */$len")
            return r
        }
        val ins = ctx.contentResolver.openInputStream(f.uri) ?: return notFound()
        var sk = start
        while (sk > 0) {
            val k = ins.skip(sk)
            if (k <= 0) break
            sk -= k
        }
        val r = newFixedLengthResponse(
            if (part) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
            mime(f.name ?: ""), ins, end - start + 1
        )
        r.addHeader("Accept-Ranges", "bytes")
        if (part) r.addHeader("Content-Range", "bytes $start-$end/$len")
        return r
    }
}
