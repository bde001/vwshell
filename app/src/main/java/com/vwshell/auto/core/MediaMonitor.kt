package com.vwshell.auto.core

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Монитор активной MediaSession системы.
 * Использует официальный API MediaSessionManager.getActiveSessions — требует
 * Notification Listener Access (пользователь может предоставить в настройках).
 * Никаких фиктивных данных: если сессии нет — слушатель получает null.
 */
class MediaMonitor private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val manager: MediaSessionManager? =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private val mainHandler = Handler(Looper.getMainLooper())

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

    private var currentController: MediaController? = null

    fun addListener(l: Listener) {
        synchronized(listeners) { listeners.add(l) }
        dispatch()
    }

    fun removeListener(l: Listener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    fun refresh() {
        if (manager == null) { dispatch(); return }
        if (!isNotificationServiceEnabled(appContext)) {
            hasNotificationAccess = false
            detachController()
            dispatch()
            return
        }
        hasNotificationAccess = true
        try {
            // QUERY_ALL_PACKAGES не запрашиваем; getActiveSessions бросает исключение без NLA —
            // при наличии доступа возвращает активные сессии медиаприложений.
            val sessions = manager.getActiveSessions(componentNames(appContext))
            val active = sessions.firstOrNull { it.playbackState?.state != PlaybackState.STATE_NONE }
                ?: sessions.firstOrNull()
            attachController(active)
        } catch (s: SecurityException) {
            hasNotificationAccess = false
            Log.i(TAG, "No notification access; media source hidden")
            detachController()
        } catch (t: Throwable) {
            Log.w(TAG, "getActiveSessions failed", t)
            detachController()
        }
        dispatch()
    }

    private fun attachController(c: MediaController?) {
        if (currentController === c) return
        currentController?.unregisterCallback(stateCallback)
        currentController = c
        c?.registerCallback(stateCallback)
    }

    private fun detachController() {
        currentController?.unregisterCallback(stateCallback)
        currentController = null
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
            return flat.split(":").any { it == mine }
        }

        private fun componentNames(context: Context) =
            listOf(ComponentName(context, ShellNotificationListener::class.java))
    }
}
