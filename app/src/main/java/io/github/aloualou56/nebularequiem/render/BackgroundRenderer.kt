package io.github.aloualou56.nebularequiem.render

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import io.github.aloualou56.nebularequiem.core.Audio
import io.github.aloualou56.nebularequiem.core.Bg
import io.github.aloualou56.nebularequiem.core.ColorUtil
import io.github.aloualou56.nebularequiem.core.Lattice
import io.github.aloualou56.nebularequiem.core.Light
import io.github.aloualou56.nebularequiem.core.NebulaBuilder
import io.github.aloualou56.nebularequiem.core.NebulaPalette
import io.github.aloualou56.nebularequiem.core.Pal
import io.github.aloualou56.nebularequiem.core.Quality
import io.github.aloualou56.nebularequiem.core.Rng
import io.github.aloualou56.nebularequiem.core.ValueNoise
import io.github.aloualou56.nebularequiem.core.World
import io.github.aloualou56.nebularequiem.core.clamp
import io.github.aloualou56.nebularequiem.core.lerp
import io.github.aloualou56.nebularequiem.core.mod
import io.github.aloualou56.nebularequiem.core.smoothstep
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * §7b BACKGROUND rendering: a domain-warped fBm nebula (built on a worker thread so a sector warp
 * swaps in a ready-made image instead of stalling a frame), a demoscene plasma field, three
 * parallax star layers, plus the lighting grid and spacetime lattice overlays.
 */
class BackgroundRenderer(private val atlas: Atlas, private val batch: SpriteBatch) : NebulaBuilder {
    private var nebula: Bitmap? = null
    private var nebulaKey = ""
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "nebula-builder").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 } }
    private var pending: Future<Bitmap>? = null
    private var pendingKey = ""

    private val plasmaW = 72; private val plasmaH = 40
    private val plasmaPx = IntArray(plasmaW * plasmaH)
    private val plasma: Bitmap = Bitmap.createBitmap(plasmaW, plasmaH, Bitmap.Config.ARGB_8888)
    private var plasmaAt = -1e9

    private var lightBmp: Bitmap? = null
    private var lightGen = -1
    private var lineBuf = FloatArray(4096)

    private val fill = Paint().apply { isFilterBitmap = true }
    private val add = Paint().apply { isFilterBitmap = true; blendMode = BlendMode.PLUS }
    private val screen = Paint().apply { isFilterBitmap = true; blendMode = BlendMode.SCREEN }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; blendMode = BlendMode.PLUS; strokeWidth = 1f }
    private val rect = RectF()

    private fun key(seed: Int, pal: NebulaPalette) = "$seed/${pal.name}/${World.w.roundToInt()}x${World.h.roundToInt()}"

    override fun prepare(seed: Int, palette: NebulaPalette) {
        val k = key(seed, palette)
        if (k == pendingKey && pending != null) return
        val w = World.w; val h = World.h
        pendingKey = k
        pending = worker.submit<Bitmap> { build(seed, palette, w, h) }
    }

    override fun adopt(seed: Int, palette: NebulaPalette, sync: Boolean) {
        val k = key(seed, palette)
        if (k == nebulaKey && nebula != null) return
        val job = pending
        val bmp = if (k == pendingKey && job != null) {
            // Finishes here only if the warp outran the background build.
            try { job.get() } catch (e: Exception) { build(seed, palette, World.w, World.h) }
        } else build(seed, palette, World.w, World.h)
        if (k == pendingKey) { pending = null; pendingKey = "" }
        nebula = bmp
        nebulaKey = k
    }

    /** Domain-warped fractal noise (Inigo Quilez): q = (fbm(p), fbm(p+(5.2,1.3))), n = fbm(p + 3.4q). */
    fun build(seed: Int, pal: NebulaPalette, w: Double, h: Double): Bitmap {
        val aspect = w / h
        val NW = 300; val NH = max(80, (NW / aspect).roundToInt())
        val noise = ValueNoise(seed)
        val A = pal.a; val B = pal.b; val C = pal.c; val Z = pal.base
        val px = IntArray(NW * NH)
        val sc = 3.1
        for (j in 0 until NH) {
            val wash = 0.18 + 0.12 * sin((j.toDouble() / NH) * PI)
            for (i in 0 until NW) {
                val x = (i.toDouble() / NW) * sc * aspect; val y = (j.toDouble() / NH) * sc
                val qx = noise.fbm2(x, y, 4); val qy = noise.fbm2(x + 5.2, y + 1.3, 4)
                val n = noise.fbm2(x + 3.4 * qx, y + 3.4 * qy, 5)
                val lane = smoothstep(0.52, 0.72, noise.fbm2(x * 2.3 + 11.7, y * 2.3 + 4.1, 3))
                var dens = smoothstep(0.36, 0.86, n) * (1 - 0.7 * lane)
                dens = dens.pow(1.25)
                val tAB = clamp(qx * 1.6 - 0.3, 0.0, 1.0); val hot = smoothstep(0.62, 0.95, n) * (1 - lane)
                var r = lerp(ColorUtil.r(A).toDouble(), ColorUtil.r(B).toDouble(), tAB)
                var g = lerp(ColorUtil.g(A).toDouble(), ColorUtil.g(B).toDouble(), tAB)
                var b = lerp(ColorUtil.b(A).toDouble(), ColorUtil.b(B).toDouble(), tAB)
                r = lerp(r, ColorUtil.r(C).toDouble(), hot * 0.5); g = lerp(g, ColorUtil.g(C).toDouble(), hot * 0.5); b = lerp(b, ColorUtil.b(C).toDouble(), hot * 0.5)
                val R = (ColorUtil.r(Z) + r * (dens * 0.78 + wash * 0.08)).toInt().coerceIn(0, 255)
                val G = (ColorUtil.g(Z) + g * (dens * 0.78 + wash * 0.08)).toInt().coerceIn(0, 255)
                val Bc = (ColorUtil.b(Z) + b * (dens * 0.78 + wash * 0.12)).toInt().coerceIn(0, 255)
                px[j * NW + i] = (0xFF shl 24) or (R shl 16) or (G shl 8) or Bc
            }
        }
        val small = Bitmap.createBitmap(px, NW, NH, Bitmap.Config.ARGB_8888)
        // Two-step upscale plus a light blur hides bilinear diamonds in the low-res field.
        val W2 = min(1400.0, w * (1 + 2 * Bg.margin) * 0.75).roundToInt().coerceAtLeast(64)
        val H2 = (W2 / aspect).roundToInt().coerceAtLeast(32)
        val mid = Bitmap.createScaledBitmap(small, W2 / 2, H2 / 2, true)
        val big = Bitmap.createScaledBitmap(mid, W2, H2, true).copy(Bitmap.Config.ARGB_8888, true)
        small.recycle(); mid.recycle()
        blur(big, 2)
        // A few bright stellar nurseries burnt into the gas.
        val c = Canvas(big)
        val lr = Rng(seed * 31 + 7)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
        val cols = intArrayOf(pal.b, pal.c, pal.a)
        for (i in 0 until 5) {
            val gx = (lr.range(0.1, 0.9) * W2).toFloat(); val gy = (lr.range(0.1, 0.9) * H2).toFloat(); val gr = (lr.range(0.08, 0.2) * W2).toFloat()
            val col = lr.pick(cols)
            p.shader = RadialGradient(gx, gy, gr, intArrayOf(ColorUtil.withAlpha(col, 0.22), ColorUtil.withAlpha(col, 0.0)), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W2.toFloat(), H2.toFloat(), p)
        }
        big.prepareToDraw()
        return big
    }

    /** Separable box blur, `passes` iterations of radius 1 (≈ Gaussian). */
    private fun blur(bmp: Bitmap, passes: Int) {
        val w = bmp.width; val h = bmp.height
        val a = IntArray(w * h); val t = IntArray(w * h)
        bmp.getPixels(a, 0, w, 0, 0, w, h)
        repeat(passes) {
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val l = a[row + max(0, x - 1)]; val m = a[row + x]; val r = a[row + min(w - 1, x + 1)]
                    t[row + x] = avg3(l, m, r)
                }
            }
            for (y in 0 until h) {
                val up = max(0, y - 1) * w; val row = y * w; val dn = min(h - 1, y + 1) * w
                for (x in 0 until w) a[row + x] = avg3(t[up + x], t[row + x], t[dn + x])
            }
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h)
    }

    private fun avg3(a: Int, b: Int, c: Int): Int {
        val r = (((a shr 16) and 255) + ((b shr 16) and 255) * 2 + ((c shr 16) and 255)) shr 2
        val g = (((a shr 8) and 255) + ((b shr 8) and 255) * 2 + ((c shr 8) and 255)) shr 2
        val bl = ((a and 255) + (b and 255) * 2 + (c and 255)) shr 2
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** Classic demoscene plasma: Σ sin(...) of four phase-shifted fields through a thin "vein" transfer. */
    fun updatePlasma(t: Double, energy: Double, nowMs: Double) {
        if (World.quality == Quality.LOW || nowMs - plasmaAt < 15) return
        plasmaAt = nowMs
        val pl = Bg.palette.plasma
        val tr = pl[0]; val tg = pl[1]; val tb = pl[2]
        val cx = 3.6 + 2.2 * sin(t * 0.21); val cy = 2 + 1.4 * cos(t * 0.17)
        for (j in 0 until plasmaH) {
            val y = (j.toDouble() / plasmaH) * 4
            for (i in 0 until plasmaW) {
                val x = (i.toDouble() / plasmaW) * 7.2
                val dx = x - cx; val dy = y - cy
                val v = sin(x * 1.3 + t * 0.9) + sin(y * 1.9 - t * 0.7) + sin((x + y) * 0.8 + t * 0.55) + sin(sqrt(dx * dx + dy * dy) * 2.1 - t * 1.6)
                val s = 0.5 + 0.5 * sin(v * PI)
                val s2 = s * s; val vein = s2 * s2 * s2 * energy
                val r = (255 * vein * tr).toInt().coerceIn(0, 255); val g = (255 * vein * tg).toInt().coerceIn(0, 255); val b = (255 * vein * tb).toInt().coerceIn(0, 255)
                plasmaPx[j * plasmaW + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        plasma.setPixels(plasmaPx, 0, plasmaW, 0, 0, plasmaW, plasmaH)
    }

    /** Draw in surface-pixel space (identity transform). Parallax: layer offset = −(focus − centre)·f. */
    fun draw(c: Canvas, plasmaAlpha: Double) {
        val S = World.scale.toFloat(); val W = World.pxW.toFloat(); val H = World.pxH.toFloat()
        val pal = Bg.palette
        c.drawColor(pal.base)
        val fx = Bg.focusX - World.w / 2; val fy = Bg.focusY - World.h / 2
        val neb = nebula
        if (neb != null) {
            val m = Bg.margin; val f = 0.05
            val ox = (clamp(-fx * f, -World.w * m, World.w * m) * S).toFloat()
            val oy = (clamp(-fy * f, -World.h * m, World.h * m) * S).toFloat()
            val breathe = (1 + 0.01 * sin(Bg.time * 0.2) + 0.008 * Audio.pulse).toFloat()
            val dw = W * (1 + 2 * m).toFloat() * breathe; val dh = H * (1 + 2 * m).toFloat() * breathe
            rect.set((W - dw) / 2 + ox, (H - dh) / 2 + oy, (W + dw) / 2 + ox, (H + dh) / 2 + oy)
            fill.alpha = 255
            c.drawBitmap(neb, null, rect, fill)
            if (Bg.dim > 0) c.drawColor(ColorUtil.withAlpha(pal.base, Bg.dim))
        }
        if (plasmaAlpha > 0.003 && World.quality != Quality.LOW) {
            screen.alpha = (clamp(plasmaAlpha, 0.0, 1.0) * 255).toInt()
            rect.set(0f, 0f, W, H)
            c.drawBitmap(plasma, null, rect, screen)
        }
        // stars
        batch.begin(c, BlendMode.PLUS)
        val warp = Bg.warp
        for (layer in Bg.stars) {
            val lox = -fx * layer.f; val loy = -fy * layer.f + Bg.driftY * layer.f
            val streak = (warp * 160 * layer.f * S).toFloat()
            for (s in layer.list) {
                val x = (mod(s.x + lox, World.w) * S).toFloat(); val y = (mod(s.y + loy, World.h) * S).toFloat()
                val tw = 0.7 + 0.3 * sin(Bg.time * s.tw + s.ph)
                val col = batch.argb(s.c, (s.a * tw).toFloat())
                if (streak > 1.5f) batch.line(x, y, x, y - streak, (s.s * S).toFloat(), col)
                else if (layer.glow) { val g = (s.s * S * 4).toFloat(); batch.sprite(x, y, g, g, atlas.glow, col) }
                else { val z = (s.s * S / 2).toFloat(); batch.rect(x - z, y - z, x + z, y + z, col) }
            }
        }
        batch.end()
    }

    /** Lighting grid: the tone-mapped field upscaled bilinearly across the arena (world space). */
    fun drawLight(c: Canvas, alpha: Double) {
        val cols = Light.cols; val rows = Light.rows
        var bmp = lightBmp
        if (bmp == null || bmp.width != cols || bmp.height != rows) {
            bmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888); lightBmp = bmp; lightGen = -1
        }
        if (lightGen != Light.generation && Light.pixels.size == cols * rows) { bmp.setPixels(Light.pixels, 0, cols, 0, 0, cols, rows); lightGen = Light.generation }
        val cell = Light.cell.toFloat()
        // Cell (i,j) is centred at world ((i−1)·cell, (j−1)·cell); offset half a cell so texel centres land there.
        rect.set(-cell * 1.5f, -cell * 1.5f, cols * cell - cell * 1.5f, rows * cell - cell * 1.5f)
        add.alpha = (clamp(alpha, 0.0, 1.0) * 255).toInt()
        c.drawBitmap(bmp, null, rect, add)
    }

    /** Spacetime lattice (world space): strained segments (|u| > 10) get a second, hot pass. */
    fun drawLattice(c: Canvas, alpha: Double) {
        val cols = Lattice.cols; val rows = Lattice.rows
        if (cols == 0) return
        val rx = Lattice.rx; val ry = Lattice.ry; val ux = Lattice.ux; val uy = Lattice.uy
        val segs = (rows * (cols - 1) + cols * (rows - 1)) * 4
        if (lineBuf.size < segs) lineBuf = FloatArray(segs)
        var n = 0
        val b = lineBuf
        for (j in 0 until rows) for (i in 0 until cols - 1) {
            val k = j * cols + i
            b[n++] = rx[k] + ux[k]; b[n++] = ry[k] + uy[k]; b[n++] = rx[k + 1] + ux[k + 1]; b[n++] = ry[k + 1] + uy[k + 1]
        }
        for (i in 0 until cols) for (j in 0 until rows - 1) {
            val k = j * cols + i
            b[n++] = rx[k] + ux[k]; b[n++] = ry[k] + uy[k]; b[n++] = rx[k + cols] + ux[k + cols]; b[n++] = ry[k + cols] + uy[k + cols]
        }
        linePaint.color = Lattice.color
        linePaint.alpha = (clamp(alpha, 0.0, 1.0) * 255).toInt()
        linePaint.strokeWidth = 1f
        c.drawLines(b, 0, n, linePaint)
        n = 0
        for (j in 0 until rows) for (i in 0 until cols) {
            val k = j * cols + i
            if (ux[k] * ux[k] + uy[k] * uy[k] < 100f) continue
            val x = rx[k] + ux[k]; val y = ry[k] + uy[k]
            if (i + 1 < cols) { b[n++] = x; b[n++] = y; b[n++] = rx[k + 1] + ux[k + 1]; b[n++] = ry[k + 1] + uy[k + 1] }
            if (j + 1 < rows) { b[n++] = x; b[n++] = y; b[n++] = rx[k + cols] + ux[k + cols]; b[n++] = ry[k + cols] + uy[k + cols] }
        }
        if (n > 0) {
            linePaint.color = Pal.ION
            linePaint.alpha = (min(0.35, alpha * 3) * 255).toInt()
            linePaint.strokeWidth = 1.4f
            c.drawLines(b, 0, n, linePaint)
        }
    }
}
