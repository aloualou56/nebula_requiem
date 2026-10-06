package io.github.aloualou56.nebularequiem.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import io.github.aloualou56.nebularequiem.core.Audio
import io.github.aloualou56.nebularequiem.core.Cam
import io.github.aloualou56.nebularequiem.core.ColorUtil
import io.github.aloualou56.nebularequiem.core.Env
import io.github.aloualou56.nebularequiem.core.Game
import io.github.aloualou56.nebularequiem.core.GameState
import io.github.aloualou56.nebularequiem.core.Input
import io.github.aloualou56.nebularequiem.core.AimSource
import io.github.aloualou56.nebularequiem.core.Lattice
import io.github.aloualou56.nebularequiem.core.Light
import io.github.aloualou56.nebularequiem.core.Pal
import io.github.aloualou56.nebularequiem.core.PostFx
import io.github.aloualou56.nebularequiem.core.Quality
import io.github.aloualou56.nebularequiem.core.Rng
import io.github.aloualou56.nebularequiem.core.Save
import io.github.aloualou56.nebularequiem.core.World
import io.github.aloualou56.nebularequiem.core.clamp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Frame compositor: background (screen space) → world (camera space) → post-processing → the
 * optical overlays (vignette, hurt and overdrive washes, film grain
 * and scanlines). The UI/HUD is drawn on top by the caller.
 */
class Renderer {
    val atlas = Atlas()
    val batch = SpriteBatch(atlas)
    val background = BackgroundRenderer(atlas, batch)
    val world = WorldRenderer(atlas, batch, background)
    private val post = PostProcessor()
    private val view = Matrix()
    private val mv = FloatArray(9)
    private val renderRng = Rng(99)
    private var glitchTick = -1L
    private var glitchRnd = 0.0
    var reducedMotion = false

    private val flashPaint = Paint().apply { blendMode = BlendMode.PLUS }
    private val overlay = Paint()
    private var overlayW = -1; private var overlayH = -1
    private var vignette: Shader? = null; private var hurt: Shader? = null; private var od: Shader? = null
    private val grainBmp: Bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).also { b ->
        val r = Rng(4242); val px = IntArray(160 * 160) { val v = (r.next() * 255).toInt(); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        b.setPixels(px, 0, 160, 0, 0, 160, 160)
    }
    private val grainShader = BitmapShader(grainBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    private val scanBmp: Bitmap = Bitmap.createBitmap(1, 3, Bitmap.Config.ARGB_8888).also { it.setPixel(0, 0, 0x09FFFFFF); it.setPixel(0, 1, 0); it.setPixel(0, 2, 0) }
    private val scanShader = BitmapShader(scanBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    private val grainMatrix = Matrix()

    /** Per-frame presentation updates: lights, lattice, plasma. */
    fun prepare(dt: Double) {
        val frozen = Game.state == GameState.PAUSED || Game.state == GameState.DRAFT
        val now = Env.now()
        if (!frozen) {
            Game.gatherFields()
            Light.upload(dt)
            Lattice.update(dt * Game.timeScale)
        } else Lattice.clearWells()
        val bossE = if (Game.boss != null && !Game.boss!!.dead) 0.7 else 0.0
        val odE = if (Game.player != null && Game.player!!.overdrive > 0) 0.6 else 0.0
        val energy = 0.55 + bossE + odE + Audio.pulse * 0.6
        background.updatePlasma(Game.time * (if (frozen) 0.0 else 1.0) + now / 4000, min(1.6, energy), now)
    }

    fun drawWorld(c: Canvas) {
        val s = Save.data.settings
        Cam.build(s.shake * if (reducedMotion) 0.35 else 1.0)
        val bossE = if (Game.boss != null && !Game.boss!!.dead) 0.7 else 0.0
        val odE = if (Game.player != null && Game.player!!.overdrive > 0) 0.6 else 0.0
        val eclipse = if (Game.director.has("eclipse") && Game.run != null) 0.5 else 1.0
        val q = World.quality
        val wantBloom = s.bloom && q == Quality.HIGH
        // chromatic aberration
        var caK = 0f; var caShift = 0f
        if (s.aberration && q != Quality.LOW && !(Input.touchMode && q == Quality.MEDIUM)) {
            val constant = q == Quality.HIGH && !Input.touchMode
            val dpr = World.density * World.renderScale
            val base = if (constant) 0.6 + Audio.pulse * 1.4 else 0.0
            val edge = (base + PostFx.caPulse) * dpr
            val tick = floor(Env.now() / (1000.0 / 60)).toLong()
            if (tick != glitchTick) { glitchTick = tick; glitchRnd = renderRng.range(-1.0, 1.0) }
            val shift = if (PostFx.glitch > 0) glitchRnd * PostFx.glitch * 10 * dpr else 0.0
            if (edge > (if (constant) 0.6 else 2.0) || abs(shift) > 0.5) { caK = (edge / (World.pxW / 2.0)).toFloat(); caShift = shift.toFloat() }
        }
        val rs = World.renderScale.toFloat()
        val usePost = c.isHardwareAccelerated && post.supported && (wantBloom || caK > 0f || caShift != 0f || rs < 0.999f)
        if (usePost) {
            val sc = post.beginScene(World.pxW, World.pxH, rs)
            // A RenderNode left recording would make every later beginRecording throw.
            try { drawScene(sc, (0.13 + bossE * 0.18 + odE * 0.2 + Audio.pulse * 0.08) * eclipse) } finally { post.endScene() }
            val sigma = if (wantBloom) (4.5 * World.density * World.renderScale).toFloat().coerceIn(3f, 12f) else 0f
            post.composite(c, sigma, caK, caShift)
        } else drawScene(c, (0.13 + bossE * 0.18 + odE * 0.2 + Audio.pulse * 0.08) * eclipse)

        if (PostFx.flashA > 0.01) {
            flashPaint.color = PostFx.flashColor; flashPaint.alpha = (min(1.0, PostFx.flashA) * 255).toInt()
            c.drawRect(0f, 0f, World.pxW.toFloat(), World.pxH.toFloat(), flashPaint)
        }
        drawOverlays(c)
    }

    private fun drawScene(c: Canvas, plasmaAlpha: Double) {
        background.draw(c, plasmaAlpha)
        val m = Cam.matrix
        mv[0] = m.a.toFloat(); mv[1] = m.c.toFloat(); mv[2] = m.e.toFloat()
        mv[3] = m.b.toFloat(); mv[4] = m.d.toFloat(); mv[5] = m.f.toFloat()
        mv[6] = 0f; mv[7] = 0f; mv[8] = 1f
        view.setValues(mv)
        c.save()
        c.concat(view)
        world.draw(c)
        c.restore()
        if (Game.state == GameState.PLAYING && !Input.touchMode && Input.lastAimSource == AimSource.MOUSE && Input.mouseInside) {
            val d = World.density.toFloat()
            world.drawCrosshair(c, (Input.mouseX * World.density).toFloat(), (Input.mouseY * World.density).toFloat(), d, Env.now() / 1000)
        }
    }

    private fun buildOverlays(w: Int, h: Int) {
        overlayW = w; overlayH = h
        fun ellipse(rxFrac: Float, ryFrac: Float, colors: IntArray, stops: FloatArray): Shader {
            val g = RadialGradient(0f, 0f, 1f, colors, stops, Shader.TileMode.CLAMP)
            val m = Matrix(); m.setScale(w * rxFrac, h * ryFrac); m.postTranslate(w / 2f, h / 2f)
            g.setLocalMatrix(m)
            return g
        }
        // radial-gradient(ellipse 120% 95%, transparent 52%, rgb(2 0 8 / .78) 100%)
        vignette = ellipse(1.2f, 0.95f, intArrayOf(0x00020008, 0x00020008, 0xC7020008.toInt()), floatArrayOf(0f, 0.52f, 1f))
        // radial-gradient(ellipse 110% 90%, transparent 45%, crimson / .9 at 120%)
        hurt = ellipse(1.1f * 1.2f, 0.9f * 1.2f, intArrayOf(0x00FF3B5C, 0x00FF3B5C, ColorUtil.withAlpha(Pal.CRIMSON, 0.9)), floatArrayOf(0f, 0.45f / 1.2f, 1f))
        // radial-gradient(ellipse 120% 100%, transparent 40%, ion / .55 at 125%)
        od = ellipse(1.2f * 1.25f, 1.0f * 1.25f, intArrayOf(0x003DF2FF, 0x003DF2FF, ColorUtil.withAlpha(Pal.ION, 0.55)), floatArrayOf(0f, 0.4f / 1.25f, 1f))
    }

    private fun drawOverlays(c: Canvas) {
        val w = World.pxW; val h = World.pxH
        if (w != overlayW || h != overlayH) buildOverlays(w, h)
        val W = w.toFloat(); val H = h.toFloat()
        val touch = Input.touchMode
        // Film grain and scanlines: desktop-style (keyboard/mouse) play on medium/high only.
        if (!touch && World.quality != Quality.LOW) {
            val step = floor(Env.now() / 100.0).toInt() % 6
            val ox = GRAIN_X[step] * 1.6f * World.density.toFloat(); val oy = GRAIN_Y[step] * 1.6f * World.density.toFloat()
            grainMatrix.setScale(World.density.toFloat(), World.density.toFloat()); grainMatrix.postTranslate(ox, oy)
            grainShader.setLocalMatrix(grainMatrix)
            overlay.shader = grainShader; overlay.blendMode = BlendMode.OVERLAY; overlay.alpha = 19
            c.drawRect(0f, 0f, W, H, overlay)
            grainMatrix.setScale(World.density.toFloat(), World.density.toFloat())
            scanShader.setLocalMatrix(grainMatrix)
            overlay.shader = scanShader; overlay.alpha = 179
            c.drawRect(0f, 0f, W, H, overlay)
        }
        overlay.blendMode = BlendMode.SRC_OVER; overlay.alpha = 255
        overlay.shader = vignette
        c.drawRect(0f, 0f, W, H, overlay)
        val p = Game.player
        val odA = if (p != null && p.overdrive > 0) min(1.0, p.overdrive) * 0.8 else 0.0
        if (odA > 0.005) {
            overlay.shader = od; overlay.blendMode = if (touch) BlendMode.SRC_OVER else BlendMode.SCREEN; overlay.alpha = (clamp(odA, 0.0, 1.0) * 255).toInt()
            c.drawRect(0f, 0f, W, H, overlay)
        }
        if (Game.hurtFade > 0.005) {
            overlay.shader = hurt; overlay.blendMode = if (touch) BlendMode.SRC_OVER else BlendMode.SCREEN; overlay.alpha = (clamp(Game.hurtFade, 0.0, 1.0) * 255).toInt()
            c.drawRect(0f, 0f, W, H, overlay)
        }
        overlay.shader = null; overlay.blendMode = BlendMode.SRC_OVER; overlay.alpha = 255
    }

    companion object {
        // the grain-shift keyframes: translate (%, %) of the 160 px tile, six steps per 0.6 s
        private val GRAIN_X = intArrayOf(0, -7, 5, -3, 8, -6)
        private val GRAIN_Y = intArrayOf(0, 4, -6, 8, 2, -4)
    }
}
