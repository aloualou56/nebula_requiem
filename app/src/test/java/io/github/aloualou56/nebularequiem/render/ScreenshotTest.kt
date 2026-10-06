package io.github.aloualou56.nebularequiem.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import org.robolectric.RuntimeEnvironment
import io.github.aloualou56.nebularequiem.core.BOSS_DEFS
import io.github.aloualou56.nebularequiem.core.Bg
import io.github.aloualou56.nebularequiem.core.Boss
import io.github.aloualou56.nebularequiem.core.Cfg
import io.github.aloualou56.nebularequiem.core.DirState
import io.github.aloualou56.nebularequiem.core.Env
import io.github.aloualou56.nebularequiem.core.Fx
import io.github.aloualou56.nebularequiem.core.Game
import io.github.aloualou56.nebularequiem.core.GameState
import io.github.aloualou56.nebularequiem.core.Input
import io.github.aloualou56.nebularequiem.core.Lattice
import io.github.aloualou56.nebularequiem.core.NEBULA_PALETTES
import io.github.aloualou56.nebularequiem.core.Pal
import io.github.aloualou56.nebularequiem.core.Rng
import io.github.aloualou56.nebularequiem.core.RunCheckpoint
import io.github.aloualou56.nebularequiem.core.RunMode
import io.github.aloualou56.nebularequiem.core.Difficulty
import io.github.aloualou56.nebularequiem.core.Save
import io.github.aloualou56.nebularequiem.core.Screen
import io.github.aloualou56.nebularequiem.core.SpawnOpts
import io.github.aloualou56.nebularequiem.core.TestEnv
import io.github.aloualou56.nebularequiem.core.UPGRADES
import io.github.aloualou56.nebularequiem.core.VictoryStage
import io.github.aloualou56.nebularequiem.core.World
import io.github.aloualou56.nebularequiem.ui.Button
import io.github.aloualou56.nebularequiem.ui.Slider
import io.github.aloualou56.nebularequiem.ui.Toggle
import io.github.aloualou56.nebularequiem.ui.Ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders every screen through the real renderer and UI (software canvas, Robolectric native
 * graphics) at landscape phone, small phone, foldable and tablet sizes (the game is landscape
 * only), writes PNGs to
 * app/build/screenshots for inspection, and checks each frame actually drew something.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest {
    private val outDir = File(System.getProperty("nebula.screenshotDir") ?: "build/screenshots").apply { mkdirs() }
    private lateinit var renderer: Renderer
    private lateinit var ui: Ui
    private lateinit var bmp: Bitmap

    private fun boot(pxW: Int, pxH: Int, density: Double, touch: Boolean = true) {
        TestEnv.setup(pxW, pxH, density)
        val ctx = RuntimeEnvironment.getApplication()
        Fonts.load(ctx.resources)
        renderer = Renderer()
        Env.nebula = renderer.background
        ui = Ui(renderer)
        Game.ui = ui
        Input.onTouchModeChanged = { ui.onTouchModeChanged() }
        ui.resize(World.dpW.toFloat(), World.dpH.toFloat(), density.toFloat(), 0f, 0f)
        Input.setTouchMode(touch)
        ui.onTouchModeChanged()
        val pal = NEBULA_PALETTES[0]
        Bg.adopt(4242, pal)
        Lattice.color = pal.lattice
        val motes = intArrayOf(Pal.ION, Pal.PLASMA, Pal.VIOLET, Pal.WHITE)
        for (i in 0 until World.quality.motes) Fx.mote(Rng.vis.range(0.0, World.w), Rng.vis.range(0.0, World.h), Rng.vis.pick(motes))
        Game.enterAttract()
        Game.state = GameState.TITLE
        ui.show(Screen.TITLE)
        bmp = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
    }

    /** Advance real frames through the same per-frame pipeline as GameLoop.doFrame. */
    private fun run(seconds: Double, hz: Int = 60, each: (() -> Unit)? = null) {
        val dt = 1.0 / hz
        for (i in 0 until (seconds * hz).toInt()) {
            TestEnv.nowMs += dt * 1000
            each?.invoke()
            ui.frameInput()
            Game.frame(dt)
            ui.update(dt)
            renderer.prepare(dt)
            Input.endFrame()
        }
    }

    private fun shot(name: String) {
        val c = Canvas(bmp)
        renderer.drawWorld(c)
        ui.draw(c, 1.0 / 60)
        File(outDir, "$name.png").let { f -> FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        // Something beyond the void colour must have been drawn across the frame.
        var lit = 0
        val step = 16
        for (y in 0 until bmp.height step step) for (x in 0 until bmp.width step step) {
            val p = bmp.getPixel(x, y)
            if (((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255) > 60) lit++
        }
        assertTrue("$name drew nothing", lit > 20)
    }

    private fun god() { Game.player?.let { it.iframes = 5.0; if (it.hp < 1) it.hp = 1 } }

    /** A finger drag with real event times: [steps] moves over [ms], then a rest of [holdMs] before it lifts. */
    private fun drag(x0: Float, y0: Float, x1: Float, y1: Float, ms: Double, steps: Int = 12, holdMs: Double = 0.0, id: Int = 7,
                     mouse: Boolean = false, pen: Boolean = false) {
        var t = TestEnv.nowMs
        ui.pointerDown(id, x0, y0, mouse, t, pen)
        for (i in 1..steps) { val k = i.toFloat() / steps; t += ms / steps; ui.pointerMove(id, x0 + (x1 - x0) * k, y0 + (y1 - y0) * k, t) }
        t += holdMs
        ui.pointerUp(id, x1, y1, t)
        TestEnv.nowMs = t
    }

    private val GameView_MOUSE_ID = 1000

    private fun tap(x: Float, y: Float, id: Int = 9) {
        val t = TestEnv.nowMs
        ui.pointerDown(id, x, y, false, t); ui.pointerUp(id, x, y, t + 60.0)
        TestEnv.nowMs = t + 60.0
    }

    private fun startRun() {
        Game.startRun(null)
        ui.show(null)
        run(0.1)
    }

    @Test fun menus_phoneLandscape() {
        boot(2400, 1080, 2.75)
        run(2.5); shot("01_title_phone_landscape")
        Game.state = GameState.HANGAR; ui.show(Screen.HANGAR); run(1.2); shot("02_hangar_phone_landscape")
        ui.show(Screen.SETTINGS); run(1.0); shot("03_settings_phone_landscape")
        ui.show(Screen.MANUAL); run(1.0); shot("04_manual_phone_landscape")
    }

    /** 640 × 360 dp: the smallest landscape phones (one settings column). */
    @Test fun menus_smallPhoneLandscape() {
        boot(1280, 720, 2.0)
        run(2.5); shot("05_title_small_phone")
        Game.state = GameState.HANGAR; ui.show(Screen.HANGAR); run(1.2); shot("06_hangar_small_phone")
        ui.show(Screen.SETTINGS); run(1.0); shot("07_settings_small_phone")
        ui.show(Screen.MANUAL); run(1.0); shot("08_manual_small_phone")
    }

    /** ≈ 841 × 674 dp: a book-style foldable opened and held sideways (stacked menu layout). */
    @Test fun menus_foldableLandscape() {
        boot(2208, 1768, 2.625)
        run(2.5); shot("08b_title_foldable")
        Game.state = GameState.HANGAR; ui.show(Screen.HANGAR); run(1.2); shot("08c_hangar_foldable")
        ui.show(Screen.SETTINGS); run(1.0); shot("08d_settings_foldable")
    }

    @Test fun menus_tablet() {
        boot(2560, 1600, 2.0)
        run(2.5); shot("09_title_tablet")
        Game.state = GameState.HANGAR; ui.show(Screen.HANGAR); run(1.2); shot("10_hangar_tablet")
        ui.show(Screen.SETTINGS); run(1.0); shot("11_settings_tablet")
    }

    /** Settings rows on tablets are 22 dp apart from their padded 44 dp hit areas: a tap flips only the row it lands on. */
    @Test fun settings_tapOnARowEdgeFlipsThatRow() {
        boot(2560, 1600, 2.0)
        ui.show(Screen.SETTINGS); run(1.0)
        val v = ui.settings
        val toggles = v.widgets.filterIsInstance<Toggle>().filter { it.visible }
        val a = toggles.first()
        val b = toggles.first { it !== a && it.r.left == a.r.left && it.r.top > a.r.top }
        assertTrue("rows must be close enough for the padded hit areas to overlap", b.hit(a.r.centerX(), a.r.bottom - 1f, 4f))
        val a0 = a.get(); val b0 = b.get()
        val x = a.r.centerX(); val y = a.r.bottom - 1f - v.scroll
        ui.pointerDown(1, x, y, false); run(0.05); ui.pointerUp(1, x, y); run(0.05)
        assertEquals("tapped row", !a0, a.get())
        assertEquals("row below", b0, b.get())
        a.set(a0)
    }

    /** Scrolling on a 640 × 360 phone behaves like an Android list. */
    @Test fun scrolling_flingsRestsSpringsAndSparesSliders() {
        boot(1280, 720, 2.0)
        ui.show(Screen.SETTINGS); run(1.0)
        val v = ui.settings
        assertTrue("settings scroll on a 640 × 360 phone", v.maxScroll > 40f)
        val slider = v.widgets.filterIsInstance<Slider>().first()
        val before = slider.get()
        val sx = slider.r.left + slider.r.width() * 0.8f; val sy = slider.r.centerY() - v.scroll
        // a vertical drag that starts on a slider scrolls the page and leaves the slider alone
        drag(sx, sy, sx, sy - 120f, 200.0, holdMs = 150.0)
        assertEquals("a vertical drag doesn't move the slider", before, slider.get(), 1e-9)
        val rested = v.scroll
        assertTrue("the page scrolled with the finger", rested > 80f && rested <= v.maxScroll)
        run(0.6)
        assertEquals("no fling after the finger rested", rested, v.scroll, 0.5f)
        // a quick flick carries on after the finger lifts
        v.scroll = 0f
        drag(300f, 300f, 300f, 250f, 50.0)
        val released = v.scroll
        run(0.8)
        assertTrue("a flick keeps going (released at $released, now ${v.scroll})", v.scroll > released + 30f)
        run(3.0)
        // dragging past the top resists, then springs back
        v.scroll = 0f; v.scrollVel = 0f
        var t = TestEnv.nowMs
        ui.pointerDown(8, 300f, 100f, false, t)
        for (i in 1..10) { t += 16.0; ui.pointerMove(8, 300f, 100f + 15f * i, t) }
        assertTrue("overscrolled, by less than the finger moved (${v.scroll})", v.scroll < -10f && v.scroll > -150f)
        t += 200.0; ui.pointerUp(8, 300f, 250f, t); TestEnv.nowMs = t
        run(1.0)
        assertEquals("sprang back to the top", 0f, v.scroll, 0.01f)
        // a sideways drag moves the slider; it follows the finger
        val sy2 = slider.r.centerY() - v.scroll
        val x1 = slider.r.left + 7f + (slider.r.width() - 14f) * 0.25f
        drag(slider.r.right - 20f, sy2, x1, sy2 + 3f, 150.0)
        assertEquals("the slider followed a sideways drag", slider.min + (slider.max - slider.min) * 0.25, slider.get(), slider.step)
        assertEquals("…without scrolling", 0f, v.scroll, 0.01f)
    }

    /** The Hangar on a phone: fixed controls and hull panel; only the refit list scrolls. */
    @Test fun hangar_phoneLayoutScrollsOnlyTheRefits() {
        boot(2772, 1240, 2.8125)
        Save.data.stardust = 5000.0
        Game.state = GameState.HANGAR; ui.show(Screen.HANGAR); run(1.2)
        val v = ui.hangar
        val region = v.scrollRegion
        assertTrue("phones get the list pane", region != null)
        region!!
        assertTrue(v.maxScroll > 100f)
        shot("10b_hangar_phone")
        // a vertical drag on the hull panel scrolls nothing
        drag(region.left - 120f, 330f, region.left - 120f, 150f, 150.0, holdMs = 100.0)
        assertEquals(0f, v.scroll, 0f)
        // the refit list scrolls under the finger
        val lx = region.left + 100f
        drag(lx, 400f, lx, 120f, 280.0, holdMs = 120.0)
        run(0.3)
        assertTrue("the list scrolled (${v.scroll})", v.scroll > 150f)
        shot("10c_hangar_phone_scrolled")
        // a refit button now on screen buys that refit
        val buys = v.widgets.subList(5, 5 + UPGRADES.size)
        val i = buys.indices.first { val y = buys[it].r.centerY() - v.scroll; y > region.top + 30f && y < region.bottom - 30f }
        val u = UPGRADES[i]; val cost = u.cost(0)
        tap(buys[i].r.centerX(), buys[i].r.centerY() - v.scroll)
        assertEquals("tapped refit bought", 1, Save.data.up(u.id))
        assertEquals(5000.0 - cost, Save.data.stardust, 1e-9)
        run(0.3)
        // a tap on a moving list only stops it
        val dust = Save.data.stardust
        v.scroll = 0f; v.scrollVel = 0f
        drag(lx, 380f, lx, 330f, 40.0)
        run(0.05)
        assertTrue("flinging", v.scrollVel > 200f)
        val j = buys.indices.first { val y = buys[it].r.centerY() - v.scroll; y > region.top + 30f && y < region.bottom - 30f }
        tap(buys[j].r.centerX(), buys[j].r.centerY() - v.scroll)
        assertEquals("the stopping tap bought nothing", dust, Save.data.stardust, 1e-9)
        assertEquals("and stopped the list", 0f, v.scrollVel, 0f)
        // the fixed controls never moved: they work while the list is still flinging
        drag(lx, 380f, lx, 330f, 40.0)
        run(0.05)
        assertTrue("flinging again", v.scrollVel > 200f)
        val back = v.widgets[0]
        tap(back.r.centerX(), back.r.centerY())
        run(2.5)
        assertEquals("Back worked mid-fling", GameState.TITLE, Game.state)
    }

    /** Content at rest stays anchored through a layout change; a pointer whose UP was lost lets go. */
    @Test fun scrolling_layoutChangesAndLostPointers() {
        boot(1280, 720, 2.0)
        ui.show(Screen.SETTINGS); run(1.0)
        val v = ui.settings
        v.scroll = v.maxScroll; run(0.1)
        val before = v.maxScroll
        // mouse mode hides the touch-only rows: the page gets shorter and stays anchored at its end
        Input.setTouchMode(false); ui.onTouchModeChanged(); run(0.05)
        assertTrue("the page got shorter (${v.maxScroll} vs $before)", v.maxScroll < before)
        assertEquals("anchored at the new end", v.maxScroll, v.scroll, 0.01f)
        Input.setTouchMode(true); ui.onTouchModeChanged(); run(0.5)
        // a pointer whose UP never came doesn't stay in charge of the scroll
        v.scroll = v.maxScroll / 2; run(0.1)
        var t = TestEnv.nowMs
        ui.pointerDown(5, 300f, 200f, false, t)
        for (i in 1..5) { t += 16.0; ui.pointerMove(5, 300f, 200f + 12f * i, t) }
        assertTrue(v.dragging)
        t += 16.0; ui.pointerDown(5, 300f, 150f, false, t)   // Android reused the id: the old UP was lost
        assertTrue("the lost pointer let go of the content", !v.dragging)
        t += 60.0; ui.pointerUp(5, 300f, 150f, t); TestEnv.nowMs = t
        run(1.0)
        assertTrue("settled in range", v.scroll >= 0f && v.scroll <= v.maxScroll && !v.dragging)
        // the first pen tap after finger use lands on what was drawn, though mouse mode changes the layout
        v.scroll = v.maxScroll; run(0.1)
        val toggles = v.widgets.filterIsInstance<Toggle>()
        val target = toggles.first { it.label == "Adaptive quality" }
        val other = toggles.first { it.label == "Auto-fire" }
        val a0 = target.get(); val b0 = other.get()
        val tx = target.r.centerX(); val ty = target.r.centerY() - v.scroll
        assertTrue("on screen ($ty)", ty > 20f && ty < 340f)
        Input.setTouchMode(false)   // the pen's button state is handled just before its DOWN
        t = TestEnv.nowMs
        ui.pointerDown(GameView_MOUSE_ID, tx, ty, true, t, pen = true)
        ui.pointerUp(GameView_MOUSE_ID, tx, ty, t + 50.0); TestEnv.nowMs = t + 50.0
        assertEquals("the toggle that was under the pen flipped", !a0, target.get())
        assertEquals("not the one the new layout would put there", b0, other.get())
        run(0.1)
        assertEquals("then the page re-laid out for mouse mode, anchored at its end", v.maxScroll, v.scroll, 0.01f)
    }

    /** Two fingers, a screen change mid-drag, a pen, and catching a bouncing list. */
    @Test fun scrolling_multiTouchPenAndBounces() {
        boot(1280, 720, 2.0)
        ui.show(Screen.MANUAL); run(1.0)
        val v = ui.manual
        assertTrue(v.maxScroll > 100f)
        // two fingers: the newest drives; lifting the first neither flings nor jumps
        var t = TestEnv.nowMs
        ui.pointerDown(1, 300f, 300f, false, t)
        for (i in 1..6) { t += 16.0; ui.pointerMove(1, 300f, 300f - 10f * i, t) }
        ui.pointerDown(2, 400f, 300f, false, t)
        for (i in 1..6) { t += 16.0; ui.pointerMove(1, 300f, 240f, t); ui.pointerMove(2, 400f, 300f - 10f * i, t) }
        val mid = v.scroll
        t += 16.0; ui.pointerUp(1, 300f, 240f, t)
        assertTrue("still dragging with the other finger", v.dragging)
        assertEquals("no fling from the finger that lifted", 0f, v.scrollVel, 0f)
        t += 16.0; ui.pointerMove(2, 400f, 230f, t)
        assertEquals("the remaining finger keeps moving the content 1:1", mid + 10f, v.scroll, 0.5f)
        t += 200.0; ui.pointerUp(2, 400f, 230f, t); TestEnv.nowMs = t
        assertTrue(!v.dragging)
        run(1.0)
        // a drag whose screen is replaced mid-gesture stops acting on anything
        v.scroll = 0f
        t = TestEnv.nowMs
        ui.pointerDown(3, 300f, 300f, false, t)
        for (i in 1..5) { t += 16.0; ui.pointerMove(3, 300f, 300f - 12f * i, t) }
        ui.action("manual-back")   // the title takes over while the finger is down
        for (i in 6..10) { t += 16.0; ui.pointerMove(3, 300f, 300f - 12f * i, t) }
        t += 16.0; ui.pointerUp(3, 300f, 180f, t); TestEnv.nowMs = t
        assertEquals("the title screen didn't move", 0f, ui.title.scroll, 0f)
        assertEquals(0f, ui.title.scrollVel, 0f)
        assertTrue("the manual let go", !v.dragging)
        // a pen scrolls like a finger
        ui.show(Screen.MANUAL); run(1.0)
        drag(300f, 300f, 300f, 180f, 200.0, holdMs = 100.0, id = GameView_MOUSE_ID, mouse = true, pen = true)
        assertTrue("a pen drag scrolled (${v.scroll})", v.scroll > 80f)
        // catching a list mid-bounce takes it where it is, without a jump
        v.scroll = 0f; v.scrollVel = 0f
        t = TestEnv.nowMs
        ui.pointerDown(4, 300f, 100f, false, t)
        for (i in 1..10) { t += 16.0; ui.pointerMove(4, 300f, 100f + 15f * i, t) }
        t += 100.0; ui.pointerUp(4, 300f, 250f, t); TestEnv.nowMs = t
        run(0.05)
        val bouncing = v.scroll
        assertTrue("springing back from overscroll ($bouncing)", bouncing < -5f)
        t = TestEnv.nowMs
        ui.pointerDown(5, 300f, 200f, false, t)
        t += 8.0; ui.pointerMove(5, 300f, 200.5f, t)
        assertEquals("caught where it was", bouncing - 0.5f, v.scroll, 1.5f)
        // letting go past an end with outward speed doesn't throw it further out
        for (i in 1..6) { t += 10.0; ui.pointerMove(5, 300f, 200.5f + 25f * i, t) }
        t += 1.0; ui.pointerUp(5, 300f, 350.5f, t); TestEnv.nowMs = t
        val released = v.scroll
        var furthest = released
        for (i in 0 until 60) { run(0.02); furthest = minOf(furthest, v.scroll) }
        assertEquals("never past where it was let go", released, furthest, 0.5f)
        assertEquals(0f, v.scroll, 0.01f)
    }

    @Test fun gameplay_touchHudDraftPauseOver() {
        boot(2400, 1080, 2.75)
        Save.data.upgrades["reactor"] = 3
        startRun()
        run(6.0) { god() }
        shot("12_gameplay_touch_hud")
        // a twin-stick drag shows the stick rings
        ui.pointerDown(1, 120f, 300f, false); run(0.05)
        ui.pointerMove(1, 150f, 270f); run(0.3) { god() }
        shot("13_gameplay_touch_stick")
        ui.pointerUp(1, 150f, 270f)
        Game.pendingDrafts = 1
        run(1.4) { god() }
        shot("14_graft_draft")
        if (Game.state == GameState.DRAFT) Game.pickPerk(0)
        run(1.0) { god() }
        Game.pause(); run(0.8); shot("15_pause")
        Game.resume(); run(0.2)
        Game.run!!.score = 48250.0
        val p = Game.player!!
        p.iframes = 0.0; p.shield = 0; p.hp = 1; p.hurt()
        run(1.0); shot("16_dying")
        run(5.0); shot("17_game_over")
    }

    @Test fun gameplay_keyboardMode() {
        boot(2400, 1080, 2.75, touch = false)
        startRun()
        Input.mouseMove(600.0, 200.0)
        run(5.0) { god() }
        shot("18_gameplay_keyboard_mouse")
    }

    @Test fun gameplay_smallPhoneLandscape() {
        boot(1280, 720, 2.0)
        startRun()
        run(5.0) { god() }
        shot("19_gameplay_small_phone")
        Game.pendingDrafts = 1
        run(1.4) { god() }
        shot("19b_graft_draft_small_phone")
        if (Game.state == GameState.DRAFT) Game.pickPerk(0)
        run(1.0) { god() }
        Game.pause(); run(0.8); shot("19c_pause_small_phone")
        Game.resume(); run(0.2)
        val p = Game.player!!
        p.iframes = 0.0; p.shield = 0; p.hp = 1; p.hurt()
        run(6.0); shot("19d_game_over_small_phone")
        // the sticky actions are drawn shifted to the bottom edge: a press there survives a small finger slide
        assertEquals(GameState.OVER, Game.state)
        val over = ui.over
        assertTrue("the actions are drawn shifted to the bottom edge", over.stickyOffset() < 0f)
        // pull the summary past its top and let go: it springs back, the pinned band doesn't move
        drag(300f, 60f, 300f, 200f, 120.0, holdMs = 80.0)
        assertTrue("springing back", over.scroll < 0f)
        val hangar = over.widgets.first { (it as? Button)?.label == "Hangar" }
        val x = hangar.r.centerX(); val y = hangar.r.centerY() + over.stickyOffset() - over.scroll
        ui.pointerDown(1, x, y, false); run(0.05)
        ui.pointerMove(1, x + 3f, y + 3f); run(0.05)
        ui.pointerUp(1, x + 3f, y + 3f)
        run(2.5)
        assertEquals(GameState.HANGAR, Game.state)
    }

    @Test fun hostilesAndEffects() {
        boot(2400, 1080, 2.75)
        startRun()
        Game.enemies.clear(); Game.director.state = DirState.IDLE
        val types = listOf("mote", "gyre", "dart", "weaver", "mitosis", "seer", "sower", "aegis")
        for ((i, t) in types.withIndex()) {
            val x = World.w * (0.12 + 0.76 * (i % 4) / 3.0)
            val y = World.h * (if (i < 4) 0.22 else 0.42)
            Game.spawnEnemy(t, x, y, SpawnOpts().also { it.elite = i % 3 == 0; it.instant = true })
        }
        run(2.5) { god() }
        shot("20_all_hostiles")
        // the eight that debut after the guardians, each with its tell showing
        Game.enemies.clear(); Game.ebullets.clear()
        Game.director.sector = Cfg.CAMPAIGN_SECTORS; Game.director.recompute()
        val debuts = listOf("prism", "comet", "pulsar", "hive", "vortex", "phantom", "carom", "harbinger")
        for ((i, t) in debuts.withIndex()) {
            val x = World.w * (0.12 + 0.76 * (i % 4) / 3.0)
            val y = World.h * (if (i < 4) 0.2 else 0.45)
            Game.spawnEnemy(t, x, y, SpawnOpts().also { it.elite = i == 3; it.instant = true })
        }
        run(1.7) { god() }
        shot("20b_new_hostiles")
        Game.enemies.clear(); Game.ebullets.clear(); Game.lasers.clear()
        val p = Game.player!!
        p.flux = 1.0
        Input.buttonDown(2, true); run(1.0 / 60); Input.buttonUp(2)
        run(0.6) { god() }
        shot("21_overdrive")
        Input.buttonDown(1, true); run(1.0 / 60); Input.buttonUp(1)
        run(0.25) { god() }
        shot("22_bomb_shockwave")
    }

    @Test fun guardians() {
        for (i in BOSS_DEFS.indices) {
            boot(2400, 1080, 2.75)
            startRun()
            Game.enemies.clear(); Game.director.state = DirState.BOSS
            val b = Boss.create(i)
            Game.enemies.add(b); Game.boss = b
            ui.showBoss(b)
            val name = "23_guardian_${(i + 1).toString().padStart(2, '0')}_${b.bdef.id}"
            run(9.0) { god() }
            shot(name)
            for (ph in 1 until b.phases.size) {
                b.hp = b.maxHp * (b.phases[ph - 1].until - 0.02)
                run(6.0) { god() }
                shot("${name}_phase${ph + 1}")
            }
        }
        // the longest name on the HUD of the smallest landscape phones (640 × 360 dp)
        val longest = BOSS_DEFS.indices.maxBy { BOSS_DEFS[it].name.length }
        boot(1280, 720, 2.0)
        startRun()
        Game.enemies.clear(); Game.director.state = DirState.BOSS
        val b = Boss.create(longest)
        Game.enemies.add(b); Game.boss = b
        ui.showBoss(b)
        run(3.0) { god() }
        shot("23_guardian_${(longest + 1).toString().padStart(2, '0')}_${b.bdef.id}_small_phone")
    }

    private class Size(val name: String, val w: Int, val h: Int, val density: Double)

    /** Resume at the final guardian with a long run behind it, and break it as soon as it can be hurt. */
    private fun breakFinalGuardian() {
        Save.data.run = RunCheckpoint().apply {
            hull = "lancer"; hp = 5; sector = Cfg.CAMPAIGN_SECTORS; wave = Cfg.BOSS_EVERY; bossKills = 9
            score = 1_234_567.0; kills = 2345; grazes = 3456; maxCombo = 120; maxMult = 4.25; time = 2052.0; level = 31
            perks["prism"] = 2; perks["seeker"] = 1; perks["orbital"] = 2; perks["crit"] = 1
            perksTaken.addAll(listOf("prism", "prism", "seeker", "orbital", "orbital", "crit"))
        }
        Game.resumeCheckpoint()
        run(1.5)
        var guard = 0
        while ((Game.boss == null || Game.boss!!.invulnerable) && guard++ < 600) run(1.0 / 60) { god() }
        Game.boss!!.hurt(1e9)
        assertEquals(GameState.VICTORY, Game.state)
    }

    /** From the break to the first frame of the ending (the HUD gone, no attract-mode bullets). */
    private fun playToEnding(size: Size?) {
        var guard = 0
        while (Game.victoryStage == VictoryStage.BREAKING && guard++ < 600) run(1.0 / 60) { god() }
        assertEquals(VictoryStage.PAUSE, Game.victoryStage)
        run(0.45)
        size?.let { shot("27_ending_${it.name}_1_victory_pause") }
        run(Game.VICTORY_PAUSE + 0.05)
        assertEquals(VictoryStage.WARP, Game.victoryStage)
        size?.let { shot("27_ending_${it.name}_2_warp") }
        run(1.2)
        assertEquals(VictoryStage.ENDING, Game.victoryStage)
        assertEquals(Screen.VICTORY, ui.current)
    }

    /** Every stage of the ending at every screen size the game is laid out for, then home. */
    @Test fun ending_everyStageAtEverySize() {
        for (size in SIZES) {
            boot(size.w, size.h, size.density)
            breakFinalGuardian()
            playToEnding(size)
            val v = ui.victory
            run(1.6)
            shot("27_ending_${size.name}_3_title")
            assertTrue("the HUD is hidden", !ui.hud.on)
            assertEquals("no attract-mode bullets behind it", 0, Game.ebullets.count)
            run(9.5)
            shot("27_ending_${size.name}_4_epilogue")
            run(8.5)
            shot("27_ending_${size.name}_5_roll_call")
            run(10.0)
            assertTrue(v.atStats)
            shot("27_ending_${size.name}_6_stats")
            // the stats card fits the screen: the button is fully on screen without scrolling
            assertEquals("${size.name}: nothing to scroll", 0f, v.maxScroll, 0.5f)
            assertTrue("${size.name}: the button is on screen", v.home.r.bottom <= World.dpH && v.home.r.right <= World.dpW && v.home.r.left >= 0f)
            tap(v.home.r.centerX(), v.home.r.centerY() - v.scroll)
            run(2.5)
            assertEquals(GameState.TITLE, Game.state)
            assertEquals(Screen.TITLE, ui.current)
            assertTrue("no run left to resume", Save.data.run == null)
        }
    }

    /** A tap skips the ending to its stats card (not in its first second); the button then goes home. */
    @Test fun ending_tapsSkipToTheStatsThenGoHome() {
        boot(1280, 720, 2.0)
        breakFinalGuardian()
        playToEnding(null)
        val v = ui.victory
        run(0.4)
        tap(300f, 200f)   // too early: ignored
        run(0.2)
        assertTrue(!v.atStats && Game.state == GameState.VICTORY)
        run(3.0)
        tap(300f, 200f)
        run(1.5)
        assertTrue("skipped to the stats", v.atStats)
        shot("27_ending_small_phone_7_skipped")
        // a tap off the button does nothing more
        tap(20f, 20f)
        run(0.5)
        assertEquals(GameState.VICTORY, Game.state)
        tap(v.home.r.centerX(), v.home.r.centerY() - v.scroll)
        run(2.5)
        assertEquals(GameState.TITLE, Game.state)
    }

    /** Back, Esc, Enter and the gamepad's A and B leave the ending for the title (after its first second); other keys skip. */
    @Test fun ending_backEscEnterAndPadGoHome() {
        boot(2400, 1080, 2.75, touch = false)
        breakFinalGuardian()
        assertTrue("Back is held during the victory", ui.back())
        playToEnding(null)
        assertTrue(ui.back())
        run(0.2)
        assertEquals("Back is ignored in the first second", GameState.VICTORY, Game.state)
        run(1.0)
        assertTrue(ui.back())
        run(2.5)
        assertEquals(GameState.TITLE, Game.state)

        for (how in listOf("Escape", "Enter", "Pad_dash", "Pad_bomb")) {
            breakFinalGuardian()
            playToEnding(null)
            run(1.2)
            if (how.startsWith("Pad_")) { Input.keyDown(how, false, fromPad = true); run(1.0 / 60); Input.keyUp(how) }
            else { Input.keyDown(how, false); ui.key(how, false); Input.keyUp(how) }
            run(2.5)
            assertEquals("$how goes to the title", GameState.TITLE, Game.state)
        }

        // any other key skips to the stats
        breakFinalGuardian()
        playToEnding(null)
        run(1.2)
        Input.keyDown("KeyX", false); ui.key("KeyX", false); Input.keyUp("KeyX")
        run(1.0)
        assertTrue(ui.victory.atStats)
        assertEquals(GameState.VICTORY, Game.state)
    }

    /** The flight plan at every size: both plans fully on screen with nothing to scroll, and a tap on either flies it. */
    @Test fun plan_everySizeAndATapFliesIt() {
        for (size in SIZES) {
            for (mode in RunMode.entries) {
                boot(size.w, size.h, size.density)
                Save.data.stats.requiems = 2.0; Save.data.stats.endlessBestSector = 14.0; Save.data.stats.endlessBestWave = 3.0
                run(1.0)
                ui.action("launch")
                run(1.2)
                assertEquals(Screen.PLAN, ui.current)
                val v = ui.plan
                if (mode == RunMode.STORY) shot("28_plan_${size.name}")
                assertEquals("${size.name}: nothing to scroll", 0f, v.maxScroll, 0.5f)
                for (c in listOf(v.story, v.endless)) assertTrue("${size.name}: ${c.mode} is on screen", c.r.left >= 0f && c.r.right <= World.dpW && c.r.top >= 0f && c.r.bottom <= World.dpH)
                assertTrue("${size.name}: the difficulty row is on screen", v.level.r.left >= 0f && v.level.r.right <= World.dpW && v.level.r.bottom <= v.story.r.top)
                val card = if (mode == RunMode.STORY) v.story else v.endless
                tap(card.r.centerX(), card.r.centerY())
                run(2.0)
                assertEquals("${size.name}: a tap flies ${mode.label}", GameState.PLAYING, Game.state)
                assertEquals(mode, Game.director.mode)
            }
        }
        // a first flight: no records yet
        boot(1280, 720, 2.0)
        run(1.0); ui.action("launch"); run(1.2)
        shot("28_plan_small_phone_first_flight")
    }

    /** The difficulty row: a tap picks a level (kept for next time), D steps through them, and the run flies at it. */
    @Test fun plan_difficultyPicksAndSticks() {
        boot(1280, 720, 2.0)
        run(1.0); ui.action("launch"); run(1.2)
        val v = ui.plan
        assertEquals("Medium is the default", "medium", Save.data.difficulty)
        for ((i, level) in Difficulty.entries.withIndex()) {
            val cell = v.level.cells[i]
            tap(cell.centerX(), cell.centerY())
            run(0.3)
            assertEquals(level.id, Save.data.difficulty)
            if (level == Difficulty.MANIAC) shot("28_plan_small_phone_maniac")
        }
        ui.key("KeyD", false); run(0.2)
        assertEquals("D wraps round to Easy", "easy", Save.data.difficulty)
        ui.key("KeyD", false); ui.key("KeyD", false); run(0.2)
        assertEquals("hard", Save.data.difficulty)
        tap(v.endless.r.centerX(), v.endless.r.centerY())
        run(2.0)
        assertEquals(GameState.PLAYING, Game.state)
        assertEquals(Difficulty.HARD, Game.director.difficulty)
        // the next visit offers the same level
        Game.toMenu(GameState.TITLE); run(2.0)
        ui.action("launch"); run(1.0)
        assertEquals(Difficulty.HARD.ordinal, v.level.selected())
    }

    /** Enter, Esc, 1 / 2, Back and the gamepad on the flight plan; it remembers the plan, and Relaunch flies it again. */
    @Test fun plan_keysPadBackAndRelaunch() {
        boot(2400, 1080, 2.75, touch = false)
        run(1.0)
        fun key(code: String) { Input.keyDown(code, false); ui.key(code, false); Input.keyUp(code) }
        fun pad(code: String) { Input.keyDown(code, false, fromPad = true); run(1.0 / 60); Input.keyUp(code) }
        key("Enter"); run(0.5)
        assertEquals("Enter on the title opens the flight plan", Screen.PLAN, ui.current)
        assertTrue("story is offered first", ui.focused === ui.plan.story)
        key("Escape"); run(0.5)
        assertEquals(Screen.TITLE, ui.current)
        ui.action("launch"); run(0.5)
        assertTrue(ui.back()); run(0.5)
        assertEquals("Back returns to the title", Screen.TITLE, ui.current)
        ui.action("launch"); run(0.5)
        pad("Pad_bomb"); run(0.5)
        assertEquals("gamepad B returns", Screen.TITLE, ui.current)
        // 2 flies endless, which is then the plan offered first
        ui.action("launch"); run(0.5)
        key("Digit2"); run(2.0)
        assertEquals(GameState.PLAYING, Game.state)
        assertEquals(RunMode.ENDLESS, Game.director.mode)
        assertEquals("endless", Save.data.plan)
        Game.toMenu(GameState.TITLE); run(2.0)
        ui.action("launch"); run(0.5)
        assertTrue("endless is offered first now", ui.focused === ui.plan.endless)
        pad("Pad_dash"); run(2.0)
        assertEquals("gamepad A flies the plan in focus", RunMode.ENDLESS, Game.director.mode)
        // the game over's Relaunch flies the same plan again
        Game.abandon(); run(4.0)
        assertEquals(GameState.OVER, Game.state)
        ui.action("relaunch"); run(2.0)
        assertEquals(GameState.PLAYING, Game.state)
        assertEquals(RunMode.ENDLESS, Game.director.mode)
        // 1 flies story; and from the Hangar, Back returns to the Hangar
        Game.toMenu(GameState.TITLE); run(2.0)
        ui.action("launch"); run(0.5)
        key("Digit1"); run(2.0)
        assertEquals(RunMode.STORY, Game.director.mode)
        Game.toMenu(GameState.HANGAR); run(2.0)
        ui.action("launch"); run(0.5)
        assertEquals(Screen.PLAN, ui.current)
        assertTrue(ui.back()); run(0.5)
        assertEquals(Screen.HANGAR, ui.current)
        assertEquals(GameState.HANGAR, Game.state)
    }

    companion object {
        private val SIZES = listOf(
            Size("phone", 2400, 1080, 2.75),
            Size("small_phone", 1280, 720, 2.0),
            Size("nord3", 2772, 1240, 2.8125),
            Size("foldable", 2208, 1768, 2.625),
            Size("tablet", 2560, 1600, 2.0)
        )
    }
}
