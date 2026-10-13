package com.mytv.app

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

object IndexCache {
    private const val MAGIC = 0x4D494458
    private const val VER = 1

    private fun fileFor(dir: File, f: File, kind: String): File {
        val src = f.absolutePath + "|" + f.length() + "|" + f.lastModified() + "|" + kind
        val md = MessageDigest.getInstance("SHA-1").digest(src.toByteArray())
        val sb = StringBuilder(kind)
        for (b in md) sb.append(String.format("%02x", b))
        return File(dir, sb.toString() + ".idx")
    }

    private fun wl(o: DataOutputStream, a: LongArray) {
        o.writeInt(a.size)
        for (v in a) o.writeLong(v)
    }

    private fun rl(i: DataInputStream): LongArray {
        val n = i.readInt()
        if (n < 0 || n > 50_000_000) throw java.io.IOException("bad")
        return LongArray(n) { i.readLong() }
    }

    private fun wi(o: DataOutputStream, a: IntArray) {
        o.writeInt(a.size)
        for (v in a) o.writeInt(v)
    }

    private fun ri(i: DataInputStream): IntArray {
        val n = i.readInt()
        if (n < 0 || n > 50_000_000) throw java.io.IOException("bad")
        return IntArray(n) { i.readInt() }
    }

    private fun wb(o: DataOutputStream, a: BooleanArray) {
        o.writeInt(a.size)
        for (v in a) o.writeBoolean(v)
    }

    private fun rb(i: DataInputStream): BooleanArray {
        val n = i.readInt()
        if (n < 0 || n > 50_000_000) throw java.io.IOException("bad")
        return BooleanArray(n) { i.readBoolean() }
    }

    private fun save(dir: File, f: File, kind: String, body: (DataOutputStream) -> Unit) {
        try {
            dir.mkdirs()
            val out = fileFor(dir, f, kind)
            val tmp = File(dir, out.name + ".tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { o ->
                o.writeInt(MAGIC)
                o.writeInt(VER)
                body(o)
            }
            tmp.renameTo(out)
        } catch (e: Exception) {
        }
    }

    private fun <T> load(dir: File, f: File, kind: String, body: (DataInputStream) -> T): T? {
        try {
            val file = fileFor(dir, f, kind)
            if (!file.isFile) return null
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { i ->
                if (i.readInt() != MAGIC || i.readInt() != VER) return null
                return body(i)
            }
        } catch (e: Exception) {
            return null
        }
    }

    fun saveMkv(dir: File, f: File, x: MkvIndex) = save(dir, f, "k") { o ->
        o.writeLong(x.first)
        wl(o, x.offs); wl(o, x.tcPos); wi(o, x.tcLen); wl(o, x.raw); wl(o, x.ms); wb(o, x.key)
        o.writeBoolean(x.cues)
        o.writeLong(x.segSizePos); o.writeInt(x.segSizeLen); o.writeLong(x.segData); o.writeBoolean(x.segKnown)
        wl(o, x.cuesPos); wl(o, x.seekIdPos)
    }

    fun loadMkv(dir: File, f: File): MkvIndex? = load(dir, f, "k") { i ->
        MkvIndex(
            i.readLong(), rl(i), rl(i), ri(i), rl(i), rl(i), rb(i),
            i.readBoolean(), i.readLong(), i.readInt(), i.readLong(), i.readBoolean(), rl(i), rl(i)
        )
    }

    fun saveMp4(dir: File, f: File, x: Mp4Index) = save(dir, f, "m") { o ->
        o.writeLong(x.headerEnd)
        wl(o, x.moof); wl(o, x.startMs); wb(o, x.key); wb(o, x.hasVideo)
        wi(o, x.pFrag); wl(o, x.pPos); wi(o, x.pLen); wi(o, x.pTrack); wl(o, x.pVal)
        wi(o, x.bFrag); wl(o, x.bPos); wl(o, x.bVal); wl(o, x.sidx)
        o.writeInt(x.ts.size)
        for ((k, v) in x.ts) {
            o.writeInt(k)
            o.writeLong(v)
        }
        o.writeInt(x.videoTrack)
        o.writeUTF(x.boxes)
    }

    fun loadMp4(dir: File, f: File): Mp4Index? = load(dir, f, "m") { i ->
        val headerEnd = i.readLong()
        val moof = rl(i)
        val startMs = rl(i)
        val key = rb(i)
        val hasVideo = rb(i)
        val pFrag = ri(i)
        val pPos = rl(i)
        val pLen = ri(i)
        val pTrack = ri(i)
        val pVal = rl(i)
        val bFrag = ri(i)
        val bPos = rl(i)
        val bVal = rl(i)
        val sidx = rl(i)
        val n = i.readInt()
        val ts = HashMap<Int, Long>()
        for (j in 0 until n) {
            val k = i.readInt()
            ts[k] = i.readLong()
        }
        val vt = i.readInt()
        val boxes = i.readUTF()
        Mp4Index(headerEnd, moof, startMs, key, hasVideo, pFrag, pPos, pLen, pTrack, pVal, bFrag, bPos, bVal, sidx, ts, vt, boxes)
    }
}
