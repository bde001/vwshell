package com.vwshell.auto.ui

import android.media.session.PlaybackState

/**
 * Чистая логика доступности транспорт-действий активной медиасессии
 * (покрыта unit-тестами). Никаких домыслов: действие разрешено только если
 * сессия реально объявила соответствующий ACTION_* в PlaybackState.actions.
 */
object MediaSessionLogic {

    fun canPlay(state: PlaybackState?): Boolean = supports(state, PlaybackState.ACTION_PLAY)
    fun canPause(state: PlaybackState?): Boolean = supports(state, PlaybackState.ACTION_PAUSE)
    fun canSkipNext(state: PlaybackState?): Boolean = supports(state, PlaybackState.ACTION_SKIP_TO_NEXT)
    fun canSkipPrevious(state: PlaybackState?): Boolean = supports(state, PlaybackState.ACTION_SKIP_TO_PREVIOUS)

    fun supports(state: PlaybackState?, action: Long): Boolean =
        state != null && (state.actions and action) != 0L

    fun isPlaying(state: PlaybackState?): Boolean =
        state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING

    /**
     * Выбор действия для кнопки Play/Pause:
     * @return true — можно играть, false — можно ставить на паузу, null — ничего не поддерживается.
     */
    fun playOrPause(state: PlaybackState?): Boolean? {
        if (isPlaying(state)) return if (canPause(state)) false else null
        if (canPlay(state)) return true
        // Некоторые сессии объявляют только ACTION_PLAY_PAUSE — трактуем как «можно играть/паузу».
        if (supports(state, PlaybackState.ACTION_PLAY_PAUSE)) return !isPlaying(state)
        return null
    }
}
