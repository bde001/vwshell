package com.vwshell.auto.core

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Действие рулевой клавиши (алиас вне object — вложенные typealias запрещены Kotlin). */
typealias SteeringAction = com.vwshell.auto.core.SteeringKeyMap.Action

/**
 * Расширяемый журнал аппаратных клавиш.
 * Стандартные коды (громкость, треки, play/pause, home) обрабатываются системой/MediaSession.
 * Неизвестные коды логируются локально для последующей настройки под конкретную магнитолу/CAN-box.
 */
object KeyLogger {
    private const val TAG = "VWShellKeys"
    private const val MAX_ENTRIES = 200
    private val entries = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Действие для клавиши (расширяемая карта руля/пульта). */
    fun mapAction(keyCode: Int): SteeringAction = SteeringKeyMap.map(keyCode)

    /** Возвращает true, если код известен и уже обработан стандартным механизмом. */
    fun isKnownKeyCode(keyCode: Int): Boolean = keyCode in KNOWN_KEYS

    fun logUnknown(context: Context, event: android.view.KeyEvent) {
        val line = "${timeFormat.format(Date(event.eventTime))}  code=${event.keyCode} " +
            "(0x${event.keyCode.toString(16)})  src=${event.source} action=${event.action} " +
            "name=${android.view.KeyEvent.keyCodeToString(event.keyCode)}"
        Log.i(TAG, line)
        synchronized(entries) {
            entries.addFirst(line)
            while (entries.size > MAX_ENTRIES) entries.removeLast()
        }
        // Сохранение в local logcat-файл не требуется; журнал доступен в разделе "Настройки → Журнал клавиш".
    }

    fun snapshot(): List<String> = synchronized(entries) { entries.toList() }

    private val KNOWN_KEYS = setOf(
        android.view.KeyEvent.KEYCODE_VOLUME_UP,
        android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
        android.view.KeyEvent.KEYCODE_VOLUME_MUTE,
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
        android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
        android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
        android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        android.view.KeyEvent.KEYCODE_HEADSETHOOK,
        android.view.KeyEvent.KEYCODE_HOME,
        android.view.KeyEvent.KEYCODE_BACK,
        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
        android.view.KeyEvent.KEYCODE_DPAD_UP,
        android.view.KeyEvent.KEYCODE_DPAD_DOWN,
        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        android.view.KeyEvent.KEYCODE_ENTER,
        android.view.KeyEvent.KEYCODE_MENU
    )
}
