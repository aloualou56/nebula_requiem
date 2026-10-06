package io.github.aloualou56.nebularequiem.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import io.github.aloualou56.nebularequiem.core.Boss
import io.github.aloualou56.nebularequiem.core.Cfg
import io.github.aloualou56.nebularequiem.core.Game
import io.github.aloualou56.nebularequiem.core.GameState
import io.github.aloualou56.nebularequiem.core.Input
import io.github.aloualou56.nebularequiem.core.Light
import io.github.aloualou56.nebularequiem.core.Pal
import io.github.aloualou56.nebularequiem.core.Perf
import io.github.aloualou56.nebularequiem.core.Save
import io.github.aloualou56.nebularequiem.core.World
import io.github.aloualou56.nebularequiem.core.fmtFixed
import io.github.aloualou56.nebularequiem.core.fmtInt
import io.github.aloualou56.nebularequiem.core.mutatorName
import io.github.aloualou56.nebularequiem.render.Fonts
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * §19 HUD — cockpit instruments hugging the corners. Each block glows with the colour of the light
 * behind it (the lighting grid is sampled every 80 ms), so explosions near a corner bleed onto that
 * instrument. Also draws the touch controls (floating sticks, ability buttons, pause).
 */
class Hud(private val ui: Ui) {
    var shownAt = -1e9; var hiddenAt = -1e9; var on = false
    private var wave = "Sector 1 · Wave 1"
    private var mods: List<String> = emptyList()
    private var boss: Boss? = null
    private var bossShownAt = -1e9
    private var bossLag = 1.0
    private var hullHitAt = -1e9
    private val glowRgb = IntArray(5) { Pal.ION }
    private val glowA = FloatArray(5) { 0.3f }
    private var ambientT = 0.0
    private val sample = DoubleArray(3)
    private val path = Path()
    private val rect = RectF()
    private val m = Matrix()
    /** Bottom edge (dp) of the top-centre block, for the toast lane. */
    var tcBottom = 124f
        private set

    // touch control geometry (dp)
    val btnX = FloatArray(3); val btnY = FloatArray(3); val btnR = FloatArray(3)
    var pauseX = 0f; var pauseY = 0f
    val pauseSize = 44f
    val btnDown = BooleanArray(3)
    var pauseDown = false
    private var stickMoveOffAt = -1e9; private var stickAimOffAt = -1e9

    fun show(v: Boolean, now: Double) {
        if (v == on) return
        on = v
        if (v) shownAt = now else hiddenAt = now
    }
    fun opacity(now: Double): Float = if (on) min(1.0, (now - shownAt) / 500.0).toFloat() else max(0.0, 1 - (now - hiddenAt) / 500.0).toFloat()

    fun setWave(label: String, mutators: List<String>) { wave = label; mods = ArrayList(mutators) }
    fun showBoss(b: Boss, now: Double) { boss = b; bossShownAt = now; bossLag = 1.0 }
    fun hideBoss() { boss = null }
    fun flashHull(now: Double) { hullHitAt = now }

    /** Sample the light grid behind each instrument (tl, tr, bl, br, top), quantized. */
    private fun ambient() {
        val W = World.w; val H = World.h
        for (i in 0 until 5) {
            val x0 = REG[i * 4]; val y0 = REG[i * 4 + 1]; val x1 = REG[i * 4 + 2]; val y1 = REG[i * 4 + 3]
            Light.sample(x0 * W, y0 * H, x1 * W, y1 * H, sample)
            val mx = max(max(sample[0], sample[1]), max(sample[2], 1e-3))
            fun q(v: Double) = ((v / mx) * 15).roundToInt() * 17
            glowRgb[i] = (0xFF shl 24) or (q(sample[0]) shl 16) or (q(sample[1]) shl 8) or q(sample[2])
            glowA[i] = ((min(1.0, mx * 2.2) * 20).roundToInt() / 20.0).toFloat()
        }
    }

    /** drop-shadow(0 0 (3 + 16a)px rgb(glow / (.15 + .7a))) */
    private fun glowColor(i: Int, alpha: Float) = Theme.withA(glowRgb[i], (0.15f + 0.7f * glowA[i]) * alpha)
    private fun glowRadius(i: Int) = (3f + 16f * glowA[i]) * 0.6f

    fun layoutTouch() {
        val L = ui.layout
        val W = L.w; val H = L.h; val er = L.navR
        if (L.portrait) {
            btnR[0] = 33f; btnX[0] = W - er - 16f - 33f; btnY[0] = H - 26f - 33f
            btnR[1] = 29f; btnX[1] = W - er - 20f - 29f; btnY[1] = H - 110f - 29f
            btnR[2] = 29f; btnX[2] = W - er - 20f - 29f; btnY[2] = H - 186f - 29f
        } else {
            btnR[0] = 36f; btnX[0] = W - er - 84f - 36f; btnY[0] = H - 62f - 36f
            btnR[1] = 29f; btnX[1] = W - er - 201f - 29f; btnY[1] = H - 55f - 29f
            btnR[2] = 29f; btnX[2] = W - er - 86f - 29f; btnY[2] = H - 187f - 29f
        }
        pauseX = W - er - 12f - pauseSize; pauseY = 10f
    }

    /** Which ability button (0 dash, 1 nova, 2 flux) is under (x, y), counting the 12 dp slop; −1 if none. */
    fun hitButton(x: Float, y: Float): Int {
        var best = -1; var bd = Float.MAX_VALUE
        for (i in 0 until 3) {
            val dx = x - btnX[i]; val dy = y - btnY[i]; val d = dx * dx + dy * dy; val R = btnR[i] + 12f
            if (d <= R * R && d < bd) { bd = d; best = i }
        }
        return best
    }
    fun hitPause(x: Float, y: Float, slop: Float = 12f): Boolean = x >= pauseX - slop && x <= pauseX + pauseSize + slop && y >= pauseY - slop && y <= pauseY + pauseSize + slop

    fun draw(c: Canvas, now: Double, dt: Double) {
        val op = opacity(now)
        val p = Game.player; val run = Game.run
        if (op <= 0.01f || p == null || run == null) { tcBottom = 124f; return }
        ambientT -= dt
        if (ambientT <= 0) { ambientT = 0.08; ambient() }
        val L = ui.layout
        val W = L.w; val H = L.h
        val touch = L.touch
        val labH = 10f * Txt.LH   // .hud-label line box

        /* ── top-left: score, multiplier, chain ── */
        run {
            val x = 16f; var y = 14f
            val g = glowColor(0, op); val gr = glowRadius(0)
            Txt.drawGlow(c, "Score", Styles.hudLabel, x, y, -1, g, gr, labH, op); y += labH + 6f
            val ss = (W * 0.026f).coerceIn(22f, 32f)
            Txt.drawGlow(c, fmtInt(run.score), TextStyle(Fonts.mono400, ss, 0.02f, Pal.WHITE), x, y, -1, g, gr, ss, op); y += ss + 6f
            val ms = TextStyle(Fonts.mono400, 13f, 0f, Pal.ION)
            val rowH = Txt.lineHeight(ms)
            val mtxt = "×" + fmtFixed(Game.mult, 2)
            val mw = Txt.width(mtxt, ms)
            Txt.drawGlow(c, mtxt, ms, x, y, -1, g, gr, rowH, op)
            val barW = if (L.small) 64f else 96f
            val bx = x + mw + 8f; val by = y + rowH / 2 - 1.5f
            Deco.fillRect(c, bx, by, bx + barW, by + 3f, Theme.withA(Pal.LINE, 0.2f * op))
            val k = if (Game.combo > 0) (Game.comboT / Cfg.COMBO_WINDOW).toFloat().coerceIn(0f, 1f) else 0f
            if (k > 0) Deco.fillRect(c, bx, by, bx + barW * k, by + 3f, Theme.withA(Pal.ION, op))
            if (Game.combo > 4) {
                val chain = "${Game.combo} chain"
                if (L.compact) Txt.drawGlow(c, chain, Styles.hudLabel, x, y + rowH, -1, g, gr, labH, op)
                else Txt.drawGlow(c, chain, Styles.hudLabel, bx + barW + 8f, y, -1, g, gr, rowH, op)
            }
        }

        /* ── top-centre: wave, mutator chips, guardian bar ── */
        run {
            var top = 12f; var width = min(560f, W * 0.46f)
            if (L.narrow) { width = min(520f, W * 0.6f); top = 64f }
            if (L.small) { top = 96f; width = W - 32f }
            if (L.compact) { width = min(520f, W - 2 * max(176f + L.navL, 152f + L.navR)); top = 12f }
            width = max(width, 120f)
            val cx = W / 2
            val g = glowColor(4, op); val gr = glowRadius(4)
            var y = top
            val ws = TextStyle(Fonts.display400, 13f, 0.32f, Pal.INK, true)   // .hud-wave sets no weight
            val wh = Txt.lineHeight(ws)
            Txt.drawGlow(c, wave, ws, cx, y, 0, g, gr, wh, op); y += wh
            y += 6f   // gap before #hud-mods, a flex item even when it holds no chips
            if (mods.isNotEmpty()) {
                val cs = if (L.compact) TextStyle(Fonts.mono400, 10f, 0.1f, Pal.SOLAR, true) else TextStyle(Fonts.mono400, 11f, 0.14f, Pal.SOLAR, true)
                val padX = if (L.compact) 5f else 6f; val padY = if (L.compact) 1f else 2f; val gap = if (L.compact) 4f else 6f
                val chipLh = Txt.lineHeight(cs)
                val chipH = chipLh + padY * 2
                // flex-wrap rows, centred
                var i = 0
                while (i < mods.size) {
                    var rowW = 0f; var j = i
                    while (j < mods.size) {
                        val cw = Txt.width(mutatorName(mods[j]), cs) + padX * 2
                        if (j > i && rowW + gap + cw > width) break
                        rowW += (if (j > i) gap else 0f) + cw; j++
                    }
                    var x = cx - rowW / 2
                    for (k in i until j) {
                        val name = mutatorName(mods[k]); val cw = Txt.width(name, cs) + padX * 2
                        val s = Deco.strokePaint(); s.color = Theme.withA(Pal.SOLAR, 0.4f * op); s.strokeWidth = 1f
                        c.drawRect(x + 0.5f, y + 0.5f, x + cw - 0.5f, y + chipH - 0.5f, s)
                        Txt.drawGlow(c, name, cs, x + cw / 2, y + padY, 0, g, gr, chipLh, op)
                        x += cw + gap
                    }
                    y += chipH + gap   // `gap` sets the row gap too
                    i = j
                }
                y -= gap
            }
            val b = boss
            if (b != null && !b.dead) {
                y += 6f
                val k = Theme.EASE_OUT_EXPO(min(1.0, (now - bossShownAt) / 800.0)).toFloat()
                val ry = y + 16f * (1 - k)
                val ns = TextStyle(Fonts.display800, 14f, 0.3f, Pal.PLASMA, true)
                val shown = min(b.name.length, 1 + ((now - bossShownAt) / 1000.0 * 26).toInt())
                val nh = Txt.lineHeight(ns)   // the line box outgrows min-height: 1.2em
                Txt.drawGlow(c, b.name.substring(0, shown), ns, cx, ry, 0, Theme.withA(Pal.PLASMA, 0.6f), 14f, nh, op * k)
                val ty = ry + nh + 4f
                val tx0 = cx - width / 2; val tx1 = cx + width / 2
                val frac = (b.hp / b.maxHp).coerceIn(0.0, 1.0)
                bossLag = if (bossLag > frac) max(frac, bossLag - dt * 0.6) else frac
                Deco.fillRect(c, tx0, ty, tx1, ty + 8f, Theme.withA(Pal.PLASMA, 0.12f * op * k))
                val inner = tx1 - tx0 - 2f
                Deco.fillRect(c, tx0 + 1, ty + 1, tx0 + 1 + inner * bossLag.toFloat(), ty + 7f, Theme.withA(Pal.WHITE, 0.55f * op * k))
                val fp = Deco.fillPaint()
                fp.shader = LinearGradient(tx0, 0f, tx1, 0f, Pal.PLASMA, Pal.C_FF8AE9, Shader.TileMode.CLAMP); fp.alpha = (255 * op * k).toInt()
                c.drawRect(tx0 + 1, ty + 1, tx0 + 1 + inner * frac.toFloat(), ty + 7f, fp)
                fp.shader = null; fp.alpha = 255
                for (ph in b.phases) if (ph.until > 0) { val ux = tx0 + (tx1 - tx0) * ph.until.toFloat(); Deco.fillRect(c, ux - 1f, ty, ux + 1f, ty + 8f, Theme.withA(Pal.VOID, op)) }
                val s = Deco.strokePaint(); s.color = Theme.withA(Pal.PLASMA, 0.4f * op * k); s.strokeWidth = 1f
                c.drawRect(tx0 + 0.5f, ty + 0.5f, tx1 - 0.5f, ty + 7.5f, s)
                y = ty + 8f
            }
            tcBottom = kotlin.math.round(y)   // the box height is read rounded
        }

        /* ── top-right: stardust ── */
        run {
            val right = if (touch) W - (L.navR + 72f) else W - 16f
            var y = 14f
            val g = glowColor(1, op); val gr = glowRadius(1)
            Txt.drawGlow(c, "Stardust", Styles.hudLabel, right, y, 1, g, gr, labH, op); y += labH + 6f
            val ds = TextStyle(Fonts.mono400, 20f, 0f, Pal.SOLAR)
            val dh = Txt.lineHeight(ds)
            val txt = fmtInt(run.dust)
            val tw = Txt.width(txt, ds)
            Txt.drawGlow(c, txt, ds, right, y, 1, Theme.withA(Pal.SOLAR, 0.5f), 14f, dh, op)
            Deco.dustGlyph(c, right - tw - 10f - 7f, y + dh / 2, 14f, op)
            y += dh + 6f
            if (Save.data.settings.showFps) {
                val fs = TextStyle(Fonts.mono400, 10f, 0.14f, Pal.INK_FAINT)
                Txt.draw(c, "${Perf.fps.roundToInt()} / ${Perf.targetHz().roundToInt()} FPS · ${World.quality.id} · ${Game.ebullets.count} bullets", fs, right, y, 1, labH, op)
            }
        }

        /* ── bottom-left (top-left under the score on landscape phones): hull, flux ── */
        run {
            val g = glowColor(2, op); val gr = glowRadius(2)
            val maxHp = p.stats.maxHp; val sh = p.stats.shieldMax
            val total = maxHp + sh
            val perRow = if (touch) 7 else total
            val rows = (total + perRow - 1) / max(1, perRow)
            val pipsH = rows * 10f + (rows - 1) * 4f
            val fluxRowH = if (touch) labH else max(labH, Deco.kbdHeight())   // the Q chip (15 px) sets the row height
            val blockH = labH + 6f + pipsH + 6f + 4f + fluxRowH
            val x = 16f
            var y = if (L.compact && touch) 14f + 92f else H - 14f - blockH
            Txt.drawGlow(c, "Hull integrity", Styles.hudLabel, x, y, -1, g, gr, labH, op); y += labH + 6f
            val shake = run { val k = ((now - hullHitAt) / 400.0).toFloat(); if (k in 0f..1f) Button.denyShake(k) else 0f }
            for (i in 0 until total) {
                val row = i / perRow; val col = i % perRow
                val px = x + shake + col * 23f; val py = y + row * 14f
                path.rewind(); path.moveTo(px + 4f, py); path.lineTo(px + 18f, py); path.lineTo(px + 14f, py + 10f); path.lineTo(px, py + 10f); path.close()
                val fp = Deco.fillPaint()
                val isShield = i >= maxHp
                val onPip = if (isShield) (i - maxHp) < p.shield else i < p.hp
                if (isShield) {
                    if (onPip) { fp.color = Theme.withA(Pal.WHITE, op); fp.setShadowLayer(10f, 0f, 0f, Theme.withA(Pal.WHITE, op)) } else fp.color = Theme.withA(Pal.WHITE, 0.12f * op)
                } else {
                    if (onPip) { fp.color = Theme.withA(Pal.ION, op); fp.setShadowLayer(8f, 0f, 0f, Theme.withA(Pal.ION, op)) } else fp.color = Theme.withA(Pal.LINE, 0.2f * op)
                }
                c.drawPath(path, fp)
                fp.clearShadowLayer()
            }
            y += pipsH + 6f + 4f
            val fw = Txt.drawGlow2(c, "Flux", Styles.hudLabel, x, y, g, gr, fluxRowH, op)
            val trackW = if (L.compact || L.small) 110f else 160f
            val tx = x + fw + 8f; val ty = y + fluxRowH / 2 - 3f
            Deco.fillRect(c, tx, ty, tx + trackW, ty + 6f, Theme.withA(Pal.LINE, 0.14f * op))
            val fp = Deco.fillPaint()
            if (p.flux >= 1) {
                val sheen = ((now / 1000.0) % 1.0).toFloat()
                fluxSheen.setLocalMatrix(m.apply { setScale(trackW * 2, 1f); postTranslate(tx - sheen * trackW * 2, 0f) })
                fp.shader = fluxSheen
            } else {
                fluxGrad.setLocalMatrix(m.apply { setScale(trackW, 1f); postTranslate(tx, 0f) })
                fp.shader = fluxGrad
            }
            fp.alpha = (255 * op).toInt()
            c.drawRect(tx, ty, tx + trackW * p.flux.toFloat().coerceIn(0f, 1f), ty + 6f, fp)
            fp.shader = null; fp.alpha = 255
            if (!touch) Deco.kbd(c, "Q", tx + trackW + 8f + Deco.kbdWidth("Q"), ty + 3f, op)
        }

        /* ── bottom-centre: level and experience ── */
        run {
            var width = min(420f, W * 0.38f); var bottom = if (L.compact) 14f else 16f
            var left = W / 2 - width / 2
            if (L.narrow) { width = min(320f, W * 0.5f); bottom = 78f; left = W / 2 - width / 2 }
            if (L.small) { left = 16f; width = W - 120f; bottom = 14f + 112f }
            val ls = TextStyle(Fonts.mono400, 11f, 0.2f, Pal.MINT)
            val rowH = Txt.lineHeight(ls)
            val y = H - bottom - rowH
            val lvl = "LV ${run.level}"
            val lw = Txt.width(lvl, ls)
            Txt.draw(c, lvl, ls, left, y, -1, rowH, op)
            val tx = left + lw + 10f; val tw = left + width - tx
            val ty = y + rowH / 2 - 2.5f
            Deco.fillRect(c, tx, ty, tx + tw, ty + 5f, Theme.withA(Pal.LINE, 0.16f * op))
            val k = (run.xp / run.xpNext).toFloat().coerceIn(0f, 1f)
            if (k > 0) {
                val fp = Deco.fillPaint()
                xpGrad.setLocalMatrix(m.apply { setScale(tw, 1f); postTranslate(tx, 0f) })
                fp.shader = xpGrad; fp.alpha = (255 * op).toInt()
                fp.setShadowLayer(10f, 0f, 0f, Theme.withA(Pal.MINT, op))
                c.drawRect(tx, ty, tx + tw * k, ty + 5f, fp)
                fp.clearShadowLayer(); fp.shader = null; fp.alpha = 255
            }
        }

        /* ── bottom-right ability rings (keyboard / gamepad play) ── */
        if (!touch) {
            val R = if (L.small) 19f else 22f
            val g = glowColor(3, op); val gr = glowRadius(3)
            val right = W - 16f; val bottom = H - 14f
            val dashP = if (p.dashCd > 0) (1 - p.dashCd / p.stats.dashCd).toFloat() else 1f
            val cx2 = right - R; val cx1 = cx2 - 2 * R - 12f
            val ks = 9f   // .ability kbd { font-size: 9px }
            val kh = Deco.kbdHeight(ks)
            val cy = bottom - kh - 4f - R
            ring(c, cx1, cy, R, Pal.ION, dashP, if (dashP >= 1) "" else fmtFixed(p.dashCd, 1), dashP >= 1, op)
            ring(c, cx2, cy, R, Pal.PLASMA, if (p.bombs > 0) 1f else 0f, p.bombs.toString(), p.bombs > 0, op)
            Deco.kbd(c, "Space", cx1 + Deco.kbdWidth("Space", ks) / 2, bottom - kh / 2, op, ks)
            Deco.kbd(c, "E", cx2 + Deco.kbdWidth("E", ks) / 2, bottom - kh / 2, op, ks)
        }
    }

    private val fluxGrad = LinearGradient(0f, 0f, 1f, 0f, 0xFF7A5CFF.toInt(), Pal.ION, Shader.TileMode.CLAMP)
    private val fluxSheen = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(Pal.ION, Pal.WHITE, Pal.ION, Pal.WHITE, Pal.ION), null, Shader.TileMode.REPEAT)
    private val xpGrad = LinearGradient(0f, 0f, 1f, 0f, 0xFF2BD4FF.toInt(), Pal.MINT, Shader.TileMode.CLAMP)

    /** conic-gradient progress ring with a dark centre and a value label. */
    private fun ring(c: Canvas, cx: Float, cy: Float, R: Float, col: Int, p: Float, v: String, ready: Boolean, op: Float) {
        val s = Deco.strokePaint()
        rect.set(cx - R + 2f, cy - R + 2f, cx + R - 2f, cy + R - 2f)
        s.strokeWidth = 4f
        s.color = Theme.withA(Pal.LINE, 0.14f * op); c.drawArc(rect, 0f, 360f, false, s)
        s.color = Theme.withA(col, op)
        if (ready) s.setShadowLayer(14f, 0f, 0f, Theme.withA(col, op))
        c.drawArc(rect, -90f, 360f * p.coerceIn(0f, 1f), false, s)
        s.clearShadowLayer()
        val fp = Deco.fillPaint(); fp.color = Theme.withA(0xFF070414.toInt(), 0.92f * op)
        c.drawCircle(cx, cy, R - 4f, fp)
        if (v.isNotEmpty()) Txt.draw(c, v, TextStyle(Fonts.mono400, 13f, 0f, Pal.INK), cx, cy - 10f, 0, 20f, op)
    }

    /* ───────────────────────── touch controls ───────────────────────── */

    fun drawTouch(c: Canvas, now: Double) {
        val L = ui.layout
        if (!L.touch || Game.state != GameState.PLAYING) return
        val p = Game.player ?: return
        drawStick(c, Input.moveStick, Pal.ION, now)
        drawStick(c, Input.aimStick, Pal.PLASMA, now)
        // ability buttons
        val dashP = if (p.dashCd > 0) (1 - p.dashCd / p.stats.dashCd).toFloat() else 1f
        drawBtn(c, 0, "Dash", Pal.MINT, dashP, dashP >= 1, dashP < 1, false, null, now)
        val bombOk = p.bombs > 0 && Game.nova == null
        drawBtn(c, 1, "Nova", Pal.PLASMA, if (p.bombs > 0) 1f else 0f, bombOk, !bombOk, false, p.bombs.toString(), now)
        val od = p.overdrive > 0
        val fk = if (od) 1f else if (p.flux >= 1) 1f else min(0.975f, (p.flux * 40).toInt() / 40f)
        drawBtn(c, 2, "Flux", Pal.ION, fk, od, !od && p.flux < 1, p.flux >= 1 && !od, null, now)
        // pause
        val fp = Deco.fillPaint()
        fp.color = Theme.withA(Pal.LINE, if (pauseDown) 0.4f else 0.12f)
        c.drawRect(pauseX, pauseY, pauseX + pauseSize, pauseY + pauseSize, fp)
        val s = Deco.strokePaint(); s.color = Theme.withA(Pal.LINE, 0.6f); s.strokeWidth = 1f
        c.drawRect(pauseX + 0.5f, pauseY + 0.5f, pauseX + pauseSize - 0.5f, pauseY + pauseSize - 0.5f, s)
        Txt.draw(c, "II", TextStyle(Fonts.mono400, 10f, 0.1f, Pal.WHITE), pauseX + pauseSize / 2, pauseY, 0, pauseSize, 1f)
    }

    private fun drawStick(c: Canvas, st: io.github.aloualou56.nebularequiem.core.Stick, col: Int, now: Double) {
        val on = st.id != -1
        val since = now - st.releasedAt
        val a = if (on) 1f else if (since < 0 || since >= 200) 0f else (1 - since / 200.0).toFloat()   // 200 ms fade-out
        if (a <= 0.01f) return
        val x = st.ox.toFloat(); val y = st.oy.toFloat()
        val fp = Deco.fillPaint()
        fp.shader = RadialGradient(x, y, 62f, intArrayOf(Theme.withA(col, 0.08f), Theme.withA(col, 0.04f), 0), floatArrayOf(0f, 0.5f, 0.7f), Shader.TileMode.CLAMP)
        fp.alpha = (255 * a).toInt()
        c.drawCircle(x, y, 62f, fp)
        fp.shader = null; fp.alpha = 255
        val s = Deco.strokePaint(); s.color = Theme.withA(col, 0.35f * a); s.strokeWidth = 1f
        c.drawCircle(x, y, 61.5f, s)
        val kx = x + st.kx.toFloat(); val ky = y + st.ky.toFloat()
        fp.shader = RadialGradient(kx, ky, 24f, Theme.withA(col, 0.55f), Theme.withA(col, 0.1f), Shader.TileMode.CLAMP)
        Deco.outerGlowCircle(c, kx, ky, 24f, 18f, Theme.withA(col, 0.5f * a))
        fp.alpha = (255 * a).toInt()
        c.drawCircle(kx, ky, 24f, fp)
        fp.shader = null; fp.alpha = 255
    }

    private fun drawBtn(c: Canvas, i: Int, label: String, col: Int, prog: Float, ready: Boolean, off: Boolean, full: Boolean, count: String?, now: Double) {
        val x = btnX[i]; val y = btnY[i]; val R = btnR[i]
        val alpha = if (off) 0.45f else 1f
        val fp = Deco.fillPaint()
        var bgA = if (btnDown[i]) 0.4f else 0.12f
        var glow = if (ready) 16f else 0f; var glowA = 0.55f
        if (full) {
            // t-pulse 1.1 s: box-shadow 26 px / .9 and background .3 at 50%
            val k = (0.5f - 0.5f * kotlin.math.cos((now / 1100.0 * Math.PI * 2)).toFloat())
            bgA = max(bgA, 0.12f + 0.18f * k); glow = 16f + 10f * k; glowA = 0.55f + 0.35f * k
        }
        Deco.outerGlowCircle(c, x, y, R, glow, Theme.withA(col, glowA * alpha))
        fp.color = Theme.withA(col, bgA * alpha)
        c.drawCircle(x, y, R, fp)
        val s = Deco.strokePaint(); s.color = Theme.withA(col, 0.6f * alpha); s.strokeWidth = 1f
        c.drawCircle(x, y, R - 0.5f, s)
        // progress ring: ::before insets −4 px from the padding box (R − 1), so its 3 px band spans R..R + 3
        rect.set(x - R - 1.5f, y - R - 1.5f, x + R + 1.5f, y + R + 1.5f)
        s.strokeWidth = 3f; s.color = Theme.withA(col, 0.14f * alpha); c.drawArc(rect, 0f, 360f, false, s)
        s.color = Theme.withA(col, alpha); c.drawArc(rect, -90f, 360f * prog.coerceIn(0f, 1f), false, s)
        Txt.draw(c, label, TextStyle(Fonts.mono400, if (i == 0) 11f else 10f, 0.1f, Pal.WHITE, true), x, y - 10f, 0, 20f, alpha)
        if (count != null) {
            val bx = x + R * 0.707f + 4f; val by = y - R * 0.707f - 4f
            val cs = TextStyle(Fonts.mono700, 11f, 0f, Pal.VOID)
            val w = max(20f, Txt.width(count, cs) + 10f)
            rect.set(bx - w / 2, by - 10f, bx + w / 2, by + 10f)
            fp.color = Theme.withA(col, alpha); c.drawRoundRect(rect, 10f, 10f, fp)
            Txt.draw(c, count, cs, bx, by - 10f, 0, 20f, 1f, Pal.VOID)
        }
    }

    companion object {
        /** Region of the arena (fractions of W, H) each instrument's glow samples: tl, tr, bl, br, top. */
        private val REG = doubleArrayOf(0.0, 0.0, 0.24, 0.2, 0.76, 0.0, 1.0, 0.2, 0.0, 0.78, 0.26, 1.0, 0.74, 0.78, 1.0, 1.0, 0.3, 0.0, 0.7, 0.18)
    }
}

/** Left-aligned glow text that returns its width. */
fun Txt.drawGlow2(c: Canvas, s: String, st: TextStyle, x: Float, y: Float, glow: Int, r: Float, lineH: Float, alpha: Float): Float {
    drawGlow(c, s, st, x, y, -1, glow, r, lineH, alpha)
    return width(s, st)
}
