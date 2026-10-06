package io.github.aloualou56.nebularequiem.ui

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import io.github.aloualou56.nebularequiem.core.Audio
import io.github.aloualou56.nebularequiem.core.Env
import io.github.aloualou56.nebularequiem.core.Game
import io.github.aloualou56.nebularequiem.core.HALF_PI
import io.github.aloualou56.nebularequiem.core.HULLS
import io.github.aloualou56.nebularequiem.core.HULL_ORDER
import io.github.aloualou56.nebularequiem.core.Pal
import io.github.aloualou56.nebularequiem.core.Perf
import io.github.aloualou56.nebularequiem.core.Quality
import io.github.aloualou56.nebularequiem.core.Save
import io.github.aloualou56.nebularequiem.core.Screen
import io.github.aloualou56.nebularequiem.core.Sfx
import io.github.aloualou56.nebularequiem.core.UPGRADES
import io.github.aloualou56.nebularequiem.core.fmtInt
import io.github.aloualou56.nebularequiem.core.modI
import io.github.aloualou56.nebularequiem.render.Fonts
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/* ═══════════════════════════════════ TITLE ═══════════════════════════════════ */

class TitleScreen(ui: Ui) : ScreenView(ui, Screen.TITLE, false) {
    private val resume = Button("Resume run", BtnKind.PLASMA, "R").apply { d = 1; onClick = { Audio.play(Sfx.UI_CLICK); Game.resumeCheckpoint() } }
    private val launch = Button("Launch run", BtnKind.PRIMARY, "Enter").apply { d = 2; onClick = { ui.action("launch") } }
    private val hangar = Button("Hangar", BtnKind.SOLAR, "H").apply { d = 3; onClick = { ui.action("hangar") } }
    private val manual = Button("Flight manual", BtnKind.GHOST, "M").apply { d = 4; onClick = { ui.action("manual") } }
    private val settings = Button("Settings", BtnKind.GHOST, "O").apply { d = 5; onClick = { ui.action("settings") } }
    private val taglines = listOf(
        "Ten guardians. One requiem. One white-hot hitbox.",
        "Graze the bullets. Bank the flux. Break the tesseract.",
        "Every wreck is geometry. Every geometry is a weakness.",
        "The nebula remembers each pilot it swallows."
    )
    private var tagIdx = 0
    private var tagStart = 0.0

    // layout results
    private var lx = 0f; private var ly = 0f; private var lw = 0f
    private var logoSize = 100f
    private var eyebrowY = 0f; private var logoY = 0f; private var tagY = 0f; private var gridY = 0f; private var noteY = 0f
    private var gridCellH = 40f; private var hintY = 0f; private var rx = 0f; private var rw = 0f
    private var leftGap = 16f
    private var eyebrowH = 16f; private var tagH = 19.5f

    init { widgets.addAll(listOf(resume, launch, hangar, manual, settings)) }

    override fun onShow() { tagStart = Env.now(); }

    override fun layout() {
        val L = L
        val W = L.w
        val shellW = min(1120f, W - 2 * L.padX)
        val twoCol = !L.narrow
        val gap = if (L.compact) 24f else (W * 0.04f).coerceIn(20f, 56f)
        logoSize = if (L.compact) (L.h * 0.15f).coerceIn(40f, 96f) else (W * 0.105f).coerceIn(54f, 148f)
        leftGap = if (L.compact) 10f else 16f
        val logoBlock = logoSize * 0.86f * 2 + 4f
        val cellPadY = if (L.compact) 6f else 10f; val ddSize = if (L.compact) 15f else 18f
        gridCellH = cellPadY * 2 + 10f * Txt.LH + 2f + ddSize * Txt.LH
        val gridH = gridCellH * 2 + 1f
        // left column: eyebrow, logo, tagline (min-height 1.5em) and records; long lines wrap on narrow screens
        val colW = if (!L.narrow) (shellW - gap) * 1.25f / 2.15f else shellW
        eyebrowH = Txt.wrappedHeight(EYEBROW, Styles.eyebrow, colW)
        tagH = max(13f * Txt.LH, taglines.maxOf { Txt.wrappedHeight(it, TAG_STYLE, colW) })
        var leftH = eyebrowH + leftGap + logoBlock + leftGap + tagH + leftGap + 8f + gridH
        if (!Save.available) leftH += leftGap + 11f * Txt.LH
        val hasResume = Save.data.run != null
        resume.visible = hasResume
        // compact `.title-shell > .stagger { gap: 10px }` outranks `.menu { gap: 8px }`
        val menuGap = 10f
        val btnH = 46f
        val nBtn = if (hasResume) 5 else 4
        val hintW: Float
        if (twoCol) {
            lw = (shellW - gap) * 1.25f / 2.15f; rw = (shellW - gap) * 0.9f / 2.15f
            hintW = rw
        } else { lw = shellW; rw = shellW; hintW = shellW }
        val hint = hintText()
        val hintH = Txt.wrappedHeight(hint, TextStyle(Fonts.mono400, 11f, 0.16f, Pal.INK_DIM, true), hintW)
        val rightH = nBtn * btnH + (nBtn - 1) * menuGap + menuGap + 12f + hintH
        val x0 = (W - shellW) / 2
        if (twoCol) {
            val rowH = max(leftH, rightH)
            val top = centerOffset(rowH)
            lx = x0; ly = top + (rowH - leftH) / 2
            rx = x0 + lw + gap
            val ry = top + (rowH - rightH) / 2
            layoutMenu(ry, btnH, menuGap, hasResume)
            contentH = top + rowH + L.padY
        } else {
            // one grid column: the clamp(20px, 4vw, 56px) gap now separates the rows
            val total = leftH + gap + rightH
            val top = centerOffset(total)
            lx = x0; ly = top
            rx = x0
            layoutMenu(top + leftH + gap, btnH, menuGap, hasResume)
            contentH = top + total + L.padY
        }
        eyebrowY = ly
        logoY = eyebrowY + eyebrowH + leftGap
        tagY = logoY + logoBlock + leftGap
        gridY = tagY + tagH + leftGap + 8f
        noteY = gridY + gridH + leftGap
    }

    private fun layoutMenu(top: Float, btnH: Float, gap: Float, hasResume: Boolean) {
        var y = top
        for (b in listOf(resume, launch, hangar, manual, settings)) {
            if (b === resume && !hasResume) continue
            b.r.set(rx, y, rx + rw, y + btnH); y += btnH + gap
        }
        hintY = y + 12f - gap + gap
    }

    private fun hintText() = if (L.touch) "Left thumb steers · right thumb aims and fires" else "WASD move · mouse aim · Space dash · E nova · Q overdrive"

    override fun tick(now: Double) {
        // typewriter tagline: 34 glyphs/s, then hold 4.2 s
        val text = taglines[tagIdx % taglines.size]
        val typed = (now - tagStart) / 1000.0 * 34
        if (typed >= text.length && now - tagStart > text.length / 34.0 * 1000 + 4200) { tagIdx++; tagStart = now }
        Save.data.run?.let { resume.value = "S${it.sector}·W${it.wave}" }
    }

    override fun drawContent(c: Canvas, ctx: DrawCtx, now: Double) {
        val base = ctx.alpha
        val t = t(now)
        // eyebrow (d0)
        var k = stagger(0, now)
        Txt.drawWrapped(c, EYEBROW, Styles.eyebrow, lx, eyebrowY + 16f * (1 - k), lw, Txt.LH, base * k)
        // logo (d1)
        k = stagger(1, now)
        val dy = 16f * (1 - k); val a = base * k
        val lh = logoSize * 0.86f
        val neb = TextStyle(Fonts.display900, logoSize, 0.04f, Pal.WHITE, true)
        val nw = Txt.width("NEBULA", neb)
        Glitch.bursts(c, t, false, lx, logoY + dy, nw, lh) { col, gx, gy -> Txt.draw(c, "NEBULA", neb, lx + gx, logoY + dy + gy, -1, lh, a * 0.9f, col) }
        Txt.drawGlow(c, "NEBULA", neb, lx, logoY + dy, -1, Theme.withA(Pal.PLASMA, 0.35f), 40f, lh, a)
        Txt.drawGlow(c, "NEBULA", neb, lx, logoY + dy, -1, Theme.withA(Pal.ION, 0.55f), 14f, lh, a)
        val req = TextStyle(Fonts.display800, logoSize, 0.12f, Pal.ION, true)
        val ry = logoY + lh + 4f + dy
        val rqw = Txt.width("REQUIEM", req)
        Glitch.bursts(c, t + 0.37, false, lx, ry, rqw, lh) { col, gx, gy -> Txt.drawStroke(c, "REQUIEM", req, lx + gx, ry + gy, -1, 1.5f, col, col, 0f, lh, a) }
        // text-shadow: 0 0 22px ion/.35 is cast by the (transparent) filled glyphs, under the 1.5 px stroke
        Txt.drawShadowOnly(c, "REQUIEM", req, lx, ry, -1, Theme.withA(Pal.ION, 0.35f), 22f, lh, a)
        Txt.drawStroke(c, "REQUIEM", req, lx, ry, -1, 1.5f, Theme.withA(Pal.ION, 0.95f), 0, 0f, lh, a)
        // tagline (d2) with caret
        k = stagger(2, now)
        val tag = taglines[tagIdx % taglines.size]
        val n = min(tag.length, 1 + ((now - tagStart) / 1000.0 * 34).toInt())
        // The full line is wrapped first so typed words never jump between lines.
        val tagTop = tagY + 16f * (1 - k)
        val lineH = 13f * Txt.LH
        var pos = 0; var caretX = lx; var caretY = tagTop
        for ((li, line) in Txt.wrap(tag, TAG_STYLE, lw).withIndex()) {
            if (pos >= n) break
            val vis = min(line.length, n - pos)
            val ly = tagTop + li * lineH
            caretX = lx + Txt.draw(c, line.substring(0, vis), TAG_STYLE, lx, ly, -1, lineH, base * k); caretY = ly
            pos += line.length
            if (tag.getOrNull(pos) == ' ') pos++   // the space the wrap consumed (none after a hyphen break)
        }
        // caret: inline-block .55em × 1em after a .12em margin, its bottom .12em below the baseline
        val caretB = caretY + Txt.baseline(TAG_STYLE, lineH) + 13f * 0.12f
        if (floor(now / 500.0).toInt() % 2 == 0) Deco.fillRect(c, caretX + 13f * 0.12f, caretB - 13f, caretX + 13f * 0.67f, caretB, Theme.withA(Pal.INK_DIM, base * k))
        // records (d3)
        k = stagger(3, now)
        drawRecords(c, gridY + 16f * (1 - k), base * k)
        if (!Save.available) {
            k = stagger(4, now)
            Txt.draw(c, "Storage unavailable · progress lasts until you close the app", Styles.eyebrow, lx, noteY + 16f * (1 - k), -1, 11f * Txt.LH, base * k, Pal.CRIMSON)
        }
        // menu
        for (w in widgets) {
            if (!w.visible) continue
            val sk = stagger(w.d, now)
            c.save(); c.translate(0f, 16f * (1 - sk)); ctx.alpha = base * sk; w.draw(c, ctx); c.restore()
        }
        ctx.alpha = base
        k = stagger(6, now)
        Txt.drawWrapped(c, hintText(), TextStyle(Fonts.mono400, 11f, 0.16f, Pal.INK_DIM, true), rx, hintY + 16f * (1 - k), rw, Txt.LH, base * k)
    }

    private val gridClip = Path()

    private fun drawRecords(c: Canvas, y: Float, a: Float) {
        val w = lw; val cw = (w - 1f) / 2; val h = gridCellH * 2 + 1f
        // `.record-grid { background }` replaces the slab's gradient and corner stripes: line/.16 shows
        // only through the 1px gaps between near-opaque cells, all clipped to the 12px chamfer
        // (the slab's inset edge line is painted under the cells, so no border either).
        c.save(); Deco.chamfer(gridClip, lx, y, w, h, 12f); c.clipPath(gridClip)
        Deco.fillRect(c, lx, y, lx + w, y + h, Theme.withA(Pal.LINE, 0.16f * a))
        val cellBg = Theme.withA(0xFF070414.toInt(), 0.86f * a)
        for (i in 0 until 4) { val x0 = lx + (i % 2) * (cw + 1f); val y0 = y + (i / 2) * (gridCellH + 1f); Deco.fillRect(c, x0, y0, x0 + cw, y0 + gridCellH, cellBg) }
        c.restore()
        val s = Save.data.stats
        val cells = arrayOf(
            "Best score" to fmtInt(s.bestScore),
            "Deepest sector" to (if (s.bestSector > 0) "${s.bestSector.toInt()} · wave ${s.bestWave.toInt()}" else "—"),
            "Bosses felled" to fmtInt(s.bossKills),
            "Stardust" to fmtInt(Save.data.stardust)
        )
        val padX = if (L.compact) 10f else 12f; val padY = if (L.compact) 6f else 10f; val dd = if (L.compact) 15f else 18f
        for (i in 0 until 4) {
            val cx = lx + (i % 2) * (cw + 1f) + padX; val cy = y + (i / 2) * (gridCellH + 1f) + padY
            Txt.draw(c, cells[i].first, TextStyle(Fonts.mono400, 10f, 0.22f, Pal.INK_FAINT, true), cx, cy, -1, 10f * Txt.LH, a)
            val vs = TextStyle(Fonts.mono400, dd, 0f, if (i == 3) Pal.SOLAR else Pal.INK)
            val ddY = cy + 10f * Txt.LH + 2f   // dd { margin-top: 2px }
            if (i == 3) Txt.drawGlow(c, cells[i].second, vs, cx, ddY, -1, Theme.withA(Pal.SOLAR, 0.45f), 12f, dd * Txt.LH, a)
            else Txt.draw(c, cells[i].second, vs, cx, ddY, -1, dd * Txt.LH, a)
        }
    }

    override fun onKey(code: String): Boolean = when (code) {
        "Enter", "NumpadEnter" -> if (ui.focused == null || !ui.showFocus) { ui.action("launch"); true } else false
        "KeyH" -> { ui.action("hangar"); true }
        "KeyM" -> { ui.action("manual"); true }
        "KeyO" -> { ui.action("settings"); true }
        "KeyR" -> { if (Save.data.run != null) { Audio.play(Sfx.UI_CLICK); Game.resumeCheckpoint() }; true }
        else -> false
    }
}

/* ═══════════════════════════════════ HANGAR ═══════════════════════════════════ */

class HangarScreen(ui: Ui) : ScreenView(ui, Screen.HANGAR, false) {
    private val back = Button("Back", BtnKind.GHOST, "Esc", small = true).apply { d = 0; onClick = { ui.action("back") } }
    private val launch = Button("Launch", BtnKind.PRIMARY, "Enter", small = true).apply { d = 0; onClick = { ui.action("launch") } }
    private val prev = Button("‹", BtnKind.GHOST, null, small = true).apply { d = 1; onClick = { ui.action("hull-prev") } }
    private val next = Button("›", BtnKind.GHOST, null, small = true).apply { d = 1; onClick = { ui.action("hull-next") } }
    val hullAction = Button("Selected", BtnKind.PRIMARY).apply { d = 1; onClick = { ui.hullAction(this) } }
    private val buy = UPGRADES.map { u -> Button("Refit", BtnKind.SOLAR, null, small = true).apply { d = 2; onClick = { ui.buyUpgrade(u.id, this) } } }
    var hullIdx = 0
    var dustBumpAt = -1e9
    val cardPulse = DoubleArray(UPGRADES.size) { -1e9 }

    private var shellX = 0f; private var shellW = 0f; private var headY = 0f; private var headH = 0f
    private var titleSize = 36f; private var titleLH = 40f
    private var bayX = 0f; private var bayY = 0f; private var bayW = 0f; private var bayH = 0f
    private var prevX = 0f; private var prevY = 0f; private var prevW = 0f; private var prevH = 0f
    private var descY = 0f; private var statsY = 0f; private var bayPad = 18f
    private var gridX = 0f; private var gridY = 0f; private var gridW = 0f; private var refitNoteH = 16.5f
    private var cols = 1; private var cardW = 0f
    // grid rows take their tallest card; each card's content is top-aligned (a flex column)
    private val cardTop = FloatArray(UPGRADES.size); private val cardH = FloatArray(UPGRADES.size)
    private var nameRowY = 0f; private var tagLines: List<String> = emptyList()
    private var dustX = 0f; private var dustW = 0f; private var dustY = 0f; private var dustH = 46f
    private var dustSize = 20f

    // phones and foldables: a fixed top bar and hull panel; only the refit list scrolls, in its own pane
    private var compactMode = false
    private val region = RectF()
    private var titleX = 0f
    private var descLines: List<String> = emptyList()
    private var descLH = 17f
    private var statRowH = 24f
    private var noteY = 0f
    private var btnW = 0f; private var textW = 0f; private var cardPad = 12f
    private val cardX = FloatArray(UPGRADES.size)
    private val cardDesc = arrayOfNulls<List<String>>(UPGRADES.size)
    private val cardName = arrayOfNulls<String>(UPGRADES.size)

    init { widgets.addAll(listOf(back, launch, prev, next, hullAction)); widgets.addAll(buy) }

    override val scrollRegion: RectF? get() = if (compactMode) region else null
    override fun scrolls(w: Widget): Boolean = !compactMode || w in buy

    override fun onShow() { hullIdx = max(0, HULL_ORDER.indexOf(Save.data.selected)) }

    override fun layout() {
        // phones (compact) and foldables (narrow) get the pane layout; wide tablets fit the original
        compactMode = L.compact || L.narrow
        if (compactMode) { layoutCompact(); return }
        dustSize = 20f
        val L = L
        val W = L.w
        val touch = L.touch
        shellW = min(1180f, W - 2 * L.padX); shellX = (W - shellW) / 2
        val twoCol = !L.narrow
        val gap = if (L.compact) 12f else 18f
        titleSize = if (W >= 768) 48f else 36f
        // text-4xl is 36px on a 40px line; md:text-5xl is 48px with line-height 1
        titleLH = if (W >= 768) 48f else 40f
        val smH = back.prefHeight(touch, L.compact)
        // header: title block left, chips/buttons right (wrapping on narrow screens)
        val titleBlock = 11f * Txt.LH + 4f + titleLH
        dustW = 14f + 10f + Txt.width(fmtInt(Save.data.stardust), TextStyle(Fonts.mono400, 20f)) + 28f
        dustH = 16f + 20f * Txt.LH   // padding 8px around one 20px line
        val bw = back.prefWidth(); val lw = launch.prefWidth()
        val rightW = dustW + 12f + bw + 12f + lw
        val rightH = max(dustH, smH)   // items-center: chip and buttons centred on one row
        val headerTop = L.padY
        val leftTitleW = Txt.width("HANGAR", TextStyle(Fonts.display900, titleSize, 0.1f, Pal.INK, true)) + 20f
        val oneRow = leftTitleW + 12f + rightW <= shellW
        var y: Float
        if (oneRow) {
            headH = titleBlock
            val rowTop = headerTop + titleBlock - rightH   // align-items: end
            launch.r.set(shellX + shellW - lw, rowTop + (rightH - smH) / 2, shellX + shellW, rowTop + (rightH + smH) / 2)
            back.r.set(launch.r.left - 12f - bw, launch.r.top, launch.r.left - 12f, launch.r.bottom)
            dustX = back.r.left - 12f - dustW; dustY = rowTop + (rightH - dustH) / 2
            y = headerTop + titleBlock
        } else {
            headH = titleBlock + 12f + rightH
            val rowTop = headerTop + titleBlock + 12f
            dustX = shellX; dustY = rowTop + (rightH - dustH) / 2
            back.r.set(dustX + dustW + 12f, rowTop + (rightH - smH) / 2, dustX + dustW + 12f + bw, rowTop + (rightH + smH) / 2)
            launch.r.set(back.r.right + 12f, back.r.top, back.r.right + 12f + lw, back.r.bottom)
            y = rowTop + rightH
        }
        headY = headerTop
        y += gap
        // hull bay
        bayPad = if (L.compact) 12f else 18f
        val bayGap = if (L.compact) 8f else 12f
        // minmax(0, 300px | 360px) fills to its limit before the 1fr refit column gets the rest
        bayW = if (twoCol) min(if (L.compact) 300f else 360f, shellW - gap) else shellW
        bayX = shellX; bayY = y
        val inner = bayW - bayPad * 2
        var by = bayY + bayPad
        val h = HULLS.getValue(HULL_ORDER[hullIdx])
        // name row: the tag wraps in the centre column between the arrows (justify-between, gap-2),
        // above the text-2xl name on its 32px line
        val pw = prev.prefWidth()
        tagLines = Txt.wrap(h.tag, Styles.eyebrow, inner - 2 * pw - 16f)
        val nameRowH = max(smH, tagLines.size * 11f * Txt.LH + 32f)
        prev.r.set(bayX + bayPad, by + (nameRowH - smH) / 2, bayX + bayPad + pw, by + (nameRowH - smH) / 2 + smH)
        next.r.set(bayX + bayW - bayPad - pw, prev.r.top, bayX + bayW - bayPad, prev.r.bottom)
        nameRowY = by
        by += nameRowH + bayGap
        if (L.compact) { prevH = min(L.h * 0.38f, 160f); prevW = prevH * 4f / 3f } else { prevW = inner; prevH = inner * 0.75f }
        prevX = bayX + bayPad + (inner - prevW) / 2; prevY = by
        by += prevH + bayGap
        descY = by
        by += Txt.wrappedHeight(h.desc, TextStyle(Fonts.ui400, 14f, 0f, Pal.INK_DIM), inner, 20f / 14f) + bayGap   // text-sm: 14px on a 20px line
        statsY = by
        by += 4 * 11f * Txt.LH + 3 * 8f + bayGap
        hullAction.r.set(bayX + bayPad, by, bayX + bayW - bayPad, by + 46f)
        by += 46f + bayPad
        bayH = by - bayY
        // refit grid
        if (twoCol) { gridX = bayX + bayW + gap; gridW = shellX + shellW - gridX; gridY = bayY }
        else { gridX = shellX; gridW = shellW; gridY = bayY + bayH + gap }
        cols = max(1, floor((gridW + 12f) / (232f + 12f)).toInt())
        cardW = (gridW - (cols - 1) * 12f) / cols
        val descMin = 13f * 2.8f   // p { min-height: 2.8em }
        val h3 = 15f * Txt.LH
        refitNoteH = Txt.wrappedHeight(REFIT_NOTE, Styles.eyebrow, gridW)
        var rowTop = gridY + refitNoteH + 12f
        var gridBottom = rowTop
        for (r0 in UPGRADES.indices step cols) {
            val r1 = min(UPGRADES.size, r0 + cols)
            var rowH = 0f
            for (i in r0 until r1) {
                val u = UPGRADES[i]
                val d = max(descMin, Txt.wrappedHeight(u.desc(Save.data.up(u.id)), TextStyle(Fonts.ui400, 13f, 0f, Pal.INK_DIM), cardW - 28f))
                val bTop = 14f + h3 + 8f + d + 8f + 4f + 8f
                val cx = gridX + (i - r0) * (cardW + 12f)
                buy[i].r.set(cx + 14f, rowTop + bTop, cx + cardW - 14f, rowTop + bTop + smH)
                rowH = max(rowH, bTop + smH + 12f)
            }
            for (i in r0 until r1) { cardTop[i] = rowTop; cardH[i] = rowH }
            gridBottom = rowTop + rowH
            rowTop = gridBottom + 12f
        }
        contentH = max(bayY + bayH, gridBottom) + L.padY
        refresh()
    }

    /**
     * Phones and foldables: one row of controls on top, the hull panel fitted to the height on the
     * left, and the refits as a list (one column, or two when wide) that scrolls on its own.
     */
    private fun layoutCompact() {
        refresh()   // labels and costs set the buttons' widths
        val L = L
        val W = L.w; val H = L.h
        shellW = min(1180f, W - 2 * L.padX); shellX = (W - shellW) / 2
        val barH = 44f
        val top = L.padY
        headY = top
        // top bar: Back · HANGAR ··· stardust · Launch
        back.r.set(shellX, top, shellX + back.prefWidth(), top + barH)
        val lw = launch.prefWidth()
        launch.r.set(shellX + shellW - lw, top, shellX + shellW, top + barH)
        dustSize = 18f; dustH = barH
        dustW = 12f + 14f + 10f + Txt.width(fmtInt(Save.data.stardust), TextStyle(Fonts.mono400, dustSize)) + 14f
        dustX = launch.r.left - 10f - dustW; dustY = top
        titleSize = 24f; titleLH = barH
        titleX = back.r.right + 16f
        val bodyTop = top + barH + 10f

        // hull panel
        bayPad = 10f
        bayW = (shellW * 0.36f).coerceIn(232f, 330f)
        bayX = shellX; bayY = bodyTop; bayH = H - L.padY - bodyTop
        val inner = bayW - 2 * bayPad
        val h = HULLS.getValue(HULL_ORDER[hullIdx])
        nameRowY = bayY + bayPad
        val pw = prev.prefWidth()   // the 44 dp touch target extends past the drawn arrow
        prev.r.set(bayX + bayPad, nameRowY, bayX + bayPad + pw, nameRowY + 44f)
        next.r.set(bayX + bayW - bayPad - pw, nameRowY, bayX + bayW - bayPad, nameRowY + 44f)
        tagLines = listOf(fit(h.tag, Styles.eyebrow, inner - 2 * pw - 12f))
        hullAction.r.set(bayX + bayPad, bayY + bayH - bayPad - 44f, bayX + bayW - bayPad, bayY + bayH - bayPad)
        statRowH = 24f
        statsY = hullAction.r.top - 8f - (2 * statRowH + 4f)
        // the preview takes what is left; the description keeps up to three lines while the preview stays useful
        val midTop = nameRowY + 44f + 4f; val midBottom = statsY - 6f
        val ds = descStyle()
        descLH = 17f
        val lines = Txt.wrap(h.desc, ds, inner)
        var n = min(3, lines.size)
        while (n > 0 && midBottom - midTop - (n * descLH + 6f) < 72f) n--
        descLines = if (n >= lines.size) lines else lines.take(n).toMutableList().also { if (n > 0) it[n - 1] = fit(it[n - 1] + " " + lines[n], ds, inner) }
        prevX = bayX + bayPad; prevW = inner; prevY = midTop
        prevH = max(40f, midBottom - midTop - (if (n > 0) n * descLH + 6f else 0f))
        descY = prevY + prevH + 6f

        // refit list: its own scrolling pane, down to the screen's bottom edge
        gridX = bayX + bayW + 12f; gridW = shellX + shellW - gridX
        noteY = bodyTop
        region.set(gridX, bodyTop + 20f, gridX + gridW, H)
        cardPad = 12f
        btnW = buy.maxOf { it.prefWidth() }.coerceAtLeast(96f)
        // two columns only when each card still has room for its name and level beside the button
        cols = if ((gridW - 10f) / 2 - 2 * cardPad - btnW - 10f >= 200f) 2 else 1
        cardW = (gridW - (cols - 1) * 10f) / cols
        btnW = min(btnW, cardW * 0.42f)
        textW = cardW - 2 * cardPad - btnW - 10f
        val cs = cardDescStyle(); val ns = cardNameStyle()
        var rowTop = region.top + 2f
        var bottom = rowTop
        for (r0 in UPGRADES.indices step cols) {
            val r1 = min(UPGRADES.size, r0 + cols)
            var rowH = 0f
            for (i in r0 until r1) {
                val u = UPGRADES[i]
                cardName[i] = fit(u.name, ns, textW - 36f)
                val ls = Txt.wrap(u.desc(Save.data.up(u.id)), cs, textW)
                val shown = if (ls.size <= 3) ls else ls.take(3).toMutableList().also { it[2] = fit(it[2] + " " + ls[3], cs, textW) }
                cardDesc[i] = shown
                rowH = max(rowH, max(44f, 18f + 4f + shown.size * 16f + 8f + 4f) + 2 * 10f)
            }
            for (i in r0 until r1) {
                val cx = gridX + (i - r0) * (cardW + 10f)
                cardX[i] = cx; cardTop[i] = rowTop; cardH[i] = rowH
                buy[i].r.set(cx + cardW - cardPad - btnW, rowTop + (rowH - 44f) / 2, cx + cardW - cardPad, rowTop + (rowH + 44f) / 2)
            }
            bottom = rowTop + rowH
            rowTop = bottom + 10f
        }
        contentH = bottom + 12f + L.padY
    }

    private fun descStyle() = TextStyle(Fonts.ui400, 12.5f, 0f, Pal.INK_DIM)
    private fun cardDescStyle() = TextStyle(Fonts.ui400, 12f, 0f, Pal.INK_DIM)
    private fun cardNameStyle() = TextStyle(Fonts.display600, 13.5f, 0.08f, Pal.INK, true)

    /** [s] cut with an ellipsis to fit [maxW]. */
    private fun fit(s: String, st: TextStyle, maxW: Float): String {
        if (Txt.width(s, st) <= maxW) return s
        var t = s
        while (t.isNotEmpty() && Txt.width("$t…", st) > maxW) t = t.dropLast(1)
        return t.trimEnd() + "…"
    }

    /** Update labels, costs and enabled states (refreshHangar). */
    fun refresh() {
        val save = Save.data; val dust = save.stardust
        for (i in UPGRADES.indices) {
            val u = UPGRADES[i]; val lvl = save.up(u.id); val maxed = lvl >= u.max; val cost = u.cost(lvl)
            val b = buy[i]
            b.label = if (maxed) "Maxed" else "Refit"
            b.value = if (maxed) null else fmtInt(cost)
            b.enabled = !maxed
            b.dimmed = !maxed && cost > dust
        }
        val id = HULL_ORDER[hullIdx]; val h = HULLS.getValue(id)
        val unlocked = save.unlocked.contains(id); val selected = save.selected == id
        hullAction.label = if (selected) "Selected" else if (unlocked) "Select hull" else "Unlock hull"
        hullAction.value = if (unlocked) null else fmtInt(h.cost)
        hullAction.enabled = !selected
        hullAction.kind = if (unlocked) BtnKind.PRIMARY else BtnKind.SOLAR
    }

    override fun drawContent(c: Canvas, ctx: DrawCtx, now: Double) {
        if (compactMode) { drawCompact(c, ctx, now); return }
        val base = ctx.alpha
        val t = t(now)
        // header (d0)
        var k = stagger(0, now); var dy = 16f * (1 - k); var a = base * k
        Txt.draw(c, "Orbital drydock · permanent refits", Styles.eyebrow, shellX, headY + dy, -1, 11f * Txt.LH, a)
        val ts = TextStyle(Fonts.display900, titleSize, 0.1f, Pal.INK, true)
        val tw = Txt.width("Hangar", ts)
        val ty = headY + 11f * Txt.LH + 4f + dy
        Glitch.bursts(c, t, false, shellX, ty, tw, titleLH) { col, gx, gy -> Txt.draw(c, "Hangar", ts, shellX + gx, ty + gy, -1, titleLH, a * 0.9f, col) }
        Txt.draw(c, "Hangar", ts, shellX, ty, -1, titleLH, a)
        drawDust(c, now, dy, a)
        drawWidget(c, ctx, back, now, base); drawWidget(c, ctx, launch, now, base)

        // hull bay (d1)
        k = stagger(1, now); dy = 16f * (1 - k); a = base * k
        val id = HULL_ORDER[hullIdx]; val h = HULLS.getValue(id)
        c.save(); c.translate(0f, dy)
        Deco.slab(c, bayX, bayY, bayW, bayH, 16f, h.color, a)
        val cx = bayX + bayW / 2; val tagLH = 11f * Txt.LH
        for ((li, line) in tagLines.withIndex()) Txt.draw(c, line, Styles.eyebrow, cx, nameRowY + li * tagLH, 0, tagLH, a)
        Txt.draw(c, h.name, TextStyle(Fonts.display900, 24f, 0.1f, Pal.INK, true), cx, nameRowY + tagLines.size * tagLH, 0, 32f, a)
        drawPreview(c, now, a)
        Txt.drawWrapped(c, h.desc, TextStyle(Fonts.ui400, 14f, 0f, Pal.INK_DIM), bayX + bayPad, descY, bayW - bayPad * 2, 20f / 14f, a)
        drawStats(c, h, a)
        c.restore()
        drawWidget(c, ctx, prev, now, base); drawWidget(c, ctx, next, now, base); drawWidget(c, ctx, hullAction, now, base)

        // refits (d2)
        k = stagger(2, now); dy = 16f * (1 - k); a = base * k
        c.save(); c.translate(0f, dy)
        Txt.drawWrapped(c, REFIT_NOTE, Styles.eyebrow, gridX, gridY, gridW, Txt.LH, a)
        val h3 = 15f * Txt.LH
        for (i in UPGRADES.indices) {
            val u = UPGRADES[i]; val lvl = Save.data.up(u.id); val maxed = lvl >= u.max
            val x = gridX + (i % cols) * (cardW + 12f); val y = cardTop[i]
            Deco.slab(c, x, y, cardW, cardH[i], 12f, Pal.ION, a)
            Txt.draw(c, u.name, TextStyle(Fonts.display600, 15f, 0.1f, Pal.INK, true), x + 14f, y + 14f, -1, h3, a)
            Txt.draw(c, "$lvl/${u.max}", TextStyle(Fonts.mono400, 12f, 0f, Pal.INK_FAINT), x + cardW - 14f, y + 14f, 1, h3, a)
            Txt.drawWrapped(c, u.desc(lvl), TextStyle(Fonts.ui400, 13f, 0f, Pal.INK_DIM), x + 14f, y + 14f + h3 + 8f, cardW - 28f, Txt.LH, a)
            val py = buy[i].r.top - 8f - 4f
            val pw = (cardW - 28f - (u.max - 1) * 4f) / u.max
            for (j in 0 until u.max) {
                val px = x + 14f + j * (pw + 4f)
                val onPip = j < lvl
                val fp = Deco.fillPaint()
                if (onPip) { val col = if (maxed) Pal.SOLAR else Pal.ION; fp.color = Theme.withA(col, a); fp.setShadowLayer(8f, 0f, 0f, Theme.withA(col, 0.8f * a)) } else fp.color = Theme.withA(Pal.LINE, 0.18f * a)
                c.drawRect(px, py, px + pw, py + 4f, fp)
                fp.clearShadowLayer()
            }
            val pk = ((now - cardPulse[i]) / 600.0).toFloat()
            if (pk in 0f..1f) Deco.fillRect(c, x, y, x + cardW, y + cardH[i], Theme.withA(Pal.WHITE, 0.25f * (1 - pk) * a))
        }
        c.restore()
        for (b in buy) drawWidget(c, ctx, b, now, base)
        ctx.alpha = base
    }

    /** Dust chip (slab solar, cut 10) with its bump animation after a purchase. */
    private fun drawDust(c: Canvas, now: Double, dy: Float, a: Float) {
        val bump = ((now - dustBumpAt) / 450.0).toFloat()
        val sc = if (bump in 0f..1f) { val e = Theme.EASE_SNAP(bump.toDouble()).toFloat(); if (e < 0.4f) 1f + 0.12f * (e / 0.4f) else 1.12f - 0.12f * ((e - 0.4f) / 0.6f) } else 1f
        c.save(); c.translate(0f, dy); c.scale(sc, sc, dustX + dustW / 2, dustY + dustH / 2)
        Deco.slab(c, dustX, dustY, dustW, dustH, 10f, Pal.SOLAR, a)
        Deco.dustGlyph(c, dustX + 14f + 7f, dustY + dustH / 2, 14f, a)
        Txt.drawGlow(c, fmtInt(Save.data.stardust), TextStyle(Fonts.mono400, dustSize, 0f, Pal.SOLAR), dustX + 14f + 14f + 10f, dustY, -1, Theme.withA(Pal.SOLAR, 0.5f), 14f, dustH, a)
        c.restore()
    }

    private fun drawCompact(c: Canvas, ctx: DrawCtx, now: Double) {
        val base = ctx.alpha
        // top bar (d0)
        var k = stagger(0, now); var dy = 16f * (1 - k); var a = base * k
        val ts = TextStyle(Fonts.display900, titleSize, 0.1f, Pal.INK, true)
        val tw = Txt.width("Hangar", ts)
        if (titleX + tw <= dustX - 8f) {
            val ty = headY + dy
            Glitch.bursts(c, t(now), false, titleX, ty, tw, titleLH) { col, gx, gy -> Txt.draw(c, "Hangar", ts, titleX + gx, ty + gy, -1, titleLH, a * 0.9f, col) }
            Txt.draw(c, "Hangar", ts, titleX, ty, -1, titleLH, a)
        }
        drawDust(c, now, dy, a)
        drawWidget(c, ctx, back, now, base); drawWidget(c, ctx, launch, now, base)

        // hull panel (d1)
        k = stagger(1, now); dy = 16f * (1 - k); a = base * k
        val h = HULLS.getValue(HULL_ORDER[hullIdx])
        c.save(); c.translate(0f, dy)
        Deco.slab(c, bayX, bayY, bayW, bayH, 14f, h.color, a)
        val cx = bayX + bayW / 2
        Txt.draw(c, tagLines.firstOrNull() ?: "", Styles.eyebrow, cx, nameRowY + 1f, 0, 15f, a)
        Txt.draw(c, h.name, TextStyle(Fonts.display900, 19f, 0.1f, Pal.INK, true), cx, nameRowY + 16f, 0, 26f, a)
        drawPreview(c, now, a)
        val ds = descStyle()
        for ((i, line) in descLines.withIndex()) Txt.draw(c, line, ds, bayX + bayPad, descY + i * descLH, -1, descLH, a)
        drawStatsGrid(c, h, a)
        c.restore()
        drawWidget(c, ctx, prev, now, base); drawWidget(c, ctx, next, now, base); drawWidget(c, ctx, hullAction, now, base)

        // refit list (d2): a fixed caption over a pane that scrolls on its own
        k = stagger(2, now); dy = 16f * (1 - k); a = base * k
        Txt.draw(c, "Refit modules", Styles.eyebrow, gridX, noteY + dy, -1, 16f, a)
        val hs = TextStyle(Fonts.mono400, 10f, 0.2f, Pal.INK_FAINT, true)
        val hint = "Cost rises each level"
        if (Txt.width("Refit modules", Styles.eyebrow) + 16f + Txt.width(hint, hs) <= gridW) Txt.draw(c, hint, hs, gridX + gridW, noteY + dy, 1, 16f, a)
        c.save()
        c.clipRect(region)
        c.translate(0f, -scroll)
        c.save(); c.translate(0f, dy)
        for (i in UPGRADES.indices) drawCardCompact(c, i, now, a)
        c.restore()
        for (i in UPGRADES.indices) if (cardVisible(i)) drawWidget(c, ctx, buy[i], now, base)
        c.restore()
        ctx.alpha = base
    }

    private fun cardVisible(i: Int): Boolean = cardTop[i] - scroll < region.bottom && cardTop[i] + cardH[i] - scroll > region.top

    private fun drawCardCompact(c: Canvas, i: Int, now: Double, a: Float) {
        if (!cardVisible(i)) return
        val u = UPGRADES[i]; val lvl = Save.data.up(u.id); val maxed = lvl >= u.max
        val x = cardX[i]; val y = cardTop[i]; val w = cardW; val h = cardH[i]
        Deco.slab(c, x, y, w, h, 10f, if (maxed) Pal.SOLAR else Pal.ION, a)
        val tx = x + cardPad; val ty = y + 10f
        Txt.draw(c, cardName[i] ?: u.name, cardNameStyle(), tx, ty, -1, 18f, a)
        Txt.draw(c, "$lvl/${u.max}", TextStyle(Fonts.mono400, 11f, 0f, Pal.INK_FAINT), tx + textW, ty, 1, 18f, a)
        val cs = cardDescStyle()
        cardDesc[i]?.let { lines -> for ((j, line) in lines.withIndex()) Txt.draw(c, line, cs, tx, ty + 22f + j * 16f, -1, 16f, a) }
        // level pips along the bottom of the text column
        val py = y + h - 10f - 4f
        val pw = (textW - (u.max - 1) * 3f) / u.max
        for (j in 0 until u.max) {
            val px = tx + j * (pw + 3f)
            val fp = Deco.fillPaint()
            if (j < lvl) { val col = if (maxed) Pal.SOLAR else Pal.ION; fp.color = Theme.withA(col, a); fp.setShadowLayer(8f, 0f, 0f, Theme.withA(col, 0.8f * a)) } else fp.color = Theme.withA(Pal.LINE, 0.18f * a)
            c.drawRect(px, py, px + pw, py + 4f, fp)
            fp.clearShadowLayer()
        }
        val pk = ((now - cardPulse[i]) / 600.0).toFloat()
        if (pk in 0f..1f) Deco.fillRect(c, x, y, x + w, y + h, Theme.withA(Pal.WHITE, 0.25f * (1 - pk) * a))
    }

    /** The four hull stats as a 2 × 2 grid: label and value over a slim bar. */
    private fun drawStatsGrid(c: Canvas, h: io.github.aloualou56.nebularequiem.core.HullDef, a: Float) {
        val st = TextStyle(Fonts.mono400, 10f, 0.14f, Pal.INK_DIM, true)
        val colW = (bayW - 2 * bayPad - 12f) / 2
        for ((i, r) in statRows(h).withIndex()) {
            val x0 = bayX + bayPad + (i % 2) * (colW + 12f); val y = statsY + (i / 2) * (statRowH + 4f)
            Txt.draw(c, r.first, st, x0, y, -1, 15f, a)
            Txt.draw(c, r.third, st, x0 + colW, y, 1, 15f, a)
            drawStatBar(c, h, x0, x0 + colW, y + 17f, 4f, r.second, a)
        }
    }

    private fun drawStatBar(c: Canvas, h: io.github.aloualou56.nebularequiem.core.HullDef, x0: Float, x1: Float, y: Float, th: Float, v: Double, a: Float) {
        Deco.fillRect(c, x0, y, x1, y + th, Theme.withA(Pal.LINE, 0.14f * a))
        val k = v.toFloat().coerceIn(0.05f, 1f)
        val fp = Deco.fillPaint()
        fp.shader = android.graphics.LinearGradient(x0, 0f, x1, 0f, Theme.withA(h.color, 0.5f), h.color, Shader.TileMode.CLAMP); fp.alpha = (255 * a).toInt()
        c.drawRect(x0, y, x0 + (x1 - x0) * k, y + th, fp)
        fp.shader = null; fp.alpha = 255
    }

    private fun statRows(h: io.github.aloualou56.nebularequiem.core.HullDef): Array<Triple<String, Double, String>> {
        fun dps(x: io.github.aloualou56.nebularequiem.core.HullDef) = x.damage * x.fireRate * (x.guns.size + 1.6 * x.spread)
        val all = HULL_ORDER.map { HULLS.getValue(it) }
        val maxHp = all.maxOf { it.hp }.toDouble(); val maxSp = all.maxOf { it.speed }; val maxDps = all.maxOf { dps(it) }; val minCd = all.minOf { it.dashCd }
        return arrayOf(
            Triple("Hull", h.hp / maxHp, h.hp.toString()), Triple("Speed", h.speed / maxSp, h.speed.roundToInt().toString()),
            Triple("Output", dps(h) / maxDps, dps(h).roundToInt().toString()), Triple("Dash", minCd / h.dashCd, "${h.dashCd}s")
        )
    }

    private fun drawWidget(c: Canvas, ctx: DrawCtx, w: Widget, now: Double, base: Float) {
        val sk = stagger(w.d, now)
        c.save(); c.translate(0f, 16f * (1 - sk)); ctx.alpha = base * sk; w.draw(c, ctx); c.restore()
    }

    private fun drawStats(c: Canvas, h: io.github.aloualou56.nebularequiem.core.HullDef, a: Float) {
        val rows = statRows(h)
        // .stat-row: uppercase 11px mono (values too) on a 16.5px line, the 6px bar centred; gap-2 between rows
        val st = TextStyle(Fonts.mono400, 11f, 0.14f, Pal.INK_DIM, true)
        val rh = 11f * Txt.LH; val by0 = (rh - 6f) / 2
        val x0 = bayX + bayPad; val x1 = bayX + bayW - bayPad
        for ((i, r) in rows.withIndex()) {
            val y = statsY + i * (rh + 8f)
            Txt.draw(c, r.first, st, x0, y, -1, rh, a)
            drawStatBar(c, h, x0 + 82f + 10f, x1 - 34f - 10f, y + by0, 6f, r.second, a)
            Txt.draw(c, r.third, st, x1, y, 1, rh, a)
        }
    }

    /** Live hull preview: the ship banks through a slow figure-eight under its own engine glow. */
    private fun drawPreview(c: Canvas, now: Double, a: Float) {
        val t = now / 1000.0
        val id = HULL_ORDER[hullIdx]; val h = HULLS.getValue(id)
        val W = prevW; val H = prevH; val x = prevX; val y = prevY
        val unlocked = Save.data.unlocked.contains(id)
        c.save()
        c.clipRect(x, y, x + W, y + H)
        val fp = Deco.fillPaint()
        fp.shader = RadialGradient(x + W / 2, y + H / 2, W * 0.6f, Theme.withA(h.color, 0.16f), 0x0005030D, Shader.TileMode.CLAMP); fp.alpha = (255 * a).toInt()
        c.drawRect(x, y, x + W, y + H, fp)
        fp.shader = null; fp.alpha = 255
        // blueprint grid (32 px cells in the original 480×360 canvas); a wide, short preview (phones)
        // scales by its height so the ship stays inside
        val s = min(W / 480f, H / 360f)
        val sp = Deco.strokePaint(); sp.color = Theme.withA(h.color, 0.08f * a); sp.strokeWidth = s   // 1 px of the 480×360 bitmap
        var gx = ((t * 20) % 32).toFloat() * s
        while (gx < W) { c.drawLine(x + gx, y, x + gx, y + H, sp); gx += 32f * s }
        var gy = 0f
        while (gy < H) { c.drawLine(x, y + gy, x + W, y + gy, sp); gy += 32f * s }
        val bank = sin(t * 1.3) * 0.8; val ang = -HALF_PI + sin(t * 0.65) * 0.25
        c.translate(x + W / 2, y + H / 2 + (sin(t * 1.3) * 6).toFloat() * s)
        c.rotate(Math.toDegrees(ang).toFloat())
        c.scale(4.2f * s, 4.2f * s)
        ui.renderer.world.drawEngines(c, h, 0.8 + 0.3 * sin(t * 5), t)
        ui.renderer.world.drawShip(c, h, bank, 0.0, (if (unlocked) 1.0 else 0.45) * a)
        c.restore()
        if (!unlocked) {
            // sized by the width (as on the 4:3 tablet preview), and kept inside a short phone preview
            val ks = W / 480f
            Txt.draw(c, "LOCKED · ${fmtInt(h.cost)} STARDUST", TextStyle(Fonts.mono700, 15f * min(1f, ks * 1.6f), 0f, Theme.withA(Pal.SOLAR, 0.9f)), x + W / 2, y + H - max(34f * ks, 22f), 0, 20f, a)
        }
    }

    override fun onKey(code: String): Boolean = when (code) {
        "Escape" -> { ui.action("back"); true }
        "Enter" -> { if (ui.focused == null || !ui.showFocus) { ui.action("launch"); true } else false }
        "ArrowLeft" -> { if (!ui.showFocus) { ui.action("hull-prev"); true } else false }
        "ArrowRight" -> { if (!ui.showFocus) { ui.action("hull-next"); true } else false }
        else -> false
    }
    override fun onBack(): Boolean { ui.action("back"); return true }

    fun cycleHull(dir: Int) { hullIdx = modI(hullIdx + dir, HULL_ORDER.size); relayout(true) }
}

/* ═══════════════════════════════════ SETTINGS ═══════════════════════════════════ */

class SettingsScreen(ui: Ui) : ScreenView(ui, Screen.SETTINGS, true) {
    private val S get() = Save.data.settings
    private val done = Button("Done", BtnKind.GHOST, "Esc", small = true).apply { d = 0; onClick = { ui.action("settings-back") } }
    private val sliders = listOf(
        Triple("Master volume", Slider(0.0, 1.0, 0.01, { S.master }, { S.master = it; Audio.applyVolumes(); Save.commit() }), 0),
        Triple("Music", Slider(0.0, 1.0, 0.01, { S.music }, { S.music = it; Audio.applyVolumes(); Save.commit() }), 1),
        Triple("Effects", Slider(0.0, 1.0, 0.01, { S.sfx }, { S.sfx = it; Audio.applyVolumes(); Save.commit() }), 2),
        Triple("Screenshake", Slider(0.0, 1.5, 0.05, { S.shake }, { S.shake = it; Save.commit() }), 3)
    )
    private val quality = Segmented(listOf("Low", "Med", "High"), { Quality.entries.indexOfFirst { it.id == S.quality } }) { i ->
        S.quality = Quality.entries[i].id; Save.commit(); Game.applyQuality(Quality.entries[i]); Audio.play(Sfx.UI_CLICK)
    }
    private val fps = Segmented(listOf("60", "120", "144", "Max"), { when (S.fpsCap) { 60 -> 0; 120 -> 1; 144 -> 2; else -> 3 } }) { i ->
        S.fpsCap = intArrayOf(60, 120, 144, 0)[i]; Save.commit(); Perf.playerChoseRate(); Env.platform.setFrameRateHint(S.fpsCap); Audio.play(Sfx.UI_CLICK)
    }
    private val toggles = listOf(
        Toggle("Chromatic aberration", { S.aberration }) { S.aberration = it },
        Toggle("Bloom", { S.bloom }) { S.bloom = it },
        Toggle("Auto-fire", { S.autofire }) { S.autofire = it },
        Toggle("Damage numbers", { S.damageNumbers }) { S.damageNumbers = it },
        Toggle("Adaptive quality", { S.adaptive }) { S.adaptive = it },
        Toggle("FPS counter", { S.showFps }) { S.showFps = it },
        Toggle("Vibration", { S.haptics }) { S.haptics = it }
    )
    private val eraseAsk = Button("Erase save", BtnKind.CRIMSON, null, small = true).apply { d = 7; onClick = { ui.action("reset-ask") } }
    private val eraseYes = Button("Confirm erase", BtnKind.CRIMSON, null, small = true).apply { d = 7; visible = false; onClick = { ui.action("reset-yes") } }
    private val eraseNo = Button("Keep", BtnKind.GHOST, null, small = true).apply { d = 7; visible = false; onClick = { ui.action("reset-no") } }
    var confirming = false
        set(v) { field = v; eraseAsk.visible = !v; eraseYes.visible = v; eraseNo.visible = v; relayout(true) }

    private var px = 0f; private var py = 0f; private var pw = 0f; private var ph = 0f; private var padX = 24f; private var padY = 24f
    private class Row(val label: String, val w: Widget?, val out: (() -> String)?, var x: Float = 0f, var y: Float = 0f, var lw: Float = 0f, var h: Float = 0f, var ow: Float = 48f, var labelAbove: Boolean = false, val d: Int = 1)
    private val rows = ArrayList<Row>()
    private var noteY = 0f; private var noteW = 0f; private var footY = 0f; private var headY = 0f; private var toggleTop = 0f

    init {
        widgets.add(done)
        for ((_, s, _) in sliders) { widgets.add(s); s.onCommit = { Audio.play(Sfx.UI_CLICK) } }
        widgets.add(quality); widgets.add(fps)
        for (t in toggles) { t.d = 6; widgets.add(t); t.onClick = { t.toggle(Env.now()); Save.commit(); Audio.play(Sfx.UI_CLICK) } }
        widgets.add(eraseAsk); widgets.add(eraseYes); widgets.add(eraseNo)
        for ((_, s, d) in sliders) s.d = d + 1
        quality.d = 5; fps.d = 5
    }

    override fun onShow() { if (confirming) confirming = false }

    private fun fpsNote(): String {
        val hz = Perf.panelHz   // the panel's own rate: a cap may have switched it to a slower mode
        val cap = S.fpsCap
        return if (cap == 0) (if (Perf.autoCap > 0) "Held at ${Perf.autoCap.roundToInt()} fps for smooth pacing while this device can't keep up with $hz Hz. It retries the full rate on its own, or tap Max." else "Matches your display: $hz fps.")
        else if (cap >= hz * 0.97) "Your display refreshes at $hz Hz, so the game runs at $hz fps. A $cap cap applies on a faster display."
        else "Capped at $cap fps on your $hz Hz display."
    }

    override fun layout() {
        val L = L
        val W = L.w
        toggles[6].visible = L.touch
        val twoColFields = L.compact && W >= 760
        pw = if (L.compact) min(920f, W - 2 * L.padX) else min(640f, W - 2 * L.padX)
        padX = if (L.compact) 22f else (W * 0.04f).coerceIn(16f, 30f)
        padY = if (L.compact) 16f else 24f
        val gap = if (L.compact) 10f else 16f
        val inner = pw - 2 * padX
        rows.clear()
        rows.add(Row("Master volume", sliders[0].second, { "${(S.master * 100).roundToInt()}%" }, d = 1))
        rows.add(Row("Music", sliders[1].second, { "${(S.music * 100).roundToInt()}%" }, d = 2))
        rows.add(Row("Effects", sliders[2].second, { "${(S.sfx * 100).roundToInt()}%" }, d = 3))
        rows.add(Row("Screenshake", sliders[3].second, { "${(S.shake * 100).roundToInt()}%" }, d = 4))
        rows.add(Row("Render quality", quality, null, d = 5))
        rows.add(Row("Frame rate", fps, { "${Perf.panelHz} Hz" }, d = 5))
        // measure height first (panel is vertically centred)
        val colGap = 28f
        val fieldW = if (twoColFields) (inner - colGap) / 2 else inner
        val segH = 11f * Txt.LH + if (L.compact) 24f else 14f   // .seg button: one 11px line + padding 12px (7px)
        val sliderH = if (L.compact) 44f else 22f
        val labelAbove = L.small
        val labelH = 13f * Txt.LH
        val fieldH = { r: Row -> val ch = if (r.w is Slider) sliderH else segH; if (labelAbove) ch + labelH else max(ch, labelH) }
        // header: eyebrow (16.5) + gap 4 + text-3xl title (30px on a 36px line), Done aligned to its bottom
        var h = padY + HEAD_H + gap
        // each field is as tall as its own control (a flex column of separate grids); paired on wide
        // phones, a grid row takes its taller field
        val nFieldRows = if (twoColFields) (rows.size + 1) / 2 else rows.size
        val rowH = FloatArray(nFieldRows)
        for ((i, r) in rows.withIndex()) { val row = if (twoColFields) i / 2 else i; rowH[row] = max(rowH[row], fieldH(r)) }
        val fieldsH = rowH.sum() + (nFieldRows - 1) * gap
        h += fieldsH + gap
        val note = fpsNote()
        h += Txt.wrappedHeight(note, TextStyle(Fonts.ui400, 14f, 0f, Pal.INK_FAINT), inner, 20f / 14f) + gap   // text-sm: 20px lines
        val tcols = max(1, floor((inner + 12f) / (240f + 12f)).toInt())
        val visToggles = toggles.filter { it.visible }
        val trows = (visToggles.size + tcols - 1) / tcols
        val trowH = if (L.compact) 44f else 22f   // the 22px switch; .toggle-row min-height 44px on phones
        h += trows * trowH + (trows - 1) * 12f + gap
        val smH = done.prefHeight(L.touch, L.compact)
        h += 9f + max(smH, 20f) + padY   // footer: 1px border-top + pt-2 above the note / buttons row
        ph = h
        px = (W - pw) / 2; py = centerOffset(ph)
        // header
        headY = py + padY
        val dw = done.prefWidth()
        done.r.set(px + pw - padX - dw, headY + HEAD_H - smH, px + pw - padX, headY + HEAD_H)
        var y = headY + HEAD_H + gap
        val rowTop = FloatArray(nFieldRows)
        for (k in 0 until nFieldRows) rowTop[k] = if (k == 0) y else rowTop[k - 1] + rowH[k - 1] + gap
        for ((i, r) in rows.withIndex()) {
            val col = if (twoColFields) i % 2 else 0
            val row = if (twoColFields) i / 2 else i
            val fh = rowH[row]
            r.x = px + padX + col * (fieldW + colGap); r.y = rowTop[row]; r.h = fh; r.labelAbove = labelAbove
            // the value column stays reserved where it is empty (Render quality's trailing <span>)
            r.ow = if (L.compact) 44f else 48f
            val ch = if (r.w is Slider) sliderH else segH
            val w = r.w!!
            if (labelAbove) {
                r.lw = fieldW
                val cw = if (w is Segmented) min(w.prefWidth(L.compact), fieldW - r.ow) else fieldW - r.ow - 12f
                w.r.set(r.x, r.y + labelH, r.x + cw, r.y + labelH + ch)
            } else if (L.compact) {
                // grid-template-columns: 1fr auto 44px — a range input's intrinsic width is 129px
                val cw = if (w is Segmented) w.prefWidth(true) else min(129f, fieldW * 0.5f)
                val lw = fieldW - cw - r.ow - 20f
                r.lw = lw
                w.r.set(r.x + lw + 10f, r.y + (fh - ch) / 2, r.x + lw + 10f + cw, r.y + (fh - ch) / 2 + ch)
            } else {
                val avail = fieldW - 48f - 24f
                val lw = avail / 2.3f
                r.lw = lw
                // a .seg grid item stretches across the 1.3fr column (outline included); its buttons stay left
                val cw = avail * 1.3f / 2.3f
                w.r.set(r.x + lw + 12f, r.y + (fh - ch) / 2, r.x + lw + 12f + cw, r.y + (fh - ch) / 2 + ch)
            }
            if (w is Segmented) w.layoutCells(L.compact)
        }
        y += fieldsH + gap
        noteY = y; noteW = inner
        y += Txt.wrappedHeight(note, TextStyle(Fonts.ui400, 14f, 0f, Pal.INK_FAINT), inner, 20f / 14f) + gap
        toggleTop = y
        val tw = (inner - (tcols - 1) * 12f) / tcols
        for ((i, t) in visToggles.withIndex()) {
            val cx = px + padX + (i % tcols) * (tw + 12f); val cy = y + (i / tcols) * (trowH + 12f)
            t.r.set(cx, cy, cx + tw, cy + trowH)
        }
        y += trows * trowH + (trows - 1) * 12f + gap
        footY = y
        val bh = smH
        var bx = px + pw - padX
        for (b in listOf(eraseNo, eraseYes, eraseAsk).filter { it.visible }) { val w = b.prefWidth(); b.r.set(bx - w, y + 9f, bx, y + 9f + bh); bx -= w + 8f }
        contentH = py + ph + L.padY
    }

    override fun drawContent(c: Canvas, ctx: DrawCtx, now: Double) {
        val base = ctx.alpha
        Deco.slab(c, px, py, pw, ph, 16f, Pal.ION, base)
        var k = stagger(0, now); var a = base * k; var dy = 16f * (1 - k)
        Txt.draw(c, "Ship systems", Styles.eyebrow, px + padX, headY + dy, -1, 11f * Txt.LH, a)
        Txt.draw(c, "Settings", TextStyle(Fonts.display900, 30f, 0.1f, Pal.INK, true), px + padX, headY + HEAD_TITLE_Y + dy, -1, 36f, a)
        val outGap = if (L.compact) 10f else 12f
        for (r in rows) {
            k = stagger(r.d, now); a = base * k; dy = 16f * (1 - k)
            val lh = if (r.labelAbove) 13f * Txt.LH else r.h
            Txt.draw(c, r.label, TextStyle(Fonts.ui400, 13f, 0.14f, Pal.INK_DIM, true), r.x, r.y + dy, -1, lh, a)
            r.out?.let { o -> Txt.draw(c, o(), TextStyle(Fonts.mono400, 12f, 0f, Pal.INK), r.w!!.r.right + outGap + r.ow, r.w.r.top + dy, 1, r.w.r.height(), a) }
        }
        k = stagger(5, now)
        Txt.drawWrapped(c, fpsNote(), TextStyle(Fonts.ui400, 14f, 0f, Pal.INK_FAINT), px + padX, noteY + 16f * (1 - k), noteW, 20f / 14f, base * k)
        k = stagger(7, now); a = base * k; dy = 16f * (1 - k)
        Deco.fillRect(c, px + padX, footY + dy, px + pw - padX, footY + dy + 1f, Theme.withA(Pal.LINE, 0.15f * a))
        val note = if (confirming) "This cannot be undone. Settings are kept." else "Wipes stardust, refits, hulls and records."
        val btns = listOf(eraseNo, eraseYes, eraseAsk).filter { it.visible }
        val noteMax = max(80f, btns.minOf { it.r.left } - px - padX - 12f)
        // items-center: the text-sm note (14px on 20px lines) is centred on the button row
        val ns = TextStyle(Fonts.ui400, 14f, 0f, Pal.INK_FAINT)
        val nh = Txt.wrappedHeight(note, ns, noteMax, 20f / 14f); val bh = btns[0].r.height()
        Txt.drawWrapped(c, note, ns, px + padX, footY + 9f + (max(bh, nh) - nh) / 2 + dy, noteMax, 20f / 14f, a)
        for (w in widgets) {
            if (!w.visible) continue
            val sk = stagger(w.d, now)
            c.save(); c.translate(0f, 16f * (1 - sk)); ctx.alpha = base * sk; w.draw(c, ctx); c.restore()
        }
        ctx.alpha = base
    }

    override fun onKey(code: String): Boolean = when (code) { "Escape" -> { ui.action("settings-back"); true }; else -> false }
    override fun onBack(): Boolean { ui.action("settings-back"); return true }
}

/* ═══════════════════════════════════ FLIGHT MANUAL ═══════════════════════════════════ */

class ManualScreen(ui: Ui) : ScreenView(ui, Screen.MANUAL, true) {
    private val done = Button("Done", BtnKind.GHOST, "Esc", small = true).apply { d = 0; onClick = { ui.action("manual-back") } }
    private class Sec(val title: String, val text: String?, val items: List<Pair<String, String>>?)
    private val secs = listOf(
        Sec("Controls", null, listOf("Move" to "WASD / Arrows", "Aim" to "Mouse", "Fire (auto-fire off)" to "Hold click / J", "Phase dash" to "Space / Shift", "Nova bomb" to "E / K", "Overdrive" to "Q / L", "Pause" to "Esc / P")),
        Sec("The hitbox", "Only the white core of your ship can be hit. The glowing hull around it is cosmetic, so thread bullets closer than looks safe.", null),
        Sec("Graze & flux", "Bullets that pass within a hull-width charge the flux meter and feed your score. A full meter unlocks Overdrive: doubled fire rate and slowed enemy time.", null),
        Sec("Synaptic grafts", "Shards from wrecks fill the experience bar. Each level offers three grafts; legendary ones are rare. Bosses always offer at least a rare graft.", null),
        Sec("Sectors", "Three waves, then a guardian, each tougher than the last, and after each guardian a new kind of hostile joins the waves. The first six guardians you break each add a sector mutator. Story: break all ten to complete the requiem. Endless: they return in random order, and every ten sectors the nebula ascends. Pick Easy, Medium, Hard or Maniac on the flight plan: harder pays more score.", null),
        Sec("Stardust", "Gold motes and your final score convert to stardust. Spend it in the Hangar on permanent refits and new hulls.", null),
        Sec("Touch & gamepad", "On touch screens, drag the left half to steer and the right half to aim. Gamepads use the left stick to move, the right stick to aim, A to dash, B for nova, X for overdrive.", null)
    )
    private var px = 0f; private var py = 0f; private var pw = 0f; private var ph = 0f; private var pad = 24f
    private var cols = 1; private var colW = 0f
    private val secY = FloatArray(7); private val secX = FloatArray(7)
    private var gridTop = 0f

    init { widgets.add(done) }

    private fun bodySize() = if (L.compact) 12.5f else 13.5f
    private fun bodyStyle() = TextStyle(Fonts.ui400, bodySize(), 0f, Pal.INK_DIM)

    /** A Controls row: the label's lines, the row height and the chip's right edge (from the column's left). */
    private class KeyRow(val lines: List<String>, val h: Float, val right: Float)
    private var keyRows: List<KeyRow> = emptyList()

    /**
     * li { display: flex; justify-content: space-between; gap: 10px }: the nowrap chip keeps its width and
     * the label wraps in what is left (never narrower than its widest unbreakable piece, else the row
     * overflows); align-items: stretch makes the chip as tall as the label.
     */
    private fun layoutKeys(items: List<Pair<String, String>>, w: Float): List<KeyRow> {
        val bs = bodyStyle(); val size = bodySize()
        return items.map { (label, key) ->
            val kw = Deco.kbdWidth(key, size)
            val minW = label.split(' ').flatMap { it.split(AFTER_HYPHEN) }.maxOf { Txt.width(it, bs) }
            val lw = max(w - kw - 10f, minW)
            val lines = Txt.wrap(label, bs, lw)
            KeyRow(lines, max(lines.size * size * Txt.LH, Deco.kbdHeight(size)), lw + 10f + kw)
        }
    }

    private fun secHeight(s: Sec, w: Float): Float {
        var h = 13f * Txt.LH + 6f
        if (s.text != null) h += Txt.wrappedHeight(s.text, bodyStyle(), w)
        s.items?.let { keyRows = layoutKeys(it, w); h += keyRows.fold(0f) { acc, r -> acc + r.h } + (it.size - 1) * 5f }
        return h
    }

    override fun layout() {
        val L = L
        val W = L.w
        pw = min(860f, W - 2 * L.padX)
        pad = if (L.compact) 22f else (W * 0.04f).coerceIn(16f, 30f)
        val padY = if (L.compact) 16f else 24f
        val inner = pw - 2 * pad
        cols = if (L.compact) 3 else max(1, floor((inner + 22f) / (220f + 22f)).toInt())
        val gapX = if (L.compact) 18f else 22f; val gapY = if (L.compact) 10f else 14f
        colW = (inner - (cols - 1) * gapX) / cols
        var h = padY + HEAD_H + (if (L.compact) 12f else 16f)
        gridTop = h
        var rowY = 0f
        var i = 0
        while (i < secs.size) {
            var rowH = 0f
            for (k in 0 until cols) {
                if (i + k >= secs.size) break
                secX[i + k] = k * (colW + gapX); secY[i + k] = rowY
                rowH = max(rowH, secHeight(secs[i + k], colW))
            }
            rowY += rowH + gapY
            i += cols
        }
        h += rowY - gapY + padY
        ph = h
        px = (W - pw) / 2; py = centerOffset(ph)
        val smH = done.prefHeight(L.touch, L.compact); val dw = done.prefWidth()
        done.r.set(px + pw - pad - dw, py + padY + HEAD_H - smH, px + pw - pad, py + padY + HEAD_H)
        contentH = py + ph + L.padY
    }

    override fun drawContent(c: Canvas, ctx: DrawCtx, now: Double) {
        val base = ctx.alpha
        Deco.slab(c, px, py, pw, ph, 16f, Pal.ION, base)
        val padY = if (L.compact) 16f else 24f
        var k = stagger(0, now); var a = base * k; var dy = 16f * (1 - k)
        Txt.draw(c, "Pilot briefing", Styles.eyebrow, px + pad, py + padY + dy, -1, 11f * Txt.LH, a)
        Txt.draw(c, "Flight manual", TextStyle(Fonts.display900, 30f, 0.1f, Pal.INK, true), px + pad, py + padY + HEAD_TITLE_Y + dy, -1, 36f, a)
        k = stagger(1, now); a = base * k; dy = 16f * (1 - k)
        val bs = bodyStyle(); val lh = bodySize() * Txt.LH
        for ((i, s) in secs.withIndex()) {
            val x = px + pad + secX[i]; var y = py + gridTop + secY[i] + dy
            // h4: no weight of its own (preflight h4 { font-weight: inherit }), so Tektur 400
            Txt.draw(c, s.title, TextStyle(Fonts.display400, 13f, 0.16f, Pal.ION, true), x, y, -1, 13f * Txt.LH, a)
            y += 13f * Txt.LH + 6f
            if (s.text != null) Txt.drawWrapped(c, s.text, bs, x, y, colW, Txt.LH, a)
            s.items?.let { items ->
                for ((j, item) in items.withIndex()) {
                    val row = keyRows.getOrNull(j) ?: break
                    for ((li, line) in row.lines.withIndex()) Txt.draw(c, line, bs, x, y + li * lh, -1, lh, a)
                    Deco.kbdBox(c, item.second, x + row.right, y, row.h, a, bodySize())
                    y += row.h + 5f
                }
            }
        }
        val sk = stagger(done.d, now)
        c.save(); c.translate(0f, 16f * (1 - sk)); ctx.alpha = base * sk; done.draw(c, ctx); c.restore()
        ctx.alpha = base
    }

    override fun onKey(code: String): Boolean = when (code) { "Escape" -> { ui.action("manual-back"); true }; else -> false }
    override fun onBack(): Boolean { ui.action("manual-back"); return true }
}

/** Settings / manual header: eyebrow line (11 px × 1.5) + 4 px gap + a text-3xl title (30 px on a 36 px line). */
internal const val HEAD_TITLE_Y = 16.5f + 4f
internal const val HEAD_H = HEAD_TITLE_Y + 36f
/** CSS break opportunity after a hyphen between letters ("(auto-|fire"), as in Txt.wrap. */
private val AFTER_HYPHEN = Regex("(?<=\\p{L}-)(?=\\p{L})")

private const val EYEBROW = "Roguelike bullet-hell · Deep-field log 0x4E52"
private const val REFIT_NOTE = "Refit modules · costs scale geometrically per level"
private val TAG_STYLE get() = TextStyle(Fonts.mono400, 13f, 0.04f, Pal.INK_DIM)
