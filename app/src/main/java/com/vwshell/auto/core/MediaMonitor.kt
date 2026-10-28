package com.vwshell.auto.core

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Монитор активной MediaSession системы.
 * Использует официальный API MediaSessionManager.getActiveSessions — требует
 * Notification Listener Access (пользователь может предоставить в настройках).
 * Никаких фиктивных данных: если сессии нет — слушатель получает null.
 * getActiveSessions() выполняется на фоновом потоке — главный поток не блокируется.
 */
class MediaMonitor private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val manager: MediaSessionManager? =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "vw-media-monitor").apply { isDaemon = true } }
    private val refreshPending = AtomicBoolean(false)

    interface Listener {
        /** controller == null означает «источник отсутствует» (не выдумываем данные). */
        fun onActiveSource(controller: MediaController?, sourcePackage: String?)
    }

    private val listeners = mutableListOf<Listener>()
    @Volatile var hasNotificationAccess: Boolean = false
        private set

    private val stateCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            dispatch()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            dispatch()
        }

        override fun onSessionDestroyed() {
            refresh()
        }
    }

    @Volatile private var currentController: MediaController? = null

    fun addListener(l: Listener) {
        synchronized(listeners) { listeners.add(l) }
        dispatch()
    }

    fun removeListener(l: Listener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    /** Фоновое перечитание активных сессий; частые вызовы схлопываются в один ожидающий. */
    fun refresh() {
        if (manager == null) { dispatch(); return }
        if (!isNotificationServiceEnabled(appContext)) {
            hasNotificationAccess = false
            mainHandler.post { detachController(); dispatch() }
            return
        }
        hasNotificationAccess = true
        if (refreshPending.compareAndSet(false, true)) {
            worker.execute {
                val sessions: List<MediaController>? = try {
                    val self = componentNames(appContext)[0]
                    manager.getActiveSessions(self)
                } catch (s: SecurityException) {
                    // Доступ отозван пользователем во время работы — интерфейс обновится честно.
                    hasNotificationAccess = false
                    Log.i(TAG, "No notification access; media source hidden")
                    null
                } catch (t: Throwable) {
                    Log.w(TAG, "getActiveSessions failed", t)
                    null
                }
                refreshPending.set(false)
                mainHandler.post {
                    if (sessions != null) {
                        val active = sessions.firstOrNull { it.playbackState?.state != PlaybackState.STATE_NONE }
                            ?: sessions.firstOrNull()
                        attachController(active)
                    } else {
                        detachController()
                    }
                    dispatch()
                }
            }
        }
    }

    /** Синхронная проверка для UI-сценариев («нужен ли экран предоставления доступа»). */
    fun checkAccessNow(): Boolean {
        val enabled = isNotificationServiceEnabled(appContext)
        hasNotificationAccess = enabled
        return enabled
    }

    private fun attachController(c: MediaController?) {
        if (currentController === c) return
        currentController?.unregisterCallback(stateCallback)
        currentController = c
        activeToken = c?.sessionToken
        c?.registerCallback(stateCallback)
    }

    private fun detachController() {
        currentController?.unregisterCallback(stateCallback)
        currentController = null
        activeToken = null
    }

    private fun dispatch() {
        val c = currentController
        val pkg = c?.packageName
        val snapshot = synchronized(listeners) { listeners.toList() }
        mainHandler.post {
            snapshot.forEach { it.onActiveSource(c, pkg) }
        }
    }

    companion object {
        private const val TAG = "MediaMonitor"
        private const val COMPONENT_KEY = "enabled_notification_listeners"

        @Volatile private var instance: MediaMonitor? = null

        fun get(context: Context): MediaMonitor =
            instance ?: synchronized(this) {
                instance ?: MediaMonitor(context).also { instance = it }
            }

        fun isNotificationServiceEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver, COMPONENT_KEY) ?: return false
            val mine = ComponentName(context, ShellNotificationListener::class.java).flattenToString()
            return flat.split(":").any { it == mine } ||
                flat.split(":").any { it.endsWith("/$DEFAULT_COMP_SUFFIX") }
        }

        private const val DEFAULT_COMP_SUFFIX = "com.vwshell.auto.core.ShellNotificationListener"

        private fun componentNames(context: Context) =
            listOf(ComponentName(context, MediaButtonReceiver::class.java))

        /** Токен активной внешней медиасессии (для маршрутизации MEDIA_BUTTON). */
        @Volatile var activeToken: android.media.session.MediaSession.Token? = null

        fun activeSessionToken(context: Context): android.media.session.MediaSession.Token? {
            val t = activeToken ?: return null
            // Проверяем, что токен принадлежит сессии, видимой нашему receiver-компоненту.
            return runCatching {
                val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                if (mgr != null) {
                    val self = componentNames(context)[0]
                    if (mgr.getActiveSessions(self).any { it.sessionToken == t }) t else null
                } else null
            }.getOrNull()
        }
    }
}
