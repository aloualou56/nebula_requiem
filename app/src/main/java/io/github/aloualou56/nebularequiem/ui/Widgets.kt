package io.github.aloualou56.nebularequiem.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import io.github.aloualou56.nebularequiem.core.Pal
import io.github.aloualou56.nebularequiem.render.Fonts
import kotlin.math.max
import kotlin.math.min

/** Interactive element laid out in a screen's content coordinates (dp). */
abstract class Widget {
    val r = RectF()
    /** Stagger index. */
    var d = 0
    var focusable = true
    var enabled = true
    var visible = true
    var onClick: (() -> Unit)? = null
    /** ms timestamps for animations */
    var hoverAt = -1e9; var denyAt = -1e9; var pulseAt = -1e9
    /** When the pointer now pressing it went down (ms, event time). */
    var downAt = -1e9
    var hovered = false
    var pressed = false
    /** Expand the hit area to at least this many dp (touch targets). */
    open val minHit: Float get() = 44f

    fun hit(x: Float, y: Float, slop: Float = 0f): Boolean {
        if (!visible) return false
        val ex = max(0f, (minHit - r.width()) / 2) + slop; val ey = max(0f, (minHit - r.height()) / 2) + slop
        return x >= r.left - ex && x <= r.right + ex && y >= r.top - ey && y <= r.bottom + ey
    }

    abstract fun draw(c: Canvas, ctx: DrawCtx)
    /** Drag handling (sliders); return true when consumed. */
    open fun drag(x: Float, y: Float): Boolean = false
    open fun keyAdjust(dir: Int): Boolean = false
}

/** Per-frame drawing context for widgets. */
class DrawCtx {
    var now = 0.0          // ms
    var alpha = 1f         // screen × stagger opacity
    var focused: Widget? = null
    var showFocus = false
    var touchMode = false
    var compact = false
}

enum class BtnKind(val edge: Int) { PRIMARY(Pal.ION), SOLAR(Pal.SOLAR), GHOST(Pal.LINE), CRIMSON(Pal.CRIMSON), PLASMA(Pal.PLASMA) }

/**
 * `.btn`: chamfered neon button. Label left, kbd hint (or a numeric value) right. Hover/focus
 * brighten the edge, glow inside, shift 4 px right and sweep a light band across; press scales
 * down; a denied press shakes and flashes crimson.
 */
class Button(var label: String, var kind: BtnKind = BtnKind.GHOST, var kbd: String? = null, var small: Boolean = false) : Widget() {
    var value: String? = null
    var dimmed = false   // style opacity .55 (unaffordable)
    var labelStyleOverride: TextStyle? = null

    override val minHit: Float get() = 44f

    fun prefHeight(touch: Boolean, compact: Boolean): Float = if (small) (if (touch || compact) 44f else 36f) else 46f
    fun prefWidth(): Float {
        val st = if (small) Styles.btnSm else Styles.btn
        val pad = if (small) 12f else 18f
        var w = pad * 2 + Txt.width(label, st)
        value?.let { w += 14f + Txt.width(it, valueStyle()) }
        // key hints are hidden in touch mode (#app.touch-mode .btn kbd { display: none })
        if (!io.github.aloualou56.nebularequiem.core.Input.touchMode) kbd?.let { w += 14f + Deco.kbdWidth(it, if (small) 12f else 15f) }
        return w
    }

    override fun draw(c: Canvas, ctx: DrawCtx) {
        if (!visible) return
        val now = ctx.now
        val focus = ctx.showFocus && ctx.focused === this
        val hot = (hovered || focus) && enabled
        var a = ctx.alpha * (if (!enabled) 0.38f else if (dimmed) 0.55f else 1f)
        val denyK = ((now - denyAt) / 380.0).toFloat()
        val denying = denyK in 0f..1f
        val edge = if (denying) Pal.CRIMSON else kind.edge
        var dx = if (hot) 4f * Theme.EASE_OUT_EXPO(min(1.0, (now - hoverAt) / 250.0)).toFloat() else 0f
        if (denying) dx += denyShake(denyK)
        var scale = 1f
        if (pressed && enabled) { dx = 2f; scale = 0.985f }
        val cut = if (small) 8f else 10f
        c.save()
        c.translate(r.left + dx, r.top)
        if (scale != 1f) c.scale(scale, scale, r.width() / 2, r.height() / 2)
        val w = r.width(); val h = r.height()
        Deco.chamfer(path, 0f, 0f, w, h, cut)
        c.save(); c.clipPath(path)
        // background: linear-gradient(90deg, edge/.14 → edge/.03); primary: ion/.32 → plasma/.14
        val p = Deco.fillPaint()
        val c0 = if (kind == BtnKind.PRIMARY) Theme.withA(Pal.ION, 0.32f) else Theme.withA(edge, 0.14f)
        val c1 = if (kind == BtnKind.PRIMARY) Theme.withA(Pal.PLASMA, 0.14f) else Theme.withA(edge, 0.03f)
        bgShader(c0, c1, w)
        p.shader = bg; p.alpha = (a * 255).toInt().coerceIn(0, 255)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null; p.alpha = 255
        // hover sweep band: translateX(−110% → 110%) over .55 s
        if (hot) {
            val k = Theme.EASE_OUT_EXPO(min(1.0, (now - hoverAt) / 550.0)).toFloat()
            val bx = (-1.1f + 2.2f * k) * w
            sweep(edge, w)
            sweepM.setTranslate(bx, 0f); sweepShader!!.setLocalMatrix(sweepM)
            p.shader = sweepShader; p.alpha = (a * 255).toInt().coerceIn(0, 255)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = null; p.alpha = 255
        }
        // inset borders: box-shadows on the rectangle, so the clip-path leaves the cut diagonals bare
        val s = Deco.strokePaint()
        if (focus) { s.color = Theme.withA(edge, a); s.strokeWidth = 4f }
        else if (hot) { s.color = Theme.withA(edge, 0.9f * a); s.strokeWidth = 2f }
        else { s.color = Theme.withA(edge, 0.35f * a); s.strokeWidth = 2f }
        c.drawRect(0f, 0f, w, h, s)
        if (hot) { s.color = Theme.withA(edge, 0.12f * a); s.strokeWidth = 18f; c.drawRect(0f, 0f, w, h, s) }
        c.restore()
        // top-left stripe only (linear-gradient(135deg, edge 0 cut·.7071+1.5px)); .btn has no 315deg one
        Deco.cornerStripes(c, 0f, 0f, w, h, cut, edge, a * if (hot) 1f else 0.9f, bottomRight = false)
        // label & right-side content
        val st = labelStyleOverride ?: if (small) Styles.btnSm else Styles.btn
        val pad = if (small) 12f else 18f
        Txt.draw(c, label, st, pad, 0f, -1, h, a, if (hot) Pal.WHITE else st.color)
        var right = w - pad
        if (kbd != null && !ctx.touchMode) { right -= Deco.kbd(c, kbd!!, right, h / 2, a, if (small) 12f else 15f) + 8f }
        value?.let { val vs = valueStyle(); Txt.draw(c, it, vs, w - pad - if (kbd != null && !ctx.touchMode) Deco.kbdWidth(kbd!!, if (small) 12f else 15f) + 8f else 0f, 0f, 1, h, a, if (hot) Pal.WHITE else vs.color) }
        // card pulse (purchase): brightness flash
        val pk = ((now - pulseAt) / 600.0).toFloat()
        if (pk in 0f..1f) { val fp = Deco.fillPaint(); fp.color = Theme.withA(Pal.WHITE, 0.35f * (1 - pk) * a); c.drawPath(path, fp) }
        c.restore()
    }

    /** `.num` value span: mono, inheriting the button's weight 600 (JetBrains Mono loads 400/700, so 700), size, tracking and case. */
    private fun valueStyle() = TextStyle(Fonts.mono700, if (small) 12f else 15f, if (small) 0.16f else 0.2f, Pal.INK, true)

    private val path = Path()
    private var bg: LinearGradient? = null
    private var bgKey = 0L
    private fun bgShader(c0: Int, c1: Int, w: Float) {
        val key = (c0.toLong() shl 32) xor (c1.toLong() and 0xffffffffL) xor w.toRawBits().toLong()
        if (bg == null || key != bgKey) { bg = LinearGradient(0f, 0f, w, 0f, c0, c1, Shader.TileMode.CLAMP); bgKey = key }
    }
    private var sweepShader: LinearGradient? = null
    private var sweepKey = 0
    private val sweepM = Matrix()
    private fun sweep(edge: Int, w: Float) {
        val key = edge xor w.toRawBits()
        if (sweepShader == null || key != sweepKey) {
            sweepShader = LinearGradient(0f, 0f, w, w * 0.18f, intArrayOf(0, 0, Theme.withA(edge, 0.55f), 0, 0), floatArrayOf(0f, .2f, .5f, .8f, 1f), Shader.TileMode.CLAMP)
            sweepKey = key
        }
    }

    companion object {
        /** deny keyframes: 0 → −7 → 6 → −4 → 2 → 0 px */
        fun denyShake(k: Float): Float {
            val keys = floatArrayOf(0f, -7f, 6f, -4f, 2f, 0f)
            val e = Theme.EASE_OUT_EXPO(k.toDouble()).toFloat() * 5
            val i = min(4, e.toInt()); val f = e - i
            return keys[i] + (keys[i + 1] - keys[i]) * f
        }
    }
}

/** `.toggle` switch with its whole row as the tap target. */
class Toggle(val label: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : Widget() {
    var changedAt = -1e9
    override fun draw(c: Canvas, ctx: DrawCtx) {
        if (!visible) return
        val a = ctx.alpha
        val on = get()
        // .hud-label sets no font-family: outside #hud it inherits the UI font
        Txt.draw(c, label, Styles.hudLabelUi, r.left, r.top, -1, r.height(), a)
        val tw = 46f; val th = if (ctx.compact) 26f else 22f
        val x = r.right - tw; val y = r.centerY() - th / 2
        Deco.chamfer(path, x, y, tw, th, 6f)
        val p = Deco.fillPaint()
        p.color = if (on) Theme.withA(Pal.ION, 0.35f * a) else Theme.withA(Pal.LINE, 0.18f * a)
        c.drawPath(path, p)
        val k = Theme.EASE_SNAP(min(1.0, (ctx.now - changedAt) / 300.0)).toFloat()
        val pos = if (on) k else 1 - k
        val kx = x + 4f + 24f * pos; val ky = r.centerY() - 7f
        p.color = if (on) Theme.withA(Pal.WHITE, a) else Theme.withA(Pal.INK_DIM, a)
        if (on) p.setShadowLayer(10f, 0f, 0f, Theme.withA(Pal.ION, a))
        c.save(); c.clipPath(path)   // .toggle's clip-path also clips the knob's glow
        c.drawRect(kx, ky, kx + 14f, ky + 14f, p)
        c.restore()
        p.clearShadowLayer()
        if (ctx.showFocus && ctx.focused === this) {
            val s = Deco.strokePaint(); s.color = Theme.withA(Pal.ION, a); s.strokeWidth = 1f
            s.pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f, 3f), 0f)
            c.drawRect(r.left - 3, r.top - 1, r.right + 3, r.bottom + 1, s); s.pathEffect = null
        }
    }
    private val path = Path()
    fun toggle(now: Double) { set(!get()); changedAt = now }
    override fun keyAdjust(dir: Int): Boolean = false
}

/** `input[type=range]`: a 4 px track filled in ion with a rotated-square white thumb. */
class Slider(val min: Double, val max: Double, val step: Double, val get: () -> Double, val set: (Double) -> Unit) : Widget() {
    var onCommit: (() -> Unit)? = null
    override val minHit: Float get() = 44f
    override fun draw(c: Canvas, ctx: DrawCtx) {
        if (!visible) return
        val a = ctx.alpha
        val v = get(); val k = ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)
        val cy = r.centerY()
        // the track spans the whole input with the fill split at the value; the 14 px thumb's centre
        // travels 7 px inside each end, as the drag mapping does
        val fx = r.left + r.width() * k
        val tx = r.left + 7f + (r.width() - 14f) * k
        Deco.fillRect(c, r.left, cy - 2f, fx, cy + 2f, Theme.withA(Pal.ION, a))
        Deco.fillRect(c, fx, cy - 2f, r.right, cy + 2f, Theme.withA(Pal.LINE, 0.2f * a))
        val p = Deco.fillPaint()
        p.color = Theme.withA(Pal.WHITE, a)
        p.setShadowLayer(10f, 0f, 0f, Theme.withA(Pal.ION, a))
        c.save(); c.rotate(45f, tx, cy); c.drawRect(tx - 7f, cy - 7f, tx + 7f, cy + 7f, p); c.restore()
        p.clearShadowLayer()
        if (ctx.showFocus && ctx.focused === this) {
            val s = Deco.strokePaint(); s.color = Theme.withA(Pal.ION, a); s.strokeWidth = 1f
            s.pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f, 3f), 0f)
            c.drawRect(r.left - 4, cy - 12, r.right + 4, cy + 12, s); s.pathEffect = null
        }
    }
    override fun drag(x: Float, y: Float): Boolean {
        val x0 = r.left + 7f; val x1 = r.right - 7f
        val k = ((x - x0) / (x1 - x0)).coerceIn(0f, 1f)
        var v = min + (max - min) * k
        v = Math.round(v / step) * step
        set(v.coerceIn(min, max))
        return true
    }
    override fun keyAdjust(dir: Int): Boolean { set((get() + dir * step * 5).coerceIn(min, max)); onCommit?.invoke(); return true }
}

/** `.seg` segmented control. With [chooseOnAdjust], the arrow keys pick a cell at once (instead of moving focus within it). */
class Segmented(val options: List<String>, val selected: () -> Int, private val chooseOnAdjust: Boolean = false, val choose: (Int) -> Unit) : Widget() {
    val cells = ArrayList<RectF>()
    var focusIndex = 0
    private var compact = false
    fun prefWidth(compact: Boolean): Float {
        val st = segStyle(compact)
        val pad = if (compact) 9f else 12f
        var w = 0f
        for (o in options) w += Txt.width(o, st) + pad * 2
        return w
    }
    fun layoutCells(compact: Boolean) {
        this.compact = compact
        cells.clear()
        val st = segStyle(compact); val pad = if (compact) 9f else 12f
        var x = r.left
        for (o in options) { val cw = Txt.width(o, st) + pad * 2; cells.add(RectF(x, r.top, x + cw, r.bottom)); x += cw }
    }
    override fun draw(c: Canvas, ctx: DrawCtx) {
        if (!visible) return
        val a = ctx.alpha
        val s = Deco.strokePaint(); s.color = Theme.withA(Pal.LINE, 0.3f * a); s.strokeWidth = 1f
        val sel = selected()
        if (chooseOnAdjust) focusIndex = sel   // the focus is the choice, so Enter keeps it
        for (i in options.indices) {
            val cr = cells.getOrNull(i) ?: continue
            if (i == sel) Deco.fillRect(c, cr.left, cr.top, cr.right, cr.bottom, Theme.withA(Pal.ION, a))
            Txt.draw(c, options[i], segStyle(compact), cr.centerX(), cr.top, 0, cr.height(), a, if (i == sel) Pal.VOID else Pal.INK_DIM)
            if (ctx.showFocus && ctx.focused === this && i == focusIndex) {
                val f = Deco.strokePaint(); f.color = Theme.withA(Pal.ION, a); f.strokeWidth = 1f
                f.pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f, 3f), 0f)
                c.drawRect(cr.left - 2, cr.top - 2, cr.right + 2, cr.bottom + 2, f); f.pathEffect = null
            }
        }
        val s2 = Deco.strokePaint(); s2.color = Theme.withA(Pal.LINE, 0.3f * a); s2.strokeWidth = 1f
        c.drawRect(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f, s2)
    }
    fun cellAt(x: Float, y: Float): Int { for (i in cells.indices) { val cr = cells[i]; if (x >= cr.left && x < cr.right && y >= cr.top - 8 && y <= cr.bottom + 8) return i }; return -1 }
    override fun keyAdjust(dir: Int): Boolean {
        if (chooseOnAdjust) { focusIndex = (selected() + dir).coerceIn(0, options.size - 1); if (focusIndex != selected()) choose(focusIndex); return true }
        focusIndex = (focusIndex + dir).coerceIn(0, options.size - 1); return true
    }

    /** `.seg button`: mono 11px, .14em (.1em in the landscape-phone query). */
    companion object { fun segStyle(compact: Boolean) = TextStyle(Fonts.mono400, 11f, if (compact) 0.1f else 0.14f, Pal.INK_DIM, true) }
}
