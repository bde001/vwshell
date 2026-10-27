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

    /** Пользовательские сохранённые частоты как «пресеты» списка станций. */
    fun presets(): List<Int> = favorites().mapNotNull { it.toIntOrNull() }.sortedDescending()

    fun rememberPreset(freqKhz: Int) {
        val set = HashSet(sp.getStringSet(KEY_PRESETS, emptySet()) ?: emptySet())
        set.add(freqKhz.toString())
        sp.edit().putStringSet(KEY_PRESETS, set).apply()
    }

    fun lastFreqKhz(): Int? = sp.getInt(KEY_LAST_FREQ, -1).let { if (it < 0) null else it }
    fun setLastFreq(freqKhz: Int) = sp.edit().putInt(KEY_LAST_FREQ, freqKhz).apply()

    companion object {
        private const val KEY_NAV = "nav_app"
        private const val KEY_MEDIA = "media_app"
        private const val KEY_RADIO = "radio_app"
        private const val KEY_PHONE = "phone_app"
        private const val KEY_FAV = "fm_favorites"
        private const val KEY_PRESETS = "fm_presets"
        private const val KEY_LAST_FREQ = "fm_last_freq"

        @Volatile private var instance: Prefs? = null
        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context).also { instance = it }
            }
    }
}
