package com.vwshell.auto.core

/**
 * Чистая логика FM-диапазона (без Android-зависимостей — покрыта unit-тестами).
 *
 * Единица хранения: десятые доли МГц (880 = 87.5+0.5? НЕТ: 880 = 88.0 МГц).
 *   freqTenths = МГц * 10 → 875 = 87.5 МГц, 1080 = 108.0 МГц.
 * Шаг 0.1 МГц = 1 единица; шаг 1.0 МГц = 10 единиц.
 */
object FmBand {

    const val MIN = 875   // 87.5 МГц
    const val MAX = 1080  // 108.0 МГц
    const val STEP_FINE = 1     // 0.1 МГц
    const val STEP_COARSE = 10  // 1.0 МГц
    const val DEFAULT = 880     // 88.0 МГц — стартовая UI-настройка, не «реальная станция»

    fun clamp(freqTenths: Int): Int = freqTenths.coerceIn(MIN, MAX)

    /** Шаг с ограничением диапазоном. dir = ±1. */
    fun step(current: Int, dir: Int, stepTenths: Int = STEP_COARSE): Int =
        clamp(current + dir * stepTenths)

    /** Предыдущий/следующий пресет из реально сохранённых пользователем частот. */
    fun nextPreset(presets: List<Int>, current: Int, dir: Int): Int? {
        if (presets.isEmpty()) return null
        val sorted = presets.filter { it in MIN..MAX }.sorted()
        if (sorted.isEmpty()) return null
        return if (dir > 0) {
            sorted.firstOrNull { it > current } ?: sorted.first()
        } else {
            sorted.lastOrNull { it < current } ?: sorted.last()
        }
    }

    /** Формат 88.0 / 101.5 (десятичная точка, Locale-independent). */
    fun format(freqTenths: Int): String =
        String.format(java.util.Locale.US, "%.1f", freqTenths / 10.0)

    fun isValid(freqTenths: Int): Boolean = freqTenths in MIN..MAX
}
