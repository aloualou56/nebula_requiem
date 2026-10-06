package io.github.aloualou56.nebularequiem.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * §1 MATH LIBRARY — allocation-free scalar helpers. Angles are radians, +x is right, +y is DOWN
 * (screen convention), so a positive angle rotates clockwise on screen.
 */

const val TAU = PI * 2
const val HALF_PI = PI / 2
val PHI = (1 + sqrt(5.0)) / 2                       // golden ratio φ ≈ 1.618
val GOLDEN_ANGLE = TAU * (1 - 1 / PHI)              // 2π(1 − 1/φ) ≈ 137.508°
const val DEG = PI / 180

fun clamp(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else if (v > hi) hi else v
fun clampF(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v
fun clampI(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v
fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t
fun smoothstep(e0: Double, e1: Double, x: Double): Double {
    val t = clamp((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)
}

/** Frame-rate independent exponential smoothing: x → target with time constant 1/λ.
 *  dx/dt = λ(target − x)  ⇒  x(t+dt) = target + (x − target)·e^(−λ·dt). */
fun damp(a: Double, b: Double, lambda: Double, dt: Double): Double = lerp(a, b, 1 - exp(-lambda * dt))

fun wrapAngle(a0: Double): Double {
    var a = (a0 + PI) % TAU
    if (a < 0) a += TAU
    return a - PI
}

fun angleDiff(from: Double, to: Double): Double = wrapAngle(to - from)
fun dampAngle(a: Double, b: Double, lambda: Double, dt: Double): Double = a + angleDiff(a, b) * (1 - exp(-lambda * dt))
fun lerpAngle(a: Double, b: Double, t: Double): Double = a + angleDiff(a, b) * t
fun dist2(ax: Double, ay: Double, bx: Double, by: Double): Double { val dx = bx - ax; val dy = by - ay; return dx * dx + dy * dy }
fun mod(a: Double, n: Double): Double = ((a % n) + n) % n
fun modI(a: Int, n: Int): Int = ((a % n) + n) % n
fun hypot2(x: Double, y: Double): Double = sqrt(x * x + y * y)

/**
 * Count how many times a periodic event with period `interval` (phase `offset`) fires in the
 * half-open window (t − dt, t]. Floor differences instead of accumulators make attack scripts
 * deterministic regardless of step size: ⌊(t−o)/I⌋ − ⌊(t−dt−o)/I⌋.
 */
fun every(t: Double, dt: Double, interval: Double, offset: Double = 0.0): Int =
    (floor((t - offset) / interval) - floor((t - dt - offset) / interval)).toInt()

/** True only during the step in which time t first crosses `mark`. */
fun crossed(t: Double, dt: Double, mark: Double): Boolean = t >= mark && t - dt < mark

/**
 * Re-arm a periodic countdown that just crossed zero, carrying the overshoot (clamped to one
 * step) so the true period holds at any step size.
 */
fun rearm(timer: Double, period: Double, dt: Double): Double = period + max(timer, -dt)

/** Continuous emitters are tuned as "chance p per 1/120 s"; evaluate as a rate over the step. */
fun rateChance(p: Double, dt: Double): Boolean = Rng.vis.next() < min(1.0, p * dt * 120)

object Ease {
    fun linear(t: Double) = t
    fun inQuad(t: Double) = t * t
    fun outQuad(t: Double) = t * (2 - t)
    fun inCubic(t: Double) = t * t * t
    fun outCubic(t: Double): Double { val u = 1 - t; return 1 - u * u * u }
    fun inOutCubic(t: Double) = if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2
    fun outQuart(t: Double): Double { val u = 1 - t; return 1 - u * u * u * u }
    fun outExpo(t: Double) = if (t >= 1) 1.0 else 1 - 2.0.pow(-10 * t)
    fun inExpo(t: Double) = if (t <= 0) 0.0 else 2.0.pow(10 * t - 10)
    /** overshoot curve: c₁ = 1.70158 gives ~10% overshoot */
    fun outBack(t: Double): Double { val c1 = 1.70158; val c3 = c1 + 1; return 1 + c3 * (t - 1).pow(3) + c1 * (t - 1).pow(2) }
    fun outElastic(t: Double): Double {
        if (t <= 0) return 0.0
        if (t >= 1) return 1.0
        val c4 = TAU / 3
        return 2.0.pow(-10 * t) * sin((t * 10 - 0.75) * c4) + 1
    }
}

/**
 * CSS cubic-bezier(x1, y1, x2, y2) timing function, solved for y(x) with Newton–Raphson and a
 * bisection fallback (the same approach browsers use). Used for the UI's motion curves.
 */
class CubicBezier(private val x1: Double, private val y1: Double, private val x2: Double, private val y2: Double) {
    private fun sx(t: Double): Double { val u = 1 - t; return 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t }
    private fun sy(t: Double): Double { val u = 1 - t; return 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t }
    private fun dx(t: Double): Double { val u = 1 - t; return 3 * u * u * x1 + 6 * u * t * (x2 - x1) + 3 * t * t * (1 - x2) }
    operator fun invoke(x: Double): Double {
        if (x <= 0) return 0.0
        if (x >= 1) return 1.0
        var t = x
        for (i in 0 until 8) {
            val e = sx(t) - x
            if (abs(e) < 1e-6) return sy(t)
            val d = dx(t)
            if (abs(d) < 1e-6) break
            t -= e / d
        }
        var lo = 0.0; var hi = 1.0; t = x
        for (i in 0 until 30) {
            val v = sx(t)
            if (abs(v - x) < 1e-6) break
            if (v < x) lo = t else hi = t
            t = (lo + hi) / 2
        }
        return sy(t)
    }

    companion object {
        val WARP = CubicBezier(.77, 0.0, .18, 1.0)
        val OUT_EXPO = CubicBezier(.16, 1.0, .3, 1.0)
        val SNAP = CubicBezier(.34, 1.56, .64, 1.0)
        val EASE_OUT = CubicBezier(0.0, 0.0, .58, 1.0)
        val EASE_IN_OUT = CubicBezier(.42, 0.0, .58, 1.0)
    }
}

/**
 * Lead targeting: the direction a projectile of speed s must take from (sx,sy) to meet a target
 * at P with constant velocity V. Solve |P + V·t − S| = s·t for the smallest t > 0; falls back to
 * direct aim when no positive real root exists.
 */
fun interceptAngle(sx: Double, sy: Double, px: Double, py: Double, vx: Double, vy: Double, s: Double): Double {
    val dx = px - sx; val dy = py - sy
    val a = vx * vx + vy * vy - s * s
    val b = 2 * (vx * dx + vy * dy)
    val c = dx * dx + dy * dy
    var t = -1.0
    if (abs(a) < 1e-6) {
        if (abs(b) > 1e-6) t = -c / b
    } else {
        val disc = b * b - 4 * a * c
        if (disc >= 0) {
            val sq = sqrt(disc)
            val t1 = (-b - sq) / (2 * a); val t2 = (-b + sq) / (2 * a)
            t = if (min(t1, t2) > 0) min(t1, t2) else max(t1, t2)
        }
    }
    if (t > 0 && t < 4) return atan2(dy + vy * t, dx + vx * t)
    return atan2(dy, dx)
}

/** Integer with thousands separators (en-US), allocation per call: UI only. */
fun fmtInt(n: Double): String {
    val v = floor(n).toLong()
    val neg = v < 0
    val s = abs(v).toString()
    val sb = StringBuilder(s.length + s.length / 3 + 1)
    if (neg) sb.append('-')
    for (i in s.indices) {
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
        sb.append(s[i])
    }
    return sb.toString()
}
fun fmtInt(n: Int): String = fmtInt(n.toDouble())

fun fmtTime(s0: Double): String {
    val s = max(0, floor(s0).toInt())
    val m = s / 60
    val r = s % 60
    return "$m:${if (r < 10) "0" else ""}$r"
}

fun fmtFixed(v: Double, digits: Int): String {
    val p = 10.0.pow(digits)
    val r = Math.round(v * p)
    val neg = r < 0
    val a = abs(r)
    val ip = a / p.toLong()
    val fp = a % p.toLong()
    if (digits == 0) return (if (neg) "-" else "") + ip
    val fs = fp.toString().padStart(digits, '0')
    return (if (neg) "-" else "") + ip + "." + fs
}
