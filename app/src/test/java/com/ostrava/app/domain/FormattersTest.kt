package com.ostrava.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `formats metric distance`() {
        assertEquals("5.00 km", formatDistance(5000.0, imperial = false))
        assertEquals("0.50 km", formatDistance(500.0, imperial = false))
    }

    @Test
    fun `formats imperial distance`() {
        assertEquals("1.00 mi", formatDistance(METERS_PER_MILE, imperial = true))
    }

    @Test
    fun `formats duration with and without hours`() {
        assertEquals("12:05", formatDuration(725_000))
        assertEquals("1:00:01", formatDuration(3_601_000))
    }

    @Test
    fun `formats pace from speed`() {
        // 10 km/h => 6:00 /km
        assertEquals("6:00 /km", formatPace(10_000.0 / 3600.0, imperial = false))
        // Standing still is a placeholder, not infinity
        assertEquals("--:--", formatPace(0.0, imperial = false))
    }

    @Test
    fun `formats speed`() {
        assertEquals("36.0 km/h", formatSpeed(10.0, imperial = false))
    }

    @Test
    fun `formats elevation`() {
        assertEquals("100 m", formatElevation(100.0, imperial = false))
        assertEquals("328 ft", formatElevation(100.0, imperial = true))
    }
}
