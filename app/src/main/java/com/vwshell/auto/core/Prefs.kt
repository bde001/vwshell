package com.vwshell.auto.core

import android.content.Context
import android.content.SharedPreferences

/** Хранилище настроек оболочки. Никакой телеметрии — только локальные предпочтения пользователя. */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("vw_shell_prefs", Context.MODE_PRIVATE)

    var navAppPackage: String?
        get() = sp.getString(KEY_NAV, null)
        set(v) = sp.edit().putString(KEY_NAV, v).apply()

    var mediaAppPackage: String?
        get() = sp.getString(KEY_MEDIA, null)
        set(v) = sp.edit().putString(KEY_MEDIA, v).apply()

    var radioAppPackage: String?
        get() = sp.getString(KEY_RADIO, null)
        set(v) = sp.edit().putString(KEY_RADIO, v).apply()

    var phoneAppPackage: String?
        get() = sp.getString(KEY_PHONE, null)
        set(v) = sp.edit().putString(KEY_PHONE, v).apply()

    /** Пакет приложения «Автомобиль» (по умолчанию Car Scanner ELM OBD2). */
    var vehicleAppPackage: String?
        get() = sp.getString(KEY_VEHICLE, DEFAULT_VEHICLE_PKG)
        set(v) = sp.edit().putString(KEY_VEHICLE, v).apply()

    /** Выбранное приложение для плитки «AppConnect» (ожидается ZLink; выбор — через диалог). */
    var appConnectAppPackage: String?
        get() = sp.getString(KEY_APPCONNECT, null)
        set(v) = sp.edit().putString(KEY_APPCONNECT, v).apply()

    /** Выбранное приложение для плитки «Звук» (звуковое/equalizer-приложение; выбор — через диалог). */
    var soundAppPackage: String?
        get() = sp.getString(KEY_SOUND_APP, null)
        set(v) = sp.edit().putString(KEY_SOUND_APP, v).apply()

    /** Onboarding-подсказка «Сделать главным экраном» больше не показывать. */
    var homeHintDismissed: Boolean
        get() = sp.getBoolean(KEY_HOME_HINT, false)
        set(v) = sp.edit().putBoolean(KEY_HOME_HINT, v).apply()

    /** Избранные FM-частоты (только сохранённые пользователем значения, без фиктивных данных). */
    fun favorites(): MutableSet<String> = HashSet(sp.getStringSet(KEY_FAV, emptySet()) ?: emptySet())

    fun toggleFavorite(freqKhz: Int): Boolean {
        val set = favorites()
        val key = freqKhz.toString()
        val added = if (set.contains(key)) { set.remove(key); false } else { set.add(key); true }
        sp.edit().putStringSet(KEY_FAV, set).apply()
        return added
    }

    /** Пользовательские пресеты = реально сохранённые избранные частоты (десятые доли МГц). */
    fun presets(): List<Int> = favorites().mapNotNull { it.toIntOrNull() }.sorted()

    fun lastFreqTenths(): Int? = sp.getInt(KEY_LAST_FREQ, -1).let { if (it < 0) null else it }
    fun setLastFreq(freqTenths: Int) = sp.edit().putInt(KEY_LAST_FREQ, freqTenths).apply()

    /** Выбранный источник плеера: LOCAL / EXTERNAL (общее состояние PlayerSource). */
    var mediaSourceKind: String
        get() = sp.getString(KEY_MEDIA_SOURCE, "LOCAL") ?: "LOCAL"
        set(v) = sp.edit().putString(KEY_MEDIA_SOURCE, v).apply()

    /** Последний реально наблюдавшийся источник медиа (для честного восстановления UI после пробуждения). */
    var lastMediaPackage: String?
        get() = sp.getString(KEY_LAST_MEDIA_PKG, null)
        set(v) = sp.edit().putString(KEY_LAST_MEDIA_PKG, v).apply()

    var lastMediaLabel: String?
        get() = sp.getString(KEY_LAST_MEDIA_LABEL, null)
        set(v) = sp.edit().putString(KEY_LAST_MEDIA_LABEL, v).apply()

    /** Избранные приложения в App Drawer (запускаются первыми по сохранённому порядку). */
    fun favoriteApps(): List<String> =
        (sp.getString(KEY_FAV_APPS, "") ?: "").split("|").filter { it.isNotBlank() }

    fun setFavoriteApps(list: List<String>) =
        sp.edit().putString(KEY_FAV_APPS, list.joinToString("|")).apply()

    /** Скрытые приложения (не удаляются с устройства — только не показываются в сетке). */
    fun hiddenApps(): MutableSet<String> = HashSet(sp.getStringSet(KEY_HIDDEN_APPS, emptySet()) ?: emptySet())

    fun setHiddenApps(set: Set<String>) = sp.edit().putStringSet(KEY_HIDDEN_APPS, set).apply()


    /** Горизонтальный отступ между плитками главного экрана, dp. */
    var tileSpacingDp: Int
        get() = sp.getInt(KEY_TILE_SPACING, 10).coerceIn(4, 24)
        set(v) = sp.edit().putInt(KEY_TILE_SPACING, v.coerceIn(4, 24)).apply()

    /** Вертикальный отступ: меньший отступ оставляет плиткам больше видимой высоты. */
    var tileVerticalSpacingDp: Int
        get() = sp.getInt(KEY_TILE_VERTICAL_SPACING, 8).coerceIn(0, 18)
        set(v) = sp.edit().putInt(KEY_TILE_VERTICAL_SPACING, v.coerceIn(0, 18)).apply()

    /** 24-часовой формат времени (настройка интерфейса оболочки). */
    var clock24h: Boolean
        get() = sp.getBoolean(KEY_CLOCK_24H, true)
        set(v) = sp.edit().putBoolean(KEY_CLOCK_24H, v).apply()

    /** Сохранённые пользовательские предпочтения звука (применяются только если система их поддерживает). */
    var balanceLr: Int // -15..15
        get() = sp.getInt(KEY_BAL_LR, 0)
        set(v) = sp.edit().putInt(KEY_BAL_LR, v.coerceIn(-15, 15)).apply()

    var balanceFrontRear: Int
        get() = sp.getInt(KEY_BAL_FR, 0)
        set(v) = sp.edit().putInt(KEY_BAL_FR, v.coerceIn(-15, 15)).apply()

    /** Полный сброс настроек оболочки (без удаления данных системных приложений). */
    fun resetAll(context: Context) {
        sp.edit().clear().apply()
    }

    companion object {
        private const val KEY_MEDIA_SOURCE = "media_source_kind"
        private const val KEY_LAST_MEDIA_PKG = "last_media_pkg"
        private const val KEY_LAST_MEDIA_LABEL = "last_media_label"
        private const val KEY_FAV_APPS = "fav_apps"
        private const val KEY_HIDDEN_APPS = "hidden_apps"
        private const val KEY_CLOCK_24H = "clock_24h"
        private const val KEY_TILE_SPACING = "tile_spacing_dp"
        private const val KEY_TILE_VERTICAL_SPACING = "tile_vertical_spacing_dp"
        private const val KEY_BAL_LR = "audio_balance_lr"
        private const val KEY_BAL_FR = "audio_balance_fr"
        private const val KEY_NAV = "nav_app"
        private const val KEY_MEDIA = "media_app"
        private const val KEY_RADIO = "radio_app"
        private const val KEY_PHONE = "phone_app"
        private const val KEY_VEHICLE = "vehicle_app"
        private const val KEY_APPCONNECT = "appconnect_app_package"
        private const val KEY_SOUND_APP = "sound_app_package"
        private const val KEY_HOME_HINT = "home_hint_dismissed"
        /** Car Scanner ELM OBD2 (поддерживает VW/Audi, в т.ч. Golf IV BCB через ELM327). */
        const val DEFAULT_VEHICLE_PKG = "com.mgmadv.carscanner.elm.free"
        const val DEFAULT_VEHICLE_PKG_PRO = "com.mgmadv.carscanner.elm.pro"
        private const val KEY_FAV = "fm_favorites"
        private const val KEY_LAST_FREQ = "fm_last_freq"

        @Volatile private var instance: Prefs? = null
        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context).also { instance = it }
            }
    }
}
