package com.mytv.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class LocalServer(private val ctx: Context, port: Int) : NanoHTTPD("127.0.0.1", port) {
    class Entry(val name: String, val len: () -> Long, val open: () -> InputStream?, val file: File? = null)

    @Volatile private var files: Map<String, Entry> = emptyMap()
    @Volatile private var scan: Thread? = null
    private val prefs = ctx.getSharedPreferences("p", 0)

    init {
        val root = prefs.getString("root", null)
        val tree = prefs.getString("tree", null)
        if (root != null) setRoot(File(root)) else if (tree != null) setTree(Uri.parse(tree))
    }

    private fun startScan(block: () -> Map<String, Entry>) {
        val t = Thread {
            try {
                files = block()
            } catch (e: Exception) {
            }
        }
        scan = t
        t.start()
    }

    fun setRoot(dir: File) {
        prefs.edit().putString("root", dir.absolutePath).remove("tree").apply()
        startScan {
            val m = HashMap<String, Entry>()
            walkFile(dir, if (dir.name.isEmpty()) "Disk" else dir.name, m)
            m
        }
    }

    fun setTree(u: Uri) {
        prefs.edit().putString("tree", u.toString()).remove("root").apply()
        startScan {
            val m = HashMap<String, Entry>()
            val root = DocumentFile.fromTreeUri(ctx, u)
            if (root != null) walkDoc(root, root.name ?: "Disk", m)
            m
        }
    }

    private fun walkFile(d: File, path: String, m: MutableMap<String, Entry>) {
        val list = d.listFiles() ?: return
        for (f in list) {
            val p = "$path/${f.name}"
            if (f.isDirectory) walkFile(f, p, m)
            else m[p] = Entry(f.name, { f.length() }, { java.io.BufferedInputStream(FileInputStream(f), 1 shl 20) }, f)
        }
    }

    private fun walkDoc(d: DocumentFile, path: String, m: MutableMap<String, Entry>) {
        for (f in d.listFiles()) {
            val n = f.name ?: continue
            val p = "$path/$n"
            if (f.isDirectory) walkDoc(f, p, m)
            else m[p] = Entry(n, { f.length() }, { ctx.contentResolver.openInputStream(f.uri) })
        }
    }

    fun rescan() {
        val root = prefs.getString("root", null)
        val tree = prefs.getString("tree", null)
        if (root != null) setRoot(File(root)) else if (tree != null) setTree(Uri.parse(tree))
    }

    private fun ensure() {
        if (files.isEmpty() && scan?.isAlive != true) {
            prefs.getString("root", null)?.let { val f = File(it); if (f.exists()) setRoot(f) }
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

    private fun json(o: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())

    private fun roots(): JSONObject {
        val arr = JSONArray()
        val seen = HashSet<String>()
        fun add(name: String, f: File?) {
            if (f != null && seen.add(f.absolutePath)) {
                arr.put(JSONObject().put("name", name).put("path", f.absolutePath))
            }
        }
        try {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (v in sm.storageVolumes) {
                val dir: File? = if (Build.VERSION.SDK_INT >= 30) {
                    v.directory
                } else {
                    try {
                        v.javaClass.getMethod("getPathFile").invoke(v) as File?
                    } catch (e: Exception) {
                        null
                    }
                }
                add(v.getDescription(ctx) + (if (v.isRemovable) " (εξωτερικός)" else ""), dir)
            }
        } catch (e: Exception) {
        }
        try {
            File("/storage").listFiles()?.forEach {
                if (it.name != "self" && it.name != "emulated") add(it.name, it)
            }
        } catch (e: Exception) {
        }
        add("Εσωτερική μνήμη", Environment.getExternalStorageDirectory())
        return JSONObject().put("dirs", arr).put("info", "Android API " + Build.VERSION.SDK_INT)
    }

    private fun browse(path: String): JSONObject {
        val f = File(path)
        val par = f.parent?.takeIf { it != "/storage" && it != "/" && it != "/storage/emulated" } ?: ""
        val l = f.listFiles()
            ?: return JSONObject().put("error", "denied").put("path", path).put("parent", par)
        val arr = JSONArray()
        l.filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
            .forEach { arr.put(JSONObject().put("name", it.name).put("path", it.absolutePath)) }
        val vids = setOf("mp4", "m4v", "webm", "mkv", "mov", "avi")
        val n = l.count { it.isFile && it.name.substringAfterLast('.', "").lowercase() in vids }
        val js = JSONArray()
        l.filter { it.isFile && it.name.endsWith(".json", true) }
            .sortedBy { it.name.lowercase() }
            .forEach { js.put(it.name) }
        return JSONObject().put("path", path).put("parent", par).put("dirs", arr).put("videos", n).put("jsons", js)
    }

    override fun serve(s: IHTTPSession): Response {
        val uri = s.uri
        val host = (s.headers["host"] ?: "").substringBefore(":")
        if (host != "localhost" && host != "127.0.0.1") return notFound()
        try {
            if (uri == "/" || uri == "/index.html") {
                val r = newChunkedResponse(
                    Response.Status.OK, "text/html; charset=utf-8", ctx.assets.open("index.html")
                )
                r.addHeader("Cache-Control", "no-store")
                return r
            }
            if (uri == "/api/list") {
                ensure()
                scan?.join(120000)
                val arr = JSONArray()
                for (p in files.keys) arr.put(JSONObject().put("path", p))
                return json(JSONObject().put("files", arr))
            }
            if (uri == "/api/read") {
                val p = s.parameters["path"]?.firstOrNull() ?: ""
                val f = File(p)
                if (f.isFile && p.endsWith(".json", true) && f.length() < 50_000_000L) {
                    return newFixedLengthResponse(
                        Response.Status.OK, "application/json; charset=utf-8", f.readText()
                    )
                }
                return notFound()
            }
            if (uri == "/api/rescan") {
                rescan()
                return json(JSONObject().put("ok", true))
            }
            if (uri == "/api/clearroot") {
                prefs.edit().remove("root").remove("tree").apply()
                files = emptyMap()
                return json(JSONObject().put("ok", true))
            }
            if (uri == "/api/dur") {
                scan?.join(120000)
                val f = files[s.parameters["path"]?.firstOrNull() ?: ""]?.file
                var sec = 0L
                if (f != null) {
                    try {
                        val r = MediaMetadataRetriever()
                        r.setDataSource(f.absolutePath)
                        sec = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000
                        r.release()
                    } catch (e: Exception) {
                    }
                }
                return json(JSONObject().put("sec", sec))
            }
            if (uri == "/api/roots") return json(roots())
            if (uri == "/api/browse") return json(browse(s.parameters["path"]?.firstOrNull() ?: ""))
            if (uri == "/api/setroot") {
                val p = s.parameters["path"]?.firstOrNull()
                if (p != null) setRoot(File(p))
                return json(JSONObject().put("ok", p != null))
            }
            if (uri.startsWith("/f/")) {
                val e = files[uri.substring(3)] ?: return notFound()
                return stream(e, s)
            }
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.toString())
        }
        return notFound()
    }

    private fun stream(e: Entry, s: IHTTPSession): Response {
        val len = e.len()
        val rh = s.headers["range"]
        var start = 0L
        var end = len - 1
        var part = false
        if (rh != null && rh.startsWith("bytes=")) {
            val a = rh.substring(6).split(",")[0].trim().split("-")
            val s0 = a.getOrNull(0)?.toLongOrNull()
            val e0 = a.getOrNull(1)?.toLongOrNull()
            if (s0 == null && e0 != null) {
                start = maxOf(0L, len - e0)
            } else {
                start = s0 ?: 0L
                if (e0 != null) end = minOf(e0, len - 1)
            }
            part = true
        }
        if (start >= len || end < start) {
            val r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
            r.addHeader("Content-Range", "bytes */$len")
            return r
        }
        val ins = e.open() ?: return notFound()
        var sk = start
        while (sk > 0) {
            val k = ins.skip(sk)
            if (k <= 0) break
            sk -= k
        }
        val r = newFixedLengthResponse(
            if (part) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
            mime(e.name), ins, end - start + 1
        )
        r.addHeader("Accept-Ranges", "bytes")
        if (part) r.addHeader("Content-Range", "bytes $start-$end/$len")
        return r
    }
}