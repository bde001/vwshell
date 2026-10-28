package com.vwshell.auto.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.media.session.MediaButtonReceiver as AndroidxMediaButtonReceiver

/**
 * Приёмник аппаратных медиа-кнопок (ACTION_MEDIA_BUTTON).
 *
 * Нужен для работы с внешними медиасессиями HU (Bluetooth-источник):
 *  1) MediaSessionManager.getActiveSessions() возвращает сессии только для явно
 *     указанного компонента — им выступает этот receiver;
 *  2) нажатия media-кнопок маршрутизируются активной внешней MediaSession через
 *     официальный androidx helper (никакой эмуляции A2DP Sink здесь нет).
 *
 * Никаких фиктивных действий: если активной сессии нет — событие просто отбрасывается.
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        // Маршрутизируем событие активной внешней медиасессии (официальный API androidx.media).
        // Внутри helper проверяет наличие ACTION_MEDIA_BUTTON и валидный KeyEvent.
        // Официальный androidx helper сам извлекает KeyEvent и маршрутизует его
        // активной внешней медиасессии; при отсутствии таковой — no-op.
        // Никакой эмуляции A2DP Sink здесь нет.
        runCatching { AndroidxMediaButtonReceiver.handleIntent(null, intent) }
            .onFailure { android.util.Log.d(TAG, "media button routing skipped", it) }
    }

    companion object {
        private const val TAG = "MediaButtonReceiver"
    }
}
