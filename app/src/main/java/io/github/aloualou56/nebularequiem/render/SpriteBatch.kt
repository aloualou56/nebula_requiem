package io.github.aloualou56.nebularequiem.render

import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Batches textured, vertex-coloured triangles from the [Atlas] into single Canvas.drawVertices
 * calls (GPU-accelerated on Android 10+). Colours MODULATE the white atlas texels, so one sprite
 * serves every tint and alpha. All arrays are preallocated: nothing allocates while drawing.
 */
class SpriteBatch(private val atlas: Atlas, private val capacity: Int = 6000) {
    private val verts = FloatArray(capacity * 8)
    private val texs = FloatArray(capacity * 8)
    private val colors = IntArray(capacity * 4)
    private val indices = ShortArray(capacity * 6)
    private var nv = 0   // vertices
    private var ni = 0   // indices
    private val paint = Paint().apply {
        shader = BitmapShader(atlas.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        isFilterBitmap = true
        isDither = false
    }
    private var target: Canvas? = null
    private var mode: BlendMode = BlendMode.PLUS

    /** Start collecting for `canvas`; geometry is in the canvas's current coordinate space. */
    fun begin(canvas: Canvas, blend: BlendMode = BlendMode.PLUS) {
        if (target != null) flush()
        target = canvas; mode = blend; nv = 0; ni = 0
    }

    fun flush() {
        val c = target ?: return
        if (ni > 0) {
            paint.blendMode = mode
            c.drawVertices(Canvas.VertexMode.TRIANGLES, nv * 2, verts, 0, texs, 0, colors, 0, indices, 0, ni, paint)
        }
        nv = 0; ni = 0
    }

    fun end() { flush(); target = null }

    private fun ensure(v: Int, i: Int) { if (nv + v > capacity * 4 || ni + i > capacity * 6) flush() }

    private fun v(x: Float, y: Float, u: Float, t: Float, color: Int) {
        val k = nv * 2
        verts[k] = x; verts[k + 1] = y; texs[k] = u; texs[k + 1] = t; colors[nv] = color
        nv++
    }

    /** Premultiplied-friendly colour with alpha a ∈ [0,1] (ARGB int, unpremultiplied as Canvas expects). */
    fun argb(color: Int, a: Float): Int {
        val ai = (if (a <= 0f) 0f else if (a >= 1f) 1f else a) * 255f
        return (ai.toInt() shl 24) or (color and 0xFFFFFF)
    }

    /** Arbitrary textured quad (corners in order: top-left, top-right, bottom-right, bottom-left). */
    fun quad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, r: Region, color: Int) {
        if (color ushr 24 == 0) return
        ensure(4, 6)
        val b = nv
        v(x0, y0, r.u0, r.v0, color); v(x1, y1, r.u1, r.v0, color); v(x2, y2, r.u1, r.v1, color); v(x3, y3, r.u0, r.v1, color)
        indices[ni++] = b.toShort(); indices[ni++] = (b + 1).toShort(); indices[ni++] = (b + 2).toShort()
        indices[ni++] = b.toShort(); indices[ni++] = (b + 2).toShort(); indices[ni++] = (b + 3).toShort()
    }

    /** Axis-aligned sprite centred on (cx, cy) with half extents (hw, hh). */
    fun sprite(cx: Float, cy: Float, hw: Float, hh: Float, r: Region, color: Int) {
        quad(cx - hw, cy - hh, cx + hw, cy - hh, cx + hw, cy + hh, cx - hw, cy + hh, r, color)
    }

    /** Rotated sprite: local box [−hw, hw]×[−hh, hh] rotated by `angle` about (cx, cy). */
    fun spriteRot(cx: Float, cy: Float, hw: Float, hh: Float, angle: Float, r: Region, color: Int) {
        val c = cos(angle); val s = sin(angle)
        val ax = c * hw; val ay = s * hw; val bx = -s * hh; val by = c * hh
        quad(cx - ax - bx, cy - ay - by, cx + ax - bx, cy + ay - by, cx + ax + bx, cy + ay + by, cx - ax + bx, cy - ay + by, r, color)
    }

    /** Sprite placed in a local frame (lx, ly, half extents) transformed by rotation (c, s) and translation (tx, ty). */
    fun spriteLocal(tx: Float, ty: Float, c: Float, s: Float, lx: Float, ly: Float, hw: Float, hh: Float, r: Region, color: Int) {
        val ax = lx - hw; val bx = lx + hw; val ay = ly - hh; val by = ly + hh
        quad(tx + ax * c - ay * s, ty + ax * s + ay * c, tx + bx * c - ay * s, ty + bx * s + ay * c,
            tx + bx * c - by * s, ty + bx * s + by * c, tx + ax * c - by * s, ty + ax * s + by * c, r, color)
    }

    /** Soft line segment (round-ish ends) of the given width. */
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int) {
        var dx = x1 - x0; var dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        val hw = width * 0.9f
        if (len < 1e-4f) { sprite(x0, y0, hw, hw, atlas.dot, color); return }
        dx /= len; dy /= len
        val nx = -dy * hw; val ny = dx * hw
        val ex = dx * hw * 0.5f; val ey = dy * hw * 0.5f
        quad(x0 - ex + nx, y0 - ey + ny, x1 + ex + nx, y1 + ey + ny, x1 + ex - nx, y1 + ey - ny, x0 - ex - nx, y0 - ey - ny, atlas.line, color)
    }

    /** Solid filled triangle. */
    fun tri(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        if (color ushr 24 == 0) return
        ensure(3, 3)
        val b = nv
        val r = atlas.white
        val u = (r.u0 + r.u1) / 2; val t = (r.v0 + r.v1) / 2
        v(x0, y0, u, t, color); v(x1, y1, u, t, color); v(x2, y2, u, t, color)
        indices[ni++] = b.toShort(); indices[ni++] = (b + 1).toShort(); indices[ni++] = (b + 2).toShort()
    }

    /** Solid axis-aligned rectangle. */
    fun rect(x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        val r = atlas.white
        if (color ushr 24 == 0) return
        ensure(4, 6)
        val b = nv
        val u = (r.u0 + r.u1) / 2; val t = (r.v0 + r.v1) / 2
        v(x0, y0, u, t, color); v(x1, y0, u, t, color); v(x1, y1, u, t, color); v(x0, y1, u, t, color)
        indices[ni++] = b.toShort(); indices[ni++] = (b + 1).toShort(); indices[ni++] = (b + 2).toShort()
        indices[ni++] = b.toShort(); indices[ni++] = (b + 2).toShort(); indices[ni++] = (b + 3).toShort()
    }
}
