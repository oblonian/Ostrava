package com.ostrava.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class GpsSmootherTest {

    private val metersPerDegree = 111_320.0

    private fun pathLength(points: List<Pair<Double, Double>>): Double =
        points.zipWithNext().sumOf { (a, b) -> haversineMeters(a.first, a.second, b.first, b.second) }

    @Test
    fun `standing still with a jittery signal logs far less distance`() {
        val random = Random(42)
        val smoother = GpsSmoother()
        val raw = mutableListOf<Pair<Double, Double>>()
        val smoothed = mutableListOf<Pair<Double, Double>>()
        repeat(120) { second ->
            // +/- 8 m of noise around a fixed spot, reported as 10 m accuracy.
            val latitude = 50.0 + random.nextGaussian() * 8 / metersPerDegree
            val longitude = 14.0 + random.nextGaussian() * 8 / (metersPerDegree * 0.64)
            raw += latitude to longitude
            smoothed += smoother.update(latitude, longitude, 10f, second * 1000L, speedMps = 0f)
        }
        val rawLength = pathLength(raw)
        val smoothedLength = pathLength(smoothed)
        assertTrue("raw=$rawLength smoothed=$smoothedLength", smoothedLength < rawLength * 0.25)
    }

    @Test
    fun `a steady run reads much closer to its true distance`() {
        val random = Random(7)
        val smoother = GpsSmoother()
        val raw = mutableListOf<Pair<Double, Double>>()
        val smoothed = mutableListOf<Pair<Double, Double>>()
        val speed = 3.0
        repeat(600) { second ->
            // Worst case: 3 m of noise that is independent from one second to the next.
            val latitude = 50.0 + second * speed / metersPerDegree + random.nextGaussian() * 3 / metersPerDegree
            val longitude = 14.0 + random.nextGaussian() * 3 / (metersPerDegree * 0.64)
            raw += latitude to longitude
            smoothed += smoother.update(latitude, longitude, 5f, second * 1000L, speedMps = speed.toFloat())
        }
        val trueDistance = 599 * speed
        val rawError = pathLength(raw) - trueDistance
        val smoothedError = pathLength(smoothed) - trueDistance
        assertTrue("raw=$rawError smoothed=$smoothedError", smoothedError < rawError * 0.3)
        assertTrue("smoothed=$smoothedError", smoothedError < trueDistance * 0.25)
        // Smoothing must not lose real distance either.
        assertTrue("smoothed=$smoothedError", smoothedError > -trueDistance * 0.05)
    }

    @Test
    fun `reset starts again from the next fix`() {
        val smoother = GpsSmoother()
        smoother.update(50.0, 14.0, 5f, 0L, 3f)
        smoother.reset()
        val (latitude, longitude) = smoother.update(51.0, 15.0, 5f, 1000L, 3f)
        assertEquals(51.0, latitude, 1e-9)
        assertEquals(15.0, longitude, 1e-9)
    }

    @Test
    fun `time in heart rate zones adds up per zone`() {
        fun point(second: Int, heartRate: Int?) = TrackPoint(50.0, 14.0, 0.0, second * 1000L, 3f, 0, heartRate)
        // Max HR 200: 110 bpm is zone 1 (<60%), 150 is zone 3 (70-80%), 190 is zone 5 (>=90%).
        val points = listOf(point(0, 110), point(10, 110), point(20, 150), point(30, 190), point(40, 190))
        val zones = timeInHeartRateZones(points, maxHeartRate = 200)
        assertEquals(listOf(20_000L, 0L, 10_000L, 0L, 10_000L), zones)
        assertTrue(timeInHeartRateZones(listOf(point(0, null), point(10, null)), 200).isEmpty())
    }
}
