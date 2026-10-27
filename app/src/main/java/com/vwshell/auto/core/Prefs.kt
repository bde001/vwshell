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
        private const val KEY_LAST_MEDIA_PKG = "last_media_pkg"
        private const val KEY_LAST_MEDIA_LABEL = "last_media_label"
        private const val KEY_FAV_APPS = "fav_apps"
        private const val KEY_HIDDEN_APPS = "hidden_apps"
        private const val KEY_CLOCK_24H = "clock_24h"
        private const val KEY_BAL_LR = "audio_balance_lr"
        private const val KEY_BAL_FR = "audio_balance_fr"
        private const val KEY_NAV = "nav_app"
        private const val KEY_MEDIA = "media_app"
        private const val KEY_RADIO = "radio_app"
        private const val KEY_PHONE = "phone_app"
        private const val KEY_FAV = "fm_favorites"
        private const val KEY_LAST_FREQ = "fm_last_freq"

        @Volatile private var instance: Prefs? = null
        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context).also { instance = it }
            }
    }
}
