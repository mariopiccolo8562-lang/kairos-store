package com.kairos.player

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

/** Una voce di menu (traccia audio, qualità, sottotitoli). */
data class Track(val id: String, val label: String, val selected: Boolean)

interface EngineListener {
    fun onBuffering(buffering: Boolean)
    fun onReady()
    fun onError(message: String)
    fun onEnded()
}

/** Impostazioni di riproduzione scelte nell'app. */
data class PlayPrefs(val quality: String, val buffer: String, val aspect: String) {
    /** millisecondi di video da accumulare prima di partire / dopo un'interruzione */
    val startMs: Int get() = when (buffer) { "fast" -> 1200; "stable" -> 4000; else -> 2000 }
    val rebufferMs: Int get() = when (buffer) { "fast" -> 2500; "stable" -> 8000; else -> 4000 }
    val maxMs: Int get() = when (buffer) { "fast" -> 30000; "stable" -> 90000; else -> 50000 }
    val vlcCacheMs: Int get() = when (buffer) { "fast" -> 1000; "stable" -> 5000; else -> 2500 }
    val maxHeight: Int get() = quality.toIntOrNull() ?: 0
}

/** Motore di riproduzione: ExoPlayer oppure VLC. */
interface Engine {
    val name: String
    val view: View
    fun play(url: String, startMs: Long)
    fun stop()
    fun togglePause()
    fun isPlaying(): Boolean
    fun position(): Long
    fun duration(): Long
    fun seekBy(ms: Long)
    fun audioTracks(): List<Track>
    fun selectAudio(id: String)
    fun qualities(): List<Track>
    fun selectQuality(id: String)
    fun subtitles(): List<Track>
    fun selectSubtitle(id: String)
    fun setAspect(mode: String)
    fun videoHeight(): Int
    fun release()
}

private fun mimeOf(url: String): String? {
    val u = url.lowercase(Locale.ROOT).substringBefore('?')
    return when {
        u.endsWith(".m3u8") || u.contains("/hls/") -> MimeTypes.APPLICATION_M3U8
        u.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
        u.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
        u.endsWith(".mp4") || u.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
        else -> null
    }
}

private fun langName(code: String?): String {
    if (code.isNullOrBlank() || code == "und") return ""
    val n = Locale(code).getDisplayLanguage(Locale.ITALIAN)
    return if (n.isBlank()) code else n.replaceFirstChar { it.uppercase() }
}

/* ======================================================================= */
/* ExoPlayer                                                               */
/* ======================================================================= */
@OptIn(UnstableApi::class)
class ExoEngine(ctx: Context, private val l: EngineListener, ua: String, private val prefs: PlayPrefs, overlay: Boolean = false) : Engine {
    override val name = "exo"
    private val selector = DefaultTrackSelector(
        ctx,
        // passa a una qualità più alta solo dopo 10s stabili, scende solo se davvero necessario:
        // evita i cali improvvisi di qualità
        AdaptiveTrackSelection.Factory(10_000, 25_000, 25_000, 0.75f),
    )
    private var currentQuality = "auto"
    private val player: ExoPlayer
    private val pv = PlayerView(ctx)
    override val view: View get() = pv

    init {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http), extractors)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(maxOf(15_000, prefs.startMs), prefs.maxMs, prefs.startMs, prefs.rebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val renderers = DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        player = ExoPlayer.Builder(ctx, renderers)
            .setTrackSelector(selector)
            .setLoadControl(load)
            .setMediaSourceFactory(sources)
            .build()
        // allinea la frequenza dello schermo a quella del video (50/60 fps fluidi) quando possibile
        player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
        applyQuality(prefs.quality)

        pv.useController = false
        pv.setShutterBackgroundColor(Color.BLACK)
        pv.setKeepContentOnPlayerReset(true)
        pv.player = player
        // anteprima sopra la pagina web: la superficie video va disegnata sopra la finestra
        if (overlay) (pv.videoSurfaceView as? android.view.SurfaceView)?.setZOrderMediaOverlay(true)
        setAspect(prefs.aspect)

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> l.onBuffering(true)
                    Player.STATE_READY -> { l.onBuffering(false); l.onReady() }
                    Player.STATE_ENDED -> l.onEnded()
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                l.onError(error.errorCodeName)
            }
        })
    }

    override fun play(url: String, startMs: Long) {
        val b = MediaItem.Builder().setUri(Uri.parse(url))
        mimeOf(url)?.let { b.setMimeType(it) }
        b.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(10_000).build())
        player.setMediaItem(b.build(), maxOf(0L, startMs))
        player.prepare()
        player.playWhenReady = true
    }

    override fun stop() { player.stop() }
    override fun togglePause() { player.playWhenReady = !player.playWhenReady }
    override fun isPlaying(): Boolean = player.playWhenReady
    override fun position(): Long = maxOf(0L, player.currentPosition)
    override fun duration(): Long = player.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
    override fun seekBy(ms: Long) {
        val d = duration()
        var t = position() + ms
        if (t < 0) t = 0
        if (d > 0 && t > d - 1000) t = d - 1000
        player.seekTo(t)
    }

    private fun tracksOf(type: Int): List<Triple<Int, Int, Format>> {
        val out = mutableListOf<Triple<Int, Int, Format>>()
        val groups = player.currentTracks.groups
        for (gi in groups.indices) {
            val g = groups[gi]
            if (g.type != type) continue
            for (i in 0 until g.length) if (g.isTrackSupported(i)) out.add(Triple(gi, i, g.getTrackFormat(i)))
        }
        return out
    }

    private fun isSelected(gi: Int, i: Int): Boolean = player.currentTracks.groups[gi].isTrackSelected(i)

    override fun audioTracks(): List<Track> = tracksOf(C.TRACK_TYPE_AUDIO).mapIndexed { n, (gi, i, f) ->
        val parts = mutableListOf<String>()
        langName(f.language).takeIf { it.isNotBlank() }?.let { parts.add(it) }
        f.label?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        when (f.channelCount) { 1 -> parts.add("Mono"); 2 -> parts.add("Stereo"); 6 -> parts.add("5.1"); 8 -> parts.add("7.1") }
        Track("$gi:$i", if (parts.isEmpty()) "Traccia ${n + 1}" else parts.joinToString(" · "), isSelected(gi, i))
    }

    override fun selectAudio(id: String) {
        val (gi, i) = id.split(":").map { it.toInt() }
        val g = player.currentTracks.groups[gi]
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
            .build()
    }

    override fun qualities(): List<Track> {
        val q = currentQuality
        val out = mutableListOf(
            Track("auto", "Automatica (consigliata)", currentQuality == "auto"),
            Track("max", "Massima disponibile", currentQuality == "max"),
        )
        tracksOf(C.TRACK_TYPE_VIDEO)
            .filter { it.third.height > 0 }
            .sortedByDescending { it.third.height * 100_000L + it.third.bitrate.coerceAtLeast(0) }
            .distinctBy { it.third.height }
            .forEach { (gi, i, f) ->
                val mbps = if (f.bitrate > 0) " · %.1f Mbps".format(Locale.ITALIAN, f.bitrate / 1_000_000.0) else ""
                val fps = if (f.frameRate > 0) " · ${f.frameRate.toInt()} fps" else ""
                out.add(Track("t:$gi:$i", "${f.height}p$fps$mbps", currentQuality == "t:$gi:$i"))
            }
        if (out.size == 2 && q.toIntOrNull() != null) out.add(Track(q, "Fino a ${q}p", true))
        return out
    }

    private fun applyQuality(q: String) {
        val b = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        when {
            q == "max" -> b.setForceHighestSupportedBitrate(true).clearVideoSizeConstraints()
            q.toIntOrNull() != null -> b.setForceHighestSupportedBitrate(true).setMaxVideoSize(Int.MAX_VALUE, q.toInt())
            else -> b.setForceHighestSupportedBitrate(false).clearVideoSizeConstraints()
        }
        player.trackSelectionParameters = b.build()
        currentQuality = q
    }

    override fun selectQuality(id: String) {
        if (id.startsWith("t:")) {
            val (_, gi, i) = id.split(":")
            val g = player.currentTracks.groups[gi.toInt()]
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearVideoSizeConstraints()
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i.toInt()))
                .build()
            currentQuality = id
        } else applyQuality(id)
    }

    override fun subtitles(): List<Track> {
        val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val list = tracksOf(C.TRACK_TYPE_TEXT)
        val out = mutableListOf(Track("off", "Disattivati", disabled || list.none { isSelected(it.first, it.second) }))
        list.forEachIndexed { n, (gi, i, f) ->
            val name = listOf(langName(f.language), f.label ?: "").filter { it.isNotBlank() }.joinToString(" · ")
            out.add(Track("$gi:$i", name.ifBlank { "Sottotitoli ${n + 1}" }, !disabled && isSelected(gi, i)))
        }
        return out
    }

    override fun selectSubtitle(id: String) {
        val b = player.trackSelectionParameters.buildUpon()
        if (id == "off") b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        else {
            val (gi, i) = id.split(":").map { it.toInt() }
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(player.currentTracks.groups[gi].mediaTrackGroup, i))
        }
        player.trackSelectionParameters = b.build()
    }

    override fun setAspect(mode: String) {
        pv.resizeMode = when (mode) {
            "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    override fun videoHeight(): Int = player.videoSize.height
    override fun release() { pv.player = null; player.release() }
}

/* ======================================================================= */
/* VLC                                                                     */
/* ======================================================================= */
class VlcEngine(ctx: Context, private val l: EngineListener, ua: String, private val prefs: PlayPrefs) : Engine {
    override val name = "vlc"
    private val layout = VLCVideoLayout(ctx)
    override val view: View get() = layout
    private val lib: LibVLC
    private val mp: MediaPlayer
    private var url = ""
    private var maxH = prefs.maxHeight

    init {
        val opts = arrayListOf(
            "--network-caching=${prefs.vlcCacheMs}",
            "--live-caching=${prefs.vlcCacheMs}",
            "--file-caching=1500",
            "--http-reconnect",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--drop-late-frames",
            "--skip-frames",
            "--avcodec-skiploopfilter=1",
            "--audio-time-stretch",
            "--no-stats",
        )
        if (ua.isNotBlank()) opts.add("--http-user-agent=$ua")
        lib = LibVLC(ctx, opts)
        mp = MediaPlayer(lib)
        mp.attachViews(layout, null, true, false)
        mp.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Buffering -> l.onBuffering(e.buffering < 99.9f)
                MediaPlayer.Event.Playing -> { l.onBuffering(false); l.onReady() }
                MediaPlayer.Event.EncounteredError -> l.onError("VLC_ERROR")
                MediaPlayer.Event.EndReached -> l.onEnded()
                else -> {}
            }
        }
        setAspect(prefs.aspect)
    }

    override fun play(url: String, startMs: Long) {
        this.url = url
        val m = Media(lib, Uri.parse(url))
        m.setHWDecoderEnabled(true, false)
        if (maxH > 0) m.addOption(":adaptive-maxheight=$maxH")
        if (startMs > 0) m.addOption(":start-time=${startMs / 1000.0}")
        mp.media = m
        m.release()
        mp.play()
    }

    override fun stop() { mp.stop() }
    override fun togglePause() { if (mp.isPlaying) mp.pause() else mp.play() }
    override fun isPlaying(): Boolean = mp.isPlaying
    override fun position(): Long = maxOf(0L, mp.time)
    override fun duration(): Long = maxOf(0L, mp.length)
    override fun seekBy(ms: Long) { if (duration() > 2000) mp.time = (position() + ms).coerceIn(0L, duration() - 1000) }

    override fun audioTracks(): List<Track> {
        val cur = mp.audioTrack
        return (mp.audioTracks ?: emptyArray()).filter { it.id >= 0 }.map { Track(it.id.toString(), it.name ?: "Traccia ${it.id}", it.id == cur) }
    }
    override fun selectAudio(id: String) { mp.setAudioTrack(id.toInt()) }

    override fun qualities(): List<Track> = listOf(
        Track("auto", "Automatica (consigliata)", maxH == 0),
        Track("1080", "Fino a 1080p", maxH == 1080),
        Track("720", "Fino a 720p", maxH == 720),
        Track("480", "Fino a 480p (connessioni lente)", maxH == 480),
    )
    override fun selectQuality(id: String) {
        val h = if (id == "max" || id == "auto") 0 else id.toIntOrNull() ?: 0
        if (h == maxH) return
        maxH = h
        if (url.isNotEmpty()) { val t = position(); play(url, t) }
    }

    override fun subtitles(): List<Track> {
        val cur = mp.spuTrack
        val list = (mp.spuTracks ?: emptyArray()).filter { it.id >= 0 }
        return listOf(Track("-1", "Disattivati", cur < 0)) + list.map { Track(it.id.toString(), it.name ?: "Sottotitoli ${it.id}", it.id == cur) }
    }
    override fun selectSubtitle(id: String) { mp.setSpuTrack(id.toIntOrNull() ?: -1) }

    override fun setAspect(mode: String) {
        mp.setVideoScale(
            when (mode) {
                "fill" -> MediaPlayer.ScaleType.SURFACE_FILL
                "zoom" -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            },
        )
    }

    override fun videoHeight(): Int = try { mp.currentVideoTrack?.height ?: 0 } catch (_: Exception) { 0 }

    override fun release() {
        try { mp.stop() } catch (_: Exception) {}
        mp.detachViews()
        mp.release()
        lib.release()
    }
}
