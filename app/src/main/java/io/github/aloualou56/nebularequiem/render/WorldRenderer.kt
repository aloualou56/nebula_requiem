package io.github.aloualou56.nebularequiem.render

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import io.github.aloualou56.nebularequiem.core.AegisWarden
import io.github.aloualou56.nebularequiem.core.Audio
import io.github.aloualou56.nebularequiem.core.CometStreak
import io.github.aloualou56.nebularequiem.core.AutomatonAugur
import io.github.aloualou56.nebularequiem.core.BOSS_DEFS
import io.github.aloualou56.nebularequiem.core.Boss
import io.github.aloualou56.nebularequiem.core.ColorUtil
import io.github.aloualou56.nebularequiem.core.DartLancer
import io.github.aloualou56.nebularequiem.core.Ease
import io.github.aloualou56.nebularequiem.core.Enemy
import io.github.aloualou56.nebularequiem.core.EntropyEngine
import io.github.aloualou56.nebularequiem.core.EulerEidolon
import io.github.aloualou56.nebularequiem.core.FourierOrrery
import io.github.aloualou56.nebularequiem.core.FourierSeries
import io.github.aloualou56.nebularequiem.core.FractalSeraph
import io.github.aloualou56.nebularequiem.core.Fx
import io.github.aloualou56.nebularequiem.core.Game
import io.github.aloualou56.nebularequiem.core.GyreTurret
import io.github.aloualou56.nebularequiem.core.HarbingerHeavy
import io.github.aloualou56.nebularequiem.core.HelixCantor
import io.github.aloualou56.nebularequiem.core.HiveCarrier
import io.github.aloualou56.nebularequiem.core.HullDef
import io.github.aloualou56.nebularequiem.core.Laser
import io.github.aloualou56.nebularequiem.core.Light
import io.github.aloualou56.nebularequiem.core.LissajousLeviathan
import io.github.aloualou56.nebularequiem.core.MandelbrotMatriarch
import io.github.aloualou56.nebularequiem.core.MitosisCell
import io.github.aloualou56.nebularequiem.core.PHI
import io.github.aloualou56.nebularequiem.core.PKind
import io.github.aloualou56.nebularequiem.core.PLayer
import io.github.aloualou56.nebularequiem.core.Pal
import io.github.aloualou56.nebularequiem.core.PenrosePentarch
import io.github.aloualou56.nebularequiem.core.PhantomWisp
import io.github.aloualou56.nebularequiem.core.PrismShard
import io.github.aloualou56.nebularequiem.core.PulsarStar
import io.github.aloualou56.nebularequiem.core.PickupKind
import io.github.aloualou56.nebularequiem.core.Player
import io.github.aloualou56.nebularequiem.core.SeerEye
import io.github.aloualou56.nebularequiem.core.Shape
import io.github.aloualou56.nebularequiem.core.ShotKind
import io.github.aloualou56.nebularequiem.core.TAU
import io.github.aloualou56.nebularequiem.core.TrefoilHierophant
import io.github.aloualou56.nebularequiem.core.VortexCoil
import io.github.aloualou56.nebularequiem.core.clamp
import io.github.aloualou56.nebularequiem.core.lerp
import io.github.aloualou56.nebularequiem.core.lerpAngle
import io.github.aloualou56.nebularequiem.core.mod
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Precomputed dash effects for an animated line-dash offset (no allocation per frame). */
class DashCache(on: Float, off: Float, private val steps: Int = 24) {
    private val period = on + off
    private val effects = Array(steps) { DashPathEffect(floatArrayOf(on, off), period * it / steps) }
    fun at(offset: Double): DashPathEffect {
        // Canvas lineDashOffset shifts the pattern backwards; DashPathEffect phase does the same.
        val p = mod(offset, period.toDouble()) / period
        return effects[(p * steps).toInt().coerceIn(0, steps - 1)]
    }
}

/**
 * Draws everything in world space (the camera matrix is already on the canvas): lighting grid,
 * lattice, pickups, particles, hostiles, guardians, lasers,
 * shots, the player, the nova and enemy bullets. Positions are interpolated between the last two
 * fixed simulation steps with [Game.alpha].
 */
class WorldRenderer(private val atlas: Atlas, private val batch: SpriteBatch, private val bg: BackgroundRenderer) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val addFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; blendMode = BlendMode.PLUS }
    private val addStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; blendMode = BlendMode.PLUS }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Fonts.mono700 }
    private val textStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Fonts.mono700; style = Paint.Style.STROKE; strokeWidth = 3f; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val path2 = Path()
    private val oval = RectF()
    private val lines = FloatArray(256)
    private val polyCache = HashMap<FloatArray, Path>()
    private val halfPolyCache = HashMap<FloatArray, Path>()
    private val kochPath: Path = closedPath(FractalSeraph.KOCH, 1f)
    private val fourierStar = seriesPath(FourierOrrery.STAR)
    private val fourierSquare = seriesPath(FourierOrrery.SQUARE)
    private val gearPath = gear(14)
    private val penrose3 = penrose(3)
    private val penrose4 = penrose(4)
    private val pentagram = Path().apply { for (i in 0 until 5) { val a = -PI / 2 + (i * 2 % 5) * TAU / 5; if (i == 0) moveTo(cos(a).toFloat(), sin(a).toFloat()) else lineTo(cos(a).toFloat(), sin(a).toFloat()) }; close() }
    private val decagon = Path().apply { for (i in 0 until 10) { val a = (2 * i - 1) * PI / 10; if (i == 0) moveTo(cos(a).toFloat(), sin(a).toFloat()) else lineTo(cos(a).toFloat(), sin(a).toFloat()) }; close() }
    private val mandelCardioid = Path(); private val mandelBody = Path(); private val mandelAntenna = Path()
    private val chipPath = chip()
    private val sigils: Array<Path> = sigilPaths()
    private val clipPath = Path()
    private val fjx = DoubleArray(6); private val fjy = DoubleArray(6)
    private val hexPath = Path().apply { for (k in 0 until 6) { val a = k / 6.0 * TAU; val x = (cos(a) * 1.15).toFloat(); val y = (sin(a) * 0.8).toFloat(); if (k == 0) moveTo(x, y) else lineTo(x, y) }; close() }
    private val hullShaders = HashMap<String, LinearGradient>()
    private val shaderMatrix = Matrix()

    private val dashElite = DashCache(5f, 7f)
    private val dashAim = DashCache(10f, 8f)
    private val dashLaser = DashCache(14f, 10f)
    private val dashShield = DashCache(10f, 5f)
    private val dashHorizon = DashCache(3f, 9f)
    private val dashCorona = arrayOf(DashCache(8f, 12f), DashCache(14f, 16f), DashCache(20f, 20f))

    private var a = 0f   // interpolation alpha
    private fun ix(p: Double, x: Double): Float = (p + (x - p) * a).toFloat()

    private fun closedPath(pts: FloatArray, scale: Float): Path {
        val p = Path()
        val n = pts.size / 2
        for (i in 0 until n) { val x = pts[i * 2] * scale; val y = pts[i * 2 + 1] * scale; if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
        p.close()
        return p
    }

    /* ── unit-space shapes for guardians V–X, built once ── */

    /** A Fourier series' whole curve (unit amplitude), as the pen traces it. */
    private fun seriesPath(s: FourierSeries): Path {
        val p = Path()
        for (i in 0..240) {
            val th = i / 240.0 * TAU
            var x = 0.0; var y = 0.0
            for (j in s.k.indices) { x += s.a[j] * cos(s.k[j] * th); y += s.a[j] * sin(s.k[j] * th) }
            if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
        }
        p.close()
        return p
    }

    /** A clockwork gear of radius 1 with [teeth] square teeth. */
    private fun gear(teeth: Int): Path {
        val p = Path()
        for (i in 0 until teeth * 4) {
            val a = i * TAU / (teeth * 4) - TAU / (teeth * 8)
            val rr = if ((i / 2) % 2 == 0) 1.0 else 0.84
            if (i == 0) p.moveTo((cos(a) * rr).toFloat(), (sin(a) * rr).toFloat()) else p.lineTo((cos(a) * rr).toFloat(), (sin(a) * rr).toFloat())
        }
        p.close()
        return p
    }

    private class PenroseShape(val thick: Path, val thin: Path, val edges: Path)

    /**
     * A Penrose rhomb tiling of the unit decagon by Robinson-triangle deflation: a sun of ten thin halves,
     * then [depth] rounds of splitting each half along the golden ratio. Two halves make each rhomb, so
     * only their legs are stroked.
     */
    private fun penrose(depth: Int): PenroseShape {
        var tris = ArrayList<DoubleArray>()   // kind (0 thin, 1 thick), A, B, C
        for (i in 0 until 10) {
            var bx = cos((2 * i - 1) * PI / 10); var by = sin((2 * i - 1) * PI / 10)
            var cx = cos((2 * i + 1) * PI / 10); var cy = sin((2 * i + 1) * PI / 10)
            if (i % 2 == 0) { val tx = bx; bx = cx; cx = tx; val ty = by; by = cy; cy = ty }
            tris.add(doubleArrayOf(0.0, 0.0, 0.0, bx, by, cx, cy))
        }
        repeat(depth) {
            val next = ArrayList<DoubleArray>(tris.size * 3)
            for (t in tris) {
                val ax = t[1]; val ay = t[2]; val bx = t[3]; val by = t[4]; val cx = t[5]; val cy = t[6]
                if (t[0] == 0.0) {
                    val px = ax + (bx - ax) / PHI; val py = ay + (by - ay) / PHI
                    next.add(doubleArrayOf(0.0, cx, cy, px, py, bx, by)); next.add(doubleArrayOf(1.0, px, py, cx, cy, ax, ay))
                } else {
                    val qx = bx + (ax - bx) / PHI; val qy = by + (ay - by) / PHI
                    val rx = bx + (cx - bx) / PHI; val ry = by + (cy - by) / PHI
                    next.add(doubleArrayOf(1.0, rx, ry, cx, cy, ax, ay)); next.add(doubleArrayOf(1.0, qx, qy, rx, ry, bx, by)); next.add(doubleArrayOf(0.0, rx, ry, qx, qy, ax, ay))
                }
            }
            tris = next
        }
        val thick = Path(); val thin = Path(); val edges = Path()
        for (t in tris) {
            val p = if (t[0] == 1.0) thick else thin
            p.moveTo(t[1].toFloat(), t[2].toFloat()); p.lineTo(t[3].toFloat(), t[4].toFloat()); p.lineTo(t[5].toFloat(), t[6].toFloat()); p.close()
            edges.moveTo(t[5].toFloat(), t[6].toFloat()); edges.lineTo(t[1].toFloat(), t[2].toFloat()); edges.lineTo(t[3].toFloat(), t[4].toFloat())
        }
        return PenroseShape(thick, thin, edges)
    }

    /** The Automaton's core: a chip with four pins a side, half-size 1. */
    private fun chip(): Path {
        val p = Path()
        p.addRect(-0.62f, -0.62f, 0.62f, 0.62f, Path.Direction.CW)
        for (i in 0 until 4) {
            val o = -0.45f + i * 0.3f
            p.addRect(o - 0.06f, -0.9f, o + 0.06f, -0.62f, Path.Direction.CW); p.addRect(o - 0.06f, 0.62f, o + 0.06f, 0.9f, Path.Direction.CW)
            p.addRect(-0.9f, o - 0.06f, -0.62f, o + 0.06f, Path.Direction.CW); p.addRect(0.62f, o - 0.06f, 0.9f, o + 0.06f, Path.Direction.CW)
        }
        return p
    }

    /** The Eidolon's nine sigils, one motif per earlier guardian, about radius 1. */
    private fun sigilPaths(): Array<Path> {
        fun curve(n: Int, f: (Double) -> Pair<Double, Double>): Path {
            val p = Path()
            for (i in 0..n) { val (x, y) = f(i.toDouble() / n * TAU); if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat()) }
            return p
        }
        val rose = curve(120) { t -> val r = cos(3 * t); Pair(r * cos(t), r * sin(t)) }
        val lissajous = curve(120) { t -> Pair(0.95 * sin(t), 0.8 * sin(2 * t)) }
        val tesseract = Path().apply {
            addRect(-0.85f, -0.85f, 0.85f, 0.85f, Path.Direction.CW); addRect(-0.42f, -0.42f, 0.42f, 0.42f, Path.Direction.CW)
            moveTo(-0.85f, -0.85f); lineTo(-0.42f, -0.42f); moveTo(0.85f, -0.85f); lineTo(0.42f, -0.42f)
            moveTo(0.85f, 0.85f); lineTo(0.42f, 0.42f); moveTo(-0.85f, 0.85f); lineTo(-0.42f, 0.42f)
        }
        val fourier = Path(fourierStar).apply { val m = Matrix(); m.setScale((1 / FourierOrrery.STAR.rMax).toFloat(), (1 / FourierOrrery.STAR.rMax).toFloat()); transform(m) }
        val trefoil = curve(150) { t -> Pair((sin(t) + 2 * sin(2 * t)) / 3, (2 * cos(2 * t) - cos(t)) / 3 - 0.15) }
        val mandel = curve(80) { t -> val re = cos(t) / 2 - cos(2 * t) / 4; val im = sin(t) / 2 - sin(2 * t) / 4; Pair(im * 1.1, (re + 0.2) * 1.1) }
            .apply { addCircle(0f, (-0.8 * 1.1).toFloat(), (0.25 * 1.1).toFloat(), Path.Direction.CW) }
        val glider = Path().apply {
            for (k in 0 until 5) {
                val gx = (AutomatonAugur.GLIDER[k * 2] - 1).toFloat() * 0.6f; val gy = (AutomatonAugur.GLIDER[k * 2 + 1] - 1).toFloat() * 0.6f
                addRect(gx - 0.24f, gy - 0.24f, gx + 0.24f, gy + 0.24f, Path.Direction.CW)
            }
        }
        return arrayOf(rose, lissajous, closedPath(FractalSeraph.KOCH, 1f), tesseract, fourier, Path(pentagram), trefoil, mandel, glider)
    }

    init {
        // the Mandelbrot set turned head-up about the Matriarch's hub: screen (x, y) = (Im c, Re c − C0)
        val c0 = MandelbrotMatriarch.C0
        for (i in 0..200) {
            val th = i / 200.0 * TAU
            val x = (sin(th) / 2 - sin(2 * th) / 4).toFloat(); val y = (cos(th) / 2 - cos(2 * th) / 4 - c0).toFloat()
            if (i == 0) mandelCardioid.moveTo(x, y) else mandelCardioid.lineTo(x, y)
        }
        mandelCardioid.close()
        mandelBody.addPath(mandelCardioid)
        val b = MandelbrotMatriarch.BULBS
        for (k in 0 until b.size / 4) mandelBody.addCircle(b[k * 4 + 1].toFloat(), (b[k * 4] - c0).toFloat(), b[k * 4 + 2].toFloat(), Path.Direction.CW)
        mandelBody.addCircle(0f, (-1.3125 - c0).toFloat(), 0.0625f, Path.Direction.CW)   // the period-4 bulb on the head
        // the minibrot on the antenna, a tiny cardioid facing home
        for (i in 0..40) {
            val th = i / 40.0 * TAU; val s = 0.034
            val x = ((sin(th) / 2 - sin(2 * th) / 4) * s).toFloat(); val y = ((-(cos(th) / 2 - cos(2 * th) / 4)) * s - 1.755 - c0).toFloat()
            if (i == 0) mandelAntenna.moveTo(x, y) else mandelAntenna.lineTo(x, y)
        }
        mandelAntenna.close()
        mandelAntenna.moveTo(0f, (-1.375 - c0).toFloat()); mandelAntenna.lineTo(0f, (-1.72 - c0).toFloat())
        mandelAntenna.moveTo(0f, (-1.79 - c0).toFloat()); mandelAntenna.lineTo(0f, (-2.0 - c0).toFloat())
    }

    private fun deg(rad: Double): Float = Math.toDegrees(rad).toFloat()

    private fun alpha255(a: Double): Int = (clamp(a, 0.0, 1.0) * 255).toInt()
    private fun Paint.set(color: Int, a: Double): Paint { this.color = color; this.alpha = alpha255(a); return this }

    /** Glow sprite (tinted white falloff). */
    private fun glow(x: Float, y: Float, half: Float, color: Int, a: Double) { batch.sprite(x, y, half, half, atlas.glow, batch.argb(color, a.toFloat())) }
    /** White-hot core sprite: tinted rim + white heart. */
    private fun core(x: Float, y: Float, half: Float, color: Int, a: Double) {
        batch.sprite(x, y, half, half, atlas.coreRim, batch.argb(color, a.toFloat()))
        batch.sprite(x, y, half, half, atlas.coreHot, batch.argb(Pal.WHITE, a.toFloat()))
    }

    fun draw(c: Canvas) {
        a = Game.alpha.toFloat()
        val eclipse = if (Game.director.has("eclipse") && Game.run != null) 0.5 else 1.0
        bg.drawLight(c, 0.6 * eclipse)
        bg.drawLattice(c, 0.07 + Audio.pulse * 0.05 + Audio.beat * 0.04)

        // ambient motes
        batch.begin(c, BlendMode.PLUS)
        for (p in Fx.layer(PLayer.AMBIENT)) {
            val s = (p.size * (1 + 0.6 * Audio.pulse)).toFloat()
            glow(p.x.toFloat(), p.y.toFloat(), s, p.color, (0.18 + 0.16 * sin(p.ph)) * p.depth)
        }
        drawPickups()
        batch.end()

        // smoke (normal blend, beneath ships)
        batch.begin(c, BlendMode.SRC_OVER)
        for (p in Fx.layer(PLayer.UNDER)) {
            val t = p.t; val s = lerp(p.s0, p.s1, Ease.outCubic(t)).toFloat()
            batch.spriteRot(ix(p.px, p.x), ix(p.py, p.y), s, s, p.rot.toFloat(), atlas.smoke, batch.argb(p.color, (p.alpha * (1 - t) * (1 - t)).toFloat()))
        }
        batch.end()

        drawEnemies(c)
        Game.boss?.let { drawBoss(c, it) }
        for (l in Game.lasers) drawLaser(c, l)
        drawShots(c)
        Game.player?.let { drawPlayer(c, it) }
        drawAdditiveParticles(c)
        drawNova(c)
        drawBullets(c)
        drawTexts(c)
    }

    /* ───────────────────────── pickups ───────────────────────── */

    private val pk = FloatArray(32)
    private fun drawPickups() {
        for (p in Game.pickups.list) {
            val blink = !p.mag && p.life - p.age < 3 && floor(p.age * 10).toInt() % 2 == 0
            if (blink) continue
            val x = ix(p.px, p.x); val y = ix(p.py, p.y)
            val s = when (p.kind) { PickupKind.XP -> if (p.value >= 25) 9f else if (p.value >= 5) 6.5f else 4.5f; PickupKind.GEM -> 3f; else -> 8f }
            glow(x, y, s * 3, p.kind.color, 0.7)
            if (p.kind == PickupKind.GEM) continue
            val c = cos(p.rot).toFloat() * s; val sn = sin(p.rot).toFloat() * s
            val col = batch.argb(p.kind.color, 1f); val w = batch.argb(Pal.WHITE, 1f)
            tx0 = x; ty0 = y; tc = c; ts = sn
            when (p.kind) {
                PickupKind.XP -> {
                    batch.tri(X(1f, 0f), Y(1f, 0f), X(0f, .6f), Y(0f, .6f), X(-1f, 0f), Y(-1f, 0f), col)
                    batch.tri(X(1f, 0f), Y(1f, 0f), X(-1f, 0f), Y(-1f, 0f), X(0f, -.6f), Y(0f, -.6f), col)
                    outline(floatArrayOf4(1f, 0f, 0f, .6f, -1f, 0f, 0f, -.6f), 4, x, y, c, sn, w)
                }
                PickupKind.DUST -> {
                    for (k in 0 until 8) { val ang = k / 8.0 * TAU; val r = if (k % 2 == 1) 0.38 else 1.2; pk[k * 2] = (cos(ang) * r).toFloat(); pk[k * 2 + 1] = (sin(ang) * r).toFloat() }
                    for (k in 0 until 8) { val j = (k + 1) % 8; batch.tri(x, y, X(pk[k * 2], pk[k * 2 + 1]), Y(pk[k * 2], pk[k * 2 + 1]), X(pk[j * 2], pk[j * 2 + 1]), Y(pk[j * 2], pk[j * 2 + 1]), col) }
                    outline(pk, 8, x, y, c, sn, w)
                }
                PickupKind.HEAL -> {
                    quadL(-.3f, -1f, .3f, 1f, x, y, c, sn, col)
                    quadL(-1f, -.3f, -.3f, .3f, x, y, c, sn, col)
                    quadL(.3f, -.3f, 1f, .3f, x, y, c, sn, col)
                    val cross = crossPts
                    outline(cross, 12, x, y, c, sn, w)
                }
                else -> {
                    batch.sprite(x, y, s * 0.96f, s * 0.96f, atlas.dot, col)
                    batch.sprite(x, y, s * 0.99f, s * 0.99f, atlas.ring, w)
                }
            }
        }
    }
    private var tx0 = 0f; private var ty0 = 0f; private var tc = 1f; private var ts = 0f
    private fun X(lx: Float, ly: Float) = tx0 + lx * tc - ly * ts
    private fun Y(lx: Float, ly: Float) = ty0 + lx * ts + ly * tc
    private val crossPts = floatArrayOf(-.3f, -1f, .3f, -1f, .3f, -.3f, 1f, -.3f, 1f, .3f, .3f, .3f, .3f, 1f, -.3f, 1f, -.3f, .3f, -1f, .3f, -1f, -.3f, -.3f, -.3f)
    private val tmp8 = FloatArray(8)
    private fun floatArrayOf4(a0: Float, a1: Float, a2: Float, a3: Float, a4: Float, a5: Float, a6: Float, a7: Float): FloatArray {
        tmp8[0] = a0; tmp8[1] = a1; tmp8[2] = a2; tmp8[3] = a3; tmp8[4] = a4; tmp8[5] = a5; tmp8[6] = a6; tmp8[7] = a7; return tmp8
    }
    private fun quadL(x0: Float, y0: Float, x1: Float, y1: Float, tx: Float, ty: Float, c: Float, s: Float, col: Int) {
        batch.quad(tx + x0 * c - y0 * s, ty + x0 * s + y0 * c, tx + x1 * c - y0 * s, ty + x1 * s + y0 * c,
            tx + x1 * c - y1 * s, ty + x1 * s + y1 * c, tx + x0 * c - y1 * s, ty + x0 * s + y1 * c, atlas.white, col)
    }
    private fun outline(pts: FloatArray, n: Int, tx: Float, ty: Float, c: Float, s: Float, col: Int) {
        for (k in 0 until n) {
            val j = (k + 1) % n
            val ax = pts[k * 2]; val ay = pts[k * 2 + 1]; val bx = pts[j * 2]; val by = pts[j * 2 + 1]
            batch.line(tx + ax * c - ay * s, ty + ax * s + ay * c, tx + bx * c - by * s, ty + bx * s + by * c, 1.2f, col)
        }
    }

    /* ───────────────────────── hostiles ───────────────────────── */

    private fun polyPath(e: Enemy): Path = polyCache.getOrPut(e.poly) { closedPath(e.poly, 1f) }
    private fun halfPath(e: Enemy): Path = halfPolyCache.getOrPut(e.poly) { closedPath(e.poly, 0.5f) }

    private fun drawEnemies(c: Canvas) {
        batch.begin(c, BlendMode.PLUS)
        for (e in Game.enemies) {
            if (e.isBoss || e.spawnT > 0) continue
            val seen = if (e is PhantomWisp) e.presence else 1.0
            if (seen <= 0.02) continue
            val g = (e.r * if (e.elite) 4.2 else 3.2).toFloat()
            glow(ix(e.px, e.x), ix(e.py, e.y), g, e.color, (if (e.elite) 0.55 else 0.32) * seen)
        }
        for (e in Game.enemies) {
            if (e.isBoss) continue
            when (e) {
                is MitosisCell -> if (e.spawnT <= 0) core(ix(e.px, e.x), ix(e.py, e.y), (e.r * 0.9).toFloat(), e.color, 0.5 + 0.3 * sin(e.t * 4))
                is SeerEye -> if (e.spawnT <= 0) {
                    val x = ix(e.px, e.x) + (cos(e.aimA) * e.r * 0.42).toFloat(); val y = ix(e.py, e.y) + (sin(e.aimA) * e.r * 0.25).toFloat()
                    core(x, y, (e.r * 0.6).toFloat(), e.color, 1.0)
                }
                is PrismShard -> if (e.spawnT <= 0) core(ix(e.px, e.x), ix(e.py, e.y), (e.r * 0.45).toFloat(), Pal.WHITE, 0.55)
                is PulsarStar -> if (e.spawnT <= 0) core(ix(e.px, e.x), ix(e.py, e.y), (e.r * (0.5 + 0.5 * e.charge)).toFloat(), if (e.charge > 0.6) Pal.WHITE else e.color, 0.5 + 0.5 * e.charge)
                is HarbingerHeavy -> if (e.spawnT <= 0) core(ix(e.px, e.x), ix(e.py, e.y), (e.r * 0.5).toFloat(), e.color, 0.65 + 0.25 * sin(e.t * 3))
                is PhantomWisp -> if (e.spawnT <= 0 && e.presence > 0.02) core(ix(e.px, e.x), ix(e.py, e.y), (e.r * 0.5).toFloat(), Pal.WHITE, 0.6 * e.presence)
                else -> {}
            }
        }
        batch.end()
        for (e in Game.enemies) {
            if (e.isBoss) continue
            val x = ix(e.px, e.x); val y = ix(e.py, e.y)
            if (e.spawnT > 0) {
                // warp portal: two counter-rotating arcs closing around the ship as it materialises
                val k = 1 - e.spawnT / e.spawnDur
                addStroke.strokeWidth = 2f
                for (i in 0 until 2) {
                    val ang = e.t * if (i == 1) -7 else 5; val R = (e.r * (2.6 - 1.4 * k)).toFloat()
                    addStroke.set(e.color, 0.4 + 0.5 * k)
                    oval.set(x - R, y - R, x + R, y + R)
                    c.drawArc(oval, Math.toDegrees(ang).toFloat(), 216f, false, addStroke)
                }
                drawPoly(c, e, x, y, Ease.outBack(k) * 0.9 + 0.05, k)
                continue
            }
            if (e is PhantomWisp) {
                // phased: only the mark of where it will reappear
                if (e.phase == 2 || e.phase == 3) drawArrival(c, e)
                if (e.presence <= 0.02) continue
                drawPoly(c, e, x, y, 0.85 + 0.15 * e.presence, e.presence)
                if (e.phase != 0) continue
            } else drawPoly(c, e, x, y, 1.0, 1.0)
            drawExtra(c, e, x, y)
            if (e.elite) {
                addStroke.set(Pal.SOLAR, 0.7); addStroke.strokeWidth = 1.5f; addStroke.pathEffect = dashElite.at(-e.t * 30)
                c.drawCircle(x, y, (e.r * 1.55).toFloat(), addStroke)
                addStroke.pathEffect = null
            }
            if (e.hp < e.maxHp && (e.maxHp > 60 || e.elite)) {
                val k = clamp(e.hp / e.maxHp, 0.0, 1.0)
                stroke.set(e.color, 0.8); stroke.strokeWidth = 2f
                val R = (e.r * 1.3).toFloat()
                oval.set(x - R, y - R, x + R, y + R)
                c.drawArc(oval, -90f, (360 * k).toFloat(), false, stroke)
            }
        }
    }

    private fun drawPoly(c: Canvas, e: Enemy, x: Float, y: Float, scale: Double, alpha: Double) {
        val r = (e.r * scale).toFloat()
        if (r <= 0.01f) return
        c.save()
        c.translate(x, y)
        c.rotate(Math.toDegrees(lerpAngle(e.prevAngle, e.angle, a.toDouble())).toFloat())
        c.scale(r, r)
        val p = polyPath(e)
        fill.set(0xFF12091F.toInt(), 0.88 * alpha)
        c.drawPath(p, fill)
        stroke.set(e.color, alpha); stroke.strokeWidth = 2.2f / r
        c.drawPath(p, stroke)
        stroke.set(e.color, 0.45 * alpha); stroke.strokeWidth = 1.2f / r
        c.drawPath(halfPath(e), stroke)
        if (e.hitFlash > 0) { fill.set(Pal.WHITE, e.hitFlash * alpha); c.drawPath(p, fill) }
        c.restore()
    }

    /** Where a Phantom will reappear: two arcs closing on the spot, brighter as it comes. */
    private fun drawArrival(c: Canvas, e: PhantomWisp) {
        val k = if (e.phase == 2) 1 - e.phaseT / PhantomWisp.AWAY else 1.0
        val x = e.tx.toFloat(); val y = e.ty.toFloat()
        addStroke.strokeWidth = 2f
        for (i in 0 until 2) {
            val ang = e.t * if (i == 1) -6 else 5; val R = (e.r * (2.8 - 1.6 * k)).toFloat()
            addStroke.set(e.color, 0.25 + 0.6 * k)
            oval.set(x - R, y - R, x + R, y + R)
            c.drawArc(oval, Math.toDegrees(ang).toFloat(), 200f, false, addStroke)
        }
    }

    private fun drawExtra(c: Canvas, e: Enemy, x: Float, y: Float) {
        when (e) {
            is CometStreak -> if (e.state == 0) {
                // the line it is about to streak along
                val k = e.stateT / e.stateDur
                addStroke.set(e.color, (0.25 + 0.55 * k) * (0.6 + 0.4 * sin(e.t * 36))); addStroke.strokeWidth = (1 + k * 2).toFloat()
                addStroke.pathEffect = dashAim.at(0.0)
                c.drawLine(x, y, x + (cos(e.aimA) * 1400).toFloat(), y + (sin(e.aimA) * 1400).toFloat(), addStroke)
                addStroke.pathEffect = null
            } else {
                // a tapering tail behind the streak
                val bx = -cos(e.aimA).toFloat(); val by = -sin(e.aimA).toFloat()
                for (i in 0 until 3) {
                    addStroke.set(e.color, 0.5 - i * 0.14); addStroke.strokeWidth = (e.r * (0.9 - i * 0.25)).toFloat()
                    val l0 = (e.r * (0.6 + i * 1.2)).toFloat(); val l1 = (e.r * (1.8 + i * 1.6)).toFloat()
                    c.drawLine(x + bx * l0, y + by * l0, x + bx * l1, y + by * l1, addStroke)
                }
            }
            is PulsarStar -> if (e.charge > 0) {
                // a closing ring, and a notch toward the gap the pulse will leave
                val k = e.charge
                val R = (e.r * (3.2 - 2.0 * k)).toFloat()
                addStroke.set(e.color, 0.25 + 0.6 * k); addStroke.strokeWidth = 1.5f + 2f * k.toFloat()
                c.drawCircle(x, y, R, addStroke)
                val gx = cos(e.gapA).toFloat(); val gy = sin(e.gapA).toFloat(); val g0 = (e.r * 1.3).toFloat(); val g1 = (e.r * 3.4).toFloat()
                addStroke.set(Pal.WHITE, 0.35 + 0.5 * k); addStroke.strokeWidth = 2.5f
                c.drawLine(x + gx * g0, y + gy * g0, x + gx * g1, y + gy * g1, addStroke)
            }
            is HiveCarrier -> {
                // the drones it still carries
                val n = e.brood
                for (i in 0 until n) {
                    val a = e.t * 0.6 + i * TAU / max(1, n)
                    fill.set(e.color, 0.85)
                    c.drawCircle(x + (cos(a) * e.r * 0.55).toFloat(), y + (sin(a) * e.r * 0.55).toFloat(), (e.r * 0.13).toFloat(), fill)
                }
            }
            is VortexCoil -> {
                stroke.set(e.color, 0.55); stroke.strokeWidth = 1.2f
                val R = (e.r * 1.55).toFloat()
                oval.set(x - R, y - R, x + R, y + R)
                val ang = Math.toDegrees(lerpAngle(e.prevAngle, e.angle, a.toDouble())).toFloat()
                for (i in 0 until 3) c.drawArc(oval, ang + i * 120f, 60f, false, stroke)
            }
            is GyreTurret -> {
                stroke.set(e.color, 0.6); stroke.strokeWidth = 1.2f
                val ang = lerpAngle(e.prevAngle, e.angle, a.toDouble())
                var n = 0
                for (i in 0 until 3) {
                    val aa = ang + (i / 3.0) * PI
                    val dx = (cos(aa) * e.r * 0.9).toFloat(); val dy = (sin(aa) * e.r * 0.9).toFloat()
                    lines[n++] = x + dx; lines[n++] = y + dy; lines[n++] = x - dx; lines[n++] = y - dy
                }
                c.drawLines(lines, 0, n, stroke)
            }
            is DartLancer -> if (e.state == 1) {
                val k = e.stateT / e.stateDur
                addStroke.set(e.color, (0.3 + 0.5 * k) * (0.6 + 0.4 * sin(e.t * 40))); addStroke.strokeWidth = (1 + k * 2).toFloat()
                addStroke.pathEffect = dashAim.at(0.0)
                c.drawLine(x, y, x + (cos(e.aimA) * 520).toFloat(), y + (sin(e.aimA) * 520).toFloat(), addStroke)
                addStroke.pathEffect = null
            }
            is SeerEye -> if (e.fireT <= 1 && e.volley == 0) {
                addStroke.set(Pal.CRIMSON, (1 - e.fireT) * (0.5 + 0.5 * sin(e.t * 50))); addStroke.strokeWidth = 1.2f
                c.drawLine(x, y, x + (cos(e.aimA) * 1600).toFloat(), y + (sin(e.aimA) * 1600).toFloat(), addStroke)
            }
            is AegisWarden -> {
                val R = (e.r + 10).toFloat()
                oval.set(x - R, y - R, x + R, y + R)
                val start = Math.toDegrees(e.shieldA - e.halfArc).toFloat(); val sweep = Math.toDegrees(e.halfArc * 2).toFloat()
                addStroke.strokeCap = Paint.Cap.BUTT
                addStroke.set(Pal.WHITE, 0.85); addStroke.strokeWidth = 4f
                c.drawArc(oval, start, sweep, false, addStroke)
                addStroke.set(e.color, 0.3); addStroke.strokeWidth = 10f
                c.drawArc(oval, start, sweep, false, addStroke)
                addStroke.strokeCap = Paint.Cap.ROUND
            }
            else -> {}
        }
    }

    /* ───────────────────────── guardians ───────────────────────── */

    private fun drawBoss(c: Canvas, b: Boss) {
        if (b.dead) return
        val x = ix(b.px, b.x); val y = ix(b.py, b.y)
        // aura
        batch.begin(c, BlendMode.PLUS)
        if (b is LissajousLeviathan) {
            var i = 0
            while (i < b.segs.size) { val s = b.segs[i]; val g = (s.r * 4).toFloat(); glow(ix(s.px, s.x), ix(s.py, s.y), g, b.color, 0.25); i += 2 }
        } else glow(x, y, (b.r * 5).toFloat(), b.color, 0.2 + 0.1 * Audio.pulse)
        batch.end()
        when (b) {
            is HelixCantor -> drawHelix(c, b, x, y)
            is LissajousLeviathan -> drawLeviathan(c, b, x, y)
            is FractalSeraph -> drawSeraph(c, b, x, y)
            is EntropyEngine -> drawEntropy(c, b, x, y)
            is FourierOrrery -> drawFourier(c, b, x, y)
            is PenrosePentarch -> drawPenrose(c, b, x, y)
            is TrefoilHierophant -> drawTrefoil(c, b, x, y)
            is MandelbrotMatriarch -> drawMandelbrot(c, b, x, y)
            is AutomatonAugur -> drawAutomaton(c, b, x, y)
            is EulerEidolon -> drawEidolon(c, b, x, y)
        }
        // core with beat pulse
        val r = if (b is LissajousLeviathan) 26.0 else b.r
        val scale = if (b is LissajousLeviathan) 0.9 else 1.0
        val pulse = 1 + 0.12 * Audio.beat + 0.08 * sin(b.t * 6)
        val R = (r * 0.3 * pulse * scale).toFloat()
        batch.begin(c, BlendMode.PLUS)
        core(x, y, R * 2, b.color, 0.9)
        if (b.hitFlash > 0) core(x, y, R * 2.2f, Pal.WHITE, b.hitFlash * 0.35)
        batch.end()
    }

    private fun drawHelix(c: Canvas, b: HelixCantor, x: Float, y: Float) {
        val R = b.r; val rot = lerpAngle(b.prevAngle, b.angle, a.toDouble())
        fun rose(k: Int, scale: Double, rr: Double, lw: Float, alpha: Double) {
            path.rewind()
            for (i in 0..120) {
                val th = (i / 120.0) * TAU; val r = R * scale * (0.72 + 0.28 * cos(k * th + rr))
                val px = x + (cos(th) * r).toFloat(); val py = y + (sin(th) * r).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            fill.set(0xFF12091F.toInt(), 0.85 * alpha); c.drawPath(path, fill)
            stroke.set(b.color, alpha); stroke.strokeWidth = lw; c.drawPath(path, stroke)
            if (b.hitFlash > 0) { fill.set(Pal.WHITE, b.hitFlash * 0.25 * alpha); c.drawPath(path, fill) }
        }
        rose(3, 1.25, rot, 2.4f, 1.0)
        rose(5, 0.82, -rot * 1.6, 1.5f, 0.75)
        // double-helix ribbon: z-depth = sin(phase) controls size & alpha
        batch.begin(c, BlendMode.PLUS)
        for (s in 0 until 2) for (i in 0 until 18) {
            val ang = (i / 18.0) * TAU + b.t * 0.9; val z = sin(ang * 3 + b.t * 3 + s * PI)
            val rr = R * 1.55 + z * 8; val px = x + (cos(ang) * rr).toFloat(); val py = y + (sin(ang) * rr).toFloat()
            val sz = (4 + 3 * (z + 1)).toFloat()
            core(px, py, sz, if (s == 1) Pal.C_FFB238 else b.color, 0.35 + 0.3 * (z + 1))
        }
        batch.end()
    }

    private fun drawLeviathan(c: Canvas, b: LissajousLeviathan, x: Float, y: Float) {
        // spine
        var n = 0
        var px = x; var py = y
        for (s in b.segs) {
            if (n + 4 > lines.size) break
            val sx = ix(s.px, s.x); val sy = ix(s.py, s.y)
            lines[n++] = px; lines[n++] = py; lines[n++] = sx; lines[n++] = sy
            px = sx; py = sy
        }
        addStroke.set(b.color, 0.6); addStroke.strokeWidth = 3f
        c.drawLines(lines, 0, n, addStroke)
        // plates, tail first so the head overlaps
        for (i in b.segs.size - 1 downTo 0) {
            val s = b.segs[i]
            c.save()
            c.translate(ix(s.px, s.x), ix(s.py, s.y))
            c.rotate(Math.toDegrees(lerpAngle(s.pa, s.a, a.toDouble())).toFloat())
            c.scale(s.r.toFloat(), s.r.toFloat())
            fill.set(0xFF14081C.toInt(), 0.92); c.drawPath(hexPath, fill)
            stroke.set(if (i % 2 == 1) b.color else Pal.C_FFB238, 1.0); stroke.strokeWidth = (2 / s.r).toFloat(); c.drawPath(hexPath, stroke)
            if (b.hitFlash > 0) { fill.set(Pal.WHITE, b.hitFlash * 0.6); c.drawPath(hexPath, fill) }
            c.restore()
        }
        // head with mandibles
        c.save()
        c.translate(x, y)
        c.rotate(Math.toDegrees(lerpAngle(b.prevAngle, b.angle, a.toDouble())).toFloat())
        path.rewind()
        path.moveTo(34f, 0f); path.lineTo(12f, -20f); path.lineTo(-18f, -16f); path.lineTo(-24f, 0f); path.lineTo(-18f, 16f); path.lineTo(12f, 20f); path.close()
        fill.set(0xFF1A0A1E.toInt(), 1.0); c.drawPath(path, fill)
        stroke.set(b.color, 1.0); stroke.strokeWidth = 2.6f; c.drawPath(path, stroke)
        val open = 0.35 + 0.25 * sin(b.t * 6)
        stroke.set(Pal.C_FFB238, 1.0); stroke.strokeWidth = 3f
        oval.set(30f - 18f, -10f - 18f, 30f + 18f, -10f + 18f)
        c.drawArc(oval, (0.9 * 180).toFloat(), ((1.4 + open - 0.9) * 180).toFloat(), false, stroke)
        oval.set(30f - 18f, 10f - 18f, 30f + 18f, 10f + 18f)
        c.drawArc(oval, ((0.6 - open) * 180).toFloat(), ((1.1 - (0.6 - open)) * 180).toFloat(), false, stroke)
        if (b.hitFlash > 0) { fill.set(Pal.WHITE, b.hitFlash); c.drawCircle(0f, 0f, 20f, fill) }
        c.restore()
    }

    private fun drawSeraph(c: Canvas, b: FractalSeraph, x: Float, y: Float) {
        val ang = lerpAngle(b.prevAngle, b.angle, a.toDouble())
        val layers = seraphLayers
        layers[1] = ang; layers[4] = -ang * 1.6; layers[7] = ang * 3.2
        for (l in 0 until 3) {
            val scale = layers[l * 3]; val rot = layers[l * 3 + 1]; val alpha = layers[l * 3 + 2]
            val rs = (b.r * scale).toFloat()
            c.save()
            c.translate(x, y)
            c.rotate(Math.toDegrees(rot).toFloat())
            c.scale(rs, rs)
            fill.set(0xFF100820.toInt(), 0.7 * alpha); c.drawPath(kochPath, fill)
            stroke.set(if (scale > 1.5) Pal.C_FF8AE9 else b.color, alpha); stroke.strokeWidth = 2f / rs; c.drawPath(kochPath, stroke)
            if (b.hitFlash > 0) { fill.set(Pal.WHITE, b.hitFlash * 0.5); c.drawPath(kochPath, fill) }
            c.restore()
        }
    }
    private val seraphLayers = doubleArrayOf(1.7, 0.0, 0.55, 1.15, 0.0, 0.8, 0.62, 0.0, 1.0)

    private fun drawEntropy(c: Canvas, b: EntropyEngine, x: Float, y: Float) {
        val P = b.proj; val D = b.depth; val E = EntropyEngine.EDGES
        var e = 0
        while (e < E.size) {
            val i = E[e]; val j = E[e + 1]; val dz = (D[i] + D[j]) / 2
            val k = clamp((dz + 1.4) / 2.8, 0.0, 1.0)
            addStroke.set(if (k > 0.65) Pal.WHITE else b.color, 0.25 + 0.75 * k); addStroke.strokeWidth = (1 + 2.2 * k).toFloat()
            c.drawLine(x + P[i * 2].toFloat(), y + P[i * 2 + 1].toFloat(), x + P[j * 2].toFloat(), y + P[j * 2 + 1].toFloat(), addStroke)
            e += 2
        }
        batch.begin(c, BlendMode.PLUS)
        for (i in 0 until 16) {
            val k = clamp((D[i] + 1.4) / 2.8, 0.0, 1.0); val s = (4 + 7 * k).toFloat()
            core(x + P[i * 2].toFloat(), y + P[i * 2 + 1].toFloat(), s, if (k > 0.6) Pal.C_FFE066 else b.color, 0.5 + 0.5 * k)
        }
        if (b.hitFlash > 0) glow(x, y, (b.r * 2).toFloat(), Pal.WHITE, b.hitFlash * 0.6)
        batch.end()
        // event horizon
        addStroke.set(b.color, 0.5); addStroke.strokeWidth = 1.5f; addStroke.pathEffect = dashHorizon.at(b.t * 40)
        c.drawCircle(x, y, (b.r * 1.9).toFloat(), addStroke)
        addStroke.pathEffect = null
    }

    /** Epicycles: each arm's circle around the joint it turns on, the arms, the pen's fading trail and the curve it traces. */
    private fun drawFourier(c: Canvas, b: FourierOrrery, x: Float, y: Float) {
        val th = lerp(b.prevTheta, b.theta, a.toDouble())
        b.joints(th, fjx, fjy)
        val L = (b.armScale * b.unfold).toFloat()
        if (L > 1f) {
            c.save(); c.translate(x, y); c.scale(L, L)
            addStroke.set(b.color, 0.38); addStroke.strokeWidth = 1.8f / L
            c.drawPath(if (b.series === FourierOrrery.STAR) fourierStar else fourierSquare, addStroke)
            c.restore()
        }
        // the hub: a clockwork gear
        val R = (b.r * 0.92).toFloat()
        c.save(); c.translate(x, y); c.rotate(deg(lerpAngle(b.prevAngle, b.angle, a.toDouble()))); c.scale(R, R)
        fill.set(0xFF12091F.toInt(), 0.9); c.drawPath(gearPath, fill)
        stroke.set(b.color, 1.0); stroke.strokeWidth = 2.2f / R; c.drawPath(gearPath, stroke)
        stroke.set(Pal.C_FFB238, 0.55); stroke.strokeWidth = 1.2f / R; c.drawCircle(0f, 0f, 0.55f, stroke)
        if (b.hitFlash > 0) { fill.set(Pal.WHITE, b.hitFlash * 0.45); c.drawPath(gearPath, fill) }
        c.restore()
        addStroke.strokeWidth = 1.2f
        for (j in 0 until 5) {
            addStroke.set(if (j % 2 == 0) b.color else Pal.C_FFB238, 0.55)
            c.drawCircle(x + fjx[j].toFloat(), y + fjy[j].toFloat(), (abs(b.series.a[j]) * L).toFloat(), addStroke)
        }
        var n = 0
        for (j in 0 until 5) { lines[n++] = x + fjx[j].toFloat(); lines[n++] = y + fjy[j].toFloat(); lines[n++] = x + fjx[j + 1].toFloat(); lines[n++] = y + fjy[j + 1].toFloat() }
        addStroke.set(Pal.WHITE, 0.7); addStroke.strokeWidth = 2f
        c.drawLines(lines, 0, n, addStroke)
        batch.begin(c, BlendMode.PLUS)
        val dir = if (b.omega >= 0) 1.0 else -1.0
        var px = 0f; var py = 0f
        for (i in 0..40) {
            val tt = th - dir * (40 - i) / 40.0 * 1.7
            var zx = 0.0; var zy = 0.0
            for (j in 0 until 5) { val ang = b.series.k[j] * tt; zx += cos(ang) * b.series.a[j]; zy += sin(ang) * b.series.a[j] }
            val qx = x + (zx * L).toFloat(); val qy = y + (zy * L).toFloat()
            if (i > 0) batch.line(px, py, qx, qy, 1f + 2.2f * i / 40f, batch.argb(if (i > 30) Pal.C_FFE066 else b.color, i / 40f))
            px = qx; py = qy
        }
        for (j in 1..5) core(x + fjx[j].toFloat(), y + fjy[j].toFloat(), if (j == 5) 10f else 5.5f, if (j == 5) Pal.C_FFE066 else b.color, 0.9)
        batch.end()
    }

    /** The rhomb tiling (thick tiles gold, thin rose), turning a tenth of a revolution per leg, a pentagram at its heart. */
    private fun drawPenrose(c: Canvas, b: PenrosePentarch, x: Float, y: Float) {
        val R = (b.r * 1.75).toFloat()
        val turn = deg(lerp(b.prevTurn, b.turn, a.toDouble()) * PI / 5)
        val set = if (b.depth >= 4) penrose4 else penrose3
        c.save(); c.translate(x, y)
        if (b.reveal < 1.0) { clipPath.rewind(); clipPath.addCircle(0f, 0f, R * (0.05f + 1.0f * b.reveal.toFloat()), Path.Direction.CW); c.clipPath(clipPath) }
        c.rotate(turn); c.scale(R, R)
        fill.set(0xFF3B2A0A.toInt(), 0.9); c.drawPath(set.thick, fill)
        fill.set(0xFF3A1030.toInt(), 0.9); c.drawPath(set.thin, fill)
        stroke.set(b.color, 0.9); stroke.strokeWidth = 1.4f / R; c.drawPath(set.edges, stroke)
        stroke.set(b.color, 1.0); stroke.strokeWidth = 2.4f / R; c.drawPath(decagon, stroke)
        if (b.hitFlash > 0) { fill.set(Pal.WHITE, b.hitFlash * 0.3); c.drawPath(decagon, fill) }
        c.restore()
        val S = (b.r * 0.5).toFloat()
        c.save(); c.translate(x, y); c.rotate(-turn); c.scale(S, S)
        addStroke.set(Pal.WHITE, 0.85); addStroke.strokeWidth = 2f / S; c.drawPath(pentagram, addStroke)
        c.restore()
    }

    /** The knot in depth: far strands dim, near ones thick and bright over a dark casing, so crossings read over and under. */
    private fun drawTrefoil(c: Canvas, b: TrefoilHierophant, x: Float, y: Float) {
        val P = b.proj; val D = b.depth; val N = TrefoilHierophant.N
        val shown = (N * b.tie).toInt()
        batch.begin(c, BlendMode.PLUS)
        for (i in 0 until shown) {
            val j = (i + 1) % N; val dz = (D[i] + D[j]) / 2
            if (dz >= 0) continue
            val k = clamp((dz + 1.2) / 2.4, 0.0, 1.0)
            batch.line(x + P[i * 2].toFloat(), y + P[i * 2 + 1].toFloat(), x + P[j * 2].toFloat(), y + P[j * 2 + 1].toFloat(), (2.5 + 5 * k).toFloat(), batch.argb(b.color, (0.25 + 0.4 * k).toFloat()))
        }
        batch.end()
        batch.begin(c, BlendMode.SRC_OVER)
        for (i in 0 until shown) {
            val j = (i + 1) % N; val dz = (D[i] + D[j]) / 2
            if (dz < 0) continue
            val k = clamp((dz + 1.2) / 2.4, 0.0, 1.0)
            batch.line(x + P[i * 2].toFloat(), y + P[i * 2 + 1].toFloat(), x + P[j * 2].toFloat(), y + P[j * 2 + 1].toFloat(), (7 + 7 * k).toFloat(), batch.argb(0xFF12091F.toInt(), 0.95f))
        }
        batch.end()
        batch.begin(c, BlendMode.PLUS)
        for (i in 0 until shown) {
            val j = (i + 1) % N; val dz = (D[i] + D[j]) / 2
            if (dz < 0) continue
            val k = clamp((dz + 1.2) / 2.4, 0.0, 1.0)
            val x0 = x + P[i * 2].toFloat(); val y0 = y + P[i * 2 + 1].toFloat(); val x1 = x + P[j * 2].toFloat(); val y1 = y + P[j * 2 + 1].toFloat()
            batch.line(x0, y0, x1, y1, (3 + 6 * k).toFloat(), batch.argb(b.color, (0.55 + 0.4 * k).toFloat()))
            batch.line(x0, y0, x1, y1, (1 + 2 * k).toFloat(), batch.argb(Pal.WHITE, (0.3 + 0.6 * k).toFloat()))
        }
        var i = 0
        while (i < shown) {
            val k = clamp((D[i] + 1.2) / 2.4, 0.0, 1.0)
            core(x + P[i * 2].toFloat(), y + P[i * 2 + 1].toFloat(), (3 + 5 * k).toFloat(), if (k > 0.6) Pal.C_FF8AE9 else b.color, 0.35 + 0.5 * k)
            i += 8
        }
        if (b.hitFlash > 0) glow(x, y, (b.r * 2.2).toFloat(), Pal.WHITE, b.hitFlash * 0.45)
        batch.end()
    }

    /** The set head-up, ringed by escape-time bands that swell outward and fade, its minibrot on the antenna. */
    private fun drawMandelbrot(c: Canvas, b: MandelbrotMatriarch, x: Float, y: Float) {
        val u = b.unit.toFloat()
        c.save(); c.translate(x, y)
        addStroke.strokeWidth = 1.5f
        for (i in 0 until 3) {
            val f = mod(b.t * 0.32 + i / 3.0, 1.0)
            val s = (u * (1.12 + 1.5 * f) * (1 + 2.5 * (1 - b.bands))).toFloat()
            c.save(); c.scale(s, s)
            addStroke.set(if (i == 1) Pal.C_FF8AE9 else b.color, 0.4 * (1 - f) * b.bands); addStroke.strokeWidth = 1.5f / s
            c.drawPath(mandelCardioid, addStroke)
            c.restore()
        }
        c.scale(u, u)
        fill.set(0xFF14061A.toInt(), 0.92); c.drawPath(mandelBody, fill)
        stroke.set(b.color, 1.0); stroke.strokeWidth = 2.2f / u; c.drawPath(mandelBody, stroke)
        stroke.set(Pal.C_FFB238, 0.85); stroke.strokeWidth = 1.4f / u; c.drawPath(mandelAntenna, stroke)
        if (b.hitFlash > 0) { fill.set(Pal.WHITE, b.hitFlash * 0.4); c.drawPath(mandelBody, fill) }
        c.restore()
        batch.begin(c, BlendMode.PLUS)
        core(x, y + ((-1.0 - MandelbrotMatriarch.C0) * u).toFloat(), 9f, Pal.C_FFB238, 0.7 + 0.2 * sin(b.t * 5))
        core(x, y + ((-1.755 - MandelbrotMatriarch.C0) * u).toFloat(), 5f, Pal.C_FFE066, 0.8)
        batch.end()
    }

    /** The ring of cells and its last generations as fading rings, around a chip that keeps the rule. */
    private fun drawAutomaton(c: Canvas, b: AutomatonAugur, x: Float, y: Float) {
        val ang = lerpAngle(b.prevAngle, b.angle, a.toDouble())
        val N = AutomatonAugur.N; val H = AutomatonAugur.HIST
        val R0 = (b.r * 1.35).toFloat(); val band = 7.5f
        val lit = (N * b.boot).toInt()
        batch.begin(c, BlendMode.PLUS)
        for (h in 0 until H) {
            val cells = b.history[h]
            val r0 = R0 - 3.5f + h * band; val r1 = r0 + band - 2f
            val newest = h == 0
            val col = batch.argb(if (newest) Pal.C_FFE066 else b.color, if (newest) 0.95f else 0.6f * (1f - h.toFloat() / H))
            val dim = batch.argb(b.color, if (newest) 0.18f else 0f)
            for (i in 0 until N) {
                val on = (cells shr i) and 1L != 0L && (b.boot >= 1.0 || i < lit)
                if (!on && !newest) continue
                val a0 = ang + (i + 0.1) / N * TAU; val a1 = ang + (i + 0.9) / N * TAU
                val c0 = cos(a0).toFloat(); val s0 = sin(a0).toFloat(); val c1 = cos(a1).toFloat(); val s1 = sin(a1).toFloat()
                batch.quad(x + c0 * r0, y + s0 * r0, x + c1 * r0, y + s1 * r0, x + c1 * r1, y + s1 * r1, x + c0 * r1, y + s0 * r1, atlas.white, if (on) col else dim)
            }
        }
        batch.end()
        val S = (b.r * 0.62).toFloat()
        c.save(); c.translate(x, y); c.rotate(deg(ang)); c.scale(S, S)
        fill.set(0xFF101808.toInt(), 0.92); c.drawPath(chipPath, fill)
        stroke.set(b.color, 1.0); stroke.strokeWidth = 2f / S; c.drawPath(chipPath, stroke)
        stroke.set(b.color, 0.5); stroke.strokeWidth = 1.2f / S; c.drawRect(-0.38f, -0.38f, 0.38f, 0.38f, stroke)
        if (b.hitFlash > 0) { fill.set(Pal.WHITE, b.hitFlash * 0.5); c.drawPath(chipPath, fill) }
        c.restore()
    }

    /** The unit circle with its turning phasor e^{iθ}, the half-turn arc to −1, and the nine sigils of the fallen. */
    private fun drawEidolon(c: Canvas, b: EulerEidolon, x: Float, y: Float) {
        val R = b.ringR.toFloat()
        addStroke.set(b.color, 0.18); addStroke.strokeWidth = 9f; c.drawCircle(x, y, R, addStroke)
        addStroke.set(b.color, 0.75); addStroke.strokeWidth = 1.8f; c.drawCircle(x, y, R, addStroke)
        val th = lerpAngle(b.prevAngle, b.angle, a.toDouble())
        val ex = x + (cos(th) * R).toFloat(); val ey = y + (sin(th) * R).toFloat()
        addStroke.set(Pal.WHITE, 0.8); addStroke.strokeWidth = 2f; c.drawLine(x - (ex - x) * 0.999f, y - (ey - y) * 0.999f, ex, ey, addStroke)
        val r2 = R * 0.62f
        oval.set(x - r2, y - r2, x + r2, y + r2)
        addStroke.set(Pal.C_FFE066, 0.8); addStroke.strokeWidth = 2.6f; c.drawArc(oval, deg(th), 180f, false, addStroke)
        // the nine sigils fly in during the intro, then ride the circle
        val gr = R * (1f + 6f * (1f - b.gather.toFloat()))
        for (k in 0 until EulerEidolon.SIGILS) {
            val sa = b.sigilAngle(k)
            val sx = x + (cos(sa) * gr).toFloat(); val sy = y + (sin(sa) * gr).toFloat()
            val s = 13f
            c.save(); c.translate(sx, sy); c.rotate(deg(sa + PI / 2)); c.scale(s, s)
            fill.set(0xFF12091F.toInt(), 0.8); c.drawCircle(0f, 0f, 1.25f, fill)
            stroke.set(BOSS_DEFS[k].color, 1.0); stroke.strokeWidth = 1.6f / s; c.drawPath(sigils[k], stroke)
            c.restore()
        }
        batch.begin(c, BlendMode.PLUS)
        for (k in 0 until EulerEidolon.SIGILS) {
            val sa = b.sigilAngle(k)
            glow(x + (cos(sa) * gr).toFloat(), y + (sin(sa) * gr).toFloat(), 26f, BOSS_DEFS[k].color, 0.35)
        }
        core(ex, ey, 7f, Pal.WHITE, 0.9)
        core(x - (ex - x), y - (ey - y), 6f, Pal.C_FFE066, 0.8)
        if (b.hitFlash > 0) glow(x, y, R, Pal.WHITE, b.hitFlash * 0.35)
        batch.end()
    }

    /* ───────────────────────── lasers ───────────────────────── */

    private fun drawLaser(c: Canvas, l: Laser) {
        val ex = l.endX().toFloat(); val ey = l.endY().toFloat(); val x = l.x.toFloat(); val y = l.y.toFloat()
        if (l.age < l.warmup) {
            val k = l.age / l.warmup
            addStroke.set(l.color, (0.25 + 0.5 * k) * (0.7 + 0.3 * sin(l.age * 50))); addStroke.strokeWidth = (1 + k * 2).toFloat()
            addStroke.pathEffect = dashLaser.at(l.age * 120)
            c.drawLine(x, y, ex, ey, addStroke)
            addStroke.pathEffect = null
            val g = (10 + 30 * k).toFloat()
            batch.begin(c, BlendMode.PLUS); core(x, y, g, l.color, k); batch.end()
        } else {
            val tEnd = l.warmup + l.duration
            val k0 = if (l.age < l.warmup + 0.12) (l.age - l.warmup) / 0.12 else if (l.age > tEnd) 1 - (l.age - tEnd) / l.fade else 1.0
            val k = clamp(k0, 0.0, 1.0)
            val w = l.width * (0.85 + 0.15 * sin(l.age * 60)) * k
            addStroke.set(l.color, 0.18 * k); addStroke.strokeWidth = max(0.5, w * 2.6).toFloat(); c.drawLine(x, y, ex, ey, addStroke)
            addStroke.set(l.color, 0.75 * k); addStroke.strokeWidth = max(0.5, w * 1.0).toFloat(); c.drawLine(x, y, ex, ey, addStroke)
            addStroke.set(Pal.WHITE, k); addStroke.strokeWidth = max(0.5, w * 0.38).toFloat(); c.drawLine(x, y, ex, ey, addStroke)
            val g = (l.width * 2.2).toFloat()
            batch.begin(c, BlendMode.PLUS); core(x, y, g, l.color, k); batch.end()
            // light the arena along the beam
            for (i in 1..6) Light.add(lerp(l.x, ex.toDouble(), i / 7.0), lerp(l.y, ey.toDouble(), i / 7.0), 90.0, l.color, 0.5 * k)
        }
    }

    /* ───────────────────────── player shots ───────────────────────── */

    private fun drawShots(c: Canvas) {
        batch.begin(c, BlendMode.PLUS)
        val E = Atlas.BULLET_E
        for (s in Game.pshots.list) {
            val shape = when (s.kind) { ShotKind.MISSILE -> Shape.DIAMOND; ShotKind.LANCE -> Shape.LANCE; else -> Shape.BOLT }
            val half = (s.r * E * if (s.kind == ShotKind.LANCE) 1.6 else 1.0).toFloat()
            val ang = atan2(s.vy, s.vx).toFloat()
            val al = if (s.age < 0.03) 0.6f else 1f
            val x = ix(s.px, s.x); val y = ix(s.py, s.y)
            batch.spriteRot(x, y, half, half, ang, atlas.bulletBody[shape.ordinal]!!, batch.argb(s.color, al))
            batch.spriteRot(x, y, half, half, ang, atlas.bulletDetail[shape.ordinal]!!, batch.argb(Pal.WHITE, al))
        }
        batch.end()
    }

    /* ───────────────────────── enemy bullets ───────────────────────── */

    private fun drawBullets(c: Canvas) {
        batch.begin(c, BlendMode.PLUS)
        val E = Atlas.BULLET_E
        for (b in Game.ebullets.list) {
            val half = (b.r * E).toFloat()
            val body = atlas.bulletBody[b.shape.ordinal]!!; val det = atlas.bulletDetail[b.shape.ordinal]!!
            if (b.delay > 0) {
                // spawn telegraph: a faint flickering pip so delayed bullets are never invisible threats
                val al = (0.25 + 0.25 * sin(b.delay * 40)).toFloat()
                val h = half * 0.5f
                batch.sprite(b.x.toFloat(), b.y.toFloat(), h, h, body, batch.argb(b.color, al))
                batch.sprite(b.x.toFloat(), b.y.toFloat(), h, h, det, batch.argb(Pal.WHITE, al))
                continue
            }
            val al = if (b.age < 0.14) (b.age / 0.14).toFloat() else 1f   // fade in so dense emitters don't bloom into a white knot
            val x = ix(b.px, b.x); val y = ix(b.py, b.y)
            val col = batch.argb(b.color, al); val w = batch.argb(Pal.WHITE, al)
            if (b.shape.rotates || b.spin != 0.0) {
                val ang = (if (b.shape.rotates) b.angle else b.rot).toFloat()
                batch.spriteRot(x, y, half, half, ang, body, col)
                batch.spriteRot(x, y, half, half, ang, det, w)
            } else {
                batch.sprite(x, y, half, half, body, col)
                batch.sprite(x, y, half, half, det, w)
            }
        }
        batch.end()
    }

    /* ───────────────────────── player ───────────────────────── */

    private fun hullShader(h: HullDef): LinearGradient = hullShaders.getOrPut(h.id) {
        LinearGradient(-18f, -18f, 20f, 18f, intArrayOf(0xFF120A2A.toInt(), ColorUtil.mix(0xFF120A2A.toInt(), h.color, 0.35), ColorUtil.mix(0xFF120A2A.toInt(), h.color, 0.6)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    private fun hullPath(p: Path, h: HullDef, sy: Float) {
        p.rewind()
        val b = h.body; val n = b.size / 2
        for (i in 0 until n) { if (i == 0) p.moveTo(b[0], b[1] * sy) else p.lineTo(b[i * 2], b[i * 2 + 1] * sy) }
        p.close()
    }

    /** Draw a hull in local space (nose → +x). `bank` ∈ [−1, 1]; `flash` whitens; used by the hangar preview too. */
    fun drawShip(c: Canvas, h: HullDef, bank: Double, flash: Double, alpha: Double) {
        val sy = (1 - 0.32 * abs(bank)).toFloat(); val under = (-bank * 3.5).toFloat()
        batch.begin(c, BlendMode.PLUS); glow(0f, 0f, 34f, h.color, 0.5 * alpha); batch.end()
        hullPath(path, h, sy)
        // underside plate (gives the roll its depth)
        c.save(); c.translate(0f, under)
        fill.set(0xFF0A0618.toInt(), 0.9 * alpha); c.drawPath(path, fill)
        c.restore()
        // top hull
        val sh = hullShader(h)
        shaderMatrix.setScale(1f, sy)
        sh.setLocalMatrix(shaderMatrix)
        fill.shader = sh; fill.color = Pal.WHITE; fill.alpha = alpha255(alpha)
        c.drawPath(path, fill)
        fill.shader = null
        stroke.set(h.color, alpha); stroke.strokeWidth = 1.7f; c.drawPath(path, stroke)
        // panel lines
        stroke.set(h.accent, 0.55 * alpha); stroke.strokeWidth = 0.9f
        var n = 0
        for (d in h.detail) { lines[n++] = d[0]; lines[n++] = d[1] * sy; lines[n++] = d[2]; lines[n++] = d[3] * sy }
        c.drawLines(lines, 0, n, stroke)
        // cockpit
        path2.rewind(); path2.moveTo(10f, 0f); path2.lineTo(3f, -2.6f * sy); path2.lineTo(-1f, 0f); path2.lineTo(3f, 2.6f * sy); path2.close()
        fill.set(Pal.WHITE, alpha); c.drawPath(path2, fill)
        if (flash > 0) { fill.set(Pal.WHITE, flash * alpha); c.drawPath(path, fill) }
    }

    fun drawEngines(c: Canvas, h: HullDef, thrust: Double, t: Double) {
        batch.begin(c, BlendMode.PLUS)
        for (e in h.engines) {
            val flick = 0.75 + 0.25 * sin(t * 60 + e[0] * 3 + e[1])
            val len = ((10 + thrust * 18) * flick).toFloat(); val wid = (7 + thrust * 2).toFloat()
            // drawImage(core, ex − 1.6·len, ey − wid, 2·len, 2·wid)
            val cx = (e[0] - len * 0.6).toFloat(); val cy = e[1].toFloat()
            batch.sprite(cx, cy, len, wid, atlas.coreRim, batch.argb(h.color, 0.9f))
            batch.sprite(cx, cy, len, wid, atlas.coreHot, batch.argb(Pal.WHITE, 0.9f))
        }
        batch.end()
    }

    private fun drawPlayer(c: Canvas, p: Player) {
        if (!p.alive) return
        val x = ix(p.px, p.x); val y = ix(p.py, p.y)
        val ang = lerpAngle(p.prevAngle, p.angle, a.toDouble())
        val s = p.stats
        val blink = p.blink()
        // corona plasma field (beneath the hull)
        if (s.corona > 0) {
            val R = (78 + 18 * s.corona).toFloat()
            batch.begin(c, BlendMode.PLUS); glow(x, y, R, Pal.VIOLET, 0.16 + 0.06 * sin(p.t * 7)); batch.end()
            addStroke.strokeWidth = 1.5f
            for (i in 0 until 3) {
                addStroke.set(Pal.VIOLET, 0.35)
                addStroke.pathEffect = dashCorona[i].at(-p.t * (60 + i * 40) * if (i % 2 == 1) -1 else 1)
                c.drawCircle(x, y, R * (0.55f + i * 0.22f), addStroke)
            }
            addStroke.pathEffect = null
        }
        c.save()
        c.translate(x, y)
        c.rotate(Math.toDegrees(ang).toFloat())
        drawEngines(c, p.hull, p.thrust, p.t)
        drawShip(c, p.hull, p.bank, if (p.dashT > 0) 0.6 else 0.0, if (blink) 0.3 else 1.0)
        c.restore()
        // shield bubble: dashed facets that slowly rotate
        if (p.shield > 0) {
            addStroke.set(Pal.WHITE, 0.45 + 0.15 * sin(p.t * 5)); addStroke.strokeWidth = 1.5f
            addStroke.pathEffect = dashShield.at(-p.t * 30)
            c.drawCircle(x, y, 30f, addStroke)
            if (p.shield > 1) c.drawCircle(x, y, 35f, addStroke)
            addStroke.pathEffect = null
        }
        // orbitals
        if (s.orbitals > 0) {
            batch.begin(c, BlendMode.PLUS)
            for (i in 0 until s.orbitals) { val o = p.orbitals[i]; core(ix(o.px, o.x), ix(o.py, o.y), 14f, Pal.MINT, 0.9) }
            batch.end()
            addStroke.set(Pal.MINT, 0.9); addStroke.strokeWidth = 1.5f
            for (i in 0 until s.orbitals) {
                val o = p.orbitals[i]
                c.save(); c.translate(ix(o.px, o.x), ix(o.py, o.y)); c.rotate(Math.toDegrees(p.orbitA * 3 + i).toFloat())
                c.drawRect(-6f, -6f, 6f, 6f, addStroke)
                c.restore()
            }
        }
        // hitbox core — always visible so the player knows exactly what can be hit
        fill.set(Pal.WHITE, 1.0)
        c.drawCircle(x, y, (p.r * 0.8).toFloat(), fill)
        stroke.set(if (p.overdrive > 0) Pal.WHITE else p.color, 0.9); stroke.strokeWidth = 1.2f
        c.drawCircle(x, y, (p.r + 2.5).toFloat(), stroke)
    }

    /* ───────────────────────── particles (additive layer) ───────────────────────── */

    private fun drawAdditiveParticles(c: Canvas) {
        val list = Fx.layer(PLayer.ADD)
        batch.begin(c, BlendMode.PLUS)
        for (p in list) {
            val k = 1 - p.t
            when (p.kind) {
                PKind.SPARK -> {
                    val x = ix(p.px, p.x); val y = ix(p.py, p.y)
                    batch.line(x, y, x - (p.vx * p.stretch).toFloat(), y - (p.vy * p.stretch).toFloat(), (p.width * (0.35 + 0.65 * k)).toFloat(), batch.argb(p.color, k.toFloat()))
                }
                PKind.EMBER -> {
                    val s = (p.size * sqrt(max(0.0, k))).toFloat()
                    val x = ix(p.px, p.x); val y = ix(p.py, p.y)
                    if (p.hot) core(x, y, s, p.color, k) else glow(x, y, s, p.color, k)
                }
                PKind.FLASH -> { val s = (p.size * (0.6 + 0.4 * k)).toFloat(); core(p.x.toFloat(), p.y.toFloat(), s, p.color, k * k) }
                PKind.SHARD -> {
                    val x = ix(p.px, p.x); val y = ix(p.py, p.y); val cs = cos(p.rot).toFloat(); val sn = sin(p.rot).toFloat(); val t = p.tri
                    val x0 = x + t[0] * cs - t[1] * sn; val y0 = y + t[0] * sn + t[1] * cs
                    val x1 = x + t[2] * cs - t[3] * sn; val y1 = y + t[2] * sn + t[3] * cs
                    val x2 = x + t[4] * cs - t[5] * sn; val y2 = y + t[4] * sn + t[5] * cs
                    batch.tri(x0, y0, x1, y1, x2, y2, batch.argb(p.color, (0.35 * k).toFloat()))
                    val lc = batch.argb(p.color, k.toFloat())
                    batch.line(x0, y0, x1, y1, 1.6f, lc); batch.line(x1, y1, x2, y2, 1.6f, lc); batch.line(x2, y2, x0, y0, 1.6f, lc)
                }
                PKind.DEBRIS -> {
                    val x = ix(p.px, p.x); val y = ix(p.py, p.y)
                    val cc = (cos(p.ang) * p.halfLen).toFloat(); val ss = (sin(p.ang) * p.halfLen).toFloat()
                    batch.line(x - cc, y - ss, x + cc, y + ss, p.width.toFloat(), batch.argb(p.color, k.toFloat()))
                }
                else -> {}
            }
        }
        batch.end()
        for (p in list) {
            when (p.kind) {
                PKind.RING -> {
                    val t = p.t; val r = lerp(p.r0, p.r1, Ease.outCubic(t)).toFloat()
                    addStroke.set(p.color, (1 - t) * 0.9); addStroke.strokeWidth = max(0.5, p.width * (1 - t)).toFloat()
                    c.drawCircle(p.x.toFloat(), p.y.toFloat(), max(0f, r), addStroke)
                }
                PKind.BOLT -> drawBolt(c, p)
                PKind.AFTERIMAGE -> {
                    val h = p.hull ?: continue
                    c.save(); c.translate(p.x.toFloat(), p.y.toFloat()); c.rotate(Math.toDegrees(p.ang).toFloat())
                    hullPath(path, h, (1 - 0.32 * abs(p.bank)).toFloat())
                    stroke.set(p.color, (1 - p.t) * 0.55); stroke.strokeWidth = 2f
                    stroke.blendMode = BlendMode.PLUS
                    c.drawPath(path, stroke)
                    stroke.blendMode = null
                    c.restore()
                }
                else -> {}
            }
        }
    }

    private fun drawBolt(c: Canvas, p: io.github.aloualou56.nebularequiem.core.Particle) {
        val pts = p.pts ?: return
        val k = 1 - p.t
        // Flicker on the bolt's own 60 Hz clock, so it crackles the same at any refresh rate.
        val f = sin((p.seed + floor(p.age * 60)) * 12.9898) * 43758.5453
        if (f - floor(f) < 0.25) return
        var n = 0
        for (i in 0 until p.n - 1) { lines[n++] = pts[i * 2]; lines[n++] = pts[i * 2 + 1]; lines[n++] = pts[i * 2 + 2]; lines[n++] = pts[i * 2 + 3] }
        addStroke.set(p.color, k * 0.35); addStroke.strokeWidth = 6f
        c.drawLines(lines, 0, n, addStroke)
        addStroke.set(Pal.WHITE, k); addStroke.strokeWidth = 1.4f
        c.drawLines(lines, 0, n, addStroke)
    }

    private fun drawNova(c: Canvas) {
        val n = Game.nova ?: return
        val r = ix(n.pr, n.r)
        val k = 1 - r / n.max.toFloat()
        addStroke.set(Pal.WHITE, 0.85 * k); addStroke.strokeWidth = (6 + 10 * k)
        c.drawCircle(n.x.toFloat(), n.y.toFloat(), r, addStroke)
        addStroke.set(Pal.PLASMA, 0.5 * k); addStroke.strokeWidth = 30 * k + 4
        c.drawCircle(n.x.toFloat(), n.y.toFloat(), max(0f, r - 18), addStroke)
        addStroke.set(Pal.ION, 0.18 * k); addStroke.strokeWidth = 60 * k + 2
        c.drawCircle(n.x.toFloat(), n.y.toFloat(), max(0f, r - 60), addStroke)
    }

    private val fm = Paint.FontMetrics()
    private fun drawTexts(c: Canvas) {
        val list = Fx.layer(PLayer.TOP)
        if (list.isEmpty()) return
        textStroke.color = 0xD905030D.toInt()
        for (p in list) {
            val t = p.t; val pop = Ease.outBack(kotlin.math.min(1.0, t * 4)).toFloat()
            if (pop <= 0.001f) continue
            val al = if (t > 0.6) (1 - t) / 0.4 else 1.0
            c.save()
            c.translate(ix(p.px, p.x), ix(p.py, p.y))
            c.scale(pop, pop)
            text.textSize = p.size.toFloat(); textStroke.textSize = p.size.toFloat()
            text.getFontMetrics(fm)
            val by = -(fm.ascent + fm.descent) / 2
            textStroke.alpha = alpha255(al * 0.85)
            c.drawText(p.text, 0f, by, textStroke)
            text.color = p.color; text.alpha = alpha255(al)
            c.drawText(p.text, 0f, by, text)
            c.restore()
        }
    }

    /** Mouse crosshair in surface pixels. */
    fun drawCrosshair(c: Canvas, mx: Float, my: Float, d: Float, t: Double) {
        val r = ((10 + 2 * Audio.beat) * d).toFloat()
        addStroke.set(Pal.ION, 0.9); addStroke.strokeWidth = 1.5f * d
        var n = 0
        for (i in 0 until 4) {
            val ang = t * 1.5 + (i / 4.0) * TAU; val cs = cos(ang).toFloat(); val sn = sin(ang).toFloat()
            lines[n++] = mx + cs * r; lines[n++] = my + sn * r; lines[n++] = mx + cs * (r + 7 * d); lines[n++] = my + sn * (r + 7 * d)
        }
        c.drawLines(lines, 0, n, addStroke)
        fill.set(Pal.WHITE, 0.9)
        c.drawRect(mx - d, my - d, mx + d, my + d, fill)
    }
}
