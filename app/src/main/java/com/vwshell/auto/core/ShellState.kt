package com.vwshell.auto.core

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import java.util.WeakHashMap

/**
 * Централизованное состояние медиасессии оболочки.
 * MainActivity, MediaFragment и мини-плеер подписаны на ОДИН общий MediaMonitor;
 * ShellState хранит последний снимок (snapshot) реальной активной сессии и раздаёт
 * его подписчикам без дублирования бизнес-логики между экранами.
 * Никаких фиктивных данных: snapshot == null означает «источник отсутствует».
 */
object ShellState {

    /** Иммутабельный снимок реальных данных медиасессии. */
    data class MediaSnapshot(
        val controller: MediaController?,
        val sourcePackage: String?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val artwork: android.graphics.Bitmap?,
        val playing: Boolean,
        val actions: Long,
        val positionMs: Long,
        val durationMs: Long
    ) {
        fun canPlay() = actions and PlaybackState.ACTION_PLAY != 0L ||
            actions and PlaybackState.ACTION_PLAY_PAUSE != 0L
        fun canPause() = actions and PlaybackState.ACTION_PAUSE != 0L ||
            actions and PlaybackState.ACTION_PLAY_PAUSE != 0L
        fun canSkipNext() = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
        fun canSkipPrev() = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
        fun canSeek() = actions and PlaybackState.ACTION_SEEK != 0L
    }

    interface Listener {
        fun onMediaStateChanged(snap: MediaSnapshot?)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var appContext: Context? = null
    // WeakHashMap: подписчики (Fragment/Activity) не утекают, если забыли отписаться.
    private val listeners = WeakHashMap<Any, Listener>()
    private var monitor: MediaMonitor? = null

    @Volatile
    var current: MediaSnapshot? = null
        private set

    /** Сохранённый снимок последнего РЕАЛЬНО наблюдавшегося источника (для быстрого восстановления UI). */
    data class LastSource(val packageName: String?, val label: String?)

    @Volatile
    var lastKnownSource: LastSource? = null
        private set

    fun load(context: Context) {
        val p = Prefs.get(context)
        lastKnownSource = p.lastMediaPackage?.let { LastSource(it, p.lastMediaLabel) }
    }

    fun save(context: Context) {
        val p = Prefs.get(context)
        val snap = current
        if (snap?.controller != null) {
            p.lastMediaPackage = snap.sourcePackage
            p.lastMediaLabel = AppLauncher.appLabel(context, snap.sourcePackage) ?: snap.sourcePackage
        } else if (lastKnownSource != null) {
            // источник пропал — сохраняем факт «последний источник», не очищая агрессивных данных
        }
    }

    private val monitorListener = object : MediaMonitor.Listener {
        override fun onActiveSource(c: MediaController?, sourcePackage: String?) {
            current = c?.let { buildSnapshot(it, sourcePackage) }
            if (c != null && sourcePackage != null) {
                lastKnownSource = LastSource(sourcePackage, appContext?.let { AppLauncher.appLabel(it, sourcePackage) } ?: sourcePackage)
            }
            val snapshot = current
            synchronized(listeners) { listeners.values.toList() }.forEach { l ->
                mainHandler.post { l.onMediaStateChanged(snapshot) }
            }
        }
    }

    /** Инициализация общего монитора (вызывается из Activity). Повторные вызовы безопасны. */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (monitor == null) monitor = MediaMonitor.get(context)
        monitor?.refresh()
    }

    fun addListener(owner: Any, l: Listener) {
        synchronized(listeners) { listeners[owner] = l }
        // Сразу отдаём актуальный снимок — экран не остаётся «пустым» до первого события.
        mainHandler.post { l.onMediaStateChanged(current) }
    }

    fun removeListener(owner: Any) {
        synchronized(listeners) { listeners.remove(owner) }
    }

    fun refresh() {
        monitor?.refresh()
    }

    private fun buildSnapshot(c: MediaController, pkg: String?): MediaSnapshot {
        val md: MediaMetadata? = try { c.metadata } catch (_: IllegalStateException) { null }
        val ps: PlaybackState? = try { c.playbackState } catch (_: IllegalStateException) { null }
        return MediaSnapshot(
            controller = c,
            sourcePackage = pkg,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            artwork = md?.getBitmap(MediaMetadata.METADATA_KEY_ARTWORK)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
            playing = ps?.state == PlaybackState.STATE_PLAYING || ps?.state == PlaybackState.STATE_BUFFERING,
            actions = ps?.actions ?: 0L,
            positionMs = ps?.position ?: -1L,
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
        )
    }
}
