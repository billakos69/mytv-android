package com.mytv.app

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream

class MkvIndex(
    val first: Long,
    val offs: LongArray,
    val tcPos: LongArray,
    val tcLen: IntArray,
    val raw: LongArray,
    val ms: LongArray,
    val key: BooleanArray,
    val cues: Boolean,
    val segSizePos: Long,
    val segSizeLen: Int,
    val segData: Long,
    val segKnown: Boolean,
    val cuesPos: LongArray,
    val seekIdPos: LongArray
)

object Mkv {
    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val INFO = 0x1549A966L
    private const val SCALE = 0x2AD7B1L
    private const val DURATION = 0x4489L
    private const val TRACKS = 0x1654AE6BL
    private const val TRACKENTRY = 0xAEL
    private const val TRACKNUM = 0xD7L
    private const val TRACKTYPE = 0x83L
    private const val CLUSTER = 0x1F43B675L
    private const val TIMECODE = 0xE7L
    private const val SIMPLEBLOCK = 0xA3L
    private const val BLOCKGROUP = 0xA0L
    private const val BLOCK = 0xA1L
    private const val REFBLOCK = 0xFBL
    private const val CUES = 0x1C53BB6BL
    private const val SEEKHEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEKID = 0x53ABL
    private const val TAGS = 0x1254C367L
    private const val MAX_HEADER = 64L * 1024 * 1024

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
        val segSizePos = r.filePointer
        val ss = readSize(r)
        val seg = r.filePointer
        val segSizeLen = (seg - segSizePos).toInt()
        val segEnd = if (ss >= 0) minOf(seg + ss, r.length()) else r.length()
        var scale = 1_000_000L
        var first = -1L
        var cuesBefore = false
        var seekCues = false
        var hasDur = false
        var videoTrack = -1L
        val offs = ArrayList<Long>()
        val tcPos = ArrayList<Long>()
        val tcLen = ArrayList<Int>()
        val raw = ArrayList<Long>()
        val keys = ArrayList<Boolean>()
        val cuesList = ArrayList<Long>()
        val seekIds = ArrayList<Long>()
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
                    if (id2 == DURATION) hasDur = true
                    p2 = d2 + s2
                }
            } else if (id == TRACKS) {
                var p2 = ds
                val e2 = ds + sz
                while (p2 < e2) {
                    r.seek(p2)
                    val id2 = readId(r)
                    val s2 = readSize(r)
                    if (id2 < 0 || s2 < 0) break
                    val d2 = r.filePointer
                    if (id2 == TRACKENTRY && videoTrack < 0) {
                        var num = -1L
                        var type = -1L
                        var p3 = d2
                        val e3 = d2 + s2
                        while (p3 < e3) {
                            r.seek(p3)
                            val id3 = readId(r)
                            val s3 = readSize(r)
                            if (id3 < 0 || s3 < 0) break
                            val d3 = r.filePointer
                            if (id3 == TRACKNUM) num = readUInt(r, s3.toInt())
                            if (id3 == TRACKTYPE) type = readUInt(r, s3.toInt())
                            p3 = d3 + s3
                        }
                        if (type == 1L) videoTrack = num
                    }
                    p2 = d2 + s2
                }
            } else if (id == SEEKHEAD) {
                var p4 = ds
                val e4 = ds + sz
                while (p4 < e4) {
                    r.seek(p4)
                    val id4 = readId(r)
                    val s4 = readSize(r)
                    if (id4 < 0 || s4 < 0) break
                    val d4 = r.filePointer
                    if (id4 == SEEK) {
                        var p5 = d4
                        val e5 = d4 + s4
                        while (p5 < e5) {
                            r.seek(p5)
                            val id5 = readId(r)
                            val s5 = readSize(r)
                            if (id5 < 0 || s5 < 0) break
                            val d5 = r.filePointer
                            if (id5 == SEEKID && s5 <= 4) {
                                if (readUInt(r, s5.toInt()) == CUES) {
                                    seekCues = true
                                    if (s5 == 4L) seekIds.add(d5)
                                }
                            }
                            p5 = d5 + s5
                        }
                    }
                    p4 = d4 + s4
                }
            } else if (id == CUES) {
                cuesList.add(pos)
                if (first < 0) cuesBefore = true
            } else if (id == CLUSTER) {
                if (first < 0) first = pos
                var p3 = ds
                val e3 = minOf(ds + sz, segEnd)
                var found = false
                var keyKnown = false
                var key = true
                while (p3 < e3 && !(found && keyKnown)) {
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
                    } else if (id3 == SIMPLEBLOCK && !keyKnown) {
                        val tn = readSize(r)
                        r.skipBytes(2)
                        val flags = r.read()
                        if (videoTrack < 0) {
                            keyKnown = true
                        } else if (tn == videoTrack) {
                            key = (flags and 0x80) != 0
                            keyKnown = true
                        }
                    } else if (id3 == BLOCKGROUP && !keyKnown) {
                        var isVideo = false
                        var hasRef = false
                        var p6 = d3
                        val e6 = d3 + s3
                        while (p6 < e6) {
                            r.seek(p6)
                            val id6 = readId(r)
                            val s6 = readSize(r)
                            if (id6 < 0 || s6 < 0) break
                            val d6 = r.filePointer
                            if (id6 == BLOCK) {
                                val tn = readSize(r)
                                if (videoTrack < 0 || tn == videoTrack) isVideo = true
                            }
                            if (id6 == REFBLOCK) hasRef = true
                            p6 = d6 + s6
                        }
                        if (videoTrack < 0) {
                            keyKnown = true
                        } else if (isVideo) {
                            key = !hasRef
                            keyKnown = true
                        }
                    }
                    p3 = d3 + s3
                }
                if (!found) return null
                keys.add(key)
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
            BooleanArray(n) { keys[it] },
            (cuesBefore || seekCues) && hasDur,
            segSizePos,
            segSizeLen,
            seg,
            ss >= 0,
            LongArray(cuesList.size) { cuesList[it] },
            LongArray(seekIds.size) { seekIds[it] }
        )
    }

    fun pick(ix: MkvIndex, tMs: Long): Int {
        var idx = -1
        var any = 0
        for (k in ix.ms.indices) {
            if (ix.ms[k] <= tMs) {
                any = k
                if (ix.key[k]) idx = k
            }
        }
        return if (idx >= 0) idx else any
    }

    private fun putId(h: ByteArray, p: Long) {
        if (p < 0 || p + 4 > h.size) return
        val i = p.toInt()
        h[i] = 0x12
        h[i + 1] = 0x54
        h[i + 2] = 0xC3.toByte()
        h[i + 3] = 0x67
    }

    fun total(f: File, ix: MkvIndex, idx: Int): Long = ix.first + (f.length() - ix.offs[idx])

    fun shifted(f: File, ix: MkvIndex, idx: Int): InputStream? {
        if (ix.first > MAX_HEADER) return null
        val hdr = ByteArray(ix.first.toInt())
        RandomAccessFile(f, "r").use { r ->
            r.seek(0)
            r.readFully(hdr)
        }
        if (ix.segKnown && ix.segSizeLen in 1..8) {
            val newSize = total(f, ix, idx) - ix.segData
            val limit = (1L shl (7 * ix.segSizeLen)) - 1
            if (newSize in 0 until limit) {
                val enc = newSize or (1L shl (7 * ix.segSizeLen))
                for (k in 0 until ix.segSizeLen) {
                    val shift = 8 * (ix.segSizeLen - 1 - k)
                    hdr[(ix.segSizePos + k).toInt()] = ((enc shr shift) and 0xFF).toByte()
                }
            }
        }
        for (p in ix.seekIdPos) putId(hdr, p)
        for (p in ix.cuesPos) if (p < ix.first) putId(hdr, p)
        val start = ix.offs[idx]
        val pl = ArrayList<Long>()
        val ll = ArrayList<Int>()
        val vl = ArrayList<Long>()
        for (k in idx until ix.offs.size) {
            pl.add(ix.tcPos[k])
            ll.add(ix.tcLen[k])
            vl.add(ix.raw[k] - ix.raw[idx])
        }
        for (p in ix.cuesPos) {
            if (p >= start) {
                pl.add(p)
                ll.add(4)
                vl.add(TAGS)
            }
        }
        val order = pl.indices.sortedBy { pl[it] }
        val pos = LongArray(order.size) { pl[order[it]] }
        val len = IntArray(order.size) { ll[order[it]] }
        val nv = LongArray(order.size) { vl[order[it]] }
        return SequenceInputStream(ByteArrayInputStream(hdr), ClusterStream(f, start, pos, len, nv))
    }
}

class ClusterStream(
    f: File,
    private val start: Long,
    private val pos: LongArray,
    private val len: IntArray,
    private val nv: LongArray
) : InputStream() {
    private val raf = RandomAccessFile(f, "r")
    private val end = f.length()
    private var cur = start
    private var pi = 0

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, n: Int): Int {
        if (cur >= end) return -1
        val want = minOf(n.toLong(), end - cur).toInt()
        raf.seek(cur)
        val got = raf.read(b, off, want)
        if (got <= 0) return -1
        while (pi < pos.size && pos[pi] + len[pi] <= cur) pi++
        var j = pi
        while (j < pos.size && pos[j] < cur + got) {
            val v = nv[j]
            for (k in 0 until len[j]) {
                val abs = pos[j] + k
                if (abs >= cur && abs < cur + got) {
                    val shift = 8 * (len[j] - 1 - k)
                    b[off + (abs - cur).toInt()] = ((v shr shift) and 0xFF).toByte()
                }
            }
            j++
        }
        cur += got
        return got
    }

    override fun close() {
        raf.close()
    }
}