package io.github.aloualou56.nebularequiem.core

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * §7b BACKGROUND state — the procedural nebula (pixels built by the render layer), three
 * parallax star layers and the plasma parameters. Layer offset = −(focus − centre)·f.
 */
class Star(val x: Double, val y: Double, val s: Double, val a: Double, val ph: Double, val tw: Double, val c: Int)
class StarLayer(val f: Double, val glow: Boolean, val list: Array<Star>)

object Bg {
    var palette: NebulaPalette = NEBULA_PALETTES[0]
    var seed = 1
    /** Nebula overscan for parallax travel. */
    const val margin = 0.14
    var stars: Array<StarLayer> = emptyArray()
    var time = 0.0
    var focusX = 0.0; var focusY = 0.0
    var driftY = 0.0
    /** World units / s of forward travel. */
    var driftSpeed = 16.0
    /** 0..1 hyperspace streak factor. */
    var warp = 0.0
    /** 0..1 veil over the nebula (raised during play). */
    var dim = 0.1

    /** Make (seed, palette) the live background; the render layer swaps in the nebula pixels. */
    fun adopt(seed: Int, pal: NebulaPalette, sync: Boolean = true) {
        this.seed = seed; palette = pal
        buildStars(seed)
        Env.nebula.adopt(seed, pal, sync)
    }

    fun buildStars(seed: Int) {
        val lr = Rng(seed * 13 + 3)
        val area = (World.w * World.h) / (1458.0 * 760.0)
        val defs = arrayOf(
            doubleArrayOf(0.1, 170.0, 0.5, 1.1, 0.2, 0.55, 0.0),
            doubleArrayOf(0.28, 95.0, 0.9, 1.7, 0.35, 0.8, 0.0),
            doubleArrayOf(0.6, 34.0, 1.3, 2.6, 0.6, 1.0, 1.0)
        )
        val tints = listOf(palette.c, palette.b, ColorUtil.hex("#a6d8ff"))
        stars = Array(defs.size) { li ->
            val d = defs[li]
            val n = (d[1] * max(0.5, area)).roundToInt()
            StarLayer(d[0], d[6] > 0, Array(n) {
                val tint = if (lr.chance(0.18)) lr.pick(tints) else Pal.WHITE
                Star(lr.range(0.0, World.w), lr.range(0.0, World.h), lr.range(d[2], d[3]), lr.range(d[4], d[5]), lr.angle(), lr.range(0.8, 3.2), tint)
            })
        }
    }

    fun update(dt: Double, fx: Double, fy: Double) {
        time += dt
        focusX = damp(focusX, fx, 3.0, dt)
        focusY = damp(focusY, fy, 3.0, dt)
        val speed = driftSpeed + warp * 2400
        driftY += speed * dt
    }
}
