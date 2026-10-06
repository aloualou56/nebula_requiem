package io.github.aloualou56.nebularequiem.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import io.github.aloualou56.nebularequiem.core.Rng
import io.github.aloualou56.nebularequiem.core.Shape
import io.github.aloualou56.nebularequiem.core.TAU
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A rectangle of the atlas in texel units (inset half a texel against bilinear bleeding). */
class Region(x: Int, y: Int, w: Int, h: Int) {
    val u0 = x + 0.5f; val v0 = y + 0.5f; val u1 = x + w - 0.5f; val v1 = y + h - 0.5f
    val px = x; val py = y; val pw = w; val ph = h
}

/**
 * §6 SPRITES. Every glowing primitive is pre-rendered once — in WHITE — into a single atlas
 * texture and stamped with additive blending, tinted per instance by vertex colour (MODULATE),
 * so thousands of bullets, embers and glows draw in a handful of GPU batches. Nothing is drawn with
 * blur at run time.
 *
 * The original's two-tone sprites (white core over a coloured body) are split into a tintable
 * BODY layer and an untinted white DETAIL layer.
 */
class Atlas {
    val bitmap: Bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
    private val c = Canvas(bitmap)
    private var shelfX = 2; private var shelfY = 2; private var shelfH = 0

    lateinit var glow: Region; lateinit var coreRim: Region; lateinit var coreHot: Region
    lateinit var smoke: Region; lateinit var line: Region; lateinit var white: Region; lateinit var dot: Region
    lateinit var ring: Region
    val bulletBody = arrayOfNulls<Region>(Shape.entries.size)
    val bulletDetail = arrayOfNulls<Region>(Shape.entries.size)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private fun alloc(w: Int, h: Int): Region {
        if (shelfX + w + 2 > bitmap.width) { shelfX = 2; shelfY += shelfH + 3; shelfH = 0 }
        val r = Region(shelfX, shelfY, w, h)
        shelfX += w + 3
        shelfH = max(shelfH, h)
        return r
    }

    private inline fun paint(r: Region, block: (cx: Float, cy: Float) -> Unit) {
        c.save()
        c.clipRect(r.px.toFloat(), r.py.toFloat(), (r.px + r.pw).toFloat(), (r.py + r.ph).toFloat())
        block(r.px + r.pw / 2f, r.py + r.ph / 2f)
        c.restore()
    }

    private fun radial(cx: Float, cy: Float, radius: Float, stops: FloatArray, alphas: FloatArray): Shader =
        RadialGradient(cx, cy, radius, IntArray(alphas.size) { Color.argb((alphas[it] * 255).toInt(), 255, 255, 255) }, stops, Shader.TileMode.CLAMP)

    init {
        bitmap.eraseColor(0)
        // Soft radial glow: falloff stops approximate e^(−4r²).
        glow = alloc(128, 128)
        paint(glow) { cx, cy ->
            p.shader = radial(cx, cy, 64f, floatArrayOf(0f, .2f, .45f, .7f, 1f), floatArrayOf(1f, .85f, .42f, .12f, 0f))
            c.drawCircle(cx, cy, 64f, p)
        }
        // Hot white-cored glow, split into the tinted rim and the white heart.
        coreRim = alloc(128, 128)
        paint(coreRim) { cx, cy ->
            p.shader = radial(cx, cy, 64f, floatArrayOf(0f, .3f, .6f, 1f), floatArrayOf(.7f, .7f, .18f, 0f))
            c.drawCircle(cx, cy, 64f, p)
        }
        coreHot = alloc(128, 128)
        paint(coreHot) { cx, cy ->
            p.shader = radial(cx, cy, 64f, floatArrayOf(0f, .12f, .3f, 1f), floatArrayOf(1f, .9f, 0f, 0f))
            c.drawCircle(cx, cy, 64f, p)
        }
        // Lumpy smoke puff built from offset circles so puffs don't look like discs.
        smoke = alloc(96, 96)
        paint(smoke) { cx, cy ->
            val lr = Rng(97 * 7 + 42)
            for (i in 0 until 7) {
                val a = lr.angle(); val d = lr.range(0.0, 16.0)
                val x = cx + (cos(a) * d).toFloat(); val y = cy + (sin(a) * d).toFloat(); val rad = lr.range(18.0, 30.0).toFloat()
                p.shader = radial(x, y, rad, floatArrayOf(0f, 1f), floatArrayOf(.28f, 0f))
                c.drawCircle(x, y, rad, p)
            }
        }
        // Soft-edged line strip for sparks and debris (falls off across its width).
        line = alloc(64, 16)
        paint(line) { _, _ ->
            p.shader = LinearGradient(0f, line.py.toFloat(), 0f, (line.py + 16).toFloat(),
                intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, .35f, .65f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(line.px.toFloat(), line.py.toFloat(), (line.px + 64).toFloat(), (line.py + 16).toFloat(), p)
        }
        white = alloc(8, 8)
        paint(white) { _, _ -> p.shader = null; p.color = Color.WHITE; c.drawRect(white.px.toFloat(), white.py.toFloat(), white.px + 8f, white.py + 8f, p) }
        dot = alloc(32, 32)
        paint(dot) { cx, cy -> p.shader = null; p.color = Color.WHITE; c.drawCircle(cx, cy, 15f, p) }
        ring = alloc(64, 64)
        paint(ring) { cx, cy -> p.shader = null; p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 4f; c.drawCircle(cx, cy, 29f, p); p.style = Paint.Style.FILL }
        for (s in Shape.entries) {
            val S = ceil(2 * BULLET_E * BULLET_R).toInt()
            bulletBody[s.ordinal] = alloc(S, S).also { r -> paint(r) { cx, cy -> paintBullet(s, cx, cy, true) } }
            bulletDetail[s.ordinal] = alloc(S, S).also { r -> paint(r) { cx, cy -> paintBullet(s, cx, cy, false) } }
        }
        p.shader = null
    }


    private fun core(cx: Float, cy: Float, rx: Float, ry: Float) {
        val m = max(rx, ry)
        c.save()
        c.translate(cx, cy)
        c.scale(rx / m, ry / m)
        p.shader = radial(0f, 0f, m, floatArrayOf(0f, .65f, 1f), floatArrayOf(1f, 1f, 0f))
        c.drawCircle(0f, 0f, m, p)
        c.restore()
        p.shader = null
    }

    /**
     * Bullet sprites are authored for a unit radius of R = 20 sprite-px with an extent of E = 2.8
     * radii (room for the halo), drawn at world size 2·E·r so one sprite serves every radius.
     */
    private fun paintBullet(shape: Shape, cx: Float, cy: Float, body: Boolean) {
        val R = BULLET_R; val E = BULLET_E
        p.shader = null; p.color = Color.WHITE; p.style = Paint.Style.FILL; p.alpha = 255
        val stretch = when (shape) { Shape.RICE, Shape.BOLT -> 1.55f; Shape.LANCE -> 1.9f; else -> 1f }
        if (body) {
            c.save()
            c.translate(cx, cy)
            c.scale(stretch, 1f / sqrt(stretch))
            val hr = R * E / stretch
            p.shader = RadialGradient(0f, 0f, hr, intArrayOf(Color.argb(107, 255, 255, 255), Color.argb(36, 255, 255, 255), 0x00FFFFFF),
                floatArrayOf(0.3f * R / hr, 0.3f * R / hr + 0.4f * (1 - 0.3f * R / hr), 1f), Shader.TileMode.CLAMP)
            c.drawCircle(0f, 0f, hr, p)
            p.shader = null
            c.restore()
        }
        c.save()
        c.translate(cx, cy)
        when (shape) {
            Shape.RICE, Shape.BOLT -> if (body) c.drawOval(-R * 1.6f, -R * 0.72f, R * 1.6f, R * 0.72f, p) else core(0f, 0f, R * 1.1f, R * 0.4f)
            Shape.LANCE -> if (body) c.drawOval(-R * 2.2f, -R * 0.6f, R * 2.2f, R * 0.6f, p) else core(0f, 0f, R * 1.8f, R * 0.36f)
            Shape.STAR -> if (body) {
                path.rewind()
                for (i in 0 until 8) {
                    val a = (i / 8.0 * TAU); val rr = if (i % 2 == 1) R * 0.55f else R * 1.6f
                    val x = (cos(a) * rr).toFloat(); val y = (sin(a) * rr).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close(); c.drawPath(path, p)
            } else core(0f, 0f, R * 0.5f, R * 0.5f)
            Shape.DIAMOND -> {
                path.rewind()
                if (body) { path.moveTo(R * 1.55f, 0f); path.lineTo(0f, R * 0.95f); path.lineTo(-R * 1.55f, 0f); path.lineTo(0f, -R * 0.95f) }
                else { path.moveTo(R * 0.8f, 0f); path.lineTo(0f, R * 0.42f); path.lineTo(-R * 0.8f, 0f); path.lineTo(0f, -R * 0.42f) }
                path.close(); c.drawPath(path, p)
            }
            Shape.RING -> if (body) {
                p.style = Paint.Style.STROKE; p.strokeWidth = R * 0.36f; c.drawCircle(0f, 0f, R * 0.86f, p)
                p.style = Paint.Style.FILL; p.alpha = 64; c.drawCircle(0f, 0f, R * 0.7f, p); p.alpha = 255
            } else { p.style = Paint.Style.STROKE; p.strokeWidth = R * 0.14f; c.drawCircle(0f, 0f, R * 0.86f, p); p.style = Paint.Style.FILL }
            Shape.MINE -> if (body) {
                path.rewind()
                for (i in 0 until 16) {
                    val a = (i / 16.0 * TAU); val rr = if (i % 2 == 1) R * 0.75f else R * 1.25f
                    val x = (cos(a) * rr).toFloat(); val y = (sin(a) * rr).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close(); p.alpha = 217; c.drawPath(path, p); p.alpha = 255
            } else core(0f, 0f, R * 0.42f, R * 0.42f)
            Shape.BIG -> if (body) c.drawCircle(0f, 0f, R, p) else {
                p.style = Paint.Style.STROKE; p.strokeWidth = R * 0.1f; c.drawCircle(0f, 0f, R * 1.25f, p); p.style = Paint.Style.FILL
                core(0f, 0f, R * 0.68f, R * 0.68f)
            }
            Shape.ORB -> if (body) c.drawCircle(0f, 0f, R, p) else core(0f, 0f, R * 0.62f, R * 0.62f)
        }
        c.restore()
    }

    companion object {
        const val BULLET_R = 20f
        const val BULLET_E = 2.8f
    }
}
