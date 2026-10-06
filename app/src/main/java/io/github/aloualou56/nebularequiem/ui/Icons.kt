package io.github.aloualou56.nebularequiem.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.github.aloualou56.nebularequiem.core.HALF_PI
import io.github.aloualou56.nebularequiem.core.TAU
import kotlin.math.cos
import kotlin.math.sin

/** Procedural vector graft icons, rendered once per (graft, colour) into 64×64 dp bitmaps with a glow. */
object Icons {
    private val cache = HashMap<String, Bitmap>()
    var density = 2f

    fun get(id: String, color: Int): Bitmap {
        val key = "$id/$color/$density"
        cache[key]?.let { return it }
        val px = (64 * density).toInt().coerceAtLeast(16)
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(px / 64f, px / 64f)
        c.translate(32f, 32f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE; setShadowLayer(8f, 0f, 0f, color)
        }
        paint(c, p, id)
        cache[key] = b
        return b
    }

    private val path = Path()
    private val oval = RectF()

    private fun line(c: Canvas, p: Paint, vararg pts: Float) {
        path.rewind()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        p.style = Paint.Style.STROKE
        c.drawPath(path, p)
    }
    private fun circle(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, fill: Boolean = false) {
        p.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
    }
    private fun arc(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, a0: Double, a1: Double) {
        oval.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(oval, Math.toDegrees(a0).toFloat(), Math.toDegrees(a1 - a0).toFloat(), false, p)
    }

    private fun paint(c: Canvas, p: Paint, id: String) {
        when (id) {
            "overclock" -> for (i in -1..1) line(c, p, -8f + i * 9, -14f, 2f + i * 9, 0f, -8f + i * 9, 14f)
            "density" -> {
                path.rewind(); path.moveTo(0f, -18f); path.cubicTo(14f, -2f, 12f, 16f, 0f, 16f); path.cubicTo(-12f, 16f, -14f, -2f, 0f, -18f)
                c.drawPath(path, p); circle(c, p, 0f, 6f, 4f, true)
            }
            "prism" -> { line(c, p, 0f, -18f, 16f, 12f, -16f, 12f, 0f, -18f); line(c, p, -22f, 0f, -6f, 0f); line(c, p, 6f, -4f, 22f, -12f); line(c, p, 6f, 2f, 22f, 2f); line(c, p, 6f, 8f, 22f, 16f) }
            "phaseRounds" -> { line(c, p, -20f, 0f, 20f, 0f); line(c, p, 12f, -7f, 20f, 0f, 12f, 7f); p.strokeWidth = 2f; for (px in floatArrayOf(-8f, 4f)) line(c, p, px, -12f, px, 12f) }
            "seeker" -> {
                path.rewind(); path.moveTo(-18f, 14f); path.quadTo(-14f, -16f, 16f, -10f); c.drawPath(path, p)
                line(c, p, 8f, -16f, 16f, -10f, 10f, -2f); circle(c, p, -18f, 14f, 3f, true)
            }
            "orbital" -> { circle(c, p, 0f, 0f, 5f, true); p.strokeWidth = 2f; circle(c, p, 0f, 0f, 16f); circle(c, p, 16f, 0f, 4f, true); circle(c, p, -8f, 13.8f, 4f, true); circle(c, p, -8f, -13.8f, 4f, true) }
            "arc" -> { line(c, p, -4f, -20f, -12f, 2f, 2f, 0f, -6f, 20f); line(c, p, 8f, -14f, 14f, -2f, 6f, 2f, 12f, 14f) }
            "ricochet" -> { line(c, p, -20f, -14f, 0f, 12f, 20f, -14f); p.pathEffect = DashPathEffect(floatArrayOf(3f, 5f), 0f); line(c, p, -22f, 16f, 22f, 16f); p.pathEffect = null }
            "singularity" -> { circle(c, p, 0f, 0f, 6f, true); for (i in 0 until 8) { val a = i / 8.0 * TAU; line(c, p, (cos(a) * 11).toFloat(), (sin(a) * 11).toFloat(), (cos(a) * 19).toFloat(), (sin(a) * 19).toFloat()) } }
            "magnet" -> {
                arc(c, p, 0f, -2f, 13f, Math.PI, 2 * Math.PI)
                line(c, p, -13f, -2f, -13f, 14f); line(c, p, 13f, -2f, 13f, 14f)
                p.strokeWidth = 5f; line(c, p, -13f, 10f, -13f, 16f); line(c, p, 13f, 10f, 13f, 16f)
            }
            "nanorepair", "repair" -> { p.strokeWidth = 6f; line(c, p, 0f, -15f, 0f, 15f); line(c, p, -15f, 0f, 15f, 0f) }
            "aegis" -> { line(c, p, 0f, -18f, 15f, -10f, 12f, 8f, 0f, 18f, -12f, 8f, -15f, -10f, 0f, -18f); p.strokeWidth = 2f; line(c, p, 0f, -10f, 0f, 10f) }
            "siphon" -> {
                path.rewind()
                for (i in 0..40) { val t = i / 40.0; val a = t * TAU * 1.6; val r = 18 * (1 - t); val x = (cos(a) * r).toFloat(); val y = (sin(a) * r).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                c.drawPath(path, p)
            }
            "crit" -> { circle(c, p, 0f, 0f, 14f); line(c, p, 0f, -22f, 0f, -8f); line(c, p, 0f, 8f, 0f, 22f); line(c, p, -22f, 0f, -8f, 0f); line(c, p, 8f, 0f, 22f, 0f); circle(c, p, 0f, 0f, 3f, true) }
            "afterburner" -> {
                path.rewind(); path.moveTo(0f, -18f); path.cubicTo(12f, -4f, 10f, 14f, 0f, 18f); path.cubicTo(-10f, 14f, -12f, -4f, 0f, -18f); c.drawPath(path, p)
                path.rewind(); path.moveTo(0f, -4f); path.cubicTo(5f, 4f, 4f, 12f, 0f, 13f); path.cubicTo(-4f, 12f, -5f, 4f, 0f, -4f)
                p.style = Paint.Style.FILL; c.drawPath(path, p); p.style = Paint.Style.STROKE
            }
            "phaseEdge" -> { line(c, p, -18f, 16f, 16f, -18f); p.strokeWidth = 2f; line(c, p, -18f, 4f, 4f, -18f); line(c, p, -6f, 16f, 16f, -6f) }
            "corona" -> { circle(c, p, 0f, 0f, 7f, true); p.strokeWidth = 2f; p.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f); circle(c, p, 0f, 0f, 13f); circle(c, p, 0f, 0f, 19f); p.pathEffect = null }
            "rear" -> { line(c, p, 14f, 0f, -14f, 0f); line(c, p, -6f, -8f, -14f, 0f, -6f, 8f); line(c, p, 18f, -12f, 18f, 12f) }
            "chrono" -> {
                line(c, p, -12f, -18f, 12f, -18f, -12f, 18f, 12f, 18f, -12f, -18f)
                path.rewind(); path.moveTo(-6f, 12f); path.lineTo(6f, 12f); path.lineTo(0f, 4f); path.close()
                p.style = Paint.Style.FILL; c.drawPath(path, p); p.style = Paint.Style.STROKE
            }
            "novaCache" -> { circle(c, p, 0f, 0f, 16f); circle(c, p, 0f, 0f, 8f); circle(c, p, 0f, 0f, 3f, true) }
            "velocity" -> { for (i in 0 until 3) line(c, p, -18f, -10f + i * 10, 8f - i * 4, -10f + i * 10); line(c, p, 10f, -14f, 20f, 0f, 10f, 14f) }
            "echo" -> { line(c, p, -20f, 0f, 20f, 0f); p.strokeWidth = 1.5f; for (r in floatArrayOf(8f, 14f, 20f)) arc(c, p, -20f, 0f, r, -0.6, 0.6) }
            "supernova" -> {
                path.rewind(); path.moveTo(0f, 16f); path.cubicTo(-26f, -2f, -10f, -22f, 0f, -8f); path.cubicTo(10f, -22f, 26f, -2f, 0f, 16f); c.drawPath(path, p)
                for (i in 0 until 4) { val a = i / 4.0 * TAU + 0.4; line(c, p, (cos(a) * 20).toFloat(), (sin(a) * 20).toFloat(), (cos(a) * 26).toFloat(), (sin(a) * 26).toFloat()) }
            }
            "midas", "cache" -> {
                path.rewind()
                for (k in 0 until 8) { val a = k / 8.0 * TAU - HALF_PI; val r = if (k % 2 == 1) 7.0 else 19.0; val x = (cos(a) * r).toFloat(); val y = (sin(a) * r).toFloat(); if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                path.close(); c.drawPath(path, p)
            }
            else -> circle(c, p, 0f, 0f, 12f)
        }
    }
}
