package io.github.aloualou56.nebularequiem.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScrollPhysicsTest {
    @Test fun velocityFollowsTheFinger() {
        val v = VelocityTracker1D()
        // 1200 dp/s upward, sampled at 240 Hz
        for (i in 0..24) v.add(i * 1000.0 / 240, 500f - 1200f * i / 240f)
        assertEquals(-1200f, v.velocity(100.0), 1f)
    }

    @Test fun samplesBatchedIntoOneFrameStillMeasureTheRealSpeed() {
        val v = VelocityTracker1D()
        // three samples per 120 Hz frame, each with its own time (what MotionEvent history gives)
        var t = 0.0; var y = 300f
        repeat(12) { t += 1000.0 / 360; y -= 2f; v.add(t, y) }
        assertEquals(-720f, v.velocity(t), 1f)
    }

    @Test fun aFingerThatRestedBeforeLiftingDoesNotFling() {
        val v = VelocityTracker1D()
        for (i in 0..10) v.add(i * 8.0, 400f - 15f * i)
        assertTrue(abs(v.velocity(80.0)) > 1000f)
        // Android's 40 ms: a lift 50 ms after the last move is a rest, not a flick
        assertEquals(0f, v.velocity(80.0 + 50.0), 0f)
        assertEquals(0f, v.velocity(80.0 + 120.0), 0f)
    }

    @Test fun anAcceleratingFlickMeasuresItsReleaseSpeed() {
        val v = VelocityTracker1D()
        // the finger speeds up steadily from rest to 3000 dp/s over 90 ms (samples every 7.5 ms)
        val a = 3000.0 / 0.09
        for (i in 0..12) { val t = i * 7.5; val s = t / 1000.0; v.add(t, (300.0 - 0.5 * a * s * s).toFloat()) }
        // a straight line through the window would report about half (the average speed)
        assertEquals(-3000f, v.velocity(90.0), 150f)
    }

    @Test fun aFingerThatStopsBeforeLiftingNeverFlingsBackwards() {
        val v = VelocityTracker1D()
        // fast downward, then three samples at the same spot (within the 40 ms rest window)
        for (i in 0..6) v.add(i * 8.0, 100f + 20f * i)
        for (i in 7..9) v.add(i * 8.0, 220f)
        val vel = v.velocity(72.0)
        assertTrue("a stopping finger reads as still or slow, never reversed ($vel)", vel >= 0f && vel < 400f)
    }

    @Test fun aRestClearsTheEarlierMovement() {
        val v = VelocityTracker1D()
        for (i in 0..10) v.add(i * 8.0, 400f - 15f * i)   // a fast drag…
        v.add(80.0 + 55.0, 249f)                          // …a 55 ms rest, then a 1 dp twitch as it lifts
        assertEquals(0f, v.velocity(80.0 + 56.0), 0f)
    }

    @Test fun twoSamplesFallBackToAStraightLine() {
        val v = VelocityTracker1D()
        v.add(0.0, 100f); v.add(10.0, 110f)
        assertEquals(1000f, v.velocity(10.0), 0.5f)
    }

    @Test fun unRubberBandUndoesTheRubberBand() {
        val max = 400f
        for (raw in listOf(-150f, -40f, -1f, 0f, 200f, 400f, 401f, 460f, 600f)) {
            val shown = ScrollPhysics.rubberBand(raw, max)
            assertEquals("raw $raw", raw, ScrollPhysics.unRubberBand(shown, max), 0.05f)
        }
    }

    @Test fun onlyTheLast100MsCount() {
        val v = VelocityTracker1D()
        for (i in 0..20) v.add(i * 10.0, 100f + 30f * i)          // fast at first (3000 dp/s)…
        for (i in 21..40) v.add(i * 10.0, 700f + 2f * (i - 20))    // …then slow (200 dp/s)
        assertEquals(200f, v.velocity(400.0), 5f)
    }

    @Test fun rubberBandResistsAndIsBounded() {
        val max = 500f
        assertEquals(250f, ScrollPhysics.rubberBand(250f, max), 0f)
        val a = ScrollPhysics.rubberBand(-20f, max); val b = ScrollPhysics.rubberBand(-200f, max); val c = ScrollPhysics.rubberBand(-5000f, max)
        assertTrue(a < 0f && a > -20f)
        assertTrue(b < a)
        assertTrue(c >= -ScrollPhysics.MAX_OVERSCROLL)
        assertTrue(ScrollPhysics.rubberBand(560f, max) in 500f..560f)
    }

    @Test fun overscrollSpringsBackToTheEnd() {
        val out = FloatArray(1)
        var p = -60f; var v = 0f
        var t = 0f
        while (t < 1f) { p = ScrollPhysics.step(p, v, 500f, 1f / 120, out); v = out[0]; t += 1f / 120; assertTrue("never overshoots into the content", p <= 0.01f) }
        assertEquals(0f, p, 0.01f); assertEquals(0f, v, 0.01f)
    }

    @Test fun aFlingDecaysAndStops() {
        val out = FloatArray(1)
        var p = 100f; var v = 2000f
        var t = 0f
        while (t < 4f) { p = ScrollPhysics.step(p, v, 5000f, 1f / 60, out); v = out[0]; t += 1f / 60 }
        assertEquals(0f, v, 0f)
        // a fling at v travels about v / DECAY
        assertEquals(100f + 2000f / ScrollPhysics.DECAY, p, 40f)
    }

    @Test fun aFlingIntoTheEndBouncesBackOffIt() {
        val out = FloatArray(1)
        var p = 480f; var v = 3000f
        var peak = 0f
        var t = 0f
        while (t < 2f) { p = ScrollPhysics.step(p, v, 500f, 1f / 120, out); v = out[0]; peak = maxOf(peak, p); t += 1f / 120 }
        assertTrue("goes a little past the end", peak > 505f && peak < 560f)
        assertEquals(500f, p, 0.01f)
    }

    @Test fun theBounceOffAnEndIsTheSameAtAnyFrameRate() {
        fun peak(hz: Int, phase: Float): Float {
            val out = FloatArray(1)
            var p = 470f - phase; var v = 3000f; var pk = 0f; var t = 0f
            while (t < 2f) { p = ScrollPhysics.step(p, v, 500f, 1f / hz, out); v = out[0]; pk = maxOf(pk, p); t += 1f / hz }
            return pk
        }
        val peaks = listOf(peak(60, 0f), peak(60, 13f), peak(120, 7f), peak(240, 3f), peak(144, 21f))
        assertTrue("bounce heights $peaks", peaks.max() - peaks.min() < 4f)
    }

    @Test fun aSlowFrameDoesNotThrowTheContentFarPastTheEnd() {
        val out = FloatArray(1)
        val p = ScrollPhysics.step(400f, 3000f, 500f, 0.25f, out)
        assertTrue("landed at $p", p in 500f..560f)
    }

    @Test fun aLongFrameDoesNotBreakTheSpring() {
        val out = FloatArray(1)
        val p = ScrollPhysics.step(-80f, -500f, 500f, 0.25f, out)
        assertTrue(p in -90f..0f)
    }
}
