package io.github.aloualou56.nebularequiem.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** §11 COLLISION — primitives, a spatial hash broadphase and the collision matrix (see Game.collide). */
object Collide {
    /** Circle–circle: |a − b|² ≤ (rₐ + r_b)². */
    fun circle(ax: Double, ay: Double, ar: Double, bx: Double, by: Double, br: Double): Boolean {
        val dx = bx - ax; val dy = by - ay; val r = ar + br
        return dx * dx + dy * dy <= r * r
    }

    /** Squared distance from P to segment AB (projection clamped to [0,1]). */
    fun pointSeg2(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val abx = bx - ax; val aby = by - ay; val l2 = abx * abx + aby * aby
        var t = if (l2 > 0) ((px - ax) * abx + (py - ay) * aby) / l2 else 0.0
        t = if (t < 0) 0.0 else if (t > 1) 1.0 else t
        val cx = ax + abx * t - px; val cy = ay + aby * t - py
        return cx * cx + cy * cy
    }

    /** Swept circle test: a circle travelling A→B this step touches circle C. Prevents tunnelling. */
    fun swept(ax: Double, ay: Double, bx: Double, by: Double, r: Double, cx: Double, cy: Double, cr: Double): Boolean {
        val R = r + cr
        return pointSeg2(cx, cy, ax, ay, bx, by) <= R * R
    }

    /** Ray–circle intersection with unit direction d; nearest t ≥ 0, or −1. */
    fun ray(ox: Double, oy: Double, dx: Double, dy: Double, cx: Double, cy: Double, r: Double): Double {
        val fx = ox - cx; val fy = oy - cy; val b = fx * dx + fy * dy; val c = fx * fx + fy * fy - r * r
        val disc = b * b - c
        if (disc < 0) return -1.0
        val s = sqrt(disc)
        var t = -b - s
        if (t < 0) t = -b + s
        return if (t >= 0) t else -1.0
    }

    /** Is direction `angle` within ±halfSpan of `center`? (arc shields) */
    fun inArc(angle: Double, center: Double, halfSpan: Double): Boolean = abs(angleDiff(center, angle)) <= halfSpan
}

/**
 * Uniform-grid spatial hash over the (bounded) arena. Objects are inserted into every cell their
 * bounding box overlaps; out-of-arena coordinates clamp into the border cells, so queries stay
 * correct. Queries de-duplicate with a per-query stamp. No allocation after warm-up.
 */
class SpatialHash(private val cell: Double = 96.0) {
    private val inv = 1 / cell
    private var cols = 0; private var rows = 0
    private val margin = 2
    private var buckets: Array<ArrayList<Enemy>> = emptyArray()
    private val used = ArrayList<ArrayList<Enemy>>(256)
    private var stamp = 0

    private fun ensure() {
        val c = ceil(World.w * inv).toInt() + margin * 2 + 1
        val r = ceil(World.h * inv).toInt() + margin * 2 + 1
        if (c != cols || r != rows) {
            cols = c; rows = r
            buckets = Array(cols * rows) { ArrayList<Enemy>(8) }
            used.clear()
        }
    }

    private fun cx(x: Double) = clampI(floor(x * inv).toInt() + margin, 0, cols - 1)
    private fun cy(y: Double) = clampI(floor(y * inv).toInt() + margin, 0, rows - 1)

    fun clear() {
        ensure()
        for (i in used.indices) used[i].clear()
        used.clear()
    }

    fun insert(o: Enemy, x: Double, y: Double, r: Double) {
        val x0 = cx(x - r); val x1 = cx(x + r); val y0 = cy(y - r); val y1 = cy(y + r)
        for (j in y0..y1) for (i in x0..x1) {
            val b = buckets[j * cols + i]
            if (b.isEmpty()) used.add(b)
            b.add(o)
        }
    }

    fun query(x: Double, y: Double, r: Double, out: ArrayList<Enemy>): ArrayList<Enemy> {
        out.clear()
        if (cols == 0) return out
        val s = ++stamp
        val x0 = cx(x - r); val x1 = cx(x + r); val y0 = cy(y - r); val y1 = cy(y + r)
        for (j in y0..y1) for (i in x0..x1) {
            val b = buckets[j * cols + i]
            for (k in b.indices) { val o = b[k]; if (o.qs != s) { o.qs = s; out.add(o) } }
        }
        return out
    }
}
