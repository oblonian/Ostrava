package com.ostrava.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    @Test
    fun `heart rate zones cover 50 to 100 percent of max`() {
        val zones = heartRateZones(200)
        assertEquals(5, zones.size)
        assertEquals(100, zones.first().fromBpm)
        assertEquals(200, zones.last().toBpm)
        // Zones are contiguous
        zones.zipWithNext().forEach { (a, b) -> assertEquals(a.toBpm, b.fromBpm) }
    }

    @Test
    fun `calorie estimate scales with weight and duration`() {
        val light = estimateCalories(ActivityType.WALK, 1.4, 3_600_000, 60f)
        val heavy = estimateCalories(ActivityType.WALK, 1.4, 3_600_000, 90f)
        assertTrue(heavy > light)
        assertEquals(210, light) // 3.5 MET * 60 kg * 1 h
    }

    @Test
    fun `activity type falls back to run for unknown names`() {
        assertEquals(ActivityType.RUN, ActivityType.fromName("SWIM"))
        assertEquals(ActivityType.RIDE, ActivityType.fromName("RIDE"))
    }
}
