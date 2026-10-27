package com.vwshell.auto.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Собственный плеер локальных аудиофайлов оболочки.
 * Движок: android.media.MediaPlayer (стабильно доступен на Android 10–13; Media3 ExoPlayer
 * требует INTERNET-разрешение в манифесте, которое оболочка намеренно не запрашивает).
 * Воспроизведение публикуется как собственная MediaSession — управление с кнопок руля,
 * мини-плеера и внешнего контроллера работает через стандартный механизм Android.
 * Показываются только реальные метаданные файла; при их отсутствии — нейтральные значения.
 */
class LocalPlayerEngine private constructor(context: Context) {

    interface Listener {
        fun onPlayerEvent(state: Int, item: AudioItem?, positionMs: Long, durationMs: Long)
        fun onArtworkReady(item: AudioItem?, bitmap: Bitmap?)
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "vw-art").apply { isDaemon = true } }

    private var player: android.media.MediaPlayer? = null
    private var session: MediaSession? = null
    private var playlist: List<AudioItem> = emptyList()
    private var index = -1
    @Volatile private var prepared = false
    private var focusRequest: AudioFocusRequest? = null
    private var listeners = mutableListOf<Listener>()
    private var artworkItemId: Long = -1L

    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)
    val currentItem: AudioItem? get() = if (index in playlist.indices) playlist[index] else null
    val hasPlaylist: Boolean get() = playlist.isNotEmpty()

    private val positionRunnable = object : Runnable {
        override fun run() {
            broadcast()
            mainHandler.postDelayed(this, 500)
        }
    }

    init {
        session = MediaSession(appContext, "VWShellLocalPlayer").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resumeOrStart() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { next() }
                override fun onSkipToPrevious() { previous() }
                override fun onStop() { stop() }
                override fun onSeekTo(pos: Long) { seekTo(pos) }
            })
            isActive = true
        }
    }

    fun addListener(l: Listener) { synchronized(listeners) { listeners.add(l) }; broadcast() }
    fun removeListener(l: Listener) { synchronized(listeners) { listeners.remove(l) } }

    /** Заменяет плейлист реальными композициями и начинает воспроизведение выбранного трека. */
    fun setPlaylist(items: List<AudioItem>, startIndex: Int) {
        playlist = items
        if (items.isEmpty()) { stop(); return }
        playIndex(startIndex.coerceIn(0, items.size - 1))
    }

    fun playIndex(i: Int) {
        if (i !in playlist.indices) return
        index = i
        val item = playlist[i]
        releasePlayer()
        val p = android.media.MediaPlayer()
        player = p
        try {
            p.setDataSource(appContext, item.uri)
            p.setOnPreparedListener {
                prepared = true
                it.start()
                requestAudioFocus()
                publishMetadata(item, it.duration.toLong())
                updateState(PlaybackState.STATE_PLAYING)
                mainHandler.removeCallbacks(positionRunnable)
                mainHandler.postDelayed(positionRunnable, 500)
                loadArtwork(item)
            }
            p.setOnCompletionListener { next() }
            p.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                prepared = false
                updateState(PlaybackState.STATE_ERROR)
                true
            }
            p.prepareAsync()
            updateState(PlaybackState.STATE_BUFFERING)
            broadcast()
        } catch (t: Throwable) {
            Log.w(TAG, "play failed: ${item.uri}", t)
            releasePlayer()
            updateState(PlaybackState.STATE_STOPPED)
        }
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (!prepared) return
        if (p.isPlaying) { p.pause(); pauseFocusLossStop(false); updateState(PlaybackState.STATE_PAUSED) }
        else { p.start(); requestAudioFocus(); updateState(PlaybackState.STATE_PLAYING) }
        broadcast()
    }

    fun pause() {
        val p = player ?: return
        if (prepared && p.isPlaying) { p.pause(); abandonAudioFocus(); updateState(PlaybackState.STATE_PAUSED); broadcast() }
    }

    fun resumeOrStart() {
        val p = player
        if (p != null && prepared) { p.start(); requestAudioFocus(); updateState(PlaybackState.STATE_PLAYING) }
        else if (playlist.isNotEmpty()) playIndex(if (index >= 0) index else 0)
        broadcast()
    }

    fun next() { if (playlist.isNotEmpty()) playIndex((index + 1) % playlist.size) }
    fun previous() { if (playlist.isNotEmpty()) playIndex(if (index <= 0) playlist.size - 1 else index - 1) }

    fun seekTo(ms: Long) {
        val p = player ?: return
        if (!prepared) return
        try { p.seekTo(ms.toInt().coerceIn(0, p.duration)); updateState(if (p.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED) } catch (_: IllegalStateException) {}
        broadcast()
    }

    fun currentPosition(): Long = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
    fun currentDuration(): Long = runCatching { if (prepared) player?.duration?.toLong() ?: -1L else -1L }.getOrDefault(-1L)

    fun stop() {
        releasePlayer()
        abandonAudioFocus()
        index = -1
        updateState(PlaybackState.STATE_STOPPED)
        session?.setMetadata(null)
        broadcast()
    }

    /** Полное освобождение ресурсов (вызывается из onDestroyView владельца экрана). */
    fun release() {
        stop()
        mainHandler.removeCallbacks(positionRunnable)
        synchronized(listeners) { listeners.clear() }
        session?.apply { isActive = false; release() }
        session = null
    }

    private fun releasePlayer() {
        prepared = false
        player?.release()
        player = null
    }

    private fun pauseFocusLossStop(wasPlaying: Boolean) { if (wasPlaying) abandonAudioFocus() }

    // ---------- Аудиофокус ----------
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { /* громкость снижает система */ }
        }
    }

    private fun requestAudioFocus() {
        if (Build_M.isAtLeastO) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(focusListener, mainHandler)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        if (Build_M.isAtLeastO) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(focusListener)
    }

    // ---------- MediaSession ----------
    private fun publishMetadata(item: AudioItem, durationMs: Long) {
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, item.displayTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, item.displayArtist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, item.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.coerceAtLeast(0))
            .build()
        session?.setMetadata(md)
    }

    private fun updateState(state: Int) {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, currentPosition(), 1.0f)
                .build())
    }

    private fun loadArtwork(item: AudioItem) {
        artworkItemId = item.id
        io.execute {
            val bmp: Bitmap? = runCatching {
                // Реальная обложка: контент-URI конкретного трека MediaStore.
                val artUri = android.content.ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, item.id)
                appContext.contentResolver.openInputStream(artUri)?.use { BitmapFactory.decodeStream(it) }
            }.onFailure { Log.w(TAG, "artwork load failed for ${item.id}", it) }.getOrNull()
            mainHandler.post {
                if (artworkItemId == item.id) {
                    if (bmp != null) {
                        val base = runCatching { session?.controller?.metadata }
                            .onFailure { Log.w(TAG, "metadata read failed", it) }.getOrNull()
                        val md = MediaMetadata.Builder(base ?: MediaMetadata.Builder().build())
                            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp).build()
                        session?.setMetadata(md)
                    }
                    synchronized(listeners) { listeners.toList() }.forEach { it.onArtworkReady(item, bmp) }
                }
            }
        }
    }

    private fun broadcast() {
        val snap = synchronized(listeners) { listeners.toList() }
        val item = currentItem
        val st = when {
            player == null -> PlaybackState.STATE_NONE
            !prepared -> PlaybackState.STATE_BUFFERING
            isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        val pos = currentPosition()
        val dur = currentDuration()
        mainHandler.post { snap.forEach { it.onPlayerEvent(st, item, pos, dur) } }
    }

    /** Активная MediaSession плеера (для интеграции с внешними контроллерами). */
    fun token() = session?.sessionToken

    companion object {
        private const val TAG = "LocalPlayerEngine"
        @Volatile private var instance: LocalPlayerEngine? = null
        fun get(context: Context): LocalPlayerEngine =
            instance ?: synchronized(this) { instance ?: LocalPlayerEngine(context).also { instance = it } }

        /** Только существующий экземпляр: мини-плеер не должен создавать плеер и MediaSession «вхолостую». */
        fun peekOrNull(context: Context): LocalPlayerEngine? = instance
    }

    private object Build_M { val isAtLeastO = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O }
}
