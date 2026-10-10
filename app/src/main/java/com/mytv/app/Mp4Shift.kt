package com.mytv.app

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream

class Mp4Index(
    val headerEnd: Long,
    val moof: LongArray,
    val startMs: LongArray,
    val key: BooleanArray,
    val hasVideo: BooleanArray,
    val pFrag: IntArray,
    val pPos: LongArray,
    val pLen: IntArray,
    val pTrack: IntArray,
    val pVal: LongArray,
    val bFrag: IntArray,
    val bPos: LongArray,
    val bVal: LongArray,
    val sidx: LongArray,
    val ts: Map<Int, Long>,
    val videoTrack: Int,
    val boxes: String
)

object Mp4 {
    private const val MAX_HEADER = 64L * 1024 * 1024
    private const val FREE = 0x66726565L

    private fun name(t: Int): String {
        val c = CharArray(4)
        for (i in 0 until 4) c[i] = ((t shr (24 - 8 * i)) and 0xFF).toChar()
        return String(c)
    }

    private fun ri(r: BR): Int {
        val a = r.read()
        val b = r.read()
        val c = r.read()
        val d = r.read()
        if ((a or b or c or d) < 0) return 0
        return (a shl 24) or (b shl 16) or (c shl 8) or d
    }

    private fun rl(r: BR): Long = (ri(r).toLong() shl 32) or (ri(r).toLong() and 0xFFFFFFFFL)

    private class Box(val type: Int, val start: Long, val dataStart: Long, val end: Long)

    private fun box(r: BR, pos: Long, limit: Long): Box? {
        if (pos + 8 > limit) return null
        r.seek(pos)
        var size = ri(r).toLong() and 0xFFFFFFFFL
        val type = ri(r)
        var hdr = 8L
        if (size == 1L) {
            size = rl(r)
            hdr = 16L
        } else if (size == 0L) {
            size = limit - pos
        }
        if (size < hdr || pos + size > limit) {
            if (pos + size > limit && size >= hdr) size = limit - pos else return null
        }
        return Box(type, pos, pos + hdr, pos + size)
    }

    fun index(f: File): Mp4Index? {
        var result: Mp4Index? = null
        try {
            RandomAccessFile(f, "r").use { raf -> result = build(BR(raf), f.length()) }
        } catch (e: Exception) {
            result = null
        }
        return result
    }

    fun boxesOf(f: File): String {
        val sb = StringBuilder()
        try {
            RandomAccessFile(f, "r").use { raf ->
                val r = BR(raf)
                var pos = 0L
                val len = f.length()
                var n = 0
                while (pos < len && n < 10) {
                    val b = box(r, pos, len) ?: break
                    sb.append(name(b.type).trim()).append(',')
                    pos = b.end
                    n++
                }
            }
        } catch (e: Exception) {
        }
        return sb.toString()
    }

    private fun build(r: BR, len: Long): Mp4Index? {
        val tsMap = HashMap<Int, Long>()
        val trexFlags = HashMap<Int, Int>()
        var videoTrack = -1
        val moof = ArrayList<Long>()
        val startMs = ArrayList<Long>()
        val keyL = ArrayList<Boolean>()
        val hasV = ArrayList<Boolean>()
        val pFrag = ArrayList<Int>()
        val pPos = ArrayList<Long>()
        val pLen = ArrayList<Int>()
        val pTrack = ArrayList<Int>()
        val pVal = ArrayList<Long>()
        val bFrag = ArrayList<Int>()
        val bPos = ArrayList<Long>()
        val bVal = ArrayList<Long>()
        val sidx = ArrayList<Long>()
        var pos = 0L
        var firstMoof = -1L
        var moovSeen = false
        while (pos < len) {
            val b = box(r, pos, len) ?: break
            val tn = name(b.type)
            if (tn == "moov") {
                moovSeen = true
                var p = b.dataStart
                while (p < b.end) {
                    val c = box(r, p, b.end) ?: break
                    val cn = name(c.type)
                    if (cn == "trak") {
                        var tid = -1
                        var tsc = 0L
                        var vid = false
                        var q = c.dataStart
                        while (q < c.end) {
                            val d = box(r, q, c.end) ?: break
                            val dn = name(d.type)
                            if (dn == "tkhd") {
                                r.seek(d.dataStart)
                                val ver = r.read()
                                r.skipBytes(3)
                                r.skipBytes(if (ver == 1) 16 else 8)
                                tid = ri(r)
                            } else if (dn == "mdia") {
                                var m = d.dataStart
                                while (m < d.end) {
                                    val e = box(r, m, d.end) ?: break
                                    val en = name(e.type)
                                    if (en == "mdhd") {
                                        r.seek(e.dataStart)
                                        val ver = r.read()
                                        r.skipBytes(3)
                                        r.skipBytes(if (ver == 1) 16 else 8)
                                        tsc = ri(r).toLong() and 0xFFFFFFFFL
                                    } else if (en == "hdlr") {
                                        r.seek(e.dataStart + 8)
                                        vid = name(ri(r)) == "vide"
                                    }
                                    m = e.end
                                }
                            }
                            q = d.end
                        }
                        if (tid >= 0) {
                            tsMap[tid] = tsc
                            if (vid && videoTrack < 0) videoTrack = tid
                        }
                    } else if (cn == "mvex") {
                        var q = c.dataStart
                        while (q < c.end) {
                            val d = box(r, q, c.end) ?: break
                            if (name(d.type) == "trex") {
                                r.seek(d.dataStart + 4)
                                val tid = ri(r)
                                r.skipBytes(12)
                                trexFlags[tid] = ri(r)
                            }
                            q = d.end
                        }
                    }
                    p = c.end
                }
            } else if (tn == "sidx") {
                sidx.add(b.start)
            } else if (tn == "moof") {
                if (firstMoof < 0) firstMoof = b.start
                val frag = moof.size
                moof.add(b.start)
                var fk = false
                var fv = false
                var fs = -1L
                var p = b.dataStart
                while (p < b.end) {
                    val c = box(r, p, b.end) ?: break
                    if (name(c.type) == "traf") {
                        var tid = -1
                        var defFlags = -1
                        var key = true
                        var haveTrun = false
                        var tfdtSec = -1L
                        var q = c.dataStart
                        while (q < c.end) {
                            val d = box(r, q, c.end) ?: break
                            val dn = name(d.type)
                            if (dn == "tfhd") {
                                r.seek(d.dataStart)
                                val fl = ri(r) and 0xFFFFFF
                                tid = ri(r)
                                if ((fl and 0x1) != 0) {
                                    bFrag.add(frag)
                                    bPos.add(r.filePointer)
                                    bVal.add(rl(r))
                                }
                                if ((fl and 0x2) != 0) r.skipBytes(4)
                                if ((fl and 0x8) != 0) r.skipBytes(4)
                                if ((fl and 0x10) != 0) r.skipBytes(4)
                                if ((fl and 0x20) != 0) defFlags = ri(r)
                            } else if (dn == "tfdt") {
                                r.seek(d.dataStart)
                                val ver = r.read()
                                r.skipBytes(3)
                                val vp = r.filePointer
                                val v = if (ver == 1) rl(r) else (ri(r).toLong() and 0xFFFFFFFFL)
                                pFrag.add(frag)
                                pPos.add(vp)
                                pLen.add(if (ver == 1) 8 else 4)
                                pTrack.add(tid)
                                pVal.add(v)
                                val tsc = tsMap[tid] ?: 0L
                                if (tsc > 0) tfdtSec = v * 1000L / tsc
                            } else if (dn == "trun" && !haveTrun) {
                                haveTrun = true
                                r.seek(d.dataStart)
                                val fl = ri(r) and 0xFFFFFF
                                val cnt = ri(r)
                                if ((fl and 0x1) != 0) r.skipBytes(4)
                                var first = -1
                                if ((fl and 0x4) != 0) first = ri(r)
                                if (cnt > 0) {
                                    var sf = -1
                                    if ((fl and 0x100) != 0) r.skipBytes(4)
                                    if ((fl and 0x200) != 0) r.skipBytes(4)
                                    if ((fl and 0x400) != 0) sf = ri(r)
                                    val eff = if (first != -1 || (fl and 0x4) != 0) first
                                    else if (sf != -1 || (fl and 0x400) != 0) sf
                                    else if (defFlags != -1) defFlags
                                    else (trexFlags[tid] ?: 0)
                                    key = (eff and 0x10000) == 0
                                }
                            }
                            q = d.end
                        }
                        val isV = (videoTrack < 0) || tid == videoTrack
                        if (isV && tfdtSec >= 0 && !fv) {
                            fv = true
                            fk = key
                            fs = tfdtSec
                        }
                    }
                    p = c.end
                }
                startMs.add(if (fs >= 0) fs else 0L)
                keyL.add(fk)
                hasV.add(fv)
            }
            pos = b.end
        }
        if (!moovSeen || firstMoof < 0 || moof.size < 2) return null
        if (firstMoof > MAX_HEADER) return null
        val n = moof.size
        return Mp4Index(
            firstMoof,
            LongArray(n) { moof[it] },
            LongArray(n) { startMs[it] },
            BooleanArray(n) { keyL[it] },
            BooleanArray(n) { hasV[it] },
            IntArray(pFrag.size) { pFrag[it] },
            LongArray(pPos.size) { pPos[it] },
            IntArray(pLen.size) { pLen[it] },
            IntArray(pTrack.size) { pTrack[it] },
            LongArray(pVal.size) { pVal[it] },
            IntArray(bFrag.size) { bFrag[it] },
            LongArray(bPos.size) { bPos[it] },
            LongArray(bVal.size) { bVal[it] },
            LongArray(sidx.size) { sidx[it] },
            tsMap,
            videoTrack,
            ""
        )
    }

    fun pick(ix: Mp4Index, tMs: Long): Int {
        var idx = -1
        var any = 0
        for (k in ix.startMs.indices) {
            if (ix.startMs[k] <= tMs) {
                any = k
                if (ix.key[k] && ix.hasVideo[k]) idx = k
            }
        }
        return if (idx >= 0) idx else any
    }

    fun total(f: File, ix: Mp4Index, idx: Int): Long = ix.headerEnd + (f.length() - ix.moof[idx])

    fun shifted(f: File, ix: Mp4Index, idx: Int): InputStream? {
        val hdr = ByteArray(ix.headerEnd.toInt())
        RandomAccessFile(f, "r").use { r ->
            r.seek(0)
            r.readFully(hdr)
        }
        val start = ix.moof[idx]
        val delta = start - ix.headerEnd
        for (p in ix.sidx) {
            if (p < ix.headerEnd) {
                val i = (p + 4).toInt()
                hdr[i] = 0x66
                hdr[i + 1] = 0x72
                hdr[i + 2] = 0x65
                hdr[i + 3] = 0x65
            }
        }
        val t0 = ix.startMs[idx].toDouble() / 1000.0
        val pl = ArrayList<Long>()
        val ll = ArrayList<Int>()
        val vl = ArrayList<Long>()
        for (k in ix.pFrag.indices) {
            if (ix.pFrag[k] >= idx) {
                val tsc = ix.ts[ix.pTrack[k]] ?: 1L
                val base = Math.round(t0 * tsc)
                pl.add(ix.pPos[k])
                ll.add(ix.pLen[k])
                vl.add(maxOf(0L, ix.pVal[k] - base))
            }
        }
        for (k in ix.bFrag.indices) {
            if (ix.bFrag[k] >= idx) {
                pl.add(ix.bPos[k])
                ll.add(8)
                vl.add(ix.bVal[k] - delta)
            }
        }
        for (p in ix.sidx) {
            if (p >= start) {
                pl.add(p + 4)
                ll.add(4)
                vl.add(FREE)
            }
        }
        val order = pl.indices.sortedBy { pl[it] }
        val pos = LongArray(order.size) { pl[order[it]] }
        val len = IntArray(order.size) { ll[order[it]] }
        val nv = LongArray(order.size) { vl[order[it]] }
        return SequenceInputStream(ByteArrayInputStream(hdr), ClusterStream(f, start, pos, len, nv))
    }
}
