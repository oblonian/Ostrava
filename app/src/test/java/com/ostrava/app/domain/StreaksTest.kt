package com.ostrava.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StreaksTest {

    @Test
    fun `empty history has no streak`() {
        assertEquals(0 to 0, weeklyStreaks(emptySet(), 100L))
    }

    @Test
    fun `current streak counts consecutive weeks ending now`() {
        val (current, longest) = weeklyStreaks(setOf(98L, 99L, 100L), 100L)
        assertEquals(3, current)
        assertEquals(3, longest)
    }

    @Test
    fun `current streak tolerates an empty present week`() {
        val (current, _) = weeklyStreaks(setOf(97L, 98L, 99L), 100L)
        assertEquals(3, current)
    }

    @Test
    fun `a gap breaks the current streak but longest is remembered`() {
        val (current, longest) = weeklyStreaks(setOf(90L, 91L, 92L, 93L, 99L, 100L), 100L)
        assertEquals(2, current)
        assertEquals(4, longest)
    }

    @Test
    fun `stale history means zero current streak`() {
        val (current, longest) = weeklyStreaks(setOf(90L, 91L), 100L)
        assertEquals(0, current)
        assertEquals(2, longest)
    }
}

class IntervalConfigTest {

    @Test
    fun `phases include warmup work rest and cooldown`() {
        val config = IntervalConfig(warmupSec = 300, workSec = 120, restSec = 60, repeats = 3, cooldownSec = 300)
        val phases = config.phases()
        // warmup + (work,rest,work,rest,work) + cooldown; no rest after final work
        assertEquals(7, phases.size)
        assertEquals("Warm-up", phases.first().first)
        assertEquals("Cool-down", phases.last().first)
        assertEquals("Work 3/3", phases[5].first)
    }

    @Test
    fun `round trips through int array`() {
        val config = IntervalConfig(60, 90, 30, 4, 120)
        assertEquals(config, IntervalConfig.fromIntArray(config.toIntArray()))
    }

    @Test
    fun `rejects invalid arrays`() {
        assertEquals(null, IntervalConfig.fromIntArray(null))
        assertEquals(null, IntervalConfig.fromIntArray(intArrayOf(1, 2)))
        assertEquals(null, IntervalConfig.fromIntArray(intArrayOf(0, 0, 0, 0, 0)))
    }
}
