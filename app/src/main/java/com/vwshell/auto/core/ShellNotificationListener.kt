package com.vwshell.auto.core

import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService

/**
 * Минимальный NotificationListenerService: нужен ИСКЛЮЧИТЕЛЬНО для официального доступа
 * к MediaSessionManager.getActiveSessions (реальные метаданные воспроизведения).
 * Нотификации не читаются, не сохраняются и никуда не отправляются.
 * Пользователь включает доступ осознанно; без доступа раздел «Медиа» честно показывает
 * отсутствие источника.
 */
class ShellNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        // Намеренно пусто — содержимое уведомлений не обрабатывается.
    }

    companion object {
        fun refreshAll(context: android.content.Context) {
            MediaMonitor.get(context).refresh()
        }
    }
}
