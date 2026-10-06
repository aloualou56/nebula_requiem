package io.github.aloualou56.nebularequiem.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Shader
import io.github.aloualou56.nebularequiem.core.Audio
import io.github.aloualou56.nebularequiem.core.Cfg
import io.github.aloualou56.nebularequiem.core.Difficulty
import io.github.aloualou56.nebularequiem.core.Pal
import io.github.aloualou56.nebularequiem.core.RunMode
import io.github.aloualou56.nebularequiem.core.Save
import io.github.aloualou56.nebularequiem.core.Screen
import io.github.aloualou56.nebularequiem.core.Sfx
import io.github.aloualou56.nebularequiem.render.Fonts
import kotlin.math.max
import kotlin.math.min

/**
 * The flight plan (Screen.PLAN), between Launch run and the run itself: Story, the ten sectors that
 * end at the tenth guardian, or Endless, where the guardians return in a shuffled order and the
 * nebula ascends every ten sectors. Above them, the difficulty: Easy, Medium (the game as tuned),
 * Hard or Maniac, kept for the next launch. 1 and 2 fly a plan; Enter flies the one chosen last
 * (focused first); D steps the difficulty; Esc, gamepad B or Back return to where Launch was pressed.
 */
class PlanScreen(ui: Ui) : ScreenView(ui, Screen.PLAN, true) {
    private val back = Button("Back", BtnKind.GHOST, "Esc", small = true).apply { d = 0; onClick = { ui.action("plan-back") } }
    val story = PlanCard(RunMode.STORY, "1", Pal.ION, "Campaign", "Ten sectors · ten guardians · one ending",
        "Three waves, then a guardian, in each of ten sectors, every guardian tougher than the last. Break the tenth to complete the requiem.")
        .apply { d = 1; onClick = { ui.action("plan-story") } }
    val endless = PlanCard(RunMode.ENDLESS, "2", Pal.PLASMA, "Survival", "All ten guardians · random order · no end",
        "Three waves, then a guardian drawn at random from all ten, sector after sector. Every ten sectors the nebula ascends and everything hardens.")
        .apply { d = 2; onClick = { ui.action("plan-endless") } }
    private val cards = listOf(story, endless)
    val level = Segmented(Difficulty.entries.map { it.label }, { Difficulty.of(Save.data.difficulty).ordinal }, chooseOnAdjust = true) { i -> pick(Difficulty.entries[i]) }
        .apply { d = 1 }
    private var rowY = 0f; private var rowH = 30f; private var labelW = 0f

    private var px = 0f; private var py = 0f; private var pw = 0f; private var ph = 0f; private var pad = 24f; private var padY = 24f

    init { widgets.add(back); widgets.add(level); widgets.addAll(cards) }

    private fun pick(df: Difficulty) {
        if (Save.data.difficulty == df.id) return
        Save.data.difficulty = df.id; Save.commit()
        Audio.play(Sfx.UI_CLICK)
    }

    /** What the chosen difficulty does, in a few words, and what it pays. */
    private fun caption(df: Difficulty): String {
        val k = df.score
        val mul = if (k == Math.floor(k)) k.toInt().toString() else k.toString().trimEnd('0')
        val what = when (df) { Difficulty.EASY -> "Gentler"; Difficulty.MEDIUM -> "As tuned"; Difficulty.HARD -> "Fiercer"; Difficulty.MANIAC -> "Relentless" }
        return "$what · score ×$mul"
    }
    private fun labelStyle() = TextStyle(Fonts.mono400, 10f, 0.26f, Pal.INK_DIM, true)
    private fun captionStyle() = TextStyle(Fonts.mono400, if (L.compact) 10.5f else 11.5f, 0.12f, Pal.SOLAR, true)

    /** The card offered first: the plan flown last. */
    val offered: PlanCard get() = if (Save.data.plan == RunMode.ENDLESS.id) endless else story
    override val defaultFocus: Widget? get() = offered

    override fun onShow() { for (c in cards) c.record = record(c.mode) }

    private fun record(mode: RunMode): String {
        val s = Save.data.stats
        return when (mode) {
            RunMode.STORY -> when {
                s.requiems >= 2 -> "Requiem complete ×${s.requiems.toInt()}"
                s.requiems >= 1 -> "Requiem complete"
                else -> "Requiem not yet complete"
            }
            RunMode.ENDLESS -> if (s.endlessBestSector < 1) "No endless flight logged"
                else "Deepest · sector ${s.endlessBestSector.toInt()} · ${if (s.endlessBestWave >= Cfg.BOSS_EVERY) "guardian" else "wave ${max(1, s.endlessBestWave.toInt())}"}"
        }
    }

    override fun layout() {
        val L = L
        val W = L.w
        pw = min(860f, W - 2 * L.padX)
        pad = if (L.compact) 22f else (W * 0.04f).coerceIn(16f, 30f)
        padY = if (L.compact) 16f else 24f
        val inner = pw - 2 * pad
        val gap = if (L.compact) 14f else 22f
        val cw = (inner - gap) / 2
        for (c in cards) c.measure(cw, L.compact)
        val ch = cards.maxOf { it.contentH }
        val headGap = if (L.compact) 10f else 18f
        // the difficulty row: label, the segmented control and what the level does
        rowH = 11f * Txt.LH + if (L.compact) 24f else 14f
        val rowGap = if (L.compact) 10f else 16f
        ph = padY + HEAD_H + headGap + rowH + rowGap + ch + padY
        px = (W - pw) / 2; py = centerOffset(ph)
        rowY = py + padY + HEAD_H + headGap
        labelW = Txt.width("Difficulty", labelStyle())
        val sw = level.prefWidth(L.compact)
        level.r.set(px + pad + labelW + 14f, rowY, px + pad + labelW + 14f + sw, rowY + rowH)
        level.layoutCells(L.compact)
        val top = rowY + rowH + rowGap
        story.r.set(px + pad, top, px + pad + cw, top + ch)
        endless.r.set(px + pad + cw + gap, top, px + pad + inner, top + ch)
        val smH = back.prefHeight(L.touch, L.compact); val bw = back.prefWidth()
        back.r.set(px + pw - pad - bw, py + padY + HEAD_H - smH, px + pw - pad, py + padY + HEAD_H)
        contentH = py + ph + L.padY
    }

    override fun drawContent(c: Canvas, ctx: DrawCtx, now: Double) {
        val base = ctx.alpha
        Deco.slab(c, px, py, pw, ph, 16f, Pal.ION, base)
        var k = stagger(0, now); var a = base * k; var dy = 16f * (1 - k)
        Txt.draw(c, "Launch run", Styles.eyebrow, px + pad, py + padY + dy, -1, 11f * Txt.LH, a)
        Txt.draw(c, "Flight plan", TextStyle(Fonts.display900, 30f, 0.1f, Pal.INK, true), px + pad, py + padY + HEAD_TITLE_Y + dy, -1, 36f, a)
        k = stagger(level.d, now); a = base * k; dy = 16f * (1 - k)
        Txt.draw(c, "Difficulty", labelStyle(), px + pad, rowY + dy, -1, rowH, a)
        // the caption sits after the control, when the row has room for it
        val cs = captionStyle(); val cap = caption(Difficulty.of(Save.data.difficulty))
        val cx = level.r.right + 16f
        if (cx + Txt.width(cap, cs) <= px + pw - pad) Txt.draw(c, cap, cs, cx, rowY + dy, -1, rowH, a)
        for (w in widgets) {
            val sk = stagger(w.d, now)
            c.save(); c.translate(0f, 16f * (1 - sk)); ctx.alpha = base * sk; w.draw(c, ctx); c.restore()
        }
        ctx.alpha = base
    }

    override fun onKey(code: String): Boolean = when (code) {
        "KeyD" -> { val n = Difficulty.entries.size; pick(Difficulty.entries[(Difficulty.of(Save.data.difficulty).ordinal + 1) % n]); true }
        "Digit1", "Numpad1" -> { ui.action("plan-story"); true }
        "Digit2", "Numpad2" -> { ui.action("plan-endless"); true }
        "Enter", "NumpadEnter" -> if (ui.focused == null || !ui.showFocus) { ui.action(if (offered === endless) "plan-endless" else "plan-story"); true } else false
        "Escape" -> { ui.action("plan-back"); true }
        else -> false
    }
    override fun onBack(): Boolean { ui.action("plan-back"); return true }
}

/** One flight plan: a chamfered slab in its plan's colour with its name, promise, rules and record. */
class PlanCard(val mode: RunMode, private val key: String, private val edge: Int, private val kicker: String, private val tagline: String, private val desc: String) : Widget() {
    var record = ""
    /** The height its text needs at the width it was last measured for. */
    var contentH = 0f
        private set
    private var compact = false
    private var pad = 18f
    private var descLines: List<String> = emptyList()
    private var tagLines: List<String> = emptyList()
    private val path = Path()
    private val washM = Matrix()
    private val washes = HashMap<Int, LinearGradient>()

    override val minHit: Float get() = 0f

    /** A vertical fade of [color] to clear (unit height; placed with a local matrix), made once per colour. */
    private fun wash(color: Int): LinearGradient = washes.getOrPut(color) { LinearGradient(0f, 0f, 0f, 1f, color, color and 0xFFFFFF, Shader.TileMode.CLAMP) }

    /** Rows of whole " · "-separated parts, so no row starts with a separator (a part too long for a row wraps by words). */
    private fun wrapParts(text: String, st: TextStyle, w: Float): List<String> {
        val rows = ArrayList<String>()
        var row = ""
        for (part in text.split(" · ")) {
            val cand = if (row.isEmpty()) part else "$row · $part"
            if (row.isNotEmpty() && Txt.width(cand, st) > w) { rows.add(row); row = part } else row = cand
        }
        if (row.isNotEmpty()) rows.add(row)
        return rows.flatMap { if (Txt.width(it, st) > w) Txt.wrap(it, st, w) else listOf(it) }
    }

    private fun kickerStyle() = TextStyle(Fonts.mono400, 10f, 0.3f, edge, true)
    private fun nameStyle() = TextStyle(Fonts.display900, if (compact) 24f else 30f, 0.08f, Pal.WHITE, true)
    private fun tagStyle() = TextStyle(Fonts.mono400, if (compact) 10.5f else 11.5f, 0.12f, Pal.INK, true)
    private fun descStyle() = TextStyle(Fonts.ui400, if (compact) 12.5f else 14f, 0f, Pal.INK_DIM)
    private fun recordStyle() = TextStyle(Fonts.mono400, if (compact) 10.5f else 11.5f, 0.14f, Pal.SOLAR, true)
    private fun descLh() = if (compact) 1.35f else Txt.LH
    private fun gap() = if (compact) 6f else 10f

    fun measure(w: Float, compact: Boolean) {
        this.compact = compact
        pad = if (compact) 12f else 18f
        val iw = w - 2 * pad
        tagLines = wrapParts(tagline, tagStyle(), iw)
        descLines = Txt.wrap(desc, descStyle(), iw)
        val ks = kickerStyle(); val ds = descStyle()
        // on compact screens the kicker line gives its room to the difficulty row
        contentH = pad + (if (compact) 0f else Txt.lineHeight(ks) + gap() * 0.5f) + nameStyle().size * 1.05f + gap() * 0.6f +
            tagLines.size * Txt.lineHeight(tagStyle()) + gap() + descLines.size * ds.size * descLh() + gap() + Txt.lineHeight(recordStyle()) + pad
    }

    override fun draw(c: Canvas, ctx: DrawCtx) {
        if (!visible) return
        val now = ctx.now
        val focus = ctx.showFocus && ctx.focused === this
        val hot = hovered || focus
        val a = ctx.alpha
        val lift = if (hot) 4f * Theme.EASE_OUT_EXPO(min(1.0, (now - hoverAt) / 250.0)).toFloat() else 0f
        val x = r.left; val y = r.top - lift + (if (pressed) 2f else 0f); val w = r.width(); val h = r.height()
        val cut = if (compact) 14f else 18f
        Deco.slab(c, x, y, w, h, cut, edge, a)
        // a wash of the plan's colour fading down from the top, brighter while hovered or focused
        Deco.chamfer(path, x, y, w, h, cut)
        val sh = wash(Theme.withA(edge, if (hot) 0.16f else 0.09f))
        washM.setScale(1f, h * 0.75f); washM.postTranslate(x, y); sh.setLocalMatrix(washM)
        val fp = Deco.fillPaint(); fp.shader = sh; fp.alpha = (a * 255).toInt().coerceIn(0, 255)
        c.drawPath(path, fp)
        fp.shader = null; fp.alpha = 255
        if (focus) {
            val s = Deco.strokePaint(); s.color = Theme.withA(edge, a); s.strokeWidth = 2f
            Deco.chamfer(path, x + 1, y + 1, w - 2, h - 2, cut); c.drawPath(path, s)
        } else if (hot) {
            val s = Deco.strokePaint(); s.color = Theme.withA(edge, 0.6f * a); s.strokeWidth = 1.5f
            Deco.chamfer(path, x + 1, y + 1, w - 2, h - 2, cut); c.drawPath(path, s)
        }
        // shown in touch mode too, like the draft cards' numbers
        Deco.kbd(c, key, x + w - pad, y + pad + Deco.kbdHeight() / 2 - 2f, a)
        var yy = y + pad
        val ks = kickerStyle()
        if (!compact) { Txt.draw(c, kicker, ks, x + pad, yy, -1, Txt.lineHeight(ks), a); yy += Txt.lineHeight(ks) + gap() * 0.5f }
        val ns = nameStyle(); val nlh = ns.size * 1.05f
        Txt.drawGlow(c, mode.label, ns, x + pad, yy, -1, Theme.withA(edge, if (hot) 0.7f else 0.45f), if (hot) 22f else 14f, nlh, a)
        yy += nlh + gap() * 0.6f
        val ts = tagStyle(); val tlh = Txt.lineHeight(ts)
        for (l in tagLines) { Txt.draw(c, l, ts, x + pad, yy, -1, tlh, a); yy += tlh }
        yy += gap()
        val ds = descStyle(); val dlh = ds.size * descLh()
        for (l in descLines) { Txt.draw(c, l, ds, x + pad, yy, -1, dlh, a); yy += dlh }
        val rs = recordStyle()
        Txt.draw(c, record, rs, x + pad, y + h - pad - Txt.lineHeight(rs), -1, Txt.lineHeight(rs), a * 0.9f)
    }
}
