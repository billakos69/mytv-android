package com.mytv.app

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

class MkvIndex(
    val first: Long,
    val offs: LongArray,
    val tcPos: LongArray,
    val tcLen: IntArray,
    val raw: LongArray,
    val ms: LongArray,
    val cues: Boolean
)

object Mkv {
    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val INFO = 0x1549A966L
    private const val SCALE = 0x2AD7B1L
    private const val CLUSTER = 0x1F43B675L
    private const val TIMECODE = 0xE7L
    private const val CUES = 0x1C53BB6BL

    private fun readId(r: RandomAccessFile): Long {
        val b = r.read()
        if (b < 0) return -1
        var len = 1
        var mask = 0x80
        while (len <= 4 && (b and mask) == 0) {
            len++
            mask = mask shr 1
        }
        if (len > 4) return -2
        var v = b.toLong()
        for (i in 1 until len) {
            val c = r.read()
            if (c < 0) return -1
            v = (v shl 8) or c.toLong()
        }
        return v
    }

    private fun readSize(r: RandomAccessFile): Long {
        val b = r.read()
        if (b < 0) return -2
        var len = 1
        var mask = 0x80
        while (len <= 8 && (b and mask) == 0) {
            len++
            mask = mask shr 1
        }
        if (len > 8) return -2
        var v = (b and (mask - 1)).toLong()
        var ones = (b and (mask - 1)) == (mask - 1)
        for (i in 1 until len) {
            val c = r.read()
            if (c < 0) return -2
            if (c != 0xFF) ones = false
            v = (v shl 8) or c.toLong()
        }
        return if (ones) -1 else v
    }

    private fun readUInt(r: RandomAccessFile, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or r.read().toLong()
        return v
    }

    fun index(f: File): MkvIndex? {
        var result: MkvIndex? = null
        try {
            RandomAccessFile(f, "r").use { r -> result = build(r) }
        } catch (e: Exception) {
            result = null
        }
        return result
    }

    private fun build(r: RandomAccessFile): MkvIndex? {
        if (readId(r) != EBML) return null
        val hs = readSize(r)
        if (hs < 0) return null
        r.seek(r.filePointer + hs)
        if (readId(r) != SEGMENT) return null
        val ss = readSize(r)
        val seg = r.filePointer
        val segEnd = if (ss >= 0) minOf(seg + ss, r.length()) else r.length()
        var scale = 1_000_000L
        var first = -1L
        var cues = false
        val offs = ArrayList<Long>()
        val tcPos = ArrayList<Long>()
        val tcLen = ArrayList<Int>()
        val raw = ArrayList<Long>()
        var pos = seg
        while (pos < segEnd) {
            r.seek(pos)
            val id = readId(r)
            if (id < 0) break
            val sz = readSize(r)
            if (sz < -1) break
            val ds = r.filePointer
            if (sz == -1L) {
                if (id == CLUSTER) return null
                break
            }
            if (id == INFO) {
                var p2 = ds
                val e2 = ds + sz
                while (p2 < e2) {
                    r.seek(p2)
                    val id2 = readId(r)
                    val s2 = readSize(r)
                    if (id2 < 0 || s2 < 0) break
                    val d2 = r.filePointer
                    if (id2 == SCALE) scale = readUInt(r, s2.toInt())
                    p2 = d2 + s2
                }
            } else if (id == CUES) {
                cues = true
            } else if (id == CLUSTER) {
                if (first < 0) first = pos
                var p3 = ds
                val e3 = minOf(ds + sz, segEnd)
                var found = false
                while (p3 < e3 && !found) {
                    r.seek(p3)
                    val id3 = readId(r)
                    val s3 = readSize(r)
                    if (id3 < 0 || s3 < 0) break
                    val d3 = r.filePointer
                    if (id3 == TIMECODE) {
                        raw.add(readUInt(r, s3.toInt()))
                        tcPos.add(d3)
                        tcLen.add(s3.toInt())
                        offs.add(pos)
                        found = true
                    }
                    p3 = d3 + s3
                }
                if (!found) return null
            }
            pos = ds + sz
        }
        if (first < 0 || offs.isEmpty()) return null
        val n = offs.size
        return MkvIndex(
            first,
            LongArray(n) { offs[it] },
            LongArray(n) { tcPos[it] },
            IntArray(n) { tcLen[it] },
            LongArray(n) { raw[it] },
            LongArray(n) { raw[it] * scale / 1_000_000L },
            cues
        )
    }

    fun pick(ix: MkvIndex, tMs: Long): Int {
        var idx = 0
        for (k in ix.ms.indices) if (ix.ms[k] <= tMs) idx = k
        return idx
    }

    fun total(f: File, ix: MkvIndex, idx: Int): Long = ix.first + (f.length() - ix.offs[idx])

    fun shifted(f: File, ix: MkvIndex, idx: Int): InputStream {
        val n = ix.offs.size - idx
        val pos = LongArray(n) { ix.tcPos[idx + it] }
        val len = IntArray(n) { ix.tcLen[idx + it] }
        val nv = LongArray(n) { ix.raw[idx + it] - ix.raw[idx] }
        return ShiftedStream(f, ix.first, ix.offs[idx], pos, len, nv)
    }
}

class ShiftedStream(
    f: File,
    private val first: Long,
    private val start: Long,
    private val pos: LongArray,
    private val len: IntArray,
    private val nv: LongArray
) : InputStream() {
    private val raf = RandomAccessFile(f, "r")
    private val total = first + (f.length() - start)
    private var logical = 0L
    private var pi = 0

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, n: Int): Int {
        if (logical >= total) return -1
        var want = minOf(n.toLong(), total - logical).toInt()
        val fileOff: Long
        if (logical < first) {
            fileOff = logical
            want = minOf(want.toLong(), first - logical).toInt()
        } else {
            fileOff = start + (logical - first)
        }
        raf.seek(fileOff)
        val got = raf.read(b, off, want)
        if (got <= 0) return -1
        if (logical >= first) {
            while (pi < pos.size && pos[pi] + len[pi] <= fileOff) pi++
            var j = pi
            while (j < pos.size && pos[j] < fileOff + got) {
                val v = nv[j]
                for (k in 0 until len[j]) {
                    val abs = pos[j] + k
                    if (abs >= fileOff && abs < fileOff + got) {
                        val shift = 8 * (len[j] - 1 - k)
                        b[off + (abs - fileOff).toInt()] = ((v shr shift) and 0xFF).toByte()
                    }
                }
                j++
            }
        }
        logical += got
        return got
    }

    override fun close() {
        raf.close()
    }
}