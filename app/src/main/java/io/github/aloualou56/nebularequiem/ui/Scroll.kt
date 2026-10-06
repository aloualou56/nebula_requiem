package io.github.aloualou56.nebularequiem.ui

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * Finger velocity from timestamped samples, as Android's default VelocityTracker computes it: a
 * least-squares quadratic through the last [WINDOW_MS] of movement, whose slope at the newest
 * sample is the velocity (a flick speeds up until the finger lifts; a straight line would report
 * the average). A finger that rested for [REST_MS] before lifting has no velocity.
 */
class VelocityTracker1D {
    private val t = DoubleArray(SIZE)
    private val p = FloatArray(SIZE)
    private var n = 0
    private var head = 0

    fun reset() { n = 0; head = 0 }

    /** Add a sample: event time in ms, position in dp. */
    fun add(timeMs: Double, pos: Float) {
        if (n > 0) {
            val last = t[(head - 1 + SIZE) % SIZE]
            // a sample older than the newest makes the fit meaningless; one after a rest starts a new
            // movement (as Android's tracker forgets the pointer after 40 ms without movement)
            if (timeMs < last || timeMs - last > REST_MS) reset()
        }
        t[head] = timeMs; p[head] = pos
        head = (head + 1) % SIZE
        if (n < SIZE) n++
    }

    /** Velocity in dp/s at [nowMs] (positive = position increasing), 0 if the finger rested. */
    fun velocity(nowMs: Double): Float {
        if (n < 2) return 0f
        val newest = (head - 1 + SIZE) % SIZE
        val tN = t[newest]
        if (nowMs - tN > REST_MS) return 0f
        // sums for the normal equations, x in seconds relative to the newest sample (x ≤ 0)
        var k = 0
        var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var sy = 0.0; var sxy = 0.0; var sx2y = 0.0
        for (i in 0 until n) {
            val j = (newest - i + SIZE) % SIZE
            val dt = t[j] - tN
            if (-dt > WINDOW_MS) break
            val x = dt / 1000.0; val y = p[j].toDouble(); val x2 = x * x
            k++; s1 += x; s2 += x2; s3 += x2 * x; s4 += x2 * x2
            sy += y; sxy += x * y; sx2y += x2 * y
        }
        if (k < 2) return 0f
        // the straight line: the average speed over the window
        val den = k * s2 - s1 * s1
        if (den <= 1e-12) return 0f
        val line = (k * sxy - s1 * sy) / den
        if (k >= 3) {
            // y = a + b·x + c·x²: solve the 3 × 3 system by Cramer's rule; b is the slope at x = 0
            val det = k * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s3 * s2) + s2 * (s1 * s3 - s2 * s2)
            val scale = k * s2 * s4
            if (scale > 0 && abs(det) > 1e-9 * scale) {
                val curve = (k * (sxy * s4 - s3 * sx2y) - sy * (s1 * s4 - s3 * s2) + s2 * (s1 * sx2y - sxy * s2)) / det
                // a finger that slowed to a stop can bend the curve back past it: that is a stop,
                // never a flick the other way
                return if (curve * line > 0) curve.toFloat() else 0f
            }
        }
        // two samples, or samples too bunched for a curve: the straight line
        return line.toFloat()
    }

    companion object {
        private const val SIZE = 24
        const val WINDOW_MS = 100.0
        /** Android's ASSUME_POINTER_STOPPED_TIME: no movement for this long means the finger stopped. */
        const val REST_MS = 40.0
    }
}

/**
 * Scroll motion for a list: exponential fling decay, rubber-band overscroll while dragging past an
 * end, and a critically damped spring back to the end (which also turns a fling that runs into an
 * end into a short bounce).
 */
object ScrollPhysics {
    /** Fling decay rate (1/s): a fling at v dp/s travels about v / DECAY dp. */
    const val DECAY = 3.2f
    /** A fling ends below this speed (dp/s): the tail would only creep. */
    const val STOP_SPEED = 20f
    /** Spring stiffness (1/s) of the return from overscroll. */
    const val SPRING = 16f
    /** Furthest a drag can pull past an end, in dp. */
    const val MAX_OVERSCROLL = 90f
    /** Speed (dp/s) a fling keeps when it runs into an end: the size of the bounce. */
    const val EDGE_SPEED = 1200f
    const val MIN_FLING = 60f
    const val MAX_FLING = 4500f
    /** Distance (dp) a finger travels before a press becomes a drag (Android's touch slop is 8 dp). */
    const val SLOP = 8f

    /** Where a drag to raw offset [raw] puts the content: 1:1 inside [0, max], resisting past the ends. */
    fun rubberBand(raw: Float, max: Float): Float = when {
        raw < 0f -> -soft(-raw)
        raw > max -> max + soft(raw - max)
        else -> raw
    }

    /** The raw drag offset [rubberBand] shows at [shown], so a drag can pick up overscrolled content without a jump. */
    fun unRubberBand(shown: Float, max: Float): Float = when {
        shown < 0f -> -unSoft(-shown)
        shown > max -> max + unSoft(shown - max)
        else -> shown
    }

    private fun soft(d: Float): Float = MAX_OVERSCROLL * (1f - exp(-d * 0.5f / MAX_OVERSCROLL))
    // a bounce can go past the drag limit, so stay just inside the logarithm's domain
    private fun unSoft(s: Float): Float = -2f * MAX_OVERSCROLL * ln(1f - min(s, 0.98f * MAX_OVERSCROLL) / MAX_OVERSCROLL)

    /**
     * Advance [pos] (with velocity [vel], dp/s) by [dt] seconds inside [0, max]. Returns the new
     * position and writes the new velocity to [out] (out[0]).
     */
    fun step(pos: Float, vel: Float, max: Float, dt: Float, out: FloatArray): Float {
        if (dt <= 0f) { out[0] = vel; return pos }
        if (pos < 0f || pos > max) {
            val edge = if (pos < 0f) 0f else max
            return spring(pos - edge, vel, edge, dt, out)
        }
        if (abs(vel) < STOP_SPEED) { out[0] = 0f; return pos }
        val e = exp(-DECAY * dt)
        // exact exponential decay: x(t) = v (1 − e^(−kt)) / k
        val p = pos + vel * (1f - e) / DECAY
        if (p < 0f || p > max) {
            // reached an end partway through the frame: fling up to it, then bounce off it for the rest
            val edge = if (p < 0f) 0f else max
            val frac = ((edge - pos) * DECAY / vel).coerceIn(0f, 0.999999f)
            val tau = -ln(1f - frac) / DECAY
            val vEdge = (vel * exp(-DECAY * tau)).coerceIn(-EDGE_SPEED, EDGE_SPEED)
            return spring(0f, vEdge, edge, dt - tau, out)
        }
        val v = vel * e
        out[0] = if (abs(v) < STOP_SPEED) 0f else v
        return p
    }

    /** Critically damped spring towards [edge] from offset [x0]: x(t) = (x0 + (v0 + ωx0) t) e^(−ωt). */
    private fun spring(x0: Float, v0: Float, edge: Float, dt: Float, out: FloatArray): Float {
        val w = SPRING
        val e = exp(-w * dt)
        val c = v0 + w * x0
        var x = (x0 + c * dt) * e
        var v = (v0 - w * c * dt) * e
        if (abs(x) < 0.25f && abs(v) < 8f) { x = 0f; v = 0f }
        out[0] = v
        return edge + x
    }
}
