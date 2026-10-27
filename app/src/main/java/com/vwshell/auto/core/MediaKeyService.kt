package com.vwshell.auto.core

import android.view.KeyEvent

/**
 * Расширяемая карта аппаратных клавиш руля/пульта.
 * Стандартные коды обрабатываются системой и MediaSession автоматически:
 *   KEYCODE_VOLUME_UP/DOWN  → системная громкость
 *   KEYCODE_MEDIA_NEXT/PREV/PLAY_PAUSE → активная MediaSession источника
 *   KEYCODE_HOME → возврат на главный экран (мы — HOME)
 * Нестандартные OEM-коды конкретной магнитолы логируются KeyLogger; при необходимости
 * сюда добавляется сопоставление «код устройства → действие» после замеров на железе.
 */
object SteeringKeyMap {

    enum class Action { VOLUME_UP, VOLUME_DOWN, TRACK_NEXT, TRACK_PREV, PLAY_PAUSE, HOME, UNKNOWN }

    fun map(keyCode: Int): Action = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> Action.VOLUME_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> Action.VOLUME_DOWN
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Action.TRACK_NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> Action.TRACK_PREV
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> Action.PLAY_PAUSE
        else -> Action.UNKNOWN
    }
}
