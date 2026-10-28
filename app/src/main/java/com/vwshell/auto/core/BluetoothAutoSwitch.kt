package com.vwshell.auto.core

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Фича 1: автоопределение Bluetooth-источника для плеера.
 *
 * Реализовано на ОФИЦИАЛЬНОМ API MediaSessionManager.setOnActiveSessionsChangedListener
 * (ComponentName с существующим в проекте MediaButtonReceiver) + BroadcastReceiver на
 * ACTION_STATE_CHANGED / ACTION_ACL_DISCONNECTED.
 *
 * Обоснование размещения в MainActivity (не сервис): слушатель живёт ровно столько,
 * сколько видим лаунчер (HOME-приложение почти всегда живо); отдельный foreground-сервис
 * на урезанной HU требует уведомления и лишних разрешений (POST_NOTIFICATIONS/FOREGROUND_SERVICE),
 * чего спецификация запрещает ("не запрашивая избыточных разрешений").
 *
 * Никакой эмуляции A2DP Sink: управляется реальная внешняя медиасессия источника.
 * При bt_auto_switch == false источник переключается только вручную кнопкой BT.
 */
object BluetoothAutoSwitch {

    interface Listener {
        /** Найдена играющая внешняя сессия (UI может взять её контроллер напрямую). */
        fun onExternalPlaying(controller: MediaController, packageName: String)
        /** Внешних играющих сессий нет — источник должен быть локальным. */
        fun onNoExternal()
    }

    private const val TAG = "BtAutoSwitch"

    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()

    @Volatile private var registered = false
    @Volatile private var lastSeenPlaying: MediaController? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        mainHandler.post { evaluate(sessions) }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                        Log.i(TAG, "Bluetooth off -> force LOCAL")
                        forceLocal()
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    // Дисконнект устройства сам по себе не означает «музыка кончилась»
                    // (источник мог остаться USB/локальный) — честно перепроверяем сессии.
                    refreshNow()
                }
            }
        }
    }

    fun addListener(l: Listener) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: Listener) { synchronized(listeners) { listeners.remove(l) } }

    /** Регистрация слушателей. Вызывается из MainActivity.onCreate. */
    fun start(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (registered) return
        manager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        try {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                invokeSetSessionsListener(manager, ComponentName(ctx, MediaButtonReceiver::class.java), sessionsListener)
            }
        } catch (s: SecurityException) {
            // Нет доступа к уведомлениям — getActiveSessions недоступен; UI честен через MediaMonitor.
            Log.w(TAG, "No notification access for active-sessions listener")
        } catch (t: Throwable) {
            Log.w(TAG, "setOnActiveSessionsChangedListener failed", t)
        }
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            ctx.registerReceiver(btReceiver, filter)
        } catch (t: Throwable) {
            Log.w(TAG, "BT receiver registration failed", t)
        }
        registered = true
        // Первичная оценка текущих сессий.
        refreshNow()
    }

    /** Аннрегистрация. Вызывается из MainActivity.onDestroy. */
    fun stop(context: Context) {
        if (!registered) return
        registered = false
        try {
            invokeSetSessionsListener(manager,
                ComponentName(context.applicationContext, MediaButtonReceiver::class.java), null)
        } catch (_: Throwable) {}
        try { context.applicationContext.unregisterReceiver(btReceiver) } catch (_: Exception) {}
        manager = null
        lastSeenPlaying = null
    }

    /** Принудительная переоценка активных сессий (после подключения BT-наушников и т.п.). */
    fun refreshNow() {
        val mgr = manager ?: return
        val ctx = appContext ?: return
        if (!MediaMonitor.isNotificationServiceEnabled(ctx)) return
        Thread {
            val sessions = try {
                mgr.getActiveSessions(ComponentName(ctx, MediaButtonReceiver::class.java))
            } catch (_: Throwable) { null }
            if (sessions != null) mainHandler.post { evaluate(sessions) }
        }.also { it.isDaemon = true }.start()
    }

    /**
     * Рефлексивный вызов MediaSessionManager.setOnActiveSessionsChangedListener.
     * Метод существует в платформе с API 21, но отсутствует в SDK-стабах, используемых
     * этой сборкой (unresolved reference при компиляции) — вызываем через рефлексию,
     * любое падение логируется и не влияет на остальной лаунчер.
     */
    private fun invokeSetSessionsListener(
        mgr: MediaSessionManager?,
        cn: ComponentName,
        listener: MediaSessionManager.OnActiveSessionsChangedListener?
    ) {
        if (mgr == null) return
        try {
            val m = mgr.javaClass.getMethod(
                "setOnActiveSessionsChangedListener",
                MediaSessionManager.OnActiveSessionsChangedListener::class.java,
                ComponentName::class.java)
            m.invoke(mgr, listener, cn)
        } catch (t: Throwable) {
            Log.w(TAG, "setOnActiveSessionsChangedListener(reflect) failed", t)
        }
    }

    private fun evaluate(sessions: List<MediaController>?) {
        val ctx = appContext ?: return
        val own = ctx.packageName
        val playing = sessions?.firstOrNull { c ->
            c.packageName != own &&
                runCatching { c.playbackState?.state == PlaybackState.STATE_PLAYING }.getOrDefault(false)
        }
        lastSeenPlaying = playing
        val autoOn = Prefs.get(ctx).btAutoSwitch
        if (playing != null) {
            listenersSnapshot().forEach { it.onExternalPlaying(playing, playing.packageName ?: "") }
            if (autoOn && PlayerSource.kind != PlayerSource.Kind.EXTERNAL) {
                PlayerSource.setKind(PlayerSource.Kind.EXTERNAL)
                Prefs.get(ctx).mediaSourceKind = PlayerSource.Kind.EXTERNAL.name
                Log.i(TAG, "Auto-switched to EXTERNAL (${playing.packageName})")
            }
        } else {
            listenersSnapshot().forEach { it.onNoExternal() }
            if (autoOn && PlayerSource.kind == PlayerSource.Kind.EXTERNAL) {
                PlayerSource.setKind(PlayerSource.Kind.LOCAL)
                Prefs.get(ctx).mediaSourceKind = PlayerSource.Kind.LOCAL.name
                Log.i(TAG, "No external playing session -> LOCAL")
            }
        }
    }

    private fun forceLocal() {
        appContext?.let { ctx ->
            PlayerSource.setKind(PlayerSource.Kind.LOCAL)
            Prefs.get(ctx).mediaSourceKind = PlayerSource.Kind.LOCAL.name
        }
        listenersSnapshot().forEach { it.onNoExternal() }
    }

    private fun listenersSnapshot(): List<Listener> = synchronized(listeners) { listeners.toList() }
}
