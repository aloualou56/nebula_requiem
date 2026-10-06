package io.github.aloualou56.nebularequiem.ui

import android.graphics.Canvas
import io.github.aloualou56.nebularequiem.core.Audio
import io.github.aloualou56.nebularequiem.core.BOSS_DEFS
import io.github.aloualou56.nebularequiem.core.Ease
import io.github.aloualou56.nebularequiem.core.Input
import io.github.aloualou56.nebularequiem.core.Pal
import io.github.aloualou56.nebularequiem.core.RunSummary
import io.github.aloualou56.nebularequiem.core.Screen
import io.github.aloualou56.nebularequiem.core.Sfx
import io.github.aloualou56.nebularequiem.core.fmtInt
import io.github.aloualou56.nebularequiem.render.Fonts
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The ending (Screen.VICTORY), after the final guardian. Over the live nebula, with the HUD gone, it
 * plays a title, an epilogue typed line by line, a roll call of the ten guardians in their colours
 * (one after another, like credits) and the run's telemetry with the stardust it banked, then waits
 * on "Return to title". Input is ignored for its first second; after that a tap or a key skips to
 * the telemetry, and Return to title, Enter, Esc, gamepad A or B, or Back go to the title.
 */
class VictoryScreen(ui: Ui) : ScreenView(ui, Screen.VICTORY, false) {
    /** A tap anywhere but on the button skips ahead: it spans the screen, under the button in hit order. */
    private val skipArea = object : Widget() {
        override val minHit: Float get() = 0f
        override fun draw(c: Canvas, ctx: DrawCtx) {}
    }.apply { focusable = false; onClick = { if (downAt >= armAt) skip() } }
    val home = Button("Return to title", BtnKind.PRIMARY, "Enter").apply { visible = false; onClick = { leave() } }

    private var sum: RunSummary? = null
    private var log = ""
    // the timeline, in ms on the UI clock (render() lays it out)
    private var at0 = 0.0
    /** Input counts from here (the first second is ignored). */
    var armAt = 0.0
        private set
    /** When the telemetry takes the stage (moved up by a skip). */
    var statsAt = 0.0
        private set
    private var skippedAt = -1.0
    private var typedAt = 0.0
    private var countAt = -1.0
    private var purchased = false
    private var leaving = false
    private var titled = false
    private var chimes = 0
    private val lineAt = DoubleArray(EPILOGUE.size); private val lineEnd = DoubleArray(EPILOGUE.size)
    private var epiOut = 0.0; private var rollAt = 0.0; private var rollOut = 0.0

    // layout
    private var titleSize = 60f; private var introTop = 0f; private var epiW = 0f; private var epiGap = 8f
    private var epiRows: List<List<String>> = emptyList()
    private var rollTop = 0f; private var rollX0 = 0f; private var rollColW = 0f; private var colGap = 40f; private var numW = 34f
    private var nameSize = 18f; private var subSize = 11.5f; private var rowH = 40f
    private var shellX = 0f; private var shellW = 0f; private var statsTop = 0f; private var statTitle = 48f
    private var logY = 0f; private var logH = 0f; private var logPad = 16f; private var footY = 0f; private var footH = 46f; private var rewardW = 0f

    init { widgets.add(skipArea); widgets.add(home) }

    /** The stage after which the screen waits for the player. */
    val atStats: Boolean get() = sum != null && ui.now() >= statsAt

    fun render(s: RunSummary, now: Double) {
        sum = s
        fun pad(k: String) = ("$k ").padEnd(18, '.') + " "
        val total = BOSS_DEFS.size
        log = listOf(
            "> ${pad("HULL")}${s.hull}",
            "> ${pad("RUN TIME")}${s.time}",
            "> ${pad("HOSTILES")}${s.kills} destroyed",
            "> ${pad("GUARDIANS")}${if (s.bosses > total) "$total/$total · ${s.bosses} broken in all" else "${s.bosses}/$total"}${if (s.level.isNotEmpty()) " · on ${s.level}" else ""}",
            "> ${pad("GRAZES")}${s.grazes} · peak chain ${s.chain}",
            "> ${pad("GRAFTS")}${if (s.perks.isEmpty()) "none" else s.perks.joinToString(", ")}",
            "> ${pad("SCORE")}${s.score}${if (s.record) "  ◆ NEW RECORD" else ""}"
        ).joinToString("\n")
        at0 = now; armAt = now + ARM_MS
        var t = now + EPI_MS
        for (i in EPILOGUE.indices) { lineAt[i] = t; lineEnd[i] = t + EPILOGUE[i].length / CPS * 1000; t = lineEnd[i] + LINE_GAP_MS }
        epiOut = lineEnd[EPILOGUE.size - 1] + EPI_HOLD_MS
        rollAt = epiOut + FADE_MS
        rollOut = rollAt + ROLL_LEAD_MS + BOSS_DEFS.size * ROLL_STEP_MS + ROLL_HOLD_MS
        statsAt = rollOut + FADE_MS
        typedAt = statsAt + 450
        skippedAt = -1.0; countAt = -1.0; purchased = false; leaving = false; titled = false; chimes = 0
        home.visible = false
        relayout(true)
    }

    private fun armed(now: Double = ui.now()) = sum != null && now >= armAt

    /** Jump to the telemetry: the cinematic fades out, the log shows in full and the count-up runs. */
    fun skip() {
        val now = ui.now()
        if (leaving || !armed(now) || now >= statsAt) return
        skippedAt = now; statsAt = now; typedAt = now - 1e7
        Audio.play(Sfx.UI_CLICK)
    }

    private fun leave() {
        if (leaving || !armed()) return
        leaving = true
        ui.action("title")
    }

    override fun onKey(code: String): Boolean {
        if (!armed()) return true   // the first second swallows everything
        when (code) {
            "Enter", "NumpadEnter", "Escape", "Pad_dash", "Pad_bomb" -> leave()
            else -> skip()
        }
        return true
    }

    override fun onBack(): Boolean { leave(); return true }

    override fun tick(now: Double) {
        if (sum == null) return
        if (!titled && now >= at0 + TITLE_MS) { titled = true; Audio.play(Sfx.LEVEL_UP) }
        // each name in the roll call chimes a step higher than the last
        if (chimes < BOSS_DEFS.size && now < statsAt && now >= rollAt + ROLL_LEAD_MS + chimes * ROLL_STEP_MS) { Audio.play(Sfx.PICKUP, pitch = 0.85 + chimes * 0.08); chimes++ }
        if (now >= statsAt) {
            if (!home.visible) {
                home.visible = true
                relayout(true)
                if (!Input.touchMode) ui.focused = home
            }
            val typedEnd = typedAt + log.length / LOG_CPS * 1000
            if (countAt < 0 && now >= max(typedEnd, statsAt + 500)) countAt = now
            if (countAt >= 0 && !purchased && now - countAt >= COUNT_MS) { purchased = true; Audio.play(Sfx.PURCHASE) }
        }
    }

    private fun epiStyle() = TextStyle(Fonts.mono400, if (L.compact) 12.5f else if (L.w >= 1100) 16f else 14.5f, 0.02f, Pal.INK_DIM)
    private fun titleStyle(size: Float) = TextStyle(Fonts.display900, size, 0.05f, Pal.WHITE)
    private fun nameStyle(def: Int) = TextStyle(Fonts.display800, nameSize, 0.08f, BOSS_DEFS[def].color, true)
    private fun subStyle() = TextStyle(Fonts.mono400, subSize, 0.14f, Pal.INK_DIM, true)
    private fun numeralStyle() = TextStyle(Fonts.mono400, if (L.compact) 11f else 13f, 0.1f, Pal.INK_FAINT)
    private fun logStyle() = TextStyle(Fonts.mono400, if (L.compact) 12.5f else 13.5f, 0f, Pal.INK_DIM)
    private fun numStyle() = TextStyle(Fonts.mono400, if (L.compact) 26f else 34f, 0f, Pal.SOLAR)

    override fun layout() {
        val L = L
        val W = L.w; val H = L.h
        val compact = L.compact
        val eh = Txt.lineHeight(Styles.eyebrow)
        // title and epilogue, centred
        titleSize = if (compact) (H * 0.15f).coerceIn(34f, 60f) else (W * 0.07f).coerceIn(46f, 96f)
        titleSize = min(titleSize, titleSize * (W - 2 * L.padX) / max(1f, Txt.width(TITLE, titleStyle(titleSize))))
        epiW = min(if (compact) 700f else 820f, W - 2 * L.padX)
        val es = epiStyle()
        epiRows = EPILOGUE.map { evenWrap(it, es, epiW) }
        epiGap = if (compact) 6f else 10f
        val epiH = epiRows.sumOf { it.size } * Txt.lineHeight(es) + (EPILOGUE.size - 1) * epiGap
        introTop = centerOffset(eh + 6f + titleSize * 0.9f + (if (compact) 16f else 28f) + epiH)
        // the roll call: two columns of five
        colGap = if (compact) 28f else 48f
        rollColW = min(if (W >= 1100) 440f else 380f, (W - 2 * L.padX - colGap) / 2)
        rollX0 = (W - (2 * rollColW + colGap)) / 2
        // the numeral column fits the widest numeral (VIII) with room to spare
        numW = NUMERALS.maxOf { Txt.width(it, numeralStyle()) } + (if (compact) 12f else 16f)
        nameSize = if (compact) 15f else if (W >= 1100) 21f else 18f
        subSize = if (compact) 10f else if (W >= 1100) 12.5f else 11.5f
        val nameMax = BOSS_DEFS.indices.maxOf { Txt.width(BOSS_DEFS[it].name, nameStyle(it)) }
        if (nameMax > rollColW - numW) nameSize *= (rollColW - numW) / nameMax
        val subMax = BOSS_DEFS.maxOf { Txt.width(it.title, subStyle()) }
        if (subMax > rollColW - numW) subSize *= (rollColW - numW) / subMax
        val rowGap = if (compact) 9f else 16f
        rowH = Txt.lineHeight(nameStyle(0), 1.2f) + 3f + Txt.lineHeight(subStyle()) + rowGap
        rollTop = centerOffset(eh + (if (compact) 10f else 18f) + ROWS * rowH - rowGap)
        // the telemetry
        shellW = min(760f, W - 2 * L.padX); shellX = (W - shellW) / 2
        val gap = if (compact) 9f else 16f
        statTitle = if (compact) (H * 0.1f).coerceIn(28f, 44f) else (W * 0.055f).coerceIn(40f, 72f)
        logPad = if (compact) 12f else 16f
        logH = Txt.wrappedHeight(log, logStyle(), shellW - 2 * logPad) + 2 * logPad
        val rewardH = Txt.lineHeight(numStyle()) + 2 * (if (compact) 9f else 14f)
        footH = max(rewardH, 46f)
        val total = eh + 4f + statTitle * 0.9f + gap + logH + gap + footH
        statsTop = centerOffset(total)
        logY = statsTop + eh + 4f + statTitle * 0.9f + gap
        footY = logY + logH + gap
        val bw = max(home.prefWidth(), 210f)
        rewardW = shellW - bw - 10f
        home.r.set(shellX + shellW - bw, footY + (footH - 46f) / 2, shellX + shellW, footY + (footH + 46f) / 2)
        contentH = if (home.visible) statsTop + total + L.padY else H
        skipArea.r.set(0f, 0f, W, max(H, contentH))
    }

    /** Wraps [s] in as few rows as [maxW] allows, as even as they go, so no word is left alone on a row. */
    private fun evenWrap(s: String, st: TextStyle, maxW: Float): List<String> {
        val rows = Txt.wrap(s, st, maxW)
        if (rows.size < 2) return rows
        var lo = 0f; var hi = maxW   // wrapping to hi takes rows.size rows; to lo, more
        while (hi - lo > 1f) { val mid = (lo + hi) / 2; if (Txt.wrap(s, st, mid).size > rows.size) lo = mid else hi = mid }
        return Txt.wrap(s, st, hi)
    }

    override fun drawContent(c: Canvas, ctx: DrawCtx, now: Double) {
        val s = sum ?: return
        val base = ctx.alpha
        // a soft veil keeps the words legible over the nebula
        c.save(); c.translate(0f, scroll); Deco.modalBackdrop(c, L.w, L.h, base * 0.55f); c.restore()
        val skipK = if (skippedAt < 0) 1f else (1f - ((now - skippedAt) / 300.0).toFloat()).coerceIn(0f, 1f)
        if (skipK > 0f && now < epiOut + FADE_MS) drawIntro(c, now, base * skipK * fadeOut(now, epiOut))
        if (skipK > 0f && now >= rollAt && now < rollOut + FADE_MS) drawRoll(c, now, base * skipK * fadeOut(now, rollOut) * fade(now, rollAt, 500.0))
        if (now >= statsAt) drawStats(c, ctx, now, s, base)
        ctx.alpha = base
    }

    private fun fade(now: Double, from: Double, ms: Double): Float = ((now - from) / ms).toFloat().coerceIn(0f, 1f)
    private fun fadeOut(now: Double, from: Double): Float = 1f - Theme.EASE_WARP(((now - from) / FADE_MS).coerceIn(0.0, 1.0)).toFloat()
    private fun rise(now: Double, from: Double): Float = if (ui.reducedMotion) fade(now, from, 300.0) else Theme.EASE_OUT_EXPO(((now - from) / 700.0).coerceIn(0.0, 1.0)).toFloat()

    /** The title unfolds from a squashed streak (like the banners) above the epilogue, typed line by line. */
    private fun drawIntro(c: Canvas, now: Double, a0: Float) {
        if (a0 <= 0.003f) return
        val W = L.w; val eh = Txt.lineHeight(Styles.eyebrow)
        val tStart = at0 + TITLE_MS
        if (now < tStart) return
        val k = rise(now, tStart)
        Txt.draw(c, EYEBROW, Styles.eyebrow, W / 2, introTop + 12f * (1 - k), 0, eh, a0 * k, Pal.SOLAR)
        val ts = titleStyle(titleSize); val lh = titleSize * 0.9f
        val ty = introTop + eh + 6f
        val u = Theme.EASE_OUT_EXPO(((now - tStart) / 700.0).coerceIn(0.0, 1.0)).toFloat()
        var ta: Float; var sx = 1f; var sy = 1f
        if (ui.reducedMotion) ta = u
        else if (u < 0.6f) { val f = u / 0.6f; ta = f; sx = 1.6f + (0.96f - 1.6f) * f; sy = 0.2f + (1.05f - 0.2f) * f }
        else { val f = (u - 0.6f) / 0.4f; ta = 1f; sx = 0.96f + 0.04f * f; sy = 1.05f - 0.05f * f }
        c.save(); c.scale(sx, sy, W / 2, ty + lh / 2)
        val tw = Txt.width(TITLE, ts)
        if (!ui.reducedMotion) Glitch.bursts(c, (now - tStart) / 1000.0, false, W / 2 - tw / 2, ty, tw, lh) { col, gx, gy -> Txt.draw(c, TITLE, ts, W / 2 + gx, ty + gy, 0, lh, a0 * ta * 0.9f, col) }
        Txt.drawGlow(c, TITLE, ts, W / 2, ty, 0, Theme.withA(Pal.SOLAR, 0.6f), 26f, lh, a0 * ta)
        c.restore()
        // the epilogue: each line typed in turn, wrapped in full first so words never jump rows
        val es = epiStyle(); val elh = Txt.lineHeight(es)
        var y = ty + lh + (if (L.compact) 16f else 28f)
        for ((i, rows) in epiRows.withIndex()) {
            if (now >= lineAt[i]) {
                val text = EPILOGUE[i]
                val n = if (ui.reducedMotion) text.length else min(text.length, 1 + ((now - lineAt[i]) / 1000.0 * CPS).toInt())
                val la = a0 * fade(now, lineAt[i], 250.0)
                var pos = 0; var caretX = 0f; var caretY = y
                for ((ri, row) in rows.withIndex()) {
                    val ry = y + ri * elh
                    val vis = (n - pos).coerceIn(0, row.length)
                    if (vis > 0) {
                        // centred on where the whole row will sit, so typing never shifts it
                        val left = W / 2 - Txt.width(row, es) / 2
                        caretX = left + Txt.draw(c, row.substring(0, vis), es, left, ry, -1, elh, la); caretY = ry
                    }
                    pos += row.length
                    if (text.getOrNull(pos) == ' ') pos++
                }
                if (n < text.length && floor(now / 400.0).toInt() % 2 == 0) Deco.fillRect(c, caretX + 2f, caretY + elh * 0.18f, caretX + 2f + es.size * 0.55f, caretY + elh * 0.82f, Theme.withA(Pal.INK_DIM, la))
            }
            y += rows.size * elh + epiGap
        }
    }

    /** Ten names, one after another, each in its guardian's colour: I–V down the left, VI–X down the right. */
    private fun drawRoll(c: Canvas, now: Double, a0: Float) {
        if (a0 <= 0.003f) return
        val W = L.w; val eh = Txt.lineHeight(Styles.eyebrow)
        Txt.draw(c, ROLL_HEADING, Styles.eyebrow, W / 2, rollTop, 0, eh, a0)
        val top = rollTop + eh + (if (L.compact) 10f else 18f)
        val nlh = Txt.lineHeight(nameStyle(0), 1.2f); val ss = subStyle(); val slh = Txt.lineHeight(ss); val ns = numeralStyle()
        for (i in BOSS_DEFS.indices) {
            val at = rollAt + ROLL_LEAD_MS + i * ROLL_STEP_MS
            if (now < at) break
            val k = rise(now, at)
            val col = i / ROWS; val row = i % ROWS
            val x = rollX0 + col * (rollColW + colGap); val y = top + row * rowH + 14f * (1 - k)
            val def = BOSS_DEFS[i]
            val a = a0 * k
            Txt.draw(c, NUMERALS[i], ns, x, y, -1, nlh, a)
            // each name flares as it is called, then settles to a steady glow
            val flare = (1f - ((now - at) / 900.0).toFloat()).coerceIn(0f, 1f)
            Txt.drawGlow(c, def.name, nameStyle(i), x + numW, y, -1, Theme.withA(def.color, 0.45f + 0.5f * flare), 10f + 18f * flare, nlh, a)
            Txt.draw(c, def.title, ss, x + numW, y + nlh + 3f, -1, slh, a)
        }
    }

    /** The telemetry: the run's log typed out in a solar slab, the stardust it banked counting up, and the way home. */
    private fun drawStats(c: Canvas, ctx: DrawCtx, now: Double, s: RunSummary, base: Float) {
        val W = L.w; val eh = Txt.lineHeight(Styles.eyebrow)
        fun st(d: Int): Float = if (ui.reducedMotion) 1f else Theme.EASE_OUT_EXPO(((now - statsAt - (d * 70 + 120)) / 700.0).coerceIn(0.0, 1.0)).toFloat()
        var k = st(0); var a = base * k; var dy = 16f * (1 - k)
        Txt.draw(c, EYEBROW, Styles.eyebrow, shellX, statsTop + dy, -1, eh, a, Pal.SOLAR)
        val ts = titleStyle(statTitle); val lh = statTitle * 0.9f; val ty = statsTop + eh + 4f + dy
        val tw = Txt.width(TITLE, ts)
        if (!ui.reducedMotion) Glitch.bursts(c, (now - statsAt) / 1000.0, false, shellX, ty, tw, lh) { col, gx, gy -> Txt.draw(c, TITLE, ts, shellX + gx, ty + gy, -1, lh, a * 0.9f, col) }
        Txt.drawGlow(c, TITLE, ts, shellX, ty, -1, Theme.withA(Pal.SOLAR, 0.6f), 22f, lh, a)
        k = st(1); a = base * k; dy = 16f * (1 - k)
        Deco.slab(c, shellX, logY + dy, shellW, logH, 12f, Pal.SOLAR, a)
        val n = if (now < typedAt) 0 else min(log.length, 1 + ((now - typedAt) / 1000.0 * LOG_CPS).toInt())
        Txt.drawWrapped(c, log.substring(0, n), logStyle(), shellX + logPad, logY + logPad + dy, shellW - 2 * logPad, Txt.LH, a)
        k = st(2); a = base * k; dy = 16f * (1 - k)
        Deco.slab(c, shellX, footY + dy, rewardW, footH, 14f, Pal.SOLAR, a)
        val ns = numStyle(); val nlh = Txt.lineHeight(ns); val ls = Styles.hudLabelUi; val llh = Txt.lineHeight(ls)
        val rx = if (L.compact) 14f else 18f; val ry = footY + (footH - nlh) / 2 + dy
        Txt.draw(c, "Stardust earned", ls, shellX + rx, ry + Txt.baseline(ns, nlh) - Txt.baseline(ls, llh), -1, llh, a)
        val shown = if (countAt < 0) 0.0 else s.dust * Ease.outCubic(((now - countAt) / COUNT_MS).coerceIn(0.0, 1.0))
        Txt.drawGlow(c, (if (countAt < 0) "" else "+") + fmtInt(shown), ns, shellX + rewardW - rx, ry, 1, Theme.withA(Pal.SOLAR, 0.55f), 18f, nlh, a)
        k = st(3)
        c.save(); c.translate(0f, 16f * (1 - k)); ctx.alpha = base * k; home.draw(c, ctx); c.restore()
    }

    companion object {
        const val TITLE = "Requiem complete"
        const val EYEBROW = "All ten guardians broken"
        const val ROLL_HEADING = "Roll call of the broken"
        val EPILOGUE = listOf(
            "The last proof collapses. Its geometry scatters into quiet light.",
            "Ten guardians. Ten theorems. Every one undone by a single white-hot point.",
            "The nebula remembers each pilot it swallows. It will remember the one it could not.",
            "Set course for the drydock, pilot. The requiem is yours."
        )
        val NUMERALS = arrayOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")
        const val ROWS = 5
        const val ARM_MS = 1000.0
        const val TITLE_MS = 250.0
        const val EPI_MS = 1700.0
        const val CPS = 38.0
        const val LINE_GAP_MS = 500.0
        const val EPI_HOLD_MS = 2200.0
        const val FADE_MS = 600.0
        const val ROLL_LEAD_MS = 450.0
        const val ROLL_STEP_MS = 550.0
        const val ROLL_HOLD_MS = 2800.0
        const val LOG_CPS = 90.0
        const val COUNT_MS = 1100.0
    }
}
