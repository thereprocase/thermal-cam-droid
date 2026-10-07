package com.thereprocase.thermalfield

import org.junit.Assert.*
import org.junit.Test

class IsothermTest {
    @Test fun singleThresholdIgnoresUnusedField() {
        assertEquals(IsothermLimits(35f, 35f), parseIsotherm(3, "40", "35", false))
        assertEquals(IsothermLimits(-20f, -20f), parseIsotherm(2, "-20", "not a number", false))
        assertEquals(IsothermLimits(35f, 35f), parseIsotherm(3, "NaN", "35", false))
    }
    @Test fun bandRequiresOrderedFiniteLimits() {
        assertNull(parseIsotherm(1, "40", "35", false))
        assertNull(parseIsotherm(1, "NaN", "35", false))
        assertNull(parseIsotherm(2, "Infinity", "20", false))
        assertNull(parseIsotherm(3, "20", "invalid", false))
        assertEquals(IsothermLimits(20f, 20f), parseIsotherm(1, "20", "20", false))
    }
    @Test fun thresholdConversionAndCaptionsUseActiveLimit() {
        assertEquals(0f, parseIsotherm(2, "32", "invalid", true)!!.lower, 0.0001f)
        assertEquals(55f, parseIsotherm(3, "invalid", "131", true)!!.upper, 0.0001f)
        assertEquals("below 20.0 °C", isothermDescription(2, 20.0, 30.0, false))
        assertEquals("above 86.0 °F", isothermDescription(3, 20.0, 30.0, true))
        assertEquals("band 20.0 °C–30.0 °C", isothermDescription(1, 20.0, 30.0, false))
    }
}
