package com.mytv.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject

class NativePlayer(
    private val ctx: Context,
    private val host: FrameLayout,
    private val send: (String) -> Unit
) {
    private val surface = SurfaceView(ctx)
    private var player: ExoPlayer? = null
    private var live = false
    private var audioOff = false
    private var want = 0L
    private var checked = false
    private val handler = Handler(Looper.getMainLooper())

    init {
        surface.visibility = View.GONE
        host.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
    }

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            val o = JSONObject()
                .put("pos", p.currentPosition / 1000)
                .put("dur", if (p.duration > 0) p.duration / 1000 else 0L)
                .put("playing", p.isPlaying)
                .put("state", p.playbackState)
            send("window.onNative&&window.onNative(" + o.toString() + ")")
            handler.postDelayed(this, 1000)
        }
    }

    fun play(url: String, startMs: Long, isLive: Boolean) {
        stop()
        live = isLive
        audioOff = false
        want = startMs
        checked = false
        val rf = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(ctx, rf).build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages("el", "en")
            .build()
        p.setVideoSurfaceView(surface)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    send("window.onNativeEnd&&window.onNativeEnd()")
                }
                if (playbackState == Player.STATE_READY && !checked) {
                    checked = true
                    if (want > 3000) {
                        if (!p.isCurrentMediaItemSeekable) {
                            val w = "Το αρχείο δεν επιτρέπει μετάβαση στη μέση — παίζει από την αρχή"
                            send("window.onNativeWarn&&window.onNativeWarn(" + JSONObject.quote(w) + ")")
                        } else if (Math.abs(p.currentPosition - want) > 3000) {
                            p.seekTo(want)
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val ex = error as? ExoPlaybackException
                val f = ex?.rendererFormat
                val mime = f?.sampleMimeType ?: ""
                if (mime.startsWith("audio/") && !audioOff) {
                    audioOff = true
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
                    p.prepare()
                    p.playWhenReady = true
                    val w = "Ο ήχος ($mime) δεν υποστηρίζεται — παίζει χωρίς ήχο"
                    send("window.onNativeWarn&&window.onNativeWarn(" + JSONObject.quote(w) + ")")
                    return
                }
                val res = if (f != null && f.width > 0) f.width.toString() + "x" + f.height else ""
                val cause = error.cause?.message ?: (error.cause?.javaClass?.simpleName ?: "")
                val o = JSONObject()
                    .put("code", error.errorCodeName)
                    .put("mime", mime)
                    .put("codecs", f?.codecs ?: "")
                    .put("res", res)
                    .put("cause", cause)
                    .put("uri", p.currentMediaItem?.localConfiguration?.uri?.toString() ?: "")
                send("window.onNativeError&&window.onNativeError(" + o.toString() + ")")
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                fit(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
            }
        })
        player = p
        surface.visibility = View.VISIBLE
        p.setMediaItem(MediaItem.fromUri(url), startMs)
        p.prepare()
        p.playWhenReady = true
        handler.post(ticker)
    }

    private fun fit(w: Int, h: Int, par: Float) {
        if (w <= 0 || h <= 0) return
        val pw = host.width
        val ph = host.height
        if (pw <= 0 || ph <= 0) return
        val ar = (w * par) / h
        var tw = pw
        var th = (pw / ar).toInt()
        if (th > ph) {
            th = ph
            tw = (ph * ar).toInt()
        }
        surface.layoutParams = FrameLayout.LayoutParams(tw, th, Gravity.CENTER)
    }

    fun stop() {
        handler.removeCallbacks(ticker)
        player?.release()
        player = null
        surface.visibility = View.GONE
    }

    fun pause() {
        if (!live) player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun seekBy(ms: Long) {
        val p = player ?: return
        if (live) return
        p.seekTo(maxOf(0L, p.currentPosition + ms))
    }

    private fun label(g: Tracks.Group, i: Int): String {
        val f = g.getTrackFormat(i)
        return (f.label ?: f.language ?: "?") + " (" + f.channelCount + "ch)"
    }

    fun nextAudio(): String {
        val p = player ?: return ""
        val list = ArrayList<Triple<Tracks.Group, Int, Boolean>>()
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) {
                if (g.isTrackSupported(i)) list.add(Triple(g, i, g.isTrackSelected(i)))
            }
        }
        if (list.isEmpty()) return ""
        if (list.size < 2) return label(list[0].first, list[0].second)
        val cur = list.indexOfFirst { it.third }
        val nxt = list[(cur + 1) % list.size]
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(nxt.first.mediaTrackGroup, nxt.second))
            .build()
        return label(nxt.first, nxt.second)
    }
}