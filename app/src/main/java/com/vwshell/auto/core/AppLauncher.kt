package com.vwshell.auto.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import android.Manifest
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log

/** Описание установленного приложения для списков выбора. */
data class AppEntry(val packageName: String, val activity: String?, val label: String)

/**
 * Безопасный запуск внешних приложений с обработкой отсутствия пакета/ошибок запуска.
 * Никаких фиктивных действий: если приложение недоступно — возвращается false.
 */
object AppLauncher {

    fun launchPackage(context: Context, packageName: String?): Boolean {
        if (packageName == null) return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w("AppLauncher", "launch $packageName failed", t)
            false
        }
    }

    fun launchIntentSafely(context: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        Log.w("AppLauncher", "intent failed: ${intent.action}", t)
        false
    }

    /** Все приложения с Launcher-иконкой (для списков выбора). */
    fun installedLaunchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try { pm.queryIntentActivities(main, 0) } catch (e: Exception) { emptyList() }
        return resolved.map {
            AppEntry(it.activityInfo.packageName, it.activityInfo.name,
                it.loadLabel(pm).toString())
        }.sortedBy { it.label.lowercase() }
    }

    /** Навигационные приложения: умеют открывать geo:-ссылки или имеют лаунчер + известный пакет навигатора. */
    fun navigationApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val knownNavPackages = listOf(
            "com.google.android.apps.maps",                       // Google Maps
            "ru.yandex.yandexnavi",                               // Яндекс Навигатор
            "ru.yandex.yandexmaps",                               // Яндекс Карты
            "com.waze",                                           // Waze
            "com.navio.app", "ru.dublgis.dgismobile",             // ДубльГИС
            "com.sygic.aura", "net.osmand.plus", "net.osmand"     // Sygic / OsmAnd
        )
        val result = LinkedHashMap<String, AppEntry>()
        for (pkg in knownNavPackages) {
            val li = pm.getLaunchIntentForPackage(pkg) ?: continue
            val ri = pm.resolveActivity(li, 0) ?: continue
            result[pkg] = AppEntry(pkg, ri.activityInfo?.name, ri.loadLabel(pm).toString())
        }
        // Плюс всё, что зарегистрировано как обработчик geo:
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
        try {
            pm.queryIntentActivities(geo, 0).forEach {
                if (!result.containsKey(it.activityInfo.packageName)) {
                    result[it.activityInfo.packageName] =
                        AppEntry(it.activityInfo.packageName, it.activityInfo.name, it.loadLabel(pm).toString())
                }
            }
        } catch (_: Exception) {}
        return result.values.sortedBy { it.label.lowercase() }
    }

    /** Открыть маршрут/поиск адреса через geo:-интент (поддерживается Google Maps, Yandex, OsmAnd и др.). */
    fun openGeo(context: Context, query: String, pkg: String?): Boolean {
        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(query.ifBlank { "" }))
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (pkg != null) intent.setPackage(pkg)
        return if (pkg != null) {
            launchIntentSafely(context, intent) || launchPackage(context, pkg)
        } else {
            // Без пакета — системный выбор приложения
            try {
                context.startActivity(Intent.createChooser(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null))
                true
            } catch (t: Throwable) { Log.w("AppLauncher", "geo chooser failed", t); false }
        }
    }

    /** Запуск штатного Dialer/телефонного приложения. */
    fun launchDialer(context: Context, pkg: String?): Boolean {
        if (pkg != null && launchPackage(context, pkg)) return true
        val telecom = Intent(Intent.ACTION_DIAL).setData(Uri.parse("tel:"))
        if (launchIntentSafely(context, telecom)) return true
        val classic = Intent(Intent.ACTION_CALL_BUTTON)
        return launchIntentSafely(context, classic)
    }

    /** Системная панель аудиоэффектов/эквалайзера — если прошивка предоставляет обработчик. */
    fun openAudioEffectsPanel(context: Context): Boolean =
        launchIntentSafely(context, Intent("android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL"))

    /** Системные настройки звука Android. */
    fun openSoundSettings(context: Context): Boolean =
        launchIntentSafely(context, Intent(Settings.ACTION_SOUND_SETTINGS))

    /** Общие настройки Android. */
    fun openAndroidSettings(context: Context): Boolean =
        launchIntentSafely(context, Intent(Settings.ACTION_SETTINGS))

    /** Экран предоставления доступа к уведомлениям (Notification Listener). */
    fun openNotificationListenerSettings(context: Context): Boolean =
        launchIntentSafely(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    /** Системные настройки яркости. */
    fun openDisplaySettings(context: Context): Boolean =
        launchIntentSafely(context, Intent(Settings.ACTION_DISPLAY_SETTINGS))

    /** Системные настройки Bluetooth (штатное приложение BT магнитолы). */
    fun openBluetoothSettings(context: Context): Boolean =
        launchIntentSafely(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    /** Настройки приложения по умолчанию (Android-механизм назначения Home). */
    fun openDefaultAppSettings(context: Context): Boolean =
        launchIntentSafely(context, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))

    /** Радио: сначала пользовательский выбор, затем FM-интенты Android, затем любое радио-приложение. */
    fun launchRadio(context: Context, pkg: String?): Boolean {
        if (pkg != null && launchPackage(context, pkg)) return true
        val candidates = listOf(
            // MediaStore.ACTION_AUDIO_CAPTURE_FM отсутствует в public SDK API — используем только FM-интенты производителей
            Intent("android.media.action.RADIO"),
            Intent("android.intent.action.RADIO_TUNER_SETTINGS")
        )
        for (i in candidates) { if (launchIntentSafely(context, i)) return true }
        // Поиск установленного радио-приложения
        val guess = installedLaunchableApps(context).firstOrNull {
            val l = it.label.lowercase()
            l.contains("radio") || l.contains("радио") || l.contains("fm") || it.packageName.contains("radio")
        }
        return guess != null && launchPackage(context, guess.packageName)
    }

    /** Есть ли у устройства FM-тюнер (реальный аппаратный признак). */
    fun hasFmTuner(context: Context): Boolean =
        context.packageManager.hasSystemFeature("android.hardware.fm")
            || context.packageManager.hasSystemFeature("android.hardware.radio.fm")

    /** Название установленного приложения по пакету или null. */
    fun appLabel(context: Context, packageName: String?): String? {
        if (packageName == null) return null
        return try {
            val ai = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) { null }
    }

    /** Пакет установлен и имеет хотя бы одну компоненту? */
    fun isPackageInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0) != null
    } catch (_: Exception) { false }

    /**
     * Навигация: прямой запуск Google Maps без промежуточного экрана выбора.
     * Порядок: сохранённый выбор пользователя → Google Maps (явный пакет) → geo:-интент.
     * Возвращает true только если запуск реально выполнен.
     */
    fun launchMapsDirect(context: Context, savedPkg: String?): Boolean {
        if (savedPkg != null && launchPackage(context, savedPkg)) return true
        if (launchPackage(context, GOOGLE_MAPS_PKG)) return true
        // Fallback: geo:-интент — система сама найдёт обработчик карт (если он есть).
        return launchIntentSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")))
    }

    /** Открыть страницу установки приложения в магазине приложений (Play Market или OEM-магазин магнитолы). */
    fun openInstallPage(context: Context, packageName: String): Boolean {
        val uri = Uri.parse("market://details?id=$packageName")
        if (launchIntentSafely(context, Intent(Intent.ACTION_VIEW, uri))) return true
        return launchIntentSafely(context, Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
    }

    /**
     * Автомобиль: прямой запуск Car Scanner ELM OBD2 (free / pro), затем сохранённый выбор.
     * Никаких фиктивных телеметрических экранов: если приложение не установлено — false.
     */
    fun launchCarScanner(context: Context, savedPkg: String?): Boolean {
        if (savedPkg != null && launchPackage(context, savedPkg)) return true
        if (launchPackage(context, CAR_SCANNER_FREE)) return true
        return launchPackage(context, CAR_SCANNER_PRO)
    }

    /** Google Maps установлен и запускается? (проверка реального лаунчер-интента, не догадка) */
    fun isMapsAvailable(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(GOOGLE_MAPS_PKG) != null

    const val GOOGLE_MAPS_PKG = "com.google.android.apps.maps"
    const val CAR_SCANNER_FREE = "com.mgmadv.carscanner.elm.free"
    const val CAR_SCANNER_PRO = "com.mgmadv.carscanner.elm.pro"

    /**
     * Реально установленные совместимые диагностические (OBD2) приложения — только те,
     * что имеют лаунчер-активность на этом устройстве (Android 11+: видимость обеспечивается
     * элементом <queries> MAIN/LAUNCHER в манифесте, избыточных разрешений нет).
     */
    fun diagnosticApps(context: Context): List<AppEntry> {
        val launchable = installedLaunchableApps(context)
        return launchable.filter { e ->
            val p = e.packageName.lowercase()
            val l = e.label.lowercase()
            p in OBD_KNOWN_PACKAGES || p.contains("obd") || p.contains("carscanner") ||
                p.contains("car_scanner") || l.contains("obd") ||
                l.contains("car scanner") || l.contains("диагност")
        }
    }

    private val OBD_KNOWN_PACKAGES = setOf(
        "com.mgmadv.carscanner.elm.free",   // Car Scanner ELM OBD2 (free)
        "com.mgmadv.carscanner.elm.pro",    // Car Scanner ELM OBD2 (pro)
        "com.fordtechservice.dealerconnection.8590D3DA65DCB", // FORScan
        "org.puretel.obdautotester",
        "com.obdautodo.starter",
        "com.pluxiosoft.carly"
    )

    /**
     * Состояние Bluetooth: null — адаптера нет / нет доступа (BLUETOOTH_CONNECT не выдано);
     * иначе — включён ли адаптер. Только реально доступные данные.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun adapterEnabled(context: Context): Boolean? = try {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        mgr?.adapter?.isEnabled
    } catch (s: SecurityException) {
        null
    } catch (t: Throwable) { null }

    /** Требуется ли runtime-разрешение BLUETOOTH_CONNECT на этом устройстве (Android 12+). */
    fun needsBluetoothPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Есть ли уже разрешение BLUETOOTH_CONNECT (на Android 10/11 — всегда «есть», если объявлено в манифесте). */
    fun hasBluetoothPermission(context: Context): Boolean =
        !needsBluetoothPermission() ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Безопасное чтение состояния Bluetooth: без разрешения возвращает null — индикатор скрывается,
     * защищённые API не вызываются, SecurityException невозможен.
     */
    fun bluetoothState(context: Context): Boolean? {
        if (!hasBluetoothPermission(context)) return null
        return adapterEnabled(context)
    }

    /** Уровень сигнала сотовой сети в реальном времени или null, если данных нет. */
    @Suppress("DEPRECATION")
    fun signalLevel(context: Context): Int? {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
            if (!tm.isDataEnabled && tm.dataNetworkType == TelephonyManager.NETWORK_TYPE_UNKNOWN) return null
            val listeners = object : android.telephony.PhoneStateListener() {
                var last: Int? = null
                override fun onSignalStrengthsChanged(signalStrength: android.telephony.SignalStrength) {
                    last = signalStrength.level
                }
            }
            tm.listen(listeners, android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
            tm.listen(listeners, android.telephony.PhoneStateListener.LISTEN_NONE)
            val level = listeners.last
            if (level != null && level > 0) level else null
        } catch (s: SecurityException) {
            // READ_PHONE_STATE не запрашивается намеренно — индикатор просто скрывается.
            null
        } catch (t: Throwable) { null }
    }
}
