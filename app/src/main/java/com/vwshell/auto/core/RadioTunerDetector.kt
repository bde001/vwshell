package com.vwshell.auto.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils

/**
 * Фича 2: автоопределение FM/DAB-тюнера.
 *
 * Честность: реальный приём эфира НЕ эмулируется ни в одном режиме.
 * Детектор определяет лишь СПОСОБНОСТЬ системы: наличие нативного тюнера,
 * внешнего приложения радио, либо ничего (STUB — демонстрационный режим).
 */
object RadioTunerDetector {

    enum class TunerKind { NATIVE_TUNER, EXTERNAL_APP, STUB }

    data class Result(val kind: TunerKind, val packageName: String?, val label: String?)

    /** Системные пакеты FM-приложений, распространённые на китайских HU/SoC. */
    private val NATIVE_FM_PACKAGES = listOf(
        "com.android.fmradio",
        "com.mediatek.fmradio",
        "com.broadcom.fm",
        "com.samsung.fmradio",
        "com.quicinc.fm",
        "fm.radio",
    )

    private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

    /**
     * Определение по порядку:
     *  а) hasSystemFeature("android.hardware.fmaradio") ИЛИ системный FM-пакет -> NATIVE_TUNER;
     *  б) launchable-приложение с именем, содержащим radio/fm/tuner (кроме VWShell) -> EXTERNAL_APP;
     *  в) иначе STUB.
     */
    fun detect(context: Context): Result {
        val pm = context.packageManager
        val own = context.packageName

        // (а) нативный тюнер
        val hasFeature = runCatching {
            pm.hasSystemFeature("android.hardware.fmaradio")
        }.getOrDefault(false)
        val nativePkg = NATIVE_FM_PACKAGES.firstOrNull { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        }
        if (hasFeature || nativePkg != null) {
            val pkg = nativePkg ?: findFmServicePackage(context)
            return Result(TunerKind.NATIVE_TUNER, pkg, pkg)
        }

        // (б) внешнее приложение радио
        val candidate = AppLauncher.allLaunchableApps(context).firstOrNull { li ->
            li.packageName != own &&
                listOf("radio", "fm", "tuner").any { kw ->
                    li.label.contains(kw, ignoreCase = true) ||
                        li.packageName.contains(kw, ignoreCase = true)
                }
        }
        if (candidate != null) {
            return Result(TunerKind.EXTERNAL_APP, candidate.packageName, candidate.label)
        }

        // (в) заглушка
        return Result(TunerKind.STUB, null, null)
    }

    /** Пакет активного радио из AudioManager.getActiveRadios (hidden API — только в runCatching, без падений). */
    private fun findFmServicePackage(context: Context): String? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return null
        return runCatching {
            val m = am.javaClass.getMethod("getActiveRadios")
            @Suppress("UNCHECKED_CAST")
            val radios = m.invoke(am) as? List<Any>
            radios?.firstNotNullOfOrNull { r ->
                runCatching {
                    r.javaClass.getMethod("getId").invoke(r)?.toString()?.split(":")?.firstOrNull()
                }.getOrNull()
            }
        }.getOrNull()
    }

    /** Кешированный результат: не чаще раза в сутки (или после resetCache — «пересканировать»). */
    fun detectCached(context: Context, force: Boolean = false): Result {
        val prefs = Prefs.get(context)
        val cached = prefs.tunerCache()
        if (!force && cached != null && System.currentTimeMillis() - cached.second < CACHE_TTL_MS) {
            return Result(runCatching { TunerKind.valueOf(cached.first.first) }.getOrDefault(TunerKind.STUB),
                cached.first.second, cached.first.third)
        }
        val res = detect(context)
        prefs.setTunerCache(res)
        return res
    }

    fun resetCache(context: Context) = Prefs.get(context).clearTunerCache()

    /** Открытие внешнего приложения радио (для режима EXTERNAL_APP). */
    fun openExternalApp(context: Context, result: Result): Boolean {
        val pkg = result.packageName ?: return false
        return AppLauncher.launchPackage(context, pkg)
    }

    /** Проверка разрешения ACCESS_MEDIA_AUDIO-style broadcast для BT-гарнитур (используется SettingsFragment). */
    fun canOpenExternal(context: Context, result: Result): Boolean =
        result.kind == TunerKind.EXTERNAL_APP && result.packageName != null
}
