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

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Доступ подтверждён системой — обновляем медиасессии сразу, без ожидания onResume.
        MediaMonitor.get(applicationContext).refresh()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Доступ отозван — интерфейс должен честно показать отсутствие источника.
        MediaMonitor.get(applicationContext).refresh()
        try { requestRebind(android.content.ComponentName(this, ShellNotificationListener::class.java)) } catch (_: Throwable) {}
    }

    companion object {
        fun refreshAll(context: android.content.Context) {
            MediaMonitor.get(context).refresh()
        }
    }
}
