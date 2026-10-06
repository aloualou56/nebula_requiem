package io.github.aloualou56.nebularequiem.ui

import android.graphics.Canvas
import android.graphics.RectF
import io.github.aloualou56.nebularequiem.core.Screen
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Viewport-derived layout mode, mirroring the original's media queries (in dp = CSS px). */
class LayoutInfo {
    var w = 0f; var h = 0f
    /** (orientation: landscape) and (max-height: 500px) — phones held sideways. */
    var compact = false
    /** (max-width: 900px) and (portrait or min-height: 501px) — stacked menus. */
    var narrow = false
    /** (max-width: 560px) */
    var small = false
    var portrait = false
    var touch = true
    var navL = 0f; var navR = 0f
    /** .screen padding: max(16px, safe-area) — 10px block padding on compact phones. */
    val padX: Float get() = 16f
    val padY: Float get() = if (compact) 10f else 16f
    fun update(w: Float, h: Float, touch: Boolean, navL: Float, navR: Float) {
        this.w = w; this.h = h; this.touch = touch; this.navL = navL; this.navR = navR
        portrait = h > w
        compact = !portrait && h <= 500
        narrow = w <= 900 && (portrait || h >= 501)
        small = w <= 560
    }
    fun key(): String = "${w.toInt()}x${h.toInt()}/$touch/${navL.toInt()}/${navR.toInt()}"
}

/**
 * A full-screen menu layer (the original's `<section class="screen">`): fades and scales in,
 * staggers its children up into place, scrolls when its content is taller than the viewport,
 * and owns a focus order for keyboard and gamepad navigation.
 */
abstract class ScreenView(val ui: Ui, val id: Screen, val modal: Boolean) {
    var active = false
    var shownAt = -1e9
    var hiddenAt = -1e9
    val widgets = ArrayList<Widget>()
    /** Scroll offset in dp; briefly outside [0, maxScroll] while overscrolled. */
    var scroll = 0f
    var contentH = 0f
    /** Fling velocity in dp/s (positive = scrolling further down the content). */
    var scrollVel = 0f
    /** A finger is dragging the content: no fling or spring until it lets go. */
    var dragging = false
    /** When the content last moved (for the scroll bar's fade). */
    var scrolledAt = -1e9
    private val stepOut = FloatArray(1)
    private var layoutKey = ""

    val L: LayoutInfo get() = ui.layout

    /** Seconds since the screen became active. */
    fun t(now: Double): Double = (now - shownAt) / 1000.0

    fun show(now: Double) {
        if (!active) { shownAt = now; scroll = 0f; scrollVel = 0f; dragging = false }
        active = true
        onShow()
        relayout(true)
    }

    fun hide(now: Double) { if (active) hiddenAt = now; active = false; onHide() }

    open fun onShow() {}
    open fun onHide() {}

    fun relayout(force: Boolean = false) {
        val k = L.key()
        if (!force && k == layoutKey) return
        layoutKey = k
        // content at rest stays anchored (clamped) when the layout changes; content in motion or under
        // a finger is left to the fling and spring (no snap mid-bounce, e.g. when a purchase relays out
        // the Hangar while its list is still moving)
        val settled = !dragging && scrollVel == 0f && scroll >= 0f && scroll <= maxScroll
        layout()
        if (!active || settled) clampScroll()
    }

    /** Position widgets in content coordinates and set [contentH]. */
    abstract fun layout()
    abstract fun drawContent(c: Canvas, ctx: DrawCtx, now: Double)

    /** .screen opacity .45 s and scale 1.04 → 1 over .7 s (ease-out-expo); reversed when leaving. */
    fun opacity(now: Double): Float {
        return if (active) Theme.EASE_OUT_EXPO(min(1.0, (now - shownAt) / 450.0)).toFloat()
        else 1f - Theme.EASE_OUT_EXPO(min(1.0, (now - hiddenAt) / 450.0)).toFloat()
    }
    fun scale(now: Double): Float {
        return if (active) 1.04f - 0.04f * Theme.EASE_OUT_EXPO(min(1.0, (now - shownAt) / 700.0)).toFloat()
        else 1f + 0.04f * Theme.EASE_OUT_EXPO(min(1.0, (now - hiddenAt) / 700.0)).toFloat()
    }
    fun visible(now: Double): Boolean = active || now - hiddenAt < 700

    /** `.stagger > *` rise: .7 s ease-out-expo, delay d·70 ms + 120 ms. Returns progress 0..1. */
    fun stagger(d: Int, now: Double): Float {
        if (!active) return 1f
        if (ui.reducedMotion) return 1f
        val k = ((now - shownAt - (d * 70 + 120)) / 700.0).coerceIn(0.0, 1.0)
        return Theme.EASE_OUT_EXPO(k).toFloat()
    }

    /**
     * The part of the screen that scrolls, in screen coordinates; null when the whole screen does.
     * With a region, the screen draws its own scrolled part (clipped, offset by [scroll]) and only
     * the widgets [scrolls] says move with it.
     */
    open val scrollRegion: RectF? get() = null
    /** Whether a widget moves with the scroll. */
    open fun scrolls(w: Widget): Boolean = true
    /** A screen y (dp) in the frame [w] was laid out in. */
    open fun widgetY(w: Widget, y: Float): Float = if (scrolls(w)) y + scroll else y
    /** Whether (x, y) is on a control pinned in place while the content scrolls (game over's sticky actions). */
    open fun pinnedAt(x: Float, y: Float): Boolean = false
    /** Whether a drag that starts at (x, y) scrolls this screen. */
    fun scrollsAt(x: Float, y: Float): Boolean = maxScroll > 0 && (scrollRegion?.contains(x, y) ?: true)

    val viewTop: Float get() = scrollRegion?.top ?: 0f
    val viewBottom: Float get() = scrollRegion?.bottom ?: L.h
    /** [contentH] is where the scrolling content ends (screen y at scroll 0). */
    val maxScroll: Float get() = max(0f, contentH - viewBottom)
    fun clampScroll() { scroll = scroll.coerceIn(0f, maxScroll) }

    /** Vertical offset that centres content shorter than the viewport (margin-block: auto). */
    fun centerOffset(blockH: Float): Float = max(L.padY, (L.h - blockH) / 2)

    fun focusables(): List<Widget> = widgets.filter { it.visible && it.enabled && it.focusable }
    /** What keyboard or gamepad focus lands on first (null: the primary button, else the first focusable). */
    open val defaultFocus: Widget? get() = null

    /** Screen-specific shortcut keys (the original's UI.key). Return true when handled. */
    open fun onKey(code: String): Boolean = false
    /** Android Back / Escape / gamepad B on this screen. Return true when handled. */
    open fun onBack(): Boolean = false
    /** Called every frame while active (animations needing logic, e.g. count-ups). */
    open fun tick(now: Double) {}

    /** Fling and overscroll spring-back, once per frame. */
    fun updateScroll(dt: Float, now: Double) {
        if (dragging) return
        val max = maxScroll
        if (scrollVel == 0f && scroll >= 0f && scroll <= max) return
        scroll = ScrollPhysics.step(scroll, scrollVel, max, dt, stepOut)
        scrollVel = stepOut[0]
        scrolledAt = now
    }

    /** A finger dragging the content to raw offset [raw] (resisting past the ends). */
    fun dragTo(raw: Float, now: Double) {
        scroll = ScrollPhysics.rubberBand(raw, maxScroll)
        scrolledAt = now
    }

    /** Make sure a focused widget is on screen. */
    fun reveal(w: Widget) {
        if (maxScroll <= 0 || !scrolls(w)) return
        val top = viewTop + 12; val bottom = viewBottom - 12
        if (w.r.top - scroll < top) scroll = w.r.top - top
        else if (w.r.bottom - scroll > bottom) scroll = w.r.bottom - bottom
        clampScroll()
    }
}
