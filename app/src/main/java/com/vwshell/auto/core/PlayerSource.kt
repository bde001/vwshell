package com.vwshell.auto.core

import android.content.Context
import android.media.session.PlaybackState

/**
 * Единое общее состояние источника аудиоплеера оболочки.
 *
 * Используется ВСЕМИ компонентами: MediaFragment (единый экран плеера), мини-плеером
 * главного экрана и кнопкой Bluetooth на главном экране — расхождение состояний
 * между экранами невозможно по конструкции (ОДИН объект на процесс).
 *
 * - LOCAL  — собственный движок LocalPlayerEngine (локальные файлы MediaStore);
 * - EXTERNAL — внешняя активная медиасессия Android из ShellState (BT-телефон, USB-плеер).
 *   Приём Bluetooth-A2DP-потока «внутрь» приложения оболочкой НЕ поддерживается и не эмулируется:
 *   управляется именно реальная внешняя медиасессия источника.
 */
object PlayerSource {

    enum class Kind { LOCAL, EXTERNAL }

    interface Listener {
        /** Вызывается в главном потоке при любом изменении выбранного источника. */
        fun onPlayerSourceChanged(kind: Kind)
    }

    @Volatile
    var kind: Kind = Kind.LOCAL
        private set

    private val listeners = mutableListOf<Listener>()

    fun setKind(k: Kind) {
        if (kind == k) return
        kind = k
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it.onPlayerSourceChanged(k) }
    }

    fun addListener(l: Listener) { synchronized(listeners) { listeners.add(l) } }

    fun removeListener(l: Listener) { synchronized(listeners) { listeners.remove(l) } }

    /**
     * Разрешение MediaFragment: локальный движок активен только когда выбран LOCAL;
     * внешняя сессия — только когда выбран EXTERNAL. Мини-плеер использует тот же метод,
     * поэтому оба UI всегда показывают один и тот же источник.
     */
    fun allowsLocal(engineHasPlaylist: Boolean): Boolean = kind == Kind.LOCAL && engineHasPlaylist

    fun allowsExternal(snap: ShellState.MediaSnapshot?): Boolean =
        kind == Kind.EXTERNAL && snap != null && snap.controller != null

    /** Активный контроллер внешней сессии или null (проверка объявленных действий — на стороне вызывающего). */
    fun externalController(snap: ShellState.MediaSnapshot?) =
        if (allowsExternal(snap)) snap?.controller else null

    /** Реально объявленные источником транспортные действия (0 — если сессия недоступна). */
    fun externalActions(snap: ShellState.MediaSnapshot?): Long =
        if (!allowsExternal(snap)) 0L
        else try { snap?.controller?.playbackState?.actions ?: 0L } catch (_: IllegalStateException) { 0L }

    fun externalCanPlay(snap: ShellState.MediaSnapshot?): Boolean {
        val a = externalActions(snap)
        return a and PlaybackState.ACTION_PLAY != 0L || a and PlaybackState.ACTION_PLAY_PAUSE != 0L
    }

    fun externalCanPause(snap: ShellState.MediaSnapshot?): Boolean {
        val a = externalActions(snap)
        return a and PlaybackState.ACTION_PAUSE != 0L || a and PlaybackState.ACTION_PLAY_PAUSE != 0L
    }

    fun externalCanSkipNext(snap: ShellState.MediaSnapshot?): Boolean =
        externalActions(snap) and PlaybackState.ACTION_SKIP_TO_NEXT != 0L

    fun externalCanSkipPrev(snap: ShellState.MediaSnapshot?): Boolean =
        externalActions(snap) and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L

    /**
     * Переключение LOCAL ⇄ EXTERNAL (кнопка Bluetooth на главном экране).
     * Возвращает false, если внешняя медиасессия недоступна — источник остаётся без изменений
     * (вызовущий показывает понятное сообщение).
     */
    fun toggle(context: Context): Boolean {
        return when (kind) {
            Kind.LOCAL -> {
                if (ShellState.current?.controller == null) {
                    Prefs.get(context).mediaSourceKind = Kind.LOCAL.name
                    false
                } else {
                    setKind(Kind.EXTERNAL)
                    Prefs.get(context).mediaSourceKind = Kind.EXTERNAL.name
                    true
                }
            }
            Kind.EXTERNAL -> {
                setKind(Kind.LOCAL)
                Prefs.get(context).mediaSourceKind = Kind.LOCAL.name
                true
            }
        }
    }

    /** Восстановление сохранённого выбора источника (из Prefs, см. ShellState.load). */
    fun restoreFromPrefs(context: Context) {
        val saved = runCatching {
            Kind.valueOf(Prefs.get(context).mediaSourceKind)
        }.getOrDefault(Kind.LOCAL)
        // Внешний источник имеет смысл только если сессия реально есть прямо сейчас.
        kind = if (saved == Kind.EXTERNAL && ShellState.current?.controller != null)
            Kind.EXTERNAL else Kind.LOCAL
    }
}
