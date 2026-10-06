package io.github.aloualou56.nebularequiem.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import io.github.aloualou56.nebularequiem.core.Audio
import io.github.aloualou56.nebularequiem.core.Pal
import io.github.aloualou56.nebularequiem.core.Sfx
import io.github.aloualou56.nebularequiem.core.Tone
import io.github.aloualou56.nebularequiem.render.Fonts
import kotlin.math.max
import kotlin.math.min

/**
 * The warp transition: eight skewed slats fall through three resting states (above → covering →
 * below) with a staggered warp easing — matrix(1.28, 0, ∓0.36, 1) per slat — while a glitching
 * label flashes in the middle. `mid` runs while the screen is covered.
 */
class Transition(private val ui: Ui) {
    var busy = false
        private set
    private var label = ""
    private var startAt = 0.0
    private var midAt = 0.0
    private var revealAt = 0.0
    private var endAt = 0.0
    private var labelOnAt = 0.0
    private var labelOffAt = 0.0
    private var mid: (() -> Unit)? = null
    private var midDone = false
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var slatShader: LinearGradient? = null
    private var slatH = -1f
    private val m = Matrix()

    private val slatDur get() = if (ui.reducedMotion) 250.0 else 520.0
    private val span get() = if (ui.reducedMotion) 260.0 else 520.0 + 7 * 42.0

    /** True while the slats fully cover the screen (input is blocked). */
    val covering: Boolean get() = busy

    fun run(label: String, now: Double, mid: () -> Unit) {
        if (busy) { mid(); return }
        busy = true
        this.label = label
        this.mid = mid
        midDone = false
        Audio.play(Sfx.TRANSITION)
        startAt = now
        labelOnAt = now + if (ui.reducedMotion) 0.0 else 220.0
        midAt = now + span
        revealAt = midAt + if (ui.reducedMotion) 60.0 else 180.0
        labelOffAt = revealAt
        endAt = revealAt + span
    }

    fun update(now: Double) {
        if (!busy) return
        if (!midDone && now >= midAt) {
            midDone = true
            try { mid?.invoke() } catch (e: Exception) { android.util.Log.e("NebulaRequiem", "transition", e) }
            mid = null
        }
        if (now >= endAt) busy = false
    }

    fun draw(c: Canvas, now: Double) {
        if (!busy) return
        val W = ui.layout.w; val H = ui.layout.h
        val sw = W / 8f
        if (slatShader == null || slatH != H) {
            slatShader = LinearGradient(0f, 0f, 0f, H, intArrayOf(0xFF0F0828.toInt(), Pal.VOID, 0xFF140A33.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            slatH = H
        }
        for (i in 0 until 8) {
            val covering = now < revealAt
            val t0 = (if (covering) startAt else revealAt) + i * 42.0
            val k = Theme.EASE_WARP(((now - t0) / slatDur).coerceIn(0.0, 1.0)).toFloat()
            val ty: Float; val skew: Float
            if (covering) { ty = -1.18f * H * (1 - k); skew = -0.36f }
            else { ty = 1.18f * H * k; skew = -0.36f + 0.72f * k }
            if (ty <= -H * 1.17f || ty >= H * 1.17f) continue
            val cx = i * sw + sw / 2; val cy = H / 2
            c.save()
            c.translate(0f, ty)
            // matrix(1.28, 0, skew, 1) about the slat centre
            m.setValues(floatArrayOf(1.28f, skew, cx - 1.28f * cx - skew * cy, 0f, 1f, 0f, 0f, 0f, 1f))
            c.concat(m)
            fill.shader = slatShader
            c.drawRect(i * sw, 0f, (i + 1) * sw + 0.5f, H, fill)
            fill.shader = null
            stroke.strokeWidth = 1f; stroke.color = Theme.withA(Pal.ION, 0.12f)
            c.drawRect(i * sw, 0f, (i + 1) * sw, H, stroke)
            fill.color = Theme.withA(Pal.PLASMA, 0.7f); c.drawRect(i * sw, H - 2f, (i + 1) * sw, H, fill)
            fill.color = Theme.withA(Pal.ION, 0.5f); c.drawRect(i * sw, 0f, (i + 1) * sw, 2f, fill)
            c.restore()
        }
        // label: opacity .25 s, scale .92 → 1 over .5 s
        if (now >= labelOnAt) {
            val on = now < labelOffAt
            val k = if (on) min(1.0, (now - labelOnAt) / 250.0) else max(0.0, 1 - (now - labelOffAt) / 250.0)
            if (k > 0.01) {
                val sk = if (on) 0.92f + 0.08f * Theme.EASE_OUT_EXPO(min(1.0, (now - labelOnAt) / 500.0)).toFloat() else 1f
                val size = (W * 0.05f).coerceIn(22f, 54f)
                val st = TextStyle(Fonts.display900, size, 0.4f, Pal.WHITE, true)
                c.save(); c.scale(sk, sk, W / 2, H / 2)
                val lh = size * Txt.LH
                val top = H / 2 - lh / 2
                val tw = Txt.width(label, st)
                Glitch.bursts(c, (now - labelOnAt) / 1000.0, true, W / 2 - tw / 2, top, tw, lh) { col, dx, dy ->
                    Txt.draw(c, label, st, W / 2 + dx, top + dy, 0, lh, k.toFloat() * 0.9f, col)
                }
                Txt.drawGlow(c, label, st, W / 2, top, 0, Theme.withA(Pal.ION, 0.8f), 24f, lh, k.toFloat())
                c.restore()
            }
        }
    }
}

/** Centre-screen banner (`#banner`): a title that unfolds from a squashed blur and a mono subtitle. */
class Banner(private val ui: Ui) {
    private var title = ""; private var sub = ""; private var tone = Tone.ION
    private var onAt = -1e9; private var offAt = -1e9
    fun show(title: String, sub: String, tone: Tone, dur: Double, now: Double) {
        this.title = title; this.sub = sub; this.tone = tone
        onAt = now; offAt = now + dur * 1000
    }
    fun draw(c: Canvas, now: Double) {
        if (now > offAt + 600 || now < onAt) return
        val L = ui.layout
        val W = L.w; val H = L.h
        val col = tone.color
        val size = if (L.compact) (H * 0.11f).coerceIn(28f, 60f) else (W * 0.06f).coerceIn(30f, 72f)
        val st = TextStyle(Fonts.display900, size, 0.12f, if (tone == Tone.WARN) Pal.CRIMSON else Pal.WHITE, true)
        val top = H * if (L.compact) 0.44f else 0.30f
        val lh = size * Txt.LH
        // title animation
        var a: Float; var sx: Float; var sy: Float
        if (now < offAt) {
            val k = min(1.0, (now - onAt) / 700.0).toFloat()
            val e = Theme.EASE_OUT_EXPO(k.toDouble()).toFloat()
            if (e < 0.6f) { val f = e / 0.6f; a = f; sx = 1.6f + (0.96f - 1.6f) * f; sy = 0.2f + (1.05f - 0.2f) * f }
            else { val f = (e - 0.6f) / 0.4f; a = 1f; sx = 0.96f + 0.04f * f; sy = 1.05f - 0.05f * f }
        } else {
            val k = Theme.EASE_WARP(min(1.0, (now - offAt) / 500.0)).toFloat()
            a = 1 - k; sx = 1f + 0.8f * k; sy = 1f - 0.95f * k
        }
        if (a > 0.01f) {
            c.save(); c.scale(sx, sy, W / 2, top + lh / 2)
            val tw = Txt.width(title, st)
            Glitch.bursts(c, (now - onAt) / 1000.0, false, W / 2 - tw / 2, top, tw, lh) { gc, dx, dy -> Txt.draw(c, title, st, W / 2 + dx, top + dy, 0, lh, a * 0.9f, gc) }
            Txt.drawGlow(c, title, st, W / 2, top, 0, Theme.withA(col, 0.75f), 22f, lh, a)
            c.restore()
        }
        // subtitle: rise .6 s after .25 s; fade .4 s on exit
        if (sub.isNotEmpty()) {
            val subA: Float; val dy: Float
            if (now < offAt) { val k = Theme.EASE_OUT_EXPO(((now - onAt - 250) / 600.0).coerceIn(0.0, 1.0)).toFloat(); subA = k; dy = 16f * (1 - k) }
            else { subA = max(0f, 1f - ((now - offAt) / 400.0).toFloat()); dy = 0f }
            if (subA > 0.01f) {
                val ss = TextStyle(Fonts.mono400, 13f, 0.4f, col, true)
                val maxW = W - 32f
                val lines = Txt.wrap(sub, ss, maxW)
                val slh = Txt.lineHeight(ss)
                var y = top + lh + 6f + dy
                for (l in lines) { Txt.draw(c, l, ss, W / 2, y, 0, slh, subA); y += slh }
            }
        }
    }
    val active: Boolean get() = false
}

/** Toast stack (`#toasts`). */
class Toasts(private val ui: Ui) {
    private class T(val text: String, val tone: Tone, val at: Double)
    private val list = ArrayList<T>()
    private val path = Path()
    fun add(text: String, tone: Tone, now: Double) {
        list.add(T(text, tone, now))
        while (list.size > 4) list.removeAt(0)
    }
    fun draw(c: Canvas, now: Double, hudTopCentreBottom: Float) {
        list.removeAll { now - it.at > 2300 }
        if (list.isEmpty()) return
        val L = ui.layout
        val compactLane = L.compact
        val portraitTouch = L.portrait && L.touch
        val st = if (compactLane) TextStyle(Fonts.mono400, 10f, 0.16f, Pal.INK, true) else TextStyle(Fonts.mono400, 12f, 0.22f, Pal.INK, true)
        val padX = if (compactLane) 10f else 12f; val padY = if (compactLane) 4f else 5f
        val lh = st.size * Txt.LH
        val gap = 6f
        val visible = if (compactLane) list.takeLast(2) else list
        if (compactLane || portraitTouch) {
            // under the top-centre HUD, newest at the bottom, in the lane between the side readouts
            val left = if (compactLane) L.navL + 176f else 16f; val right = if (compactLane) L.w - (L.navR + 152f) else L.w - 16f
            val laneW = max(80f, right - left)
            var y = hudTopCentreBottom + 14f
            for (t in visible) {
                val lines = Txt.wrap(t.text, st, laneW - padX * 2)
                // fit-content width: a toast that wraps fills the lane (max-width: 100%)
                val tw = if (lines.size > 1) laneW else Txt.width(lines[0], st) + padX * 2
                val h = lines.size * lh + padY * 2
                drawToast(c, t, now, left + laneW / 2 - tw / 2, y, tw, h, lines, st, lh, padX, padY)
                y += h + gap
            }
        } else {
            // centred above the bottom, oldest at the bottom and newer ones above (column-reverse)
            var y = L.h - 96f
            for (t in visible) {
                val tw = Txt.width(t.text, st) + padX * 2
                val h = lh + padY * 2
                y -= h
                drawToast(c, t, now, L.w / 2 - tw / 2, y, tw, h, listOf(t.text), st, lh, padX, padY)
                y -= gap
            }
        }
    }

    private fun drawToast(c: Canvas, t: T, now: Double, x: Float, y: Float, w: Float, h: Float, lines: List<String>, st: TextStyle, lh: Float, padX: Float, padY: Float) {
        // toast keyframes over 2.2 s, ease-out-expo per segment: in (0–12%); 80% sets no transform, so
        // the rise to −12 px eases over 12–100% while opacity holds until 80%, then fades
        val k = ((now - t.at) / 2200.0).toFloat().coerceIn(0f, 1f)
        val a: Float; val dy: Float; var sc = 1f
        if (k < 0.12f) { val f = Theme.EASE_OUT_EXPO((k / 0.12f).toDouble()).toFloat(); a = f; dy = 10f * (1 - f); sc = 0.9f + 0.1f * f }
        else {
            dy = -12f * Theme.EASE_OUT_EXPO(((k - 0.12f) / 0.88f).toDouble()).toFloat()
            a = if (k < 0.8f) 1f else 1f - Theme.EASE_OUT_EXPO(((k - 0.8f) / 0.2f).toDouble()).toFloat()
        }
        if (a <= 0.01f) return
        val col = t.tone.color
        c.save()
        c.translate(0f, dy)
        c.scale(sc, sc, x + w / 2, y + h / 2)
        val p = Deco.fillPaint()
        p.color = Theme.withA(0xFF070414.toInt(), 0.8f * a)
        p.setShadowLayer(18f, 0f, 0f, Theme.withA(col, 0.3f * a))
        c.drawRect(x, y, x + w, y + h, p)
        p.clearShadowLayer()
        val s = Deco.strokePaint(); s.color = Theme.withA(col, 0.6f * a); s.strokeWidth = 1f
        c.drawRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, s)
        var yy = y + padY
        for (l in lines) { Txt.draw(c, l, st, x + w / 2, yy, 0, lh, a); yy += lh }
        c.restore()
    }
}
