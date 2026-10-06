package io.github.aloualou56.nebularequiem.core

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/** Mulberry32 — tiny, fast, statistically solid 32-bit PRNG (bit-for-bit the original's). */
class Rng(seed: Int = (Math.random() * 4294967296.0).toLong().toInt()) {
    private var s: Int = seed

    fun seed(v: Int): Rng { s = v; return this }
    /** Raw generator state (determinism checks). */
    fun state(): Int = s

    fun next(): Double {
        s += 0x6d2b79f5
        var t = s
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0
    }

    fun range(a: Double, b: Double): Double = a + (b - a) * next()
    fun int(a: Int, b: Int): Int = a + floor(next() * (b - a + 1)).toInt()
    fun chance(p: Double): Boolean = next() < p
    fun sign(): Double = if (next() < 0.5) -1.0 else 1.0
    fun <T> pick(arr: List<T>): T = arr[floor(next() * arr.size).toInt()]
    fun pick(arr: IntArray): Int = arr[floor(next() * arr.size).toInt()]
    fun pick(arr: DoubleArray): Double = arr[floor(next() * arr.size).toInt()]
    fun angle(): Double = next() * TAU

    /** Box–Muller transform: two uniforms → one standard normal sample. */
    fun gauss(): Double {
        var u = 0.0
        while (u == 0.0) u = next()
        return sqrt(-2 * ln(u)) * cos(TAU * next())
    }

    inline fun <T> weighted(items: List<T>, weightOf: (T) -> Double): T {
        var total = 0.0
        for (i in items.indices) total += weightOf(items[i])
        var r = next() * total
        for (i in items.indices) {
            r -= weightOf(items[i])
            if (r <= 0) return items[i]
        }
        return items[items.size - 1]
    }

    fun <T> shuffle(arr: MutableList<T>): MutableList<T> {
        for (i in arr.size - 1 downTo 1) {
            val j = floor(next() * (i + 1)).toInt()
            val t = arr[i]; arr[i] = arr[j]; arr[j] = t
        }
        return arr
    }

    companion object {
        /** Gameplay randomness. */
        val game = Rng()
        /** Purely visual randomness (never perturbs gameplay sequences). */
        val vis = Rng()
        fun randomSeed(): Int = (Math.random() * 4294967296.0).toLong().toInt()
    }
}

/**
 * Value noise on an integer lattice with quintic (C²-continuous) interpolation:
 *   fade(t) = 6t⁵ − 15t⁴ + 10t³. fbm() sums octaves Σ gainᵏ·noise(p·lacunarityᵏ), normalised to [0,1].
 */
class ValueNoise(seed: Int = 1) {
    private val perm = IntArray(512)
    private val vals = FloatArray(256)

    init {
        val r = Rng(seed)
        val p = IntArray(256)
        for (i in 0 until 256) { p[i] = i; vals[i] = r.next().toFloat() }
        for (i in 255 downTo 1) {
            val j = floor(r.next() * (i + 1)).toInt()
            val t = p[i]; p[i] = p[j]; p[j] = t
        }
        for (i in 0 until 512) perm[i] = p[i and 255]
    }

    fun noise1(x: Double): Double {
        val xi = floor(x)
        val xf = x - xi
        val i = xi.toInt()
        val a = vals[perm[i and 255]].toDouble()
        val b = vals[perm[(i + 1) and 255]].toDouble()
        return lerp(a, b, fade(xf))
    }

    fun noise2(x: Double, y: Double): Double {
        val xi = floor(x); val yi = floor(y)
        val xf = x - xi; val yf = y - yi
        val bx = xi.toInt() and 255; val by = yi.toInt() and 255
        val aa = vals[perm[perm[bx] + by]].toDouble(); val ba = vals[perm[perm[bx + 1] + by]].toDouble()
        val ab = vals[perm[perm[bx] + by + 1]].toDouble(); val bb = vals[perm[perm[bx + 1] + by + 1]].toDouble()
        val u = fade(xf); val v = fade(yf)
        return lerp(lerp(aa, ba, u), lerp(ab, bb, u), v)
    }

    fun fbm2(x: Double, y: Double, octaves: Int = 5, lacunarity: Double = 2.02, gain: Double = 0.5): Double {
        var sum = 0.0; var amp = 0.5; var freq = 1.0; var norm = 0.0
        for (o in 0 until octaves) {
            sum += amp * noise2(x * freq, y * freq)
            norm += amp; amp *= gain; freq *= lacunarity
        }
        return sum / norm
    }

    companion object {
        fun fade(t: Double): Double = t * t * t * (t * (t * 6 - 15) + 10)
    }
}
