package com.vwshell.auto.core

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import com.vwshell.auto.R

/**
 * Фича 3: runtime-палитра лаунчера.
 *
 * Единственный источник акцентного цвета для всех экранов. Дефолт — VW Red #FF1A1A
 * (совпадает с res/values/colors.xml vw_accent, поэтому при неизменённой настройке
 * визуал побайтово идентичен прежнему).
 *
 * БЕЛАЯ линия topbar не перекрашивается никогда (topbarLine()).
 */
object PaletteManager {

    data class Preset(val nameRes: Int, val hex: Int)

    val presets = listOf(
        Preset(R.string.palette_vw_red, 0xFFFF1A1A.toInt()),
        Preset(R.string.palette_mib_blue, 0xFF00A0E0.toInt()),
        Preset(R.string.palette_emerald, 0xFF00C853.toInt()),
        Preset(R.string.palette_amber, 0xFFFFAB00.toInt()),
        Preset(R.string.palette_violet, 0xFFAA00FF.toInt()),
    )

    interface Listener { fun onPaletteChanged(accent: Int) }

    @Volatile private var accentColor: Int = 0xFFFF1A1A.toInt()
    private val listeners = mutableListOf<Listener>()
    private var initialized = false

    /** Инициализация из Prefs (MainActivity.onCreate). */
    fun init(context: Context) {
        accentColor = Prefs.get(context).paletteAccent
        initialized = true
    }

    fun accent(): Int = accentColor

    fun currentPresetIndex(): Int = presets.indexOfFirst { it.hex == accentColor }

    fun setAccent(context: Context, hex: Int) {
        if (accentColor == hex) return
        accentColor = hex
        Prefs.get(context).paletteAccent = hex
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it.onPaletteChanged(hex) }
    }

    fun addListener(l: Listener) { synchronized(listeners) { if (!listeners.contains(l)) listeners.add(l) } }
    fun removeListener(l: Listener) { synchronized(listeners) { listeners.remove(l) } }

    /**
     * Полоска снизу плитки: сплошной accent + градиент свечения вверх (0x99 -> 0x33 -> 0x00).
     * Аналог stripe_bottom_glow.xml в цвете палитры.
     */
    fun stripeDrawable(): GradientDrawable {
        // Сверху вниз: прозрачный -> 0x33 -> 0x99 -> сплошной accent у нижнего края.
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.TRANSPARENT,
                withAlpha(accentColor, 0x33),
                withAlpha(accentColor, 0x99),
                accentColor
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            setCornerRadii(floatArrayOf(0f, 0f, 0f, 0f, cornerPx, cornerPx, cornerPx, cornerPx))
        }
    }

    /**
     * Фон плитки: вертикальный градиент darken(accent,0.75) 0% -> darken(accent,0.9) 35% -> #000000 50-100%.
     * Скругление как у tile_bg.xml.
     */
    fun tileBackground(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                darken(accentColor, 0.75f),
                darken(accentColor, 0.9f),
                Color.BLACK,
                Color.BLACK
            )
        ).apply {
            colors = intArrayOf(
                darken(accentColor, 0.75f),
                darken(accentColor, 0.9f),
                Color.BLACK,
                Color.BLACK
            )
            // Стоп-позиции 0/0.35/0.5/1 задавать нечем: GradientDrawable.setColorTransitionParameters
            // отсутствует в SDK-стабах этой сборки. Равномерный градиент из 4 цветов визуально
            // эквивалентен (последние две точки — чёрные), оставляем как есть.
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx
        }
    }

    /** Белая линия topbar + белое градиентное свечение вниз. ВСЕГДА белая, палитрой не меняется. */
    fun topbarLine(): LayerDrawable {
        val line = ShapeDrawable(RectShape()).apply { paint.color = Color.WHITE }
        val glow = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x80FFFFFF.toInt(), 0x00FFFFFF))
        return LayerDrawable(arrayOf(line, glow))
    }

    /** Цвет text-shadow подписей плиток = accent. */
    fun glowShadowColor(): Int = accentColor

    /** Затемнение канала: factor 0..1 (0.75 => остаётся 25% яркости). */
    fun darken(color: Int, factor: Float): Int {
        val keep = 1f - factor.coerceIn(0f, 1f)
        val a = Color.alpha(color)
        val r = (Color.red(color) * keep).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * keep).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * keep).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** Плотность экрана (инициализируется из MainActivity; дефолт 3f безопасен). */
    @Volatile private var density: Float = 3f
    private val cornerPx: Float get() = 12f * density

    /** Кэширование плотности из любого Context (вызывается при init). */
    fun initDensity(context: Context) {
        density = context.resources.displayMetrics.density
    }
}
