package io.github.aloualou56.nebularequiem.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * §5b WORLD — the short screen edge always spans [Cfg.WORLD_SHORT_SIDE] world units, so balance is
 * identical on every display; the long edge simply reveals more arena.
 */
object World {
    /** Viewport in dp (the unit the layout constants use). */
    var dpW = 1.0; var dpH = 1.0
    /** Surface pixels per dp. */
    var density = 1.0
    /** Surface size in pixels. */
    var pxW = 1; var pxH = 1
    /** Surface pixels per world unit. */
    var scale = 1.0
    /** Arena size in world units. */
    var w = 0.0; var h = 0.0
    /** Effective quality tier (adaptive quality may lower it below the user's choice). */
    var quality = Quality.HIGH
    /** Fraction of full resolution the world layer renders at (quality pixel budget). */
    var renderScale = 1.0

    /** Returns true when the arena changed size. */
    fun resize(pxWidth: Int, pxHeight: Int, dens: Double): Boolean {
        val oldW = w; val oldH = h
        pxW = max(1, pxWidth); pxH = max(1, pxHeight)
        density = if (dens > 0) dens else 1.0
        dpW = pxW / density; dpH = pxH / density
        scale = min(pxW, pxH) / Cfg.WORLD_SHORT_SIDE
        w = pxW / scale; h = pxH / scale
        updateRenderScale()
        return abs(oldW - w) > 1 || abs(oldH - h) > 1
    }

    /** dpr ≤ the tier's cap and √(budget / area) — the canvas sizing, as a layer scale. */
    fun updateRenderScale() {
        val q = quality
        var dpr = min(density, q.densityCap)
        val area = dpW * dpH
        if (area * dpr * dpr > q.pixelBudget) dpr = sqrt(q.pixelBudget / area)
        renderScale = clamp(dpr / density, 0.25, 1.0)
    }

    /** dp point → world coordinate through the camera's stable (shake-free) inverse. */
    fun toWorld(dpX: Double, dpY: Double, out: Vec2): Vec2 = Cam.inv.point(dpX * density, dpY * density, out)
}

class Vec2(@JvmField var x: Double = 0.0, @JvmField var y: Double = 0.0) {
    fun set(x: Double, y: Double): Vec2 { this.x = x; this.y = y; return this }
}

/** 2-D affine matrix (a, b, c, d, e, f): x' = a·x + c·y + e, y' = b·x + d·y + f. */
class Mat2D {
    @JvmField var a = 1.0; @JvmField var b = 0.0; @JvmField var c = 0.0; @JvmField var d = 1.0; @JvmField var e = 0.0; @JvmField var f = 0.0
    fun set(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double): Mat2D { this.a = a; this.b = b; this.c = c; this.d = d; this.e = e; this.f = f; return this }
    fun copy(m: Mat2D) = set(m.a, m.b, m.c, m.d, m.e, m.f)
    fun identity() = set(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    fun translate(x: Double, y: Double): Mat2D { e += a * x + c * y; f += b * x + d * y; return this }
    fun scale(sx: Double, sy: Double = sx): Mat2D { a *= sx; b *= sx; c *= sy; d *= sy; return this }
    fun rotate(r: Double): Mat2D {
        val cs = cos(r); val sn = sin(r)
        val na = a * cs + c * sn; val nb = b * cs + d * sn
        val nc = c * cs - a * sn; val nd = d * cs - b * sn
        a = na; b = nb; c = nc; d = nd; return this
    }
    fun invert(): Mat2D {
        val det = a * d - b * c
        if (abs(det) < 1e-12) return identity()
        val id = 1 / det
        val na = d * id; val nb = -b * id; val nc = -c * id; val nd = a * id
        val ne = -(na * e + nc * f); val nf = -(nb * e + nd * f)
        return set(na, nb, nc, nd, ne, nf)
    }
    fun point(x: Double, y: Double, out: Vec2): Vec2 { out.x = a * x + c * y + e; out.y = b * x + d * y + f; return out }
}

/**
 * §9 CAMERA & SCREENSHAKE. Trauma model: displayed shake is trauma² and decays linearly; offsets
 * come from smooth value noise. M = S(scale)·T(c)·R(θ_shake)·S(zoom)·T(−c + offset_shake + kick).
 */
object Cam {
    private val shakeNoise = ValueNoise(1337)
    var trauma = 0.0; var time = 0.0
    var kx = 0.0; var ky = 0.0; var kvx = 0.0; var kvy = 0.0
    var zoom = 1.0; var zoomTarget = 1.0; var zoomVel = 0.0
    val matrix = Mat2D(); val base = Mat2D(); val inv = Mat2D()

    fun addTrauma(t: Double) { trauma = min(1.0, trauma + t) }
    /** Directional recoil: an impulse into a critically-damped spring (k = 260, c ≈ 32). */
    fun kick(angle: Double, mag: Double) { kvx += cos(angle) * mag; kvy += sin(angle) * mag }
    fun punch(z: Double) { zoomVel += z }

    fun update(dt: Double) {
        time += dt
        trauma = max(0.0, trauma - dt * 1.1)
        val steps = max(1, ceil(dt * 240).toInt()); val h = dt / steps
        val K = 260.0; val C = 32.0; val ZK = 120.0; val ZC = 14.0
        for (i in 0 until steps) {
            kvx += (-K * kx - C * kvx) * h; kvy += (-K * ky - C * kvy) * h
            kx += kvx * h; ky += kvy * h
            zoomVel += (-ZK * (zoom - zoomTarget) - ZC * zoomVel) * h
            zoom += zoomVel * h
        }
        kx = clamp(kx, -80.0, 80.0); ky = clamp(ky, -80.0, 80.0)
        zoom = clamp(zoom, 0.7, 1.5)
    }

    fun build(shakeScale: Double) {
        val s = trauma * trauma * shakeScale
        val t = time * 22
        val ox = 24 * s * (shakeNoise.noise1(t) * 2 - 1)
        val oy = 24 * s * (shakeNoise.noise1(t + 97.31) * 2 - 1)
        val rot = 0.05 * s * (shakeNoise.noise1(t + 211.7) * 2 - 1)
        val cx = World.w / 2; val cy = World.h / 2
        matrix.identity().scale(World.scale).translate(cx, cy).rotate(rot).scale(zoom).translate(-cx + ox + kx, -cy + oy + ky)
        base.identity().scale(World.scale).translate(cx, cy).scale(zoom).translate(-cx, -cy)
        inv.copy(base).invert()
    }

    fun reset() { trauma = 0.0; kx = 0.0; ky = 0.0; kvx = 0.0; kvy = 0.0; zoom = 1.0; zoomTarget = 1.0; zoomVel = 0.0 }
}

/** §10 POST-PROCESSING state (the renderer applies bloom / chromatic aberration / flashes). */
object PostFx {
    var caPulse = 0.0; var flashA = 0.0; var flashColor = Pal.WHITE; var glitch = 0.0
    fun pulseCA(px: Double) { caPulse = min(16.0, caPulse + px) }
    fun flash(color: Int, a: Double) { flashColor = color; flashA = max(flashA, a) }
    fun update(dt: Double) {
        caPulse = damp(caPulse, 0.0, 3.4, dt)
        flashA = max(0.0, flashA - dt * 2.6)
        glitch = max(0.0, glitch - dt * 2)
    }
    fun reset() { caPulse = 0.0; flashA = 0.0; glitch = 0.0 }
}

/**
 * §7 LIGHTING GRID — a coarse 2-D radiance field. Lights splat into cols×rows with the quartic
 * kernel w(d) = I·(1 − d²/R²)². `steady` is cleared every frame, `pulse` decays as e^(−7.5t).
 * The sum is tone-mapped with 1 − e^(−v) into [pixels] (ARGB) that the renderer uploads and
 * bilinearly upscales across the arena with additive blending.
 */
object Light {
    var cols = 1; var rows = 1
    const val cell = Cfg.LIGHT_CELL
    var steady = FloatArray(3); var pulse = FloatArray(3)
    var pixels = IntArray(1)
    /** Bumped on every upload so the renderer knows to refresh its texture. */
    var generation = 0

    fun resize(w: Double, h: Double) {
        cols = ceil(w / cell).toInt() + 2
        rows = ceil(h / cell).toInt() + 2
        val n = cols * rows * 3
        steady = FloatArray(n); pulse = FloatArray(n)
        pixels = IntArray(cols * rows) { 0xFF000000.toInt() }
    }

    fun add(x: Double, y: Double, radius: Double, color: Int, intensity: Double, transient: Boolean = false) {
        val buf = if (transient) pulse else steady
        if (buf.size < 3) return
        val r = ((color shr 16) and 255) / 255f; val g = ((color shr 8) and 255) / 255f; val b = (color and 255) / 255f
        val inv = 1 / cell
        val gx = x * inv + 1; val gy = y * inv + 1; val R = radius * inv; val R2 = R * R
        val x0 = max(0, floor(gx - R).toInt()); val x1 = min(cols - 1, ceil(gx + R).toInt())
        val y0 = max(0, floor(gy - R).toInt()); val y1 = min(rows - 1, ceil(gy + R).toInt())
        for (cy in y0..y1) {
            val dy = cy - gy; val dy2 = dy * dy; val row = cy * cols
            for (cx in x0..x1) {
                val dx = cx - gx; val d2 = dx * dx + dy2
                if (d2 >= R2) continue
                val f = 1 - d2 / R2; val w = (f * f * intensity).toFloat(); val i = (row + cx) * 3
                buf[i] += r * w; buf[i + 1] += g * w; buf[i + 2] += b * w
            }
        }
    }

    /** Called once per rendered frame: decay transient light, tone-map, clear steady. */
    fun upload(dt: Double, exposure: Double = 0.9) {
        val k = exp(-7.5 * dt).toFloat(); val p = pulse; val s = steady; val d = pixels
        val ex = exposure.toFloat()
        var j = 0
        var i = 0
        while (i < p.size) {
            p[i] *= k; p[i + 1] *= k; p[i + 2] *= k
            val r = (255 * (1 - exp((-(s[i] + p[i]) * ex).toDouble()))).toInt()
            val g = (255 * (1 - exp((-(s[i + 1] + p[i + 1]) * ex).toDouble()))).toInt()
            val b = (255 * (1 - exp((-(s[i + 2] + p[i + 2]) * ex).toDouble()))).toInt()
            d[j] = (0xFF shl 24) or (clampI(r, 0, 255) shl 16) or (clampI(g, 0, 255) shl 8) or clampI(b, 0, 255)
            i += 3; j++
        }
        s.fill(0f)
        generation++
    }

    /** Average radiance (0..1 per channel) within a world-space rectangle — feeds HUD glows. */
    fun sample(x0: Double, y0: Double, x1: Double, y1: Double, out: DoubleArray): DoubleArray {
        val inv = 1 / cell
        val cx0 = clampI(floor(x0 * inv).toInt() + 1, 0, cols - 1); val cx1 = clampI(ceil(x1 * inv).toInt() + 1, 0, cols - 1)
        val cy0 = clampI(floor(y0 * inv).toInt() + 1, 0, rows - 1); val cy1 = clampI(ceil(y1 * inv).toInt() + 1, 0, rows - 1)
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        for (y in cy0..cy1) for (x in cx0..cx1) {
            val c = pixels[y * cols + x]
            r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++
        }
        if (n == 0) n = 1
        out[0] = r / n / 255; out[1] = g / n / 255; out[2] = b / n / 255
        return out
    }

    fun clearTransient() { pulse.fill(0f); steady.fill(0f) }
}

/**
 * §8 SPACETIME LATTICE — a mass-spring grid that ripples under explosions and gravity:
 *   aᵢ = −k_a·uᵢ − c·vᵢ + k_n·Σⱼ(uⱼ − uᵢ) + F_ext, semi-implicit Euler at ≤ 1/90 s sub-steps.
 */
object Lattice {
    var cols = 0; var rows = 0
    var sp = Cfg.LATTICE_SPACING
    var rx = FloatArray(0); var ry = FloatArray(0)
    var ux = FloatArray(0); var uy = FloatArray(0)
    var vx = FloatArray(0); var vy = FloatArray(0)
    private val wells = DoubleArray(4 * 32)
    private var wellN = 0
    var color = ColorUtil.hex("#7a5cff")

    fun build(w: Double, h: Double) {
        sp = Cfg.LATTICE_SPACING
        cols = ceil(w / sp).toInt() + 3
        rows = ceil(h / sp).toInt() + 3
        val n = cols * rows
        rx = FloatArray(n); ry = FloatArray(n); ux = FloatArray(n); uy = FloatArray(n); vx = FloatArray(n); vy = FloatArray(n)
        val offX = (w - (cols - 1) * sp) / 2; val offY = (h - (rows - 1) * sp) / 2
        for (j in 0 until rows) for (i in 0 until cols) {
            val k = j * cols + i; rx[k] = (offX + i * sp).toFloat(); ry[k] = (offY + j * sp).toFloat()
        }
    }

    /** Radial velocity kick with quadratic falloff: Δv = s·(1 − d/R)²·r̂. */
    fun impulse(x: Double, y: Double, radius: Double, strength: Double) {
        if (cols == 0) return
        val R2 = radius * radius
        for (k in rx.indices) {
            val dx = rx[k] + ux[k] - x; val dy = ry[k] + uy[k] - y; val d2 = dx * dx + dy * dy
            if (d2 >= R2 || d2 < 1e-4) continue
            val d = sqrt(d2); val f = 1 - d / radius; val s = strength * f * f / d
            vx[k] += (dx * s).toFloat(); vy[k] += (dy * s).toFloat()
        }
    }

    /** A persistent gravity well for this frame (bosses, the player). Negative = repulsor. */
    fun well(x: Double, y: Double, radius: Double, strength: Double) {
        if (wellN + 4 > wells.size) return
        wells[wellN] = x; wells[wellN + 1] = y; wells[wellN + 2] = radius; wells[wellN + 3] = strength
        wellN += 4
    }

    fun update(dt: Double) {
        if (cols == 0 || dt <= 0) { wellN = 0; return }
        val steps = max(1, ceil(dt / (1.0 / 90)).toInt())
        val h = (dt / steps).toFloat()
        val KA = 26f; val C = 4.2f; val KN = 48f
        val cols = cols; val rows = rows
        val ux = ux; val uy = uy; val vx = vx; val vy = vy; val W = wells
        for (s in 0 until steps) {
            for (j in 1 until rows - 1) {
                for (i in 1 until cols - 1) {
                    val k = j * cols + i
                    val lx = ux[k - 1] + ux[k + 1] + ux[k - cols] + ux[k + cols] - 4 * ux[k]
                    val ly = uy[k - 1] + uy[k + 1] + uy[k - cols] + uy[k + cols] - 4 * uy[k]
                    var ax = -KA * ux[k] - C * vx[k] + KN * lx
                    var ay = -KA * uy[k] - C * vy[k] + KN * ly
                    var w = 0
                    while (w < wellN) {
                        val dx = W[w] - (rx[k] + ux[k]); val dy = W[w + 1] - (ry[k] + uy[k])
                        val d2 = dx * dx + dy * dy; val R = W[w + 2]
                        if (d2 < R * R && d2 > 1) {
                            val d = sqrt(d2); val f = 1 - d / R
                            ax += (dx / d * W[w + 3] * f * f).toFloat(); ay += (dy / d * W[w + 3] * f * f).toFloat()
                        }
                        w += 4
                    }
                    vx[k] += ax * h; vy[k] += ay * h
                }
            }
            for (k in ux.indices) {
                ux[k] += vx[k] * h; uy[k] += vy[k] * h
                if (ux[k] > 40f) ux[k] = 40f else if (ux[k] < -40f) ux[k] = -40f
                if (uy[k] > 40f) uy[k] = 40f else if (uy[k] < -40f) uy[k] = -40f
            }
        }
        wellN = 0
    }

    fun clearWells() { wellN = 0 }
}
