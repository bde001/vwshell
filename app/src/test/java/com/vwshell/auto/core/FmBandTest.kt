package com.vwshell.auto.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit-тесты чистой логики FM-диапазона (единицы — десятые доли МГц). */
class FmBandTest {

    @Test
    fun `fm boundaries are 875 to 1080 tenths`() {
        assertEquals(875, FmBand.MIN)
        assertEquals(1080, FmBand.MAX)
        assertTrue(FmBand.isValid(875))
        assertTrue(FmBand.isValid(1080))
        assertFalse(FmBand.isValid(874))
        assertFalse(FmBand.isValid(1081))
    }

    @Test
    fun `clamp keeps inside band`() {
        assertEquals(875, FmBand.clamp(100))
        assertEquals(1080, FmBand.clamp(9999))
        assertEquals(981, FmBand.clamp(981))
    }

    @Test
    fun `coarse step is 1 MHz = 10 units, fine step is 0_1 MHz = 1 unit`() {
        assertEquals(10, FmBand.STEP_COARSE)
        assertEquals(1, FmBand.STEP_FINE)
        assertEquals(890, FmBand.step(880, +1))
        assertEquals(875, FmBand.step(880, -1))
        assertEquals(1080, FmBand.step(1079, +1, FmBand.STEP_FINE))
        assertEquals(1080, FmBand.step(1080, +1, FmBand.STEP_FINE))
    }

    @Test
    fun `preset navigation wraps around real saved presets only`() {
        val presets = listOf(1015, 880, 981)
        assertEquals(981, FmBand.nextPreset(presets, 880, +1))
        assertEquals(1015, FmBand.nextPreset(presets, 981, +1))
        assertEquals(880, FmBand.nextPreset(presets, 1015, +1))
        assertEquals(1015, FmBand.nextPreset(presets, 880, -1))
        assertEquals(981, FmBand.nextPreset(presets, 1015, -1))
        assertNull(FmBand.nextPreset(emptyList(), 880, +1))
        assertNull(FmBand.nextPreset(listOf(2000), 880, +1))
    }

    @Test
    fun `format uses locale-independent decimal point`() {
        assertEquals("87.5", FmBand.format(875))
        assertEquals("88.0", FmBand.format(880))
        assertEquals("101.5", FmBand.format(1015))
        assertEquals("108.0", FmBand.format(1080))
    }
}
