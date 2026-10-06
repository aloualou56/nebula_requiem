package io.github.aloualou56.nebularequiem.ui

import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import io.github.aloualou56.nebularequiem.core.Audio
import io.github.aloualou56.nebularequiem.core.Draft
import io.github.aloualou56.nebularequiem.core.Ease
import io.github.aloualou56.nebularequiem.core.Game
import io.github.aloualou56.nebularequiem.core.PERK_BY_ID
import io.github.aloualou56.nebularequiem.core.Pal
import io.github.aloualou56.nebularequiem.core.PerkDef
import io.github.aloualou56.nebularequiem.core.RunSummary
import io.github.aloualou56.nebularequiem.core.Screen
import io.github.aloualou56.nebularequiem.core.Sfx
import io.github.aloualou56.nebularequiem.core.fmtInt
import io.github.aloualou56.nebularequiem.render.Fonts
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** `.perk-card`: a chamfered card edged in its rarity colour, with icon, rarity, name, effect and level pips. */
class PerkCard(val index: Int) : Widget() {
    var perk: PerkDef? = null
    var level = 0
    var chosen = false
    var leavingAt = -1e9
    var enterAt = 0.0
    private val camera = Camera()
    private val mtx = Matrix()
    val icon = RectF()

    override val minHit: Float get() = 0f

    fun draw(c: Canvas, ctx: DrawCtx, compact: Boolean, iconSize: Float) {
        val k = perk ?: return
        val now = ctx.now
        val edge = k.rarity.color
        val cut = if (compact) 14f else 18f
        val pad = if (compact) 12f else 18f
        // card-in: .8 s ease-out-expo, delay d·110 + 150 ms, from translateY(40) rotateX(18°)
        val ek = Theme.EASE_OUT_EXPO(((now - enterAt - (index * 110 + 150)) / 800.0).coerceIn(0.0, 1.0)).toFloat()
        var a = ctx.alpha * ek
        var ty = 40f * (1 - ek); var rx = 18f * (1 - ek); var sc = 1f; var bright = 0f
        val hot = (hovered || (ctx.showFocus && ctx.focused === this)) && leavingAt < 0
        if (hot && ek >= 1f) { ty -= 6f; bright = 0.18f }
        if (leavingAt > 0) {
            if (chosen) {
                val f = ((now - leavingAt) / 600.0).coerceIn(0.0, 1.0).toFloat()
                val e = Theme.EASE_OUT_EXPO(f.toDouble()).toFloat()
                if (e < 0.4f) { bright = 0.2f + 1.4f * (e / 0.4f); sc = 1f + 0.05f * (e / 0.4f) }
                else { val g = (e - 0.4f) / 0.6f; bright = 1.6f + 0.4f * g; sc = 1.05f + 0.07f * g; a *= 1 - g }
            } else {
                val f = Theme.EASE_WARP(((now - leavingAt) / 450.0).coerceIn(0.0, 1.0)).toFloat()
                a *= 1 - f; ty += 30f * f; sc = 1f - 0.06f * f
            }
        }
        if (a <= 0.01f) return
        c.save()
        c.translate(r.centerX(), r.centerY() + ty)
        if (rx > 0.05f) {
            camera.save(); camera.rotateX(rx); camera.getMatrix(mtx); camera.restore()
            c.concat(mtx)
        }
        c.scale(sc, sc)
        c.translate(-r.centerX(), -r.centerY())
        val x = r.left; val y = r.top; val w = r.width(); val h = r.height()
        Deco.slab(c, x, y, w, h, cut, edge, a)
        if (ctx.showFocus && ctx.focused === this) {
            val s = Deco.strokePaint(); s.color = Theme.withA(edge, a); s.strokeWidth = 2f
            Deco.chamfer(path, x + 1, y + 1, w - 2, h - 2, cut); c.drawPath(path, s)
        }
        Deco.kbd(c, (index + 1).toString(), x + w - 16f, y + 14f + Deco.kbdHeight() / 2, a)   // shown in touch mode too, as in the original
        var yy = y + pad
        val ib = Icons.get(k.id, edge)
        icon.set(x + pad, yy, x + pad + iconSize, yy + iconSize)
        val ip = Deco.fillPaint(); ip.isFilterBitmap = true; ip.alpha = (255 * a).toInt()
        if (k.rarity == io.github.aloualou56.nebularequiem.core.Rarity.LEGENDARY) {
            // legendary icons cycle their hue
            val hue = ((now / 6000.0) % 1.0 * 360).toFloat()
            ip.colorFilter = hueFilter(hue)
        }
        c.drawBitmap(ib, null, icon, ip)
        ip.colorFilter = null; ip.alpha = 255
        yy += iconSize + (if (compact) 6f else 10f)
        val rs = TextStyle(Fonts.mono400, 10f, 0.3f, edge, true)
        Txt.draw(c, k.rarity.label + if (!k.pseudo && level > 0) " · level ${level + 1}" else "", rs, x + pad, yy, -1, Txt.lineHeight(rs), a)
        yy += Txt.lineHeight(rs) + (if (compact) 6f else 10f)
        val ns = TextStyle(Fonts.display800, if (compact) 16f else 19f, 0.06f, Pal.INK, true)
        val nameLines = Txt.wrap(k.name, ns, w - pad * 2)
        for (l in nameLines) { Txt.draw(c, l, ns, x + pad, yy, -1, Txt.lineHeight(ns), a); yy += Txt.lineHeight(ns) }
        yy += if (compact) 6f else 10f
        val ds = TextStyle(Fonts.ui400, if (compact) 13f else 14f, 0f, Pal.INK_DIM)
        yy += Txt.drawWrapped(c, k.desc(level + 1), ds, x + pad, yy, w - pad * 2, if (compact) 1.35f else Txt.LH, a)
        // level pips (bottom; padding 18px 18px 16px)
        if (!k.pseudo) {
            val py = y + h - (if (compact) 12f else 16f) - 4f
            for (j in 0 until k.max) {
                val px = x + pad + j * 20f
                val fp = Deco.fillPaint()
                when {
                    j < level -> { fp.color = Theme.withA(edge, a); fp.setShadowLayer(8f, 0f, 0f, Theme.withA(edge, a)) }
                    j == level -> fp.color = Theme.withA(Pal.WHITE, a * flicker(now))
                    else -> fp.color = Theme.withA(Pal.LINE, 0.2f * a)
                }
                c.drawRect(px, py, px + 16f, py + 4f, fp)
                fp.clearShadowLayer()
            }
        }
        if (bright > 0.01f) {
            val fp = Deco.fillPaint(); fp.color = Theme.withA(Pal.WHITE, min(0.6f, bright * 0.3f) * a)
            fp.blendMode = android.graphics.BlendMode.PLUS
            Deco.chamfer(path, x, y, w, h, cut); c.drawPath(path, fp); fp.blendMode = null
        }
        c.restore()
    }

    override fun draw(c: Canvas, ctx: DrawCtx) {}
    private val path = android.graphics.Path()

    fun contentHeight(width: Float, compact: Boolean, iconSize: Float): Float {
        val k = perk ?: return 0f
        val pad = if (compact) 12f else 18f
        val gap = if (compact) 6f else 10f
        val ns = TextStyle(Fonts.display800, if (compact) 16f else 19f, 0.06f, Pal.INK, true)
        val ds = TextStyle(Fonts.ui400, if (compact) 13f else 14f, 0f, Pal.INK_DIM)
        val h = pad + iconSize + gap + 10f * Txt.LH + gap + Txt.wrap(k.name, ns, width - pad * 2).size * Txt.lineHeight(ns) + gap +
            Txt.wrappedHeight(k.desc(level + 1), ds, width - pad * 2, if (compact) 1.35f else Txt.LH)
        // the .lvl row is still a flex item (0 tall) for one-off grafts; bottom padding is 16px (12px compact)
        return h + gap + (if (k.pseudo) 0f else 4f) + (if (compact) 12f else 16f)
    }

    companion object {
        /** flicker keyframes (4 s): dips to .35 at 20–22% and .7 at 81% — run at 1.4 s for the next pip. */
        fun flicker(now: Double): Float {
            val f = ((now / 1400.0) % 1.0).toFloat()
            return if (f in 0.2f..0.21f || f in 0.22f..0.23f) 0.35f else if (f in 0.81f..0.82f) 0.7f else 1f
        }
        private val hueCache = HashMap<Int, android.graphics.ColorMatrixColorFilter>()
        fun hueFilter(deg: Float): android.graphics.ColorMatrixColorFilter {
            val key = (deg / 6).toInt()
            return hueCache.getOrPut(key) {
                val cm = android.graphics.ColorMatrix(); cm.setRotate(0, 0f)
                val rad = Math.toRadians((key * 6).toDouble())
                val cs = kotlin.math.cos(rad).toFloat(); val sn = kotlin.math.sin(rad).toFloat()
                // standard hue-rotate matrix (as CSS filter: hue-rotate)
                cm.set(floatArrayOf(
                    0.213f + cs * 0.787f - sn * 0.213f, 0.715f - cs * 0.715f - sn * 0.715f, 0.072f - cs * 0.072f + sn * 0.928f, 0f, 0f,
                    0.213f - cs * 0.213f + sn * 0.143f, 0.715f + cs * 0.285f + sn * 0.140f, 0.072f - cs * 0.072f - sn * 0.283f, 0f, 0f,
                    0.213f - cs * 0.213f - sn * 0.787f, 0.715f - cs * 0.715f + sn * 0.715f, 0.072f + cs * 0.928f + sn * 0.072f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f))
                android.graphics.ColorMatrixColorFilter(cm)
            }
        }
    }
}

/* ═══════════════════════════════════ DRAFT ═══════════════════════════════════ */

class DraftScreen(ui: Ui) : ScreenView(ui, Screen.DRAFT, true) {
    val cards = List(3) { i -> PerkCard(i).apply { d = i; onClick = { Game.pickPerk(i) } } }
    private val reroll = Button("Reroll", BtnKind.SOLAR, null, small = true).apply { d = 3; onClick = { Game.rerollDraft() } }
    var eyebrow = "Level up · synaptic graft"
    private var rerolls = 0
    private var shellX = 0f; private var shellW = 0f; private var titleY = 0f; private var titleSize = 30f; private var titleLH = 36f; private var cardsY = 0f; private var rowY = 0f
    private var iconSize = 64f
    var cardsShownAt = 0.0

    init { widgets.addAll(cards); widgets.add(reroll) }

    fun set(d: Draft, rerolls: Int, eyebrow: String?, now: Double) {
        eyebrow?.let { this.eyebrow = it }
        this.rerolls = rerolls
        val p = Game.player
        for (i in 0 until 3) {
            val k = d.options.getOrNull(i)
            cards[i].perk = k; cards[i].visible = k != null
            cards[i].level = if (k == null || k.pseudo) 0 else p?.level(k.id) ?: 0
            cards[i].chosen = false; cards[i].leavingAt = -1e9; cards[i].enterAt = now
        }
        cardsShownAt = now
        reroll.value = rerolls.toString()
        reroll.enabled = rerolls > 0
        relayout(true)
    }

    fun leave(chosenIndex: Int, now: Double) {
        for (c in cards) { c.leavingAt = now; c.chosen = c.index == chosenIndex }
    }

    override fun layout() {
        val L = L
        val W = L.w
        shellW = min(1040f, W - 2 * L.padX); shellX = (W - shellW) / 2
        val gap = if (L.compact) 10f else 20f
        titleSize = if (L.compact) 24f else if (W >= 768) 36f else 30f
        // text-3xl 30px/36px, md:text-4xl 36px/40px; landscape phones 24px with line-height 1.1
        titleLH = if (L.compact) 24f * 1.1f else if (W >= 768) 40f else 36f
        iconSize = if (L.compact) 40f else if (L.small) 44f else 64f
        val oneCol = L.narrow && !L.compact
        val cg = if (L.compact) 10f else 16f
        val cw = if (oneCol) shellW else (shellW - 2 * cg) / 3
        val hs = FloatArray(3) { if (cards[it].visible) cards[it].contentHeight(cw, L.compact, iconSize) else 0f }
        var ch = 0f
        for (v in hs) ch = max(ch, v)
        val titleH = Txt.lineHeight(Styles.eyebrow) + 4f + titleLH
        val smH = reroll.prefHeight(L.touch, L.compact)
        // stacked (one column): each grid row is as tall as its own card
        val cardsH = if (oneCol) hs.sum() + cg * max(0, cards.count { it.visible } - 1) else ch
        val total = titleH + gap + cardsH + gap + smH
        val top = centerOffset(total)
        titleY = top
        cardsY = top + titleH + gap
        var cy = cardsY
        for ((i, c) in cards.withIndex()) {
            if (oneCol) { c.r.set(shellX, cy, shellX + shellW, cy + hs[i]); if (c.visible) cy += hs[i] + cg }
            else c.r.set(shellX + i * (cw + cg), cardsY, shellX + i * (cw + cg) + cw, cardsY + ch)
        }
        rowY = cardsY + cardsH + gap
        val hint = if (L.touch) "Tap a graft to install it" else "Keys 1 · 2 · 3 to choose"
        val rw = reroll.prefWidth(); val hw = Txt.width(hint, Styles.eyebrow)
        val rx = W / 2 - (rw + 12f + hw) / 2
        reroll.r.set(rx, rowY, rx + rw, rowY + smH)
        contentH = rowY + smH + L.padY
    }

    override fun drawContent(c: Canvas, ctx: DrawCtx, now: Double) {
        val base = ctx.alpha
        val W = L.w
        val eh = Txt.lineHeight(Styles.eyebrow)
        Txt.draw(c, eyebrow, Styles.eyebrow, W / 2, titleY, 0, eh, base)
        val ts = TextStyle(Fonts.display900, titleSize, 0.1f, Pal.INK, true)
        val tw = Txt.width("Choose a graft", ts)
        val ty = titleY + eh + 4f
        Glitch.bursts(c, t(now), true, W / 2 - tw / 2, ty, tw, titleLH) { col, gx, gy -> Txt.draw(c, "Choose a graft", ts, W / 2 + gx, ty + gy, 0, titleLH, base * 0.9f, col) }
        Txt.draw(c, "Choose a graft", ts, W / 2, ty, 0, titleLH, base)
        for (card in cards) if (card.visible) card.draw(c, ctx, L.compact, iconSize)
        reroll.draw(c, ctx)
        val hint = if (L.touch) "Tap a graft to install it" else "Keys 1 · 2 · 3 to choose"
        Txt.draw(c, hint, Styles.eyebrow, reroll.r.right + 12f, reroll.r.top, -1, reroll.r.height(), base)
    }

    override fun onKey(code: String): Boolean = when (code) {
        "Digit1", "Numpad1" -> { Game.pickPerk(0); true }
        "Digit2", "Numpad2" -> { Game.pickPerk(1); true }
        "Digit3", "Numpad3" -> { Game.pickPerk(2); true }
        "KeyR" -> { Game.rerollDraft(); true }
        else -> false
    }
}

/* ═══════════════════════════════════ PAUSE ═══════════════════════════════════ */

class PauseScreen(ui: Ui) : ScreenView(ui, Screen.PAUSE, true) {
    private val resume = Button("Resume", BtnKind.PRIMARY, "Esc").apply { d = 2; onClick = { ui.action("resume") } }
    private val settings = Button("Settings", BtnKind.GHOST).apply { d = 2; onClick = { ui.action("pause-settings") } }
    val abandon = Button("Abandon run", BtnKind.CRIMSON).apply { d = 2; onClick = { ui.action("abandon") } }
    var sub = ""
    private var chips: List<Pair<String, Int>> = emptyList()
    private var px = 0f; private var py = 0f; private var pw = 0f; private var ph = 0f; private var pad = 24f; private var padY = 24f
    private var chipsY = 0f; private var chipRows = 1
    private val chipRects = ArrayList<RectF>()

    init { widgets.addAll(listOf(resume, settings, abandon)) }

    fun render() {
        val g = Game
        sub = "${g.director.planTag(" · ")}${g.director.label()} · ${fmtInt(g.run?.score ?: 0.0)} pts"
        val p = g.player
        chips = p?.perks?.entries?.map { it.key to it.value } ?: emptyList()
        relayout(true)
    }

    override fun layout() {
        val L = L
        val W = L.w
        pw = min(560f, W - 2 * L.padX)
        pad = if (L.compact) 20f else 24f; padY = if (L.compact) 16f else 24f
        val gap = if (L.compact) 10f else 14f
        val inner = pw - 2 * pad
        // chips (flex-wrap, gap 8)
        chipRects.clear()
        val cs = TextStyle(Fonts.ui400, 12f, 0f, Pal.INK)
        var x = 0f; var y = 0f; var rowH = 0f
        for ((id, lvl) in chips) {
            val name = PERK_BY_ID[id]?.name ?: id
            val w = 4f + 22f + 6f + Txt.width(name, cs) + 6f + Txt.width(lvl.toString(), TextStyle(Fonts.mono400, 12f)) + 8f
            if (x > 0 && x + w > inner) { x = 0f; y += rowH + 8f; rowH = 0f }
            chipRects.add(RectF(x, y, x + w, y + 30f))
            x += w + 8f; rowH = max(rowH, 30f)
        }
        val chipsH = if (chips.isEmpty()) 20f else y + rowH
        val btnH = 46f
        val headH = Txt.lineHeight(Styles.eyebrow) + 4f + 40f          // eyebrow, gap-1, text-4xl (36px/40px)
        val labelH = Txt.lineHeight(Styles.hudLabelUi) + 8f            // "Active grafts", gap-2
        ph = padY + headH + gap + labelH + chipsH + gap + btnH * 3 + 8f * 2 + padY
        px = (W - pw) / 2; py = centerOffset(ph)
        chipsY = py + padY + headH + gap + labelH
        var by = chipsY + chipsH + gap
        for (b in listOf(resume, settings, abandon)) { b.r.set(px + pad, by, px + pw - pad, by + btnH); by += btnH + 8f }
        contentH = py + ph + L.padY
    }

    override fun drawContent(c: Canvas, ctx: DrawCtx, now: Double) {
        val base = ctx.alpha
        Deco.slab(c, px, py, pw, ph, 16f, Pal.ION, base)
        var k = stagger(0, now); var a = base * k; var dy = 16f * (1 - k)
        val eh = Txt.lineHeight(Styles.eyebrow)
        Txt.draw(c, sub, Styles.eyebrow, px + pad, py + padY + dy, -1, eh, a)
        val ts = TextStyle(Fonts.display900, 36f, 0.1f, Pal.INK, true)
        val ty = py + padY + eh + 4f + dy
        val tw = Txt.width("Paused", ts)
        Glitch.bursts(c, t(now), false, px + pad, ty, tw, 40f) { col, gx, gy -> Txt.draw(c, "Paused", ts, px + pad + gx, ty + gy, -1, 40f, a * 0.9f, col) }
        Txt.draw(c, "Paused", ts, px + pad, ty, -1, 40f, a)
        k = stagger(1, now); a = base * k; dy = 16f * (1 - k)
        val ls = Styles.hudLabelUi
        Txt.draw(c, "Active grafts", ls, px + pad, chipsY - 8f - Txt.lineHeight(ls) + dy, -1, Txt.lineHeight(ls), a)
        if (chips.isEmpty()) {
            Txt.draw(c, "No grafts yet. Collect shards to level up.", TextStyle(Fonts.ui400, 14f, 0f, Pal.INK_FAINT), px + pad, chipsY + dy, -1, 20f, a)
        } else {
            val cs = TextStyle(Fonts.ui400, 12f, 0f, Pal.INK)
            for ((i, pr) in chips.withIndex()) {
                val r = chipRects.getOrNull(i) ?: continue
                val x = px + pad + r.left; val y = chipsY + r.top + dy
                Deco.fillRect(c, x, y, x + r.width(), y + r.height(), Theme.withA(Pal.LINE, 0.08f * a))
                val s = Deco.strokePaint(); s.color = Theme.withA(Pal.LINE, 0.2f * a); s.strokeWidth = 1f
                c.drawRect(x + 0.5f, y + 0.5f, x + r.width() - 0.5f, y + r.height() - 0.5f, s)
                val def = PERK_BY_ID[pr.first]
                if (def != null) {
                    val ip = Deco.fillPaint(); ip.isFilterBitmap = true; ip.alpha = (255 * a).toInt()
                    iconR.set(x + 4f, y + 4f, x + 26f, y + 26f)
                    c.drawBitmap(Icons.get(def.id, def.rarity.color), null, iconR, ip); ip.alpha = 255
                }
                val nx = x + 32f
                val nw = Txt.draw(c, def?.name ?: pr.first, cs, nx, y, -1, 30f, a)
                Txt.draw(c, pr.second.toString(), TextStyle(Fonts.mono400, 12f, 0f, Pal.ION), nx + nw + 6f, y, -1, 30f, a)
            }
        }
        for (w in widgets) {
            val sk = stagger(w.d, now)
            c.save(); c.translate(0f, 16f * (1 - sk)); ctx.alpha = base * sk; w.draw(c, ctx); c.restore()
        }
        ctx.alpha = base
    }
    private val iconR = RectF()

    override fun onBack(): Boolean { ui.action("resume"); return true }
}

/* ═══════════════════════════════════ GAME OVER ═══════════════════════════════════ */

class OverScreen(ui: Ui) : ScreenView(ui, Screen.OVER, true) {
    private val relaunch = Button("Relaunch", BtnKind.PRIMARY, "Enter").apply { d = 4; onClick = { ui.action("relaunch") } }
    private val hangar = Button("Hangar", BtnKind.SOLAR, "H").apply { d = 4; onClick = { ui.action("hangar") } }
    private val title = Button("Title", BtnKind.GHOST, "Esc").apply { d = 4; onClick = { ui.action("title") } }
    private var sum: RunSummary? = null
    private var log = ""
    private var renderAt = 0.0
    private var countStart = -1.0
    private var purchased = false
    private var shellX = 0f; private var shellW = 0f; private var y0 = 0f; private var titleSize = 60f
    private var logY = 0f; private var logH = 0f; private var rewardY = 0f; private var rewardH = 0f; private var actionsY = 0f; private var actionsH = 0f
    private var stickyActions = false

    init { widgets.addAll(listOf(relaunch, hangar, title)) }

    fun render(s: RunSummary, now: Double) {
        sum = s
        fun pad(k: String) = ("$k ").padEnd(18, '.') + " "
        log = listOf(
            "> ${pad("HULL")}${s.hull}",
            "> ${pad("SECTOR")}${s.sector}",
            "> ${pad("TIME ALIVE")}${s.time}",
            "> ${pad("HOSTILES")}${s.kills} destroyed · ${s.bosses} guardian${if (s.bosses == 1) "" else "s"}",
            "> ${pad("GRAZES")}${s.grazes} · peak chain ${s.chain}",
            "> ${pad("GRAFTS")}${if (s.perks.isEmpty()) "none" else s.perks.joinToString(", ")}",
            "> ${pad("SCORE")}${s.score}${if (s.record) "  ◆ NEW RECORD" else ""}"
        ).joinToString("\n")
        renderAt = now
        countStart = -1.0
        purchased = false
        relayout(true)
    }

    private fun logStyle() = TextStyle(Fonts.mono400, 13.5f, 0f, Pal.INK_DIM)
    private fun numStyle() = TextStyle(Fonts.mono400, if (L.compact) 28f else 34f, 0f, Pal.SOLAR)

    override fun layout() {
        val L = L
        val W = L.w
        shellW = min(760f, W - 2 * L.padX); shellX = (W - shellW) / 2
        val gap = if (L.compact) 10f else 18f
        titleSize = if (L.compact) (L.h * 0.13f).coerceIn(34f, 72f) else (W * 0.08f).coerceIn(42f, 92f)
        // .over-log: p-4 and min-height 9.5em, border-box (none on landscape phones)
        logH = max(Txt.wrappedHeight(log, logStyle(), shellW - 32f) + 32f, if (L.compact) 0f else 13.5f * 9.5f)
        // .over-reward: the number's line box plus padding 14px (10px on landscape phones)
        rewardH = Txt.lineHeight(numStyle()) + 2 * (if (L.compact) 10f else 14f)
        val btnH = 46f
        val perRow = max(1, min(3, floor((shellW + 10f) / (180f + 10f)).toInt()))
        val rows = (3 + perRow - 1) / perRow
        // landscape phones: .over-actions is sticky with padding-block 8px
        val padB = if (L.compact) 8f else 0f
        actionsH = rows * btnH + (rows - 1) * 10f + 2 * padB
        val eh = Txt.lineHeight(Styles.eyebrow)
        val total = eh + gap + titleSize * 0.9f + gap + logH + gap + rewardH + gap + actionsH
        y0 = centerOffset(total)
        logY = y0 + eh + gap + titleSize * 0.9f + gap
        rewardY = logY + logH + gap
        actionsY = rewardY + rewardH + gap
        stickyActions = L.compact
        val bw = (shellW - (perRow - 1) * 10f) / perRow
        for ((i, b) in listOf(relaunch, hangar, title).withIndex()) {
            val col = i % perRow; val by = actionsY + padB + (i / perRow) * (btnH + 10f)
            b.r.set(shellX + col * (bw + 10f), by, shellX + col * (bw + 10f) + bw, by + btnH)
        }
        contentH = actionsY + actionsH + L.padY
    }

    override fun tick(now: Double) {
        if (sum == null) return
        // log types at 90 glyphs/s after 0.5 s; then the stardust counts up over 1.1 s
        val typedEnd = renderAt + 500 + log.length / 90.0 * 1000
        if (countStart < 0 && now >= typedEnd) countStart = now
        if (countStart >= 0 && !purchased && now - countStart >= 1100) { purchased = true; Audio.play(Sfx.PURCHASE) }
    }

    override fun drawContent(c: Canvas, ctx: DrawCtx, now: Double) {
        val s = sum ?: return
        val base = ctx.alpha
        val gap = if (L.compact) 10f else 18f
        var k = stagger(0, now); var a = base * k; var dy = 16f * (1 - k)
        val eh = Txt.lineHeight(Styles.eyebrow)
        Txt.draw(c, s.eyebrow, Styles.eyebrow, shellX, y0 + dy, -1, eh, a)
        k = stagger(1, now); a = base * k; dy = 16f * (1 - k)
        val ts = TextStyle(Fonts.display900, titleSize, 0.05f, Pal.WHITE)   // .over-title has no text-transform
        val ty = y0 + eh + gap + dy
        val tw = Txt.width(s.title, ts)
        Glitch.bursts(c, t(now), true, shellX, ty, tw, titleSize * 0.9f) { col, gx, gy -> Txt.draw(c, s.title, ts, shellX + gx, ty + gy, -1, titleSize * 0.9f, a * 0.9f, col) }
        Txt.drawGlow(c, s.title, ts, shellX, ty, -1, Theme.withA(Pal.CRIMSON, 0.6f), 24f, titleSize * 0.9f, a)
        k = stagger(2, now); a = base * k; dy = 16f * (1 - k)
        Deco.slab(c, shellX, logY + dy, shellW, logH, 12f, Pal.CRIMSON, a)
        val n = if (now < renderAt + 500) 0 else min(log.length, 1 + ((now - renderAt - 500) / 1000.0 * 90).toInt())
        Txt.drawWrapped(c, log.substring(0, n), logStyle(), shellX + 16f, logY + 16f + dy, shellW - 32f, Txt.LH, a)
        k = stagger(3, now); a = base * k; dy = 16f * (1 - k)
        Deco.slab(c, shellX, rewardY + dy, shellW, rewardH, 16f, Pal.SOLAR, a)
        // flex with align-items: baseline: the label sits on the number's baseline
        val rx = if (L.compact) 14f else 18f; val ry = rewardY + (if (L.compact) 10f else 14f) + dy
        val ns = numStyle(); val nlh = Txt.lineHeight(ns); val ls = Styles.hudLabelUi; val llh = Txt.lineHeight(ls)
        Txt.draw(c, "Stardust recovered", ls, shellX + rx, ry + Txt.baseline(ns, nlh) - Txt.baseline(ls, llh), -1, llh, a)
        val shown = if (countStart < 0) 0.0 else s.dust * Ease.outCubic(((now - countStart) / 1100.0).coerceIn(0.0, 1.0))
        Txt.drawGlow(c, (if (countStart < 0) "" else "+") + fmtInt(shown), ns, shellX + shellW - rx, ry, 1, Theme.withA(Pal.SOLAR, 0.55f), 18f, nlh, a)
        if (stickyActions) {
            // landscape phones: .over-actions (a shell-wide void band with a shadow above) sticks to the bottom
            val sk = stagger(4, now); val off = stickyOffset() + 16f * (1 - sk); val top = actionsY + off
            val fp = Deco.fillPaint(); fp.color = Theme.withA(Pal.VOID, base * sk)
            fp.setShadowLayer(16f, 0f, -12f, Theme.withA(Pal.VOID, base * sk))
            c.drawRect(shellX, top, shellX + shellW, top + actionsH, fp); fp.clearShadowLayer()
            for (b in listOf(relaunch, hangar, title)) { c.save(); c.translate(0f, off); ctx.alpha = base * sk; b.draw(c, ctx); c.restore() }
        } else for (w in widgets) {
            val sk = stagger(w.d, now)
            c.save(); c.translate(0f, 16f * (1 - sk)); ctx.alpha = base * sk; w.draw(c, ctx); c.restore()
        }
        ctx.alpha = base
    }

    /**
     * Sticky actions (bottom: 0 inside the screen's 10px block padding) sit at a scroll-dependent
     * offset from their laid-out place: draw and hit-test them there.
     */
    fun stickyOffset(): Float = if (stickyActions) min(0f, scroll + L.h - L.padY - actionsH - actionsY) else 0f
    // the sticky actions (its only widgets) are drawn, and so hit, at their sticky offset
    override fun widgetY(w: Widget, y: Float): Float = super.widgetY(w, y) - stickyOffset()
    // while pinned to the bottom edge the action band doesn't move with the summary above it
    override fun pinnedAt(x: Float, y: Float): Boolean = stickyOffset() < 0f && y >= L.h - L.padY - actionsH

    override fun onKey(code: String): Boolean = when (code) {
        "Enter" -> { if (ui.focused == null || !ui.showFocus) { ui.action("relaunch"); true } else false }
        "KeyH" -> { ui.action("hangar"); true }
        "Escape" -> { ui.action("title"); true }
        else -> false
    }
    override fun onBack(): Boolean { ui.action("title"); return true }
}
