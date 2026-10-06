package io.github.aloualou56.nebularequiem.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * §6 PARTICLES — pooled, allocation-free during play. One "fat" particle class covers every kind
 * (the original's Spark, Ember, Smoke, Shard, Debris, Ring, Flash, FloatText, Bolt, Mote and
 * Afterimage subclasses), so a single pool serves them all.
 *
 * Integration is semi-implicit Euler with exact exponential drag: v ← (v + a·dt)·e^(−k·dt), p ← p + v·dt.
 */

enum class PKind { SPARK, EMBER, SMOKE, SHARD, DEBRIS, RING, FLASH, TEXT, BOLT, MOTE, AFTERIMAGE }

/** Blend layers: ambient & add are additive, under is normal-blend smoke, top is text. */
enum class PLayer { AMBIENT, UNDER, ADD, TOP }

class Particle {
    @JvmField var kind = PKind.SPARK
    @JvmField var alive = false
    @JvmField var age = 0.0
    @JvmField var life = 1.0
    @JvmField var x = 0.0; @JvmField var y = 0.0
    /** Position at the start of the latest step (render interpolation). */
    @JvmField var px = 0.0; @JvmField var py = 0.0
    @JvmField var vx = 0.0; @JvmField var vy = 0.0
    @JvmField var ax = 0.0; @JvmField var ay = 0.0
    @JvmField var drag = 0.0
    @JvmField var color = Pal.WHITE
    @JvmField var width = 2.0
    @JvmField var stretch = 0.035
    @JvmField var size = 1.0
    @JvmField var hot = false
    @JvmField var s0 = 0.0; @JvmField var s1 = 0.0; @JvmField var alpha = 1.0
    @JvmField var rot = 0.0; @JvmField var spin = 0.0
    @JvmField var r0 = 0.0; @JvmField var r1 = 0.0
    @JvmField var halfLen = 0.0; @JvmField var ang = 0.0
    @JvmField var ph = 0.0; @JvmField var depth = 1.0
    @JvmField var bank = 0.0
    @JvmField var hull: HullDef? = null
    @JvmField var text: String = ""
    @JvmField var seed = 0.0
    /** Shard triangle (local, about its centroid). */
    @JvmField var tri = FloatArray(6)
    /** Bolt polyline (33 points). */
    @JvmField var pts: FloatArray? = null
    @JvmField var n = 0

    val t: Double get() = age / life

    fun base(x: Double, y: Double, vx: Double, vy: Double, life: Double, drag: Double) {
        this.x = x; this.y = y; this.px = x; this.py = y; this.vx = vx; this.vy = vy
        this.life = life; this.drag = drag; this.ax = 0.0; this.ay = 0.0; this.age = 0.0
    }

    fun step(dt: Double) {
        px = x; py = y
        val k = if (drag != 0.0) exp(-drag * dt) else 1.0
        vx = (vx + ax * dt) * k
        vy = (vy + ay * dt) * k
        x += vx * dt; y += vy * dt
        age += dt
        if (age >= life) alive = false
    }

    fun update(dt: Double) {
        when (kind) {
            PKind.SMOKE -> { step(dt); rot += spin * dt }
            PKind.SHARD -> { step(dt); rot += spin * dt; spin *= exp(-0.8 * dt) }
            PKind.DEBRIS -> { step(dt); ang += spin * dt }
            PKind.MOTE -> {
                x += vx * dt * depth; y += vy * dt * depth; ph += dt * 1.7
                if (y < -20) y += World.h + 40; if (y > World.h + 20) y -= World.h + 40
                if (x < -20) x += World.w + 40; if (x > World.w + 20) x -= World.w + 40
                px = x; py = y
            }
            else -> step(dt)
        }
    }
}

object Fx {
    val layers = Array(PLayer.entries.size) { ArrayList<Particle>(1024) }
    private val pool = ArrayDeque<Particle>(4096)
    var count = 0
        private set
    var cap = Quality.HIGH.maxParticles
    var mul = 1.0

    fun configure(q: Quality) { cap = q.maxParticles; mul = q.particleMul }

    private fun layerOf(k: PKind): PLayer = when (k) {
        PKind.MOTE -> PLayer.AMBIENT
        PKind.SMOKE -> PLayer.UNDER
        PKind.TEXT -> PLayer.TOP
        else -> PLayer.ADD
    }

    /** Acquire a pooled particle; returns null when the global cap is reached (callers skip). */
    fun get(kind: PKind): Particle? {
        val layer = layerOf(kind)
        if (count >= cap && layer != PLayer.TOP) return null
        val p = pool.removeLastOrNull() ?: Particle()
        p.kind = kind; p.alive = true
        layers[layer.ordinal].add(p)
        count++
        return p
    }

    /** Scale an emission count by the quality multiplier, keeping at least `min`. */
    fun n(count: Double, min: Int = 1): Int = max(min, (count * mul).roundToInt())

    fun update(dt: Double) {
        for (list in layers) {
            var i = list.size - 1
            while (i >= 0) {
                val p = list[i]
                p.update(dt)
                if (!p.alive) {
                    list[i] = list[list.size - 1]; list.removeAt(list.size - 1)
                    pool.addLast(p)
                    count--
                }
                i--
            }
        }
    }

    fun clear(keepAmbient: Boolean = true) {
        for (l in PLayer.entries) {
            if (keepAmbient && l == PLayer.AMBIENT) continue
            val list = layers[l.ordinal]
            for (p in list) { p.alive = false; pool.addLast(p); count-- }
            list.clear()
        }
    }

    fun layer(l: PLayer): ArrayList<Particle> = layers[l.ordinal]

    /* ───────── primitive emitters ───────── */

    fun spark(x: Double, y: Double, angle: Double, speed: Double, life: Double, color: Int, width: Double = 2.0, stretch: Double = 0.035, drag: Double = 4.0) {
        val p = get(PKind.SPARK) ?: return
        p.base(x, y, cos(angle) * speed, sin(angle) * speed, life, drag)
        p.color = color; p.width = width; p.stretch = stretch
    }

    fun ember(x: Double, y: Double, vx: Double, vy: Double, life: Double, color: Int, size: Double, drag: Double = 2.5, hot: Boolean = false) {
        val p = get(PKind.EMBER) ?: return
        p.base(x, y, vx, vy, life, drag)
        p.color = color; p.size = size; p.hot = hot
    }

    fun smokePuff(x: Double, y: Double, vx: Double, vy: Double, life: Double, color: Int, s0: Double, s1: Double, alpha: Double = 0.8) {
        val p = get(PKind.SMOKE) ?: return
        p.base(x, y, vx, vy, life, 1.6)
        p.color = color; p.s0 = s0; p.s1 = s1; p.alpha = alpha
        p.rot = Rng.vis.angle(); p.spin = Rng.vis.range(-1.0, 1.0)
    }

    fun ring(x: Double, y: Double, r0: Double, r1: Double, life: Double, color: Int, width: Double = 4.0) {
        val p = get(PKind.RING) ?: return
        p.base(x, y, 0.0, 0.0, life, 0.0)
        p.r0 = r0; p.r1 = r1; p.color = color; p.width = width
    }

    fun flash(x: Double, y: Double, size: Double, life: Double, color: Int) {
        val p = get(PKind.FLASH) ?: return
        p.base(x, y, 0.0, 0.0, life, 0.0)
        p.size = size; p.color = color
    }

    fun text(x: Double, y: Double, str: String, color: Int, size: Double = 16.0, life: Double = 0.8, vy: Double = -60.0) {
        val p = get(PKind.TEXT) ?: return
        p.base(x, y, Rng.vis.range(-20.0, 20.0), vy, life, 2.2)
        p.text = str; p.color = color; p.size = size
    }

    /** Lightning bolt: midpoint displacement — offsets halve every subdivision level. */
    fun bolt(x1: Double, y1: Double, x2: Double, y2: Double, color: Int, life: Double = 0.2, jag: Double = 0.22) {
        val p = get(PKind.BOLT) ?: return
        p.base(x1, y1, 0.0, 0.0, life, 0.0)
        p.color = color; p.seed = Rng.vis.next() * 1000
        val segs = 32
        val pts = p.pts ?: FloatArray(66).also { p.pts = it }
        for (i in 0..segs) { pts[i * 2] = lerp(x1, x2, i.toDouble() / segs).toFloat(); pts[i * 2 + 1] = lerp(y1, y2, i.toDouble() / segs).toFloat() }
        val len = sqrt(dist2(x1, y1, x2, y2)); val ll = if (len == 0.0) 1.0 else len
        val nx = -(y2 - y1) / ll; val ny = (x2 - x1) / ll
        var amp = len * jag
        var step = segs / 2
        while (step >= 1) {
            var i = step
            while (i < segs) {
                val off = Rng.vis.range(-amp, amp)
                pts[i * 2] += (nx * off).toFloat(); pts[i * 2 + 1] += (ny * off).toFloat()
                for (j in i - step + 1 until i + step) {
                    if (j == i) continue
                    val w = 1 - kotlin.math.abs(j - i).toDouble() / step
                    pts[j * 2] += (nx * off * w).toFloat(); pts[j * 2 + 1] += (ny * off * w).toFloat()
                }
                i += step * 2
            }
            amp *= 0.5
            step /= 2
        }
        p.n = segs + 1
    }

    fun mote(x: Double, y: Double, color: Int) {
        val p = get(PKind.MOTE) ?: return
        p.base(x, y, Rng.vis.range(-8.0, 8.0), Rng.vis.range(-14.0, -2.0), 1e9, 0.0)
        p.color = color; p.size = Rng.vis.range(2.0, 6.0); p.ph = Rng.vis.angle(); p.depth = Rng.vis.range(0.3, 1.0)
    }

    /* ───────── §6 FX recipes: each touches particles, light, lattice and camera ───────── */

    fun sparks(x: Double, y: Double, count: Double, color: Int, speed: Double, spread: Double = TAU, dir: Double = 0.0, life: Double = 0.45, width: Double = 2.0) {
        val n = n(count)
        for (i in 0 until n) {
            val a = dir + (Rng.vis.next() - 0.5) * spread
            spark(x, y, a, speed * Rng.vis.range(0.35, 1.0), life * Rng.vis.range(0.6, 1.2), color, width)
        }
    }

    fun embers(x: Double, y: Double, count: Double, color: Int, speed: Double, life: Double = 0.8, size: Double = 6.0) {
        val n = n(count)
        for (i in 0 until n) {
            val a = Rng.vis.angle(); val s = speed * sqrt(Rng.vis.next())
            ember(x, y, cos(a) * s, sin(a) * s, life * Rng.vis.range(0.5, 1.2), color, size * Rng.vis.range(0.6, 1.3), 2.4, Rng.vis.chance(0.3))
        }
    }

    fun smoke(x: Double, y: Double, count: Double, color: Int, size: Double = 26.0, life: Double = 1.2) {
        val n = n(count)
        for (i in 0 until n) {
            val a = Rng.vis.angle(); val s = Rng.vis.range(10.0, 70.0)
            smokePuff(x, y, cos(a) * s, sin(a) * s, life * Rng.vis.range(0.7, 1.3), color, size * 0.4, size * Rng.vis.range(1.0, 1.8), 0.7)
        }
    }

    /** Full explosion: flash core → shockwave → sparks → embers → smoke, plus light & spacetime ripple. */
    fun explosion(x: Double, y: Double, color: Int, size: Double = 1.0) {
        flash(x, y, 70 * size, 0.28 + 0.1 * size, color)
        ring(x, y, 8 * size, 90 * size, 0.45 + 0.1 * size, color, 5 * size)
        if (size > 1.4) ring(x, y, 4.0, 160 * size, 0.8, Pal.WHITE, 2.0)
        sparks(x, y, 18 * size, color, 520 * sqrt(size), TAU, 0.0, 0.5, 2.2)
        sparks(x, y, 6 * size, Pal.WHITE, 700 * sqrt(size), TAU, 0.0, 0.3, 1.5)
        embers(x, y, 14 * size, color, 260 * sqrt(size), 0.9, 7 * sqrt(size))
        smoke(x, y, 5 * size, Pal.SMOKE, 30 * size, 1.4)
        Light.add(x, y, 210 * sqrt(size), color, 2.4 * size, true)
        Lattice.impulse(x, y, 160 * sqrt(size), 520 * size)
    }

    /**
     * Geometric destruction: every polygon edge becomes a Debris segment and every fan triangle
     * (centroid, vᵢ, vᵢ₊₁) a spinning Shard, radiating from the centre of mass.
     */
    fun shatter(verts: DoubleArray, nVerts: Int, cx: Double, cy: Double, vx: Double, vy: Double, color: Int, energy: Double = 1.0) {
        for (i in 0 until nVerts) {
            val ax = verts[i * 2]; val ay = verts[i * 2 + 1]
            val j = (i + 1) % nVerts
            val bx = verts[j * 2]; val by = verts[j * 2 + 1]
            val mx = (ax + bx) / 2; val my = (ay + by) / 2
            val ox = mx - cx; val oy = my - cy; val ol0 = sqrt(ox * ox + oy * oy); val ol = if (ol0 == 0.0) 1.0 else ol0
            val push = Rng.vis.range(140.0, 300.0) * energy
            get(PKind.DEBRIS)?.let { p ->
                p.base(mx, my, vx * 0.4 + (ox / ol) * push, vy * 0.4 + (oy / ol) * push, Rng.vis.range(0.5, 0.9), 1.1)
                p.color = color; p.halfLen = sqrt(dist2(ax, ay, bx, by)) / 2; p.ang = atan2(by - ay, bx - ax)
                p.spin = Rng.vis.range(-12.0, 12.0); p.width = 2.0
            }
            val tx = (cx + ax + bx) / 3; val ty = (cy + ay + by) / 3
            val tl0 = sqrt((tx - cx) * (tx - cx) + (ty - cy) * (ty - cy)); val tl = if (tl0 == 0.0) 1.0 else tl0
            if (mul > 0.5 || i % 2 == 0) {
                get(PKind.SHARD)?.let { p ->
                    p.base(tx, ty, vx * 0.3 + ((tx - cx) / tl) * push * 0.7, vy * 0.3 + ((ty - cy) / tl) * push * 0.7, Rng.vis.range(0.55, 1.0), 1.3)
                    p.color = color
                    val tri = p.tri
                    tri[0] = (cx - tx).toFloat(); tri[1] = (cy - ty).toFloat(); tri[2] = (ax - tx).toFloat()
                    tri[3] = (ay - ty).toFloat(); tri[4] = (bx - tx).toFloat(); tri[5] = (by - ty).toFloat()
                    p.rot = 0.0; p.spin = Rng.vis.range(-9.0, 9.0)
                }
            }
        }
    }

    fun hit(x: Double, y: Double, angle: Double, color: Int) {
        sparks(x, y, 5.0, color, 380.0, 1.3, angle + Math.PI, 0.22, 1.6)
        flash(x, y, 16.0, 0.1, color)
    }

    fun graze(x: Double, y: Double, angle: Double) {
        sparks(x, y, 2.0, Pal.WHITE, 260.0, 0.8, angle, 0.18, 1.2)
        sparks(x, y, 2.0, Pal.ION, 200.0, 1.2, angle, 0.25, 1.2)
    }

    fun muzzle(x: Double, y: Double, angle: Double, color: Int) {
        flash(x, y, 14.0, 0.06, color)
        if (Rng.vis.chance(0.5 * mul)) spark(x, y, angle + Rng.vis.range(-0.3, 0.3), Rng.vis.range(200.0, 420.0), 0.12, color, 1.4)
    }

    fun engine(x: Double, y: Double, angle: Double, color: Int, power: Double, dt: Double) {
        if (!rateChance(0.85 * mul, dt)) return
        val a = angle + Math.PI + Rng.vis.range(-0.22, 0.22); val s = Rng.vis.range(90.0, 200.0) * power
        ember(x, y, cos(a) * s, sin(a) * s, Rng.vis.range(0.18, 0.34), color, Rng.vis.range(4.0, 7.0) * (0.6 + 0.4 * power), 3.5, true)
    }

    fun warpIn(x: Double, y: Double, color: Int, r: Double) {
        ring(x, y, r * 3.2, r * 0.5, 0.7, color, 2.0)
        val n = n(10.0)
        for (i in 0 until n) {
            val a = Rng.vis.angle(); val d = r * Rng.vis.range(2.2, 3.4)
            spark(x + cos(a) * d, y + sin(a) * d, a + Math.PI, d * 1.6, 0.5, color, 1.4, 0.05, 0.5)
        }
    }

    fun pickup(x: Double, y: Double, color: Int) {
        ring(x, y, 2.0, 22.0, 0.25, color, 2.0)
        sparks(x, y, 3.0, color, 160.0, TAU, 0.0, 0.2, 1.2)
    }

    fun afterimage(p: Player) {
        val q = get(PKind.AFTERIMAGE) ?: return
        q.base(p.x, p.y, 0.0, 0.0, 0.28, 0.0)
        q.ang = p.angle; q.bank = p.bank; q.hull = p.hull; q.color = p.color
    }
}
