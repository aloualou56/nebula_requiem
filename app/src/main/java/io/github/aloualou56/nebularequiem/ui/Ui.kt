package io.github.aloualou56.nebularequiem.ui

import android.graphics.Canvas
import io.github.aloualou56.nebularequiem.core.Audio
import io.github.aloualou56.nebularequiem.core.Boss
import io.github.aloualou56.nebularequiem.core.Draft
import io.github.aloualou56.nebularequiem.core.Env
import io.github.aloualou56.nebularequiem.core.Game
import io.github.aloualou56.nebularequiem.core.GameState
import io.github.aloualou56.nebularequiem.core.HULLS
import io.github.aloualou56.nebularequiem.core.HULL_ORDER
import io.github.aloualou56.nebularequiem.core.Input
import io.github.aloualou56.nebularequiem.core.Pal
import io.github.aloualou56.nebularequiem.core.RunMode
import io.github.aloualou56.nebularequiem.core.RunSummary
import io.github.aloualou56.nebularequiem.core.Save
import io.github.aloualou56.nebularequiem.core.Screen
import io.github.aloualou56.nebularequiem.core.Sfx
import io.github.aloualou56.nebularequiem.core.Timers
import io.github.aloualou56.nebularequiem.core.Tone
import io.github.aloualou56.nebularequiem.core.UPGRADES
import io.github.aloualou56.nebularequiem.core.UiBridge
import io.github.aloualou56.nebularequiem.core.fmtInt
import io.github.aloualou56.nebularequiem.render.Renderer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * §20 UI controller: owns the screens, HUD, banner, toasts and the warp transition; implements
 * the game's [UiBridge]; and routes pointer, keyboard and gamepad input to whichever layer owns it
 * (touch controls during play, the active menu otherwise). Everything runs on the game thread.
 */
class Ui(val renderer: Renderer) : UiBridge {
    val layout = LayoutInfo()
    var reducedMotion = false
    var density = 1f

    val title = TitleScreen(this)
    val hangar = HangarScreen(this)
    val settings = SettingsScreen(this)
    val manual = ManualScreen(this)
    val draft = DraftScreen(this)
    val pause = PauseScreen(this)
    val over = OverScreen(this)
    val victory = VictoryScreen(this)
    val plan = PlanScreen(this)
    private val all = listOf(title, hangar, settings, manual, draft, pause, over, victory, plan)
    private fun view(s: Screen): ScreenView = when (s) {
        Screen.TITLE -> title; Screen.HANGAR -> hangar; Screen.SETTINGS -> settings; Screen.MANUAL -> manual
        Screen.DRAFT -> draft; Screen.PAUSE -> pause; Screen.OVER -> over; Screen.VICTORY -> victory; Screen.PLAN -> plan
    }
    /** Where the flight plan's Back returns (the title or the Hangar, whichever launched it). */
    private var planReturn = Screen.TITLE

    val hud = Hud(this)
    val transition = Transition(this)
    private val banner = Banner(this)
    private val toasts = Toasts(this)

    override var current: Screen? = null
        private set
    var focused: Widget? = null
    var showFocus = false
    private val ctx = DrawCtx()
    private var settingsReturn = Screen.TITLE
    private var pauseArmAt = 0.0
    private var draftArmAt = 0.0
    private var abandonArmed = false
    private var abandonToken = 0

    fun now() = Env.now()

    /* ─────────────────────────── layout ─────────────────────────── */

    fun resize(wDp: Float, hDp: Float, dens: Float, navL: Float, navR: Float) {
        density = dens
        Icons.density = dens
        layout.update(wDp, hDp, Input.touchMode, navL, navR)
        hud.layoutTouch()
        for (s in all) if (s.active || s.visible(now())) s.relayout()
    }

    /**
     * The input mode changed, usually because a press with another kind of pointer began (a pen's
     * button state arrives just before its DOWN; a finger's DOWN switches to touch first). Menus
     * re-lay out (touch-only rows come and go) only once no pointer is down, so that press, and its
     * release, land on the layout that was on screen.
     */
    fun onTouchModeChanged() {
        layout.update(layout.w, layout.h, Input.touchMode, layout.navL, layout.navR)
        hud.layoutTouch()
        relayoutPending = true
        if (Input.touchMode) showFocus = false
    }
    private var relayoutPending = false

    /* ─────────────────────────── UiBridge ─────────────────────────── */

    override fun toast(text: String, tone: Tone) = toasts.add(text, tone, now())
    override fun banner(title: String, sub: String, tone: Tone, dur: Double) = banner.show(title, sub, tone, dur, now())
    override fun flashHull() = hud.flashHull(now())
    override fun setWave(label: String, mutators: List<String>) = hud.setWave(label, mutators)
    override fun showBoss(boss: Boss) = hud.showBoss(boss, now())
    override fun hideBoss() = hud.hideBoss()

    override fun show(screen: Screen?) {
        val n = now()
        for (s in all) if (s.id == screen) s.show(n) else s.hide(n)
        current = screen
        focused = null
        if (screen == Screen.HANGAR) hangar.refresh()
        if (screen != null && !Input.touchMode) focusDefault()
    }

    private fun focusDefault() {
        val v = current?.let { view(it) } ?: return
        val f = v.focusables()
        focused = v.defaultFocus?.takeIf { it in f } ?: f.firstOrNull { it is Button && it.kind == BtnKind.PRIMARY } ?: f.firstOrNull { it is PerkCard } ?: f.firstOrNull()
    }

    override fun renderPause() = pause.render()

    override fun openDraft(draft: Draft, rerolls: Int, eyebrow: String) {
        val n = now()
        this.draft.set(draft, rerolls, eyebrow, n)
        show(Screen.DRAFT)
        // It opens mid-fight, under thumbs that are still mashing: ignore picks until the cards are up.
        draftArmAt = n + 450
    }

    override fun refreshDraft(draft: Draft, rerolls: Int) { this.draft.set(draft, rerolls, null, now()) }

    override fun pickAnimation(index: Int, onDone: () -> Unit) {
        draft.leave(index, now())
        Timers.after(520.0, onDone)
    }

    override fun renderGameOver(summary: RunSummary) = over.render(summary, now())
    override fun renderVictory(summary: RunSummary) = victory.render(summary, now())
    override fun transition(label: String, mid: () -> Unit) = transition.run(label, now(), mid)
    override val transitionBusy: Boolean get() = transition.busy
    override fun draftArmed(): Boolean = now() >= draftArmAt
    override fun armPauseGuard() { pauseArmAt = now() + 400 }
    private fun pauseGuard() = now() < pauseArmAt

    override fun disarmAbandon() {
        abandonArmed = false
        pause.abandon.label = "Abandon run"
    }

    /* ─────────────────────────── actions (UI.action) ─────────────────────────── */

    fun action(name: String) {
        when (name) {
            // Launch run opens the flight plan; the game over's Relaunch flies the last plan again
            "launch" -> { Audio.play(Sfx.UI_CLICK); planReturn = if (current == Screen.HANGAR) Screen.HANGAR else Screen.TITLE; show(Screen.PLAN) }
            "plan-story" -> { Audio.play(Sfx.UI_CLICK); Game.launch(RunMode.STORY) }
            "plan-endless" -> { Audio.play(Sfx.UI_CLICK); Game.launch(RunMode.ENDLESS) }
            "plan-back" -> { Audio.play(Sfx.UI_BACK); show(planReturn) }
            "relaunch" -> { Audio.play(Sfx.UI_CLICK); Game.launch(Game.lastMode, Game.lastDifficulty) }
            "hangar" -> { Audio.play(Sfx.UI_CLICK); Game.toMenu(GameState.HANGAR) }
            "title" -> { Audio.play(Sfx.UI_BACK); Game.toMenu(GameState.TITLE) }
            "settings" -> { Audio.play(Sfx.UI_CLICK); settingsReturn = Screen.TITLE; show(Screen.SETTINGS) }
            "manual" -> { Audio.play(Sfx.UI_CLICK); show(Screen.MANUAL) }
            "settings-back" -> { Audio.play(Sfx.UI_BACK); show(if (settingsReturn == Screen.PAUSE) Screen.PAUSE else Screen.TITLE) }
            "manual-back" -> { Audio.play(Sfx.UI_BACK); show(Screen.TITLE) }
            "back" -> { Audio.play(Sfx.UI_BACK); Game.toMenu(GameState.TITLE) }
            "hull-prev" -> { Audio.play(Sfx.UI_CLICK); hangar.cycleHull(-1) }
            "hull-next" -> { Audio.play(Sfx.UI_CLICK); hangar.cycleHull(1) }
            "resume" -> { if (pauseGuard()) return; Audio.play(Sfx.UI_CLICK); Game.resume() }
            "pause-settings" -> { if (pauseGuard()) return; Audio.play(Sfx.UI_CLICK); settingsReturn = Screen.PAUSE; show(Screen.SETTINGS) }
            "abandon" -> {
                if (pauseGuard()) return
                // Two taps: the first only arms it, so a stray touch can't end a run.
                if (!abandonArmed) {
                    abandonArmed = true; Audio.play(Sfx.DENY)
                    pause.abandon.label = "Tap again to abandon"
                    val token = ++abandonToken
                    Timers.after(2500.0) { if (token == abandonToken) disarmAbandon() }
                    return
                }
                disarmAbandon(); Audio.play(Sfx.UI_BACK); Game.abandon()
            }
            "reset-ask" -> { settings.confirming = true; Audio.play(Sfx.DENY) }
            "reset-no" -> { settings.confirming = false; Audio.play(Sfx.UI_BACK) }
            "reset-yes" -> {
                Save.reset(); settings.confirming = false
                toast(if (Save.available) "Save erased" else "Progress reset for this session", Tone.CRIMSON)
                Audio.play(Sfx.UI_BACK)
            }
        }
    }

    fun hullAction(el: Button) {
        val id = HULL_ORDER[hangar.hullIdx]; val h = HULLS.getValue(id); val save = Save.data
        if (save.unlocked.contains(id)) { save.selected = id; Audio.play(Sfx.UI_CLICK) }
        else if (save.stardust >= h.cost) {
            save.stardust -= h.cost; save.unlocked.add(id); save.selected = id
            Audio.play(Sfx.PURCHASE); toast("${h.name} unlocked", Tone.SOLAR); hangar.dustBumpAt = now()
        } else { deny(el, "Need ${fmtInt(h.cost - save.stardust)} more stardust"); return }
        Save.flush(); checkSave(); hangar.relayout(true)
    }

    fun buyUpgrade(id: String, el: Button) {
        val u = UPGRADES.first { it.id == id }; val save = Save.data; val lvl = save.up(id)
        if (lvl >= u.max) return
        val cost = u.cost(lvl)
        if (save.stardust < cost) { deny(el, "Need ${fmtInt(cost - save.stardust)} more stardust"); return }
        save.stardust -= cost; save.upgrades[id] = lvl + 1
        Save.flush(); checkSave()
        Audio.play(Sfx.PURCHASE)
        hangar.cardPulse[UPGRADES.indexOf(u)] = now()
        hangar.dustBumpAt = now()
        hangar.relayout(true)
    }

    private var saveWarned = false
    private fun checkSave() {
        if (!Save.available && !saveWarned) { saveWarned = true; toast("Progress can't be saved on this device right now", Tone.CRIMSON) }
    }

    private fun deny(el: Widget, msg: String) { Audio.play(Sfx.DENY); el.denyAt = now(); toast(msg, Tone.CRIMSON) }

    /* ─────────────────────────── Android Back ─────────────────────────── */

    /** Returns true when the game handled Back; false lets the app go to the background. */
    fun back(): Boolean {
        val st = Game.state
        if (transition.busy || st == GameState.DRAFT || st == GameState.DYING || st == GameState.ENDING) return true
        // a won run can't be paused: Back waits for the ending, then leaves it for the title
        if (st == GameState.VICTORY) { if (current == Screen.VICTORY) victory.onBack(); return true }
        if (st == GameState.PLAYING || st == GameState.PAUSED) { Game.togglePause(); return true }
        if (current == Screen.SETTINGS) { action("settings-back"); return true }
        if (current == Screen.MANUAL) { action("manual-back"); return true }
        if (current == Screen.PLAN) { action("plan-back"); return true }
        if (st == GameState.HANGAR || st == GameState.OVER) { action(if (st == GameState.HANGAR) "back" else "title"); return true }
        return false
    }

    /* ─────────────────────────── pointer input ─────────────────────────── */

    private enum class PKind { NONE, STICK, BUTTON, PAUSE, SCREEN, SCROLL }
    private class Ptr {
        var id = -1; var kind = PKind.NONE; var btn = -1; var widget: Widget? = null
        var x0 = 0f; var y0 = 0f; var lastY = 0f; var scroll0 = 0f; var mouse = false
        /** A pen: reported like a mouse (no touch mode), but it scrolls and stops flings like a finger. */
        var pen = false
        /** A touch slider follows the finger only once the drag turned out sideways. */
        var sliderDrag = false
        /** The screen the gesture started on: it never acts on a screen that took over since. */
        var view: ScreenView? = null
        val tracker = VelocityTracker1D()
        val touchLike: Boolean get() = !mouse || pen
    }
    private val ptrs = Array(10) { Ptr() }
    private fun ptr(id: Int): Ptr? = ptrs.firstOrNull { it.id == id }
    private fun freePtr(): Ptr? = ptrs.firstOrNull { it.id == -1 }
    /** The one pointer whose movement scrolls the content (as Android lists follow one active pointer). */
    private var dragOwner = -1

    private fun activeView(): ScreenView? = current?.let { view(it) }?.takeIf { it.active }

    private fun hitWidget(v: ScreenView, x: Float, y: Float, touch: Boolean): Widget? {
        // Touch targets are padded to 44 dp, so neighbours' hit areas can overlap (settings rows are
        // 22 dp tall): the widget whose drawn rect is nearest wins, ties to the later one.
        var best: Widget? = null; var bestD = Float.MAX_VALUE
        val region = v.scrollRegion
        for (w in v.widgets) {
            if (!w.visible) continue
            // what scrolls in a region is only there inside it
            if (region != null && v.scrolls(w) && !region.contains(x, y)) continue
            val wy = v.widgetY(w, y)
            val r = w.r
            if (if (w is PerkCard) x < r.left || x > r.right || wy < r.top || wy > r.bottom else !w.hit(x, wy, if (touch) 4f else 0f)) continue
            val d = max(0f, max(r.left - x, x - r.right)) + max(0f, max(r.top - wy, wy - r.bottom))
            if (d <= bestD) { best = w; bestD = d }
        }
        return best
    }

    fun pointerDown(id: Int, x: Float, y: Float, mouse: Boolean, t: Double = now(), pen: Boolean = false) {
        if (!mouse) Input.setTouchMode(true)
        // Android never reuses the id of a pointer that is still down: a slot with this id lost its UP
        if (ptr(id) != null) pointerCancel(id)
        val p = freePtr() ?: return
        p.id = id; p.x0 = x; p.y0 = y; p.lastY = y; p.mouse = mouse; p.pen = pen
        p.widget = null; p.btn = -1; p.sliderDrag = false; p.view = null
        p.tracker.reset(); p.tracker.add(t, y)
        if (transition.busy) { p.kind = PKind.NONE; return }
        val st = Game.state
        val v = activeView()
        if (st == GameState.PLAYING && v == null) {
            if (!mouse && Input.touchMode) {
                if (hud.hitPause(x, y, 0f)) { p.kind = PKind.PAUSE; hud.pauseDown = true; return }
                val b = hud.hitButton(x, y)
                if (b >= 0) {
                    p.kind = PKind.BUTTON; p.btn = b; hud.btnDown[b] = true
                    Input.buttonDown(b, Game.player?.canUse(b) == true)
                    return
                }
                p.kind = PKind.STICK
                Input.touchStart(id, x.toDouble(), y.toDouble(), true)
                return
            }
            p.kind = PKind.NONE
            return
        }
        if (v == null) { p.kind = PKind.NONE; return }
        p.view = v
        p.kind = PKind.SCREEN
        // A touch on moving content only stops it, as in any Android list: it presses nothing there.
        // Controls outside a scroll region (the Hangar's top bar and hull panel) never moved, so they
        // press as usual and leave the list moving.
        val onContent = (v.scrollRegion?.contains(x, y) ?: true) && !v.pinnedAt(x, y)
        if (onContent) {
            val moving = v.scrollVel != 0f || v.scroll < 0f || v.scroll > v.maxScroll
            v.scrollVel = 0f
            if (moving && p.touchLike) { startScroll(p, v, y); return }
        }
        val w = hitWidget(v, x, y, p.touchLike)
        if (w != null && w.enabled) {
            p.widget = w; w.pressed = true; w.downAt = t
            // a mouse drags a slider at once; a finger or pen first has to show it isn't scrolling
            if (w is Slider && !p.touchLike) { p.sliderDrag = true; w.drag(x, v.widgetY(w, y)) }
        }
    }

    /** [p] takes over scrolling [v] from where its content is now (no jump, even mid-bounce). */
    private fun startScroll(p: Ptr, v: ScreenView, y: Float) {
        p.kind = PKind.SCROLL
        p.widget?.pressed = false; p.widget = null
        p.y0 = y; p.scroll0 = ScrollPhysics.unRubberBand(v.scroll, v.maxScroll)
        v.dragging = true; v.scrollVel = 0f
        dragOwner = p.id
    }

    /** [p]'s screen is no longer the active one: let go of it without acting on anything. */
    private fun abandon(p: Ptr) {
        if (p.kind == PKind.SCROLL && dragOwner == p.id) { p.view?.dragging = false; dragOwner = -1 }
        p.widget?.pressed = false; p.widget = null
        p.kind = PKind.NONE
    }

    fun pointerMove(id: Int, x: Float, y: Float, t: Double = now()) {
        val p = ptr(id) ?: return
        when (p.kind) {
            PKind.STICK -> Input.touchMove(id, x.toDouble(), y.toDouble(), Game.state == GameState.PLAYING)
            PKind.BUTTON -> {
                // releasing pointer capture lets the button see the thumb slide off (pointerleave)
                val b = p.btn
                if (b >= 0 && hypot(x - hud.btnX[b], y - hud.btnY[b]) > hud.btnR[b] + 12f) { hud.btnDown[b] = false; Input.buttonUp(b); p.kind = PKind.NONE }
            }
            PKind.SCREEN, PKind.SCROLL -> {
                val v = p.view
                if (v == null || v !== activeView()) { abandon(p); return }
                p.tracker.add(t, y); p.lastY = y
                if (p.kind == PKind.SCREEN) {
                    val w = p.widget
                    val dx = x - p.x0; val dy = y - p.y0
                    if (w is Slider && !p.sliderDrag && abs(dx) > ScrollPhysics.SLOP && abs(dx) >= abs(dy)) p.sliderDrag = true
                    if (p.sliderDrag && w is Slider) { w.drag(x, v.widgetY(w, y)); return }
                    if (p.touchLike && abs(dy) > ScrollPhysics.SLOP && abs(dy) > abs(dx) && v.scrollsAt(p.x0, p.y0)) {
                        // a vertical drag scrolls, starting from here (no jump by the slop distance)
                        startScroll(p, v, y)
                    } else if (w != null && p.touchLike && !w.hit(x, v.widgetY(w, y), 12f)) {
                        // a finger that slides off a button doesn't click it
                        w.pressed = false; p.widget = null
                    }
                }
                if (p.kind == PKind.SCROLL && p.id == dragOwner) v.dragTo(p.scroll0 - (y - p.y0), now())
            }
            else -> {}
        }
    }

    fun pointerUp(id: Int, x: Float, y: Float, t: Double = now()) {
        val p = ptr(id) ?: run { Input.touchEnd(id); return }
        when (p.kind) {
            PKind.STICK -> Input.touchEnd(id)
            PKind.BUTTON -> { if (p.btn >= 0) { hud.btnDown[p.btn] = false; Input.buttonUp(p.btn) } }
            PKind.PAUSE -> {
                // Pause acts on release, and only if the finger is still on it.
                if (hud.pauseDown && hud.hitPause(x, y)) Input.pauseTapped()
                hud.pauseDown = false
            }
            PKind.SCREEN -> {
                val v = p.view?.takeIf { it === activeView() }
                val w = p.widget
                w?.pressed = false
                if (v != null && w != null && w.enabled) {
                    val wy = v.widgetY(w, y)
                    if (w is Slider) {
                        // a tap sets the slider where it landed; a drag already moved it
                        if (!p.sliderDrag) w.drag(x, wy)
                        Audio.play(Sfx.UI_CLICK)
                    } else {
                        val inside = if (w is PerkCard) (x >= w.r.left - 12 && x <= w.r.right + 12 && wy >= w.r.top - 12 && wy <= w.r.bottom + 12)
                            else w.hit(x, wy, 12f)
                        if (inside) activate(v, w, x, wy, fromPointer = true)
                    }
                }
            }
            PKind.SCROLL -> endScroll(p, t, fling = true)
            PKind.NONE -> Input.touchEnd(id)
        }
        p.id = -1; p.kind = PKind.NONE; p.widget = null; p.view = null
    }

    /**
     * A scrolling pointer lifts (or is cancelled). Another finger still scrolling the same screen takes
     * over where the content is; otherwise the content is let go, flinging at the finger's speed.
     */
    private fun endScroll(p: Ptr, t: Double, fling: Boolean) {
        val v = p.view ?: return
        if (p.id != dragOwner) return   // not the finger driving the scroll: nothing changes
        val q = ptrs.firstOrNull { it !== p && it.id != -1 && it.kind == PKind.SCROLL && it.view === v }
        if (q != null) {
            dragOwner = q.id
            q.y0 = q.lastY; q.scroll0 = ScrollPhysics.unRubberBand(v.scroll, v.maxScroll)
            return
        }
        dragOwner = -1
        v.dragging = false
        if (!fling || v !== activeView()) { v.scrollVel = 0f; return }
        // the velocity of the moves; the lift repeats the last position and adds nothing, so a
        // finger that rested before lifting measures as still
        var vel = -p.tracker.velocity(t)
        // let go past an end: don't throw it further out (the spring brings it back)
        if ((v.scroll < 0f && vel < 0f) || (v.scroll > v.maxScroll && vel > 0f)) vel = 0f
        v.scrollVel = if (abs(vel) >= ScrollPhysics.MIN_FLING) vel.coerceIn(-ScrollPhysics.MAX_FLING, ScrollPhysics.MAX_FLING) else 0f
    }

    fun pointerCancel(id: Int) {
        val p = ptr(id)
        if (p != null) {
            if (p.kind == PKind.BUTTON && p.btn >= 0) { hud.btnDown[p.btn] = false; Input.buttonUp(p.btn) }
            if (p.kind == PKind.PAUSE) hud.pauseDown = false
            if (p.kind == PKind.SCROLL) endScroll(p, now(), fling = false)
            p.widget?.pressed = false
            p.id = -1; p.kind = PKind.NONE; p.widget = null; p.view = null
        }
        Input.touchEnd(id)
    }

    /** Lift every pointer (rotation, focus loss, pause). */
    fun cancelAllPointers() {
        for (p in ptrs) if (p.id != -1) pointerCancel(p.id)
        for (s in all) s.dragging = false
        dragOwner = -1
        for (i in 0 until 3) hud.btnDown[i] = false
        hud.pauseDown = false
    }

    private var hovered: Widget? = null
    /** Mouse hover (no buttons). */
    fun hover(x: Float, y: Float) {
        val v = activeView()
        val w = if (v != null && !transition.busy) hitWidget(v, x, y, false) else null
        if (w !== hovered) {
            hovered?.hovered = false
            hovered = w
            if (w != null) { w.hovered = true; w.hoverAt = now(); if (w is Button || w is PerkCard) Audio.play(Sfx.UI_HOVER) }
        }
    }

    fun wheel(dy: Float) {
        val v = activeView() ?: return
        if (v.maxScroll <= 0) return
        v.scrollVel = 0f
        v.scroll = (v.scroll + dy).coerceIn(0f, v.maxScroll)
        v.scrolledAt = now()
    }

    private fun activate(v: ScreenView, w: Widget, x: Float, wy: Float, fromPointer: Boolean) {
        when (w) {
            is Segmented -> {
                val i = if (fromPointer) w.cellAt(x, wy) else w.focusIndex
                if (i >= 0) w.choose(i)
            }
            is Slider -> {}
            else -> w.onClick?.invoke()
        }
        if (v.active) v.relayout()
    }

    /* ─────────────────────────── keyboard & gamepad ─────────────────────────── */

    private val pauseKeys = setOf("Escape", "KeyP")

    /** A key went down (from a keyboard or a gamepad button mapped to a code). Returns true if consumed. */
    fun key(code: String, repeat: Boolean): Boolean {
        if (transition.busy) return true
        val st = Game.state
        if (st == GameState.PLAYING || st == GameState.PAUSED || st == GameState.DYING) {
            if (code in pauseKeys && !repeat) {
                if (st == GameState.PAUSED && current == Screen.SETTINGS) action("settings-back") else Game.togglePause()
                return true
            }
            if (st != GameState.PAUSED) return false
        }
        val v = activeView() ?: return false
        if (!repeat && v.onKey(code)) return true
        return navKey(v, code)
    }

    private fun navKey(v: ScreenView, code: String): Boolean {
        val f = focused
        when (code) {
            "ArrowUp", "Pad_up" -> moveFocus(v, 0, -1)
            "ArrowDown", "Pad_down" -> moveFocus(v, 0, 1)
            "ArrowLeft", "Pad_left" -> { if (showFocus && f != null && (f is Slider || f is Segmented)) f.keyAdjust(-1) else moveFocus(v, -1, 0) }
            "ArrowRight", "Pad_right" -> { if (showFocus && f != null && (f is Slider || f is Segmented)) f.keyAdjust(1) else moveFocus(v, 1, 0) }
            "Tab" -> moveFocus(v, 0, 1, linear = true)
            "Enter", "NumpadEnter", "Space", "Pad_dash" -> {
                val target = if (showFocus) f else null
                if (target != null) { activate(v, target, target.r.centerX(), target.r.centerY(), false); }
                else { showFocus = true; if (focused == null) focusDefault() }
            }
            "Escape", "Pad_bomb" -> { if (!v.onBack()) return false }
            else -> return false
        }
        return true
    }

    /** Spatial focus navigation over the active screen's focusables. */
    private fun moveFocus(v: ScreenView, dx: Int, dy: Int, linear: Boolean = false) {
        val list = v.focusables()
        if (list.isEmpty()) return
        val f = focused
        if (!showFocus || f == null || f !in list) { showFocus = true; if (f == null || f !in list) focusDefault(); focused?.let { v.reveal(it) }; return }
        if (linear) { focused = list[(list.indexOf(f) + 1) % list.size]; v.reveal(focused!!); return }
        // compare where widgets are on screen: in a scroll region, fixed and scrolled widgets mix
        fun sy(w: Widget) = w.r.centerY() - if (v.scrolls(w)) v.scroll else 0f
        val cx = f.r.centerX(); val cy = sy(f)
        var best: Widget? = null; var bd = Float.MAX_VALUE
        for (w in list) {
            if (w === f) continue
            val ox = w.r.centerX() - cx; val oy = sy(w) - cy
            val along = ox * dx + oy * dy
            if (along <= 4f) continue
            val across = abs(ox * dy) + abs(oy * dx)
            val d = along + across * 2.2f
            if (d < bd) { bd = d; best = w }
        }
        if (best == null && dy != 0) {
            // wrap vertically through the linear order
            val i = list.indexOf(f)
            best = list[(i + if (dy > 0) 1 else list.size - 1) % list.size]
        }
        if (best != null) { focused = best; best.hoverAt = now(); v.reveal(best); Audio.play(Sfx.UI_HOVER) }
    }

    private var navRepeatAt = 0.0
    private var navHeldDir = 0

    /** Per-frame gamepad menu navigation (D-pad and left stick) and pad shortcuts. */
    fun frameInput() {
        val st = Game.state
        val v = activeView() ?: return
        if (transition.busy) return
        if (st == GameState.PLAYING) return
        val pressed = Input.pressed
        if (st == GameState.VICTORY) {
            // the ending reads the pad itself: A or B leave, anything else skips ahead
            var any = false
            for (code in PAD_ALL) if (pressed.contains(code)) { v.onKey(code); any = true }
            if (any) Input.setTouchMode(false)
            return
        }
        var used = false
        for (code in PAD_NAV) if (pressed.contains(code)) { navKey(v, code); used = true }
        if (pressed.contains("Pad_dash")) {
            if (current == Screen.PLAN && (!showFocus || focused == null)) action(if (plan.offered === plan.endless) "plan-endless" else "plan-story")
            else if (st == GameState.TITLE && (!showFocus || focused == null)) action("launch")
            else if (st == GameState.OVER && (!showFocus || focused == null)) action("relaunch")
            else if (st == GameState.DRAFT && (!showFocus || focused == null)) Game.pickPerk(0)
            else navKey(v, "Pad_dash")
            used = true
        }
        if (pressed.contains("Pad_bomb")) { navKey(v, "Pad_bomb"); used = true }
        // left stick as a D-pad with key-repeat
        val lx = Input.padLx; val ly = Input.padLy
        val dir = if (abs(ly) > 0.6 && abs(ly) >= abs(lx)) (if (ly > 0) 2 else 1) else if (abs(lx) > 0.6) (if (lx > 0) 4 else 3) else 0
        val n = now()
        if (dir != 0 && (dir != navHeldDir || n >= navRepeatAt)) {
            navKey(v, when (dir) { 1 -> "Pad_up"; 2 -> "Pad_down"; 3 -> "Pad_left"; else -> "Pad_right" })
            navRepeatAt = n + if (dir != navHeldDir) 320.0 else 130.0
        }
        navHeldDir = dir
        if (used) Input.setTouchMode(false)
    }

    /* ─────────────────────────── drawing ─────────────────────────── */

    fun update(dt: Double) {
        val n = now()
        if (relayoutPending && ptrs.none { it.id != -1 }) { relayoutPending = false; for (s in all) if (s.active) s.relayout() }
        transition.update(n)
        hud.show(Game.hudShown, n)
        for (s in all) if (s.active) { s.tick(n); s.updateScroll(dt.toFloat(), n) }
    }

    /** Draw every UI layer (canvas in surface pixels). */
    fun draw(c: Canvas, dt: Double) {
        val n = now()
        c.save()
        c.scale(density, density)
        ctx.now = n; ctx.touchMode = Input.touchMode; ctx.compact = layout.compact
        ctx.focused = focused; ctx.showFocus = showFocus && !Input.touchMode
        hud.draw(c, n, dt)
        hud.drawTouch(c, n)
        banner.draw(c, n)
        for (s in all) {
            if (!s.visible(n)) continue
            val op = s.opacity(n)
            if (op <= 0.003f) continue
            if (s.modal) Deco.modalBackdrop(c, layout.w, layout.h, op)
            val sc = if (reducedMotion) 1f else s.scale(n)
            c.save()
            c.scale(sc, sc, layout.w / 2, layout.h / 2)
            ctx.alpha = op
            c.save()
            // a screen with a scroll region offsets its own scrolling part
            if (s.scrollRegion == null) c.translate(0f, -s.scroll)
            s.drawContent(c, ctx, n)
            c.restore()
            drawScrollbar(c, s, n, op)
            c.restore()
        }
        toasts.draw(c, n, hud.tcBottom)
        transition.draw(c, n)
        c.restore()
    }

    /** A thin thumb at the scrolling area's right edge while the content moves, fading when it stops. */
    private fun drawScrollbar(c: Canvas, s: ScreenView, n: Double, op: Float) {
        val max = s.maxScroll
        if (max <= 0f) return
        val idle = n - s.scrolledAt
        val k = if (s.dragging || idle < 700) 1f else (1f - ((idle - 700) / 300.0).toFloat()).coerceIn(0f, 1f)
        if (k <= 0f) return
        val top = s.viewTop + 6f; val bottom = s.viewBottom - 6f
        val track = bottom - top
        val view = s.viewBottom - s.viewTop
        val len = max(28f, track * view / (view + max))
        val f = (s.scroll / max).coerceIn(0f, 1f)
        val y0 = top + (track - len) * f
        val x = (s.scrollRegion?.right ?: layout.w) - 5f
        Deco.fillRect(c, x - 3f, y0, x, y0 + len, Theme.withA(Pal.ION, 0.55f * k * op))
    }

    companion object {
        private val PAD_NAV = arrayOf("Pad_up", "Pad_down", "Pad_left", "Pad_right")
        private val PAD_ALL = arrayOf("Pad_dash", "Pad_bomb", "Pad_overdrive", "Pad_pause", "Pad_fire", "Pad_up", "Pad_down", "Pad_left", "Pad_right")
    }
}
