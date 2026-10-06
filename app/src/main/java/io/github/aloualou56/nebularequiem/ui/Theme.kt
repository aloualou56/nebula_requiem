package io.github.aloualou56.nebularequiem.ui

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import io.github.aloualou56.nebularequiem.core.ColorUtil
import io.github.aloualou56.nebularequiem.core.Pal
import io.github.aloualou56.nebularequiem.render.Fonts
import kotlin.math.max
import kotlin.math.min

/**
 * The UI's design tokens and drawing primitives: chamfered glass slabs, glow text, kbd chips and
 * glitch titles. All UI is laid out in dp.
 */
object Theme {
    val EASE_OUT_EXPO = io.github.aloualou56.nebularequiem.core.CubicBezier.OUT_EXPO
    val EASE_WARP = io.github.aloualou56.nebularequiem.core.CubicBezier.WARP
    val EASE_SNAP = io.github.aloualou56.nebularequiem.core.CubicBezier.SNAP

    fun withA(c: Int, a: Double): Int = ColorUtil.withAlpha(c, a)
    fun withA(c: Int, a: Float): Int = ColorUtil.withAlpha(c, a.toDouble())
}

/** A text style: font, size (dp), letter spacing (em), colour, plus an optional glow shadow. */
class TextStyle(val typeface: Typeface, val size: Float, val spacing: Float = 0f, val color: Int = Pal.INK, val upper: Boolean = false) {
    fun with(size: Float = this.size, color: Int = this.color): TextStyle = TextStyle(typeface, size, spacing, color, upper)
}

object Styles {
    val eyebrow get() = TextStyle(Fonts.mono400, 11f, 0.28f, Pal.INK_DIM, true)
    val hudLabel get() = TextStyle(Fonts.mono400, 10f, 0.26f, Pal.INK_DIM, true)
    /** The same label look in the UI font (toggle-row, chip and reward labels). */
    val hudLabelUi get() = TextStyle(Fonts.ui400, 10f, 0.26f, Pal.INK_DIM, true)
    val btn get() = TextStyle(Fonts.display600, 15f, 0.2f, Pal.INK, true)
    val btnSm get() = TextStyle(Fonts.display600, 12f, 0.16f, Pal.INK, true)
    val body get() = TextStyle(Fonts.ui400, 13f, 0f, Pal.INK_DIM)
    val mono get() = TextStyle(Fonts.mono400, 12f, 0f, Pal.INK)
}

/**
 * Text measuring, wrapping (cached) and drawing with letter spacing and alignment.
 * Wrapped layouts are cached per (text, width, style) so steady-state frames don't allocate.
 *
 * Text is laid out at its dp size on a density-scaled canvas, so the paints use linear, unhinted
 * metrics: glyph advances stay fractional instead of being rounded at the small dp em
 * (rounding made 11 px monospace about 4% wider and changed where lines wrap).
 */
object Txt {
    /** Default line-height factor for text. */
    const val LH = 1.5f
    private const val FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG
    val paint = Paint(FLAGS).apply { hinting = Paint.HINTING_OFF }
    private val strokePaint = Paint(FLAGS).apply { hinting = Paint.HINTING_OFF; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val fm = Paint.FontMetrics()
    private val metrics = Paint(FLAGS).apply { hinting = Paint.HINTING_OFF }
    private val upperCache = HashMap<String, String>()
    private class WrapKey(val text: String, val width: Int, val size: Float, val tf: Typeface, val sp: Float) {
        override fun equals(other: Any?): Boolean = other is WrapKey && other.text == text && other.width == width && other.size == size && other.tf == tf && other.sp == sp
        override fun hashCode(): Int = ((text.hashCode() * 31 + width) * 31 + size.hashCode()) * 31 + tf.hashCode()
    }
    private val wrapCache = object : LinkedHashMap<WrapKey, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<WrapKey, List<String>>?): Boolean = size > 400
    }

    fun upper(s: String): String = upperCache.getOrPut(s) { s.uppercase() }
    fun text(s: String, st: TextStyle): String = if (st.upper) upper(s) else s

    fun apply(st: TextStyle, p: Paint = paint): Paint {
        p.typeface = st.typeface; p.textSize = st.size; p.letterSpacing = st.spacing; p.color = st.color
        p.shader = null; p.blendMode = null
        return p
    }

    fun width(s: String, st: TextStyle): Float { apply(st); return adv(paint, text(s, st)) }

    /**
     * Width of a run including the letter-spacing after the last glyph, which Android's measureText
     * leaves out (without it, centred text would shift left by half of it).
     */
    private fun adv(p: Paint, t: String): Float = if (t.isEmpty()) 0f else p.measureText(t) + p.letterSpacing * p.textSize

    /** Line height in dp for a style (line-height factor `lh`). */
    fun lineHeight(st: TextStyle, lh: Float = LH): Float = st.size * lh

    /** Baseline offset from the top of a line box of height `lineH`. */
    fun baseline(st: TextStyle, lineH: Float): Float {
        // A separate paint: callers evaluate this after setting the draw colour on `paint`.
        metrics.typeface = st.typeface; metrics.textSize = st.size
        metrics.getFontMetrics(fm)
        val textH = fm.descent - fm.ascent
        return (lineH - textH) / 2 - fm.ascent
    }

    fun wrap(s: String, st: TextStyle, maxWidth: Float): List<String> {
        val t = text(s, st)
        val key = WrapKey(t, maxWidth.toInt(), st.size, st.typeface, st.spacing)
        wrapCache[key]?.let { return it }
        apply(st)
        val out = ArrayList<String>()
        for (para in t.split('\n')) {
            if (para.isEmpty()) { out.add(""); continue }
            val line = StringBuilder()
            for (word in para.split(' ')) {
                // Line breaking also allows a break after a hyphen inside a word ("hull-|width").
                for ((pi, piece) in hyphenPieces(word).withIndex()) {
                    val sep = if (pi == 0 && line.isNotEmpty()) " " else ""
                    val cand = "$line$sep$piece"
                    if (line.isEmpty() || adv(paint, cand) <= maxWidth) {
                        if (line.isEmpty() && adv(paint, piece) > maxWidth) {
                            // break an over-long piece
                            var cur = StringBuilder()
                            for (ch in piece) { if (adv(paint, cur.toString() + ch) > maxWidth && cur.isNotEmpty()) { out.add(cur.toString()); cur = StringBuilder() }; cur.append(ch) }
                            line.setLength(0); line.append(cur)
                        } else { line.setLength(0); line.append(cand) }
                    } else { out.add(line.toString()); line.setLength(0); line.append(piece) }
                }
            }
            out.add(line.toString())
        }
        wrapCache[key] = out
        return out
    }

    /** Split after each hyphen that sits between a letter and a letter ("(auto-fire" → "(auto-", "fire"). */
    private fun hyphenPieces(word: String): List<String> {
        if (word.indexOf('-') <= 0) return listOf(word)
        val pieces = ArrayList<String>(2)
        var start = 0
        for (i in 1 until word.length - 1) {
            if (word[i] == '-' && word[i - 1].isLetter() && word[i + 1].isLetter()) { pieces.add(word.substring(start, i + 1)); start = i + 1 }
        }
        pieces.add(word.substring(start))
        return pieces
    }

    /** Draw a single line with its line box top at y. align: -1 left, 0 centre, 1 right. */
    fun draw(c: Canvas, s: String, st: TextStyle, x: Float, y: Float, align: Int = -1, lineH: Float = st.size * LH, alpha: Float = 1f, color: Int = st.color): Float {
        val t = text(s, st)
        apply(st)
        paint.color = color
        paint.alpha = (((color ushr 24) / 255f) * alpha * 255).toInt().coerceIn(0, 255)
        val w = adv(paint, t)
        val dx = when (align) { 0 -> x - w / 2; 1 -> x - w; else -> x }
        c.drawText(t, dx, y + baseline(st, lineH), paint)
        return w
    }

    /** Text with a glow, drawn as a soft shadow layer under the glyphs. */
    fun drawGlow(c: Canvas, s: String, st: TextStyle, x: Float, y: Float, align: Int, glow: Int, radius: Float, lineH: Float = st.size * LH, alpha: Float = 1f, color: Int = st.color) {
        val t = text(s, st)
        apply(st)
        paint.color = color
        paint.alpha = (((color ushr 24) / 255f) * alpha * 255).toInt().coerceIn(0, 255)
        if (radius > 0.5f && (glow ushr 24) > 0) paint.setShadowLayer(radius, 0f, 0f, Theme.withA(glow, ((glow ushr 24) / 255f) * alpha))
        val w = adv(paint, t)
        val dx = when (align) { 0 -> x - w / 2; 1 -> x - w; else -> x }
        c.drawText(t, dx, y + baseline(st, lineH), paint)
        paint.clearShadowLayer()
    }

    private val blurPaint = Paint(FLAGS).apply { hinting = Paint.HINTING_OFF }
    private var blurRadius = -1f

    /**
     * Only the glow of a glyph run: its filled shape blurred in `color`. The glow of transparent,
     * stroked text (the outline logo) comes from the filled glyphs, so it fills them.
     * `cssBlur` is the blur radius (2σ).
     */
    fun drawShadowOnly(c: Canvas, s: String, st: TextStyle, x: Float, y: Float, align: Int, color: Int, cssBlur: Float, lineH: Float, alpha: Float = 1f) {
        val t = text(s, st)
        apply(st, blurPaint)
        // BlurMaskFilter radius r gives σ ≈ 0.57735·r + 0.5
        val r = max(0.5f, (cssBlur / 2 - 0.5f) / 0.57735f)
        if (r != blurRadius) { blurPaint.maskFilter = android.graphics.BlurMaskFilter(r, android.graphics.BlurMaskFilter.Blur.NORMAL); blurRadius = r }
        blurPaint.color = color
        blurPaint.alpha = (((color ushr 24) / 255f) * alpha * 255).toInt().coerceIn(0, 255)
        val w = adv(blurPaint, t)
        val dx = when (align) { 0 -> x - w / 2; 1 -> x - w; else -> x }
        c.drawText(t, dx, y + baseline(st, lineH), blurPaint)
    }

    /** Outline text (-webkit-text-stroke). */
    fun drawStroke(c: Canvas, s: String, st: TextStyle, x: Float, y: Float, align: Int, strokeW: Float, color: Int, glow: Int, glowR: Float, lineH: Float, alpha: Float = 1f) {
        val t = text(s, st)
        apply(st, strokePaint)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = strokeW
        strokePaint.color = color
        strokePaint.alpha = (((color ushr 24) / 255f) * alpha * 255).toInt().coerceIn(0, 255)
        if (glowR > 0.5f) strokePaint.setShadowLayer(glowR, 0f, 0f, Theme.withA(glow, ((glow ushr 24) / 255f) * alpha))
        val w = adv(strokePaint, t)
        val dx = when (align) { 0 -> x - w / 2; 1 -> x - w; else -> x }
        c.drawText(t, dx, y + baseline(st, lineH), strokePaint)
        strokePaint.clearShadowLayer()
    }

    /** Draw wrapped paragraphs; returns the total height. */
    fun drawWrapped(c: Canvas, s: String, st: TextStyle, x: Float, y: Float, maxWidth: Float, lh: Float = LH, alpha: Float = 1f, align: Int = -1): Float {
        val lines = wrap(s, st, maxWidth)
        val lineH = st.size * lh
        var yy = y
        for (l in lines) { draw(c, l, st, if (align == 0) x + maxWidth / 2 else x, yy, align, lineH, alpha); yy += lineH }
        return lines.size * lineH
    }

    fun wrappedHeight(s: String, st: TextStyle, maxWidth: Float, lh: Float = LH): Float = wrap(s, st, maxWidth).size * st.size * lh
}

/** Chamfered glass slab and related decorations. */
object Deco {
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val glowPath = Path()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }

    /**
     * A glow (0 0 blur colour) around a circle: unlike Paint.setShadowLayer on a translucent
     * fill, the glow stays outside the element and never tints its inside.
     */
    fun outerGlowCircle(c: Canvas, x: Float, y: Float, r: Float, blur: Float, color: Int) {
        if (blur <= 0.5f || (color ushr 24) == 0) return
        glowPath.rewind(); glowPath.addCircle(x, y, r, Path.Direction.CW)
        c.save()
        c.clipOutPath(glowPath)
        glowPaint.setShadowLayer(blur, 0f, 0f, color)
        c.drawCircle(x, y, r, glowPaint)
        c.restore()
    }

    /** polygon(cut 0, 100% 0, 100% calc(100% − cut), calc(100% − cut) 100%, 0 100%, 0 cut) */
    fun chamfer(p: Path, x: Float, y: Float, w: Float, h: Float, cut: Float) {
        val k = min(cut, min(w, h) / 2)
        p.rewind()
        p.moveTo(x + k, y); p.lineTo(x + w, y); p.lineTo(x + w, y + h - k); p.lineTo(x + w - k, y + h); p.lineTo(x, y + h); p.lineTo(x, y + k); p.close()
    }

    /**
     * Slab: chamfered panel with a dark violet glass gradient (165°), an inset 1 px edge line at
     * 26% and 1.5 px bright stripes hugging both cut corners.
     */
    const val SLAB_TOP = 0xD61A0F3C.toInt()
    const val SLAB_BOTTOM = 0xE6070414.toInt()
    private val slabShader = LinearGradient(0.37f, 0f, 0.63f, 1f, SLAB_TOP, SLAB_BOTTOM, Shader.TileMode.CLAMP)
    private val backdropShader = RadialGradient(0f, 0f, 1f, Theme.withA(Pal.VOID, 0.55f), Theme.withA(Pal.VOID, 0.88f), Shader.TileMode.CLAMP)
    private val m = android.graphics.Matrix()

    fun slab(c: Canvas, x: Float, y: Float, w: Float, h: Float, cut: Float, edge: Int, alpha: Float = 1f, bgTop: Int = SLAB_TOP, bgBottom: Int = SLAB_BOTTOM) {
        if (w <= 0 || h <= 0 || alpha <= 0.003f) return
        chamfer(path, x, y, w, h, cut)
        // 165deg gradient: mostly top → bottom with a slight lean (a unit gradient mapped onto the box)
        val sh = if (bgTop == SLAB_TOP && bgBottom == SLAB_BOTTOM) slabShader else LinearGradient(0.37f, 0f, 0.63f, 1f, bgTop, bgBottom, Shader.TileMode.CLAMP)
        m.setScale(w, h); m.postTranslate(x, y)
        sh.setLocalMatrix(m)
        fill.shader = sh; fill.color = Pal.WHITE; fill.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        c.drawPath(path, fill)
        fill.shader = null; fill.alpha = 255
        // inset 0 0 0 1px edge/.26: a 1 px band inside the border box; the clip removes the cut corners
        stroke.color = Theme.withA(edge, 0.26f * alpha); stroke.strokeWidth = 2f
        c.save(); c.clipPath(path); c.drawRect(x, y, x + w, y + h, stroke); c.restore()
        cornerStripes(c, x, y, w, h, cut, edge, 0.95f * alpha)
    }

    /** The diagonal stripes on the cut corners (`.btn` has only the top-left one: bottomRight = false). */
    fun cornerStripes(c: Canvas, x: Float, y: Float, w: Float, h: Float, cut: Float, edge: Int, alpha: Float, width: Float = 1.5f, bottomRight: Boolean = true) {
        val k = min(cut, min(w, h) / 2)
        stroke.color = Theme.withA(edge, alpha); stroke.strokeWidth = width * 1.6f
        stroke.strokeCap = Paint.Cap.BUTT
        c.save(); chamfer(path, x, y, w, h, cut); c.clipPath(path)
        c.drawLine(x, y + k, x + k, y, stroke)
        if (bottomRight) c.drawLine(x + w - k, y + h, x + w, y + h - k, stroke)
        c.restore()
    }

    /**
     * A key chip, vertically centred on cy; returns its width. Chips take their parent's font
     * size, so callers pass it.
     */
    fun kbd(c: Canvas, label: String, right: Float, cy: Float, alpha: Float = 1f, size: Float = 15f): Float {
        val h = kbdHeight(size)
        return kbdBox(c, label, right, cy - h / 2, h, alpha, size)
    }

    /**
     * A key chip stretched to `height` (a flex row with align-items: stretch): border and background
     * cover the whole row while the label sits in the first line. Returns its width.
     */
    fun kbdBox(c: Canvas, label: String, right: Float, top: Float, height: Float, alpha: Float = 1f, size: Float = 15f): Float {
        val st = kbdStyle(size)
        val w = Txt.width(label, st) + 14f
        rect.set(right - w, top, right, top + height)
        fill.color = Theme.withA(Pal.LINE, 0.08f * alpha); c.drawRoundRect(rect, 3f, 3f, fill)
        stroke.color = Theme.withA(Pal.LINE, 0.35f * alpha); stroke.strokeWidth = 1f
        rect.inset(0.5f, 0.5f); c.drawRoundRect(rect, 3f, 3f, stroke)
        Txt.draw(c, label, st, right - w / 2, top + 3f, 0, size * Txt.LH, alpha)
        return w
    }

    /** padding 2px 6px + 1px border around one line (line-height 1.5). */
    fun kbdHeight(size: Float = 15f): Float = size * Txt.LH + 6f
    fun kbdWidth(label: String, size: Float = 15f): Float = Txt.width(label, kbdStyle(size)) + 14f

    private val kbdStyles = HashMap<Float, TextStyle>()
    private fun kbdStyle(size: Float): TextStyle = kbdStyles.getOrPut(size) { TextStyle(Fonts.mono400, size, 0.08f, Pal.INK_DIM, false) }

    /** `.screen--modal` backdrop: radial-gradient(rgb(5 3 13 / .55) → / .88). */
    fun modalBackdrop(c: Canvas, w: Float, h: Float, alpha: Float) {
        val r = max(w, h) * 0.6f
        m.setScale(r, r); m.postTranslate(w / 2, h / 2)
        backdropShader.setLocalMatrix(m)
        fill.shader = backdropShader; fill.color = Pal.WHITE; fill.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null; fill.alpha = 255
    }

    /** The stardust glyph: a four-point star (clip-path polygon) with a solar glow. */
    fun dustGlyph(c: Canvas, cx: Float, cy: Float, size: Float, alpha: Float = 1f) {
        val s = size / 2
        path.rewind()
        path.moveTo(cx, cy - s); path.lineTo(cx + s * .24f, cy - s * .24f); path.lineTo(cx + s, cy); path.lineTo(cx + s * .24f, cy + s * .24f)
        path.lineTo(cx, cy + s); path.lineTo(cx - s * .24f, cy + s * .24f); path.lineTo(cx - s, cy); path.lineTo(cx - s * .24f, cy - s * .24f); path.close()
        fill.color = Theme.withA(Pal.SOLAR, alpha)
        fill.setShadowLayer(8f, 0f, 0f, Theme.withA(Pal.SOLAR, alpha))
        c.drawPath(path, fill)
        fill.clearShadowLayer()
    }

    fun fillRect(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int) { fill.shader = null; fill.color = color; c.drawRect(x0, y0, x1, y1, fill) }
    fun fillPaint(): Paint { fill.shader = null; fill.blendMode = null; fill.clearShadowLayer(); return fill }
    fun strokePaint(): Paint { stroke.shader = null; stroke.pathEffect = null; stroke.blendMode = null; stroke.strokeCap = Paint.Cap.BUTT; return stroke }
    val add: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
}

/**
 * `.glitch`: two clipped copies of a title (plasma and ion, screen-blended) that jump sideways in
 * short bursts (3.2 s / 2.7 s cycles; 0.9 s / 0.75 s when "hot").
 */
object Glitch {
    private val A_KEYS = floatArrayOf(.85f, .87f, .89f, .91f, .93f)
    private val A_CLIP = floatArrayOf(.08f, .62f, .48f, .22f, .72f, .06f, .22f, .50f)
    private val A_DX = floatArrayOf(-7f, 5f, -3f, 8f); private val A_DY = floatArrayOf(-1f, 1f, 0f, -2f)
    private val B_KEYS = floatArrayOf(.79f, .81f, .83f, .85f)
    private val B_CLIP = floatArrayOf(.62f, .12f, .14f, .70f, .38f, .38f)
    private val B_DX = floatArrayOf(6f, -6f, 3f); private val B_DY = floatArrayOf(1f, 0f, -1f)

    /**
     * Calls `drawCopy(color, dx, dy)` inside a clip band for each active burst. `top`/`height`
     * describe the text box; `tSec` is the screen's clock.
     */
    inline fun bursts(c: Canvas, tSec: Double, hot: Boolean, left: Float, top: Float, width: Float, height: Float, drawCopy: (color: Int, dx: Float, dy: Float) -> Unit) {
        val pa = if (hot) 0.9 else 3.2; val pb = if (hot) 0.75 else 2.7
        val fa = ((tSec % pa) / pa).toFloat(); val fb = ((tSec % pb) / pb).toFloat()
        val ia = indexA(fa)
        if (ia >= 0) {
            val t0 = clipTop(true, ia); val b0 = clipBottom(true, ia)
            c.save(); c.clipRect(left - 20, top + height * t0, left + width + 20, top + height * (1 - b0))
            drawCopy(Pal.PLASMA, dxA(ia), dyA(ia)); c.restore()
        }
        val ib = indexB(fb)
        if (ib >= 0) {
            val t0 = clipTop(false, ib); val b0 = clipBottom(false, ib)
            c.save(); c.clipRect(left - 20, top + height * t0, left + width + 20, top + height * (1 - b0))
            drawCopy(Pal.ION, dxB(ib), dyB(ib)); c.restore()
        }
    }

    fun indexA(f: Float): Int { for (i in 0 until 4) if (f >= A_KEYS[i] && f < A_KEYS[i + 1]) return i; return -1 }
    fun indexB(f: Float): Int { for (i in 0 until 3) if (f >= B_KEYS[i] && f < B_KEYS[i + 1]) return i; return -1 }
    fun clipTop(a: Boolean, i: Int) = if (a) A_CLIP[i * 2] else B_CLIP[i * 2]
    fun clipBottom(a: Boolean, i: Int) = if (a) A_CLIP[i * 2 + 1] else B_CLIP[i * 2 + 1]
    fun dxA(i: Int) = A_DX[i]; fun dyA(i: Int) = A_DY[i]; fun dxB(i: Int) = B_DX[i]; fun dyB(i: Int) = B_DY[i]
}
