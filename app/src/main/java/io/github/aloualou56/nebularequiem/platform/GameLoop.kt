package io.github.aloualou56.nebularequiem.platform

import android.content.res.Resources
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import io.github.aloualou56.nebularequiem.core.Audio
import io.github.aloualou56.nebularequiem.core.Bg
import io.github.aloualou56.nebularequiem.core.Env
import io.github.aloualou56.nebularequiem.core.Fx
import io.github.aloualou56.nebularequiem.core.Game
import io.github.aloualou56.nebularequiem.core.GameState
import io.github.aloualou56.nebularequiem.core.Input
import io.github.aloualou56.nebularequiem.core.Lattice
import io.github.aloualou56.nebularequiem.core.Light
import io.github.aloualou56.nebularequiem.core.NEBULA_PALETTES
import io.github.aloualou56.nebularequiem.core.Pal
import io.github.aloualou56.nebularequiem.core.Perf
import io.github.aloualou56.nebularequiem.core.Quality
import io.github.aloualou56.nebularequiem.core.Rng
import io.github.aloualou56.nebularequiem.core.Save
import io.github.aloualou56.nebularequiem.core.Screen
import io.github.aloualou56.nebularequiem.core.Tone
import io.github.aloualou56.nebularequiem.core.World
import io.github.aloualou56.nebularequiem.render.Fonts
import io.github.aloualou56.nebularequiem.render.Renderer
import io.github.aloualou56.nebularequiem.ui.Ui
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The game thread. Every piece of game state is owned by this one thread: input arrives through
 * [InputQueue], lifecycle requests through [post], and frames are paced by a Choreographer on this
 * thread's looper (vsync-aligned at whatever rate the panel runs: 60, 90, 120, 144 Hz…).
 *
 * Each frame: replay input → menu/gamepad navigation → Game.frame (fixed 1/120 s simulation steps
 * with interpolation) → UI animation → presentation updates → draw the world, post-processing and
 * UI into the SurfaceView's hardware canvas → per-frame input latches cleared → performance sample.
 */
class GameLoop(private val host: Host, private val res: Resources) : Choreographer.FrameCallback {

    /** What the loop needs from the activity. */
    interface Host {
        fun hasTouchscreen(): Boolean
        fun reducedMotion(): Boolean
        fun refreshRate(): Float
        fun panelRate(): Float
        /** Called once, on the game thread, before the first frame is drawn. */
        fun onBooted()
        fun onFirstFrame()
    }

    private val thread = HandlerThread("nebula-game")
    private lateinit var handler: Handler
    val input = InputQueue()

    private var choreographer: Choreographer? = null
    private var holder: SurfaceHolder? = null
    private var surface: Surface? = null
    private var surfaceW = 0; private var surfaceH = 0
    private var lastW = 0; private var lastH = 0
    private var scheduled = false
    private var resumed = false
    private var booted = false

    lateinit var renderer: Renderer; private set
    lateinit var ui: Ui; private set

    @Volatile var density = 1f
    @Volatile var navL = 0f
    @Volatile var navR = 0f

    private var last = -1.0
    private var lastFrame = 0.0
    private var firstFrame = true
    private var errors = HashMap<String, Int>()

    fun start() {
        thread.start()
        handler = Handler(thread.looper)
        handler.post {
            // Display priority where the system allows it (it is a request, not a right).
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) } catch (_: Exception) {}
            choreographer = Choreographer.getInstance()
        }
    }

    fun post(r: Runnable) { handler.post(r) }

    /** Run on the game thread and wait (bounded) for it to finish. */
    fun runBlocking(timeoutMs: Long, r: Runnable) {
        if (Thread.currentThread() === thread) { r.run(); return }
        val latch = CountDownLatch(1)
        handler.post { try { r.run() } finally { latch.countDown() } }
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
    }

    fun quit() {
        runBlocking(1000) { stopFrames(); surface = null; holder = null }
        thread.quitSafely()
    }

    /* ─────────────────────────── surface & lifecycle (posted from the UI thread) ─────────────────────────── */

    fun surfaceCreated(h: SurfaceHolder) = post { holder = h; surface = h.surface; schedule() }

    fun surfaceChanged(h: SurfaceHolder, w: Int, hgt: Int) = post {
        holder = h; surface = h.surface
        surfaceW = w; surfaceH = hgt
        if (!booted) boot() else applySize()
        schedule()
    }

    /** Blocks until the game thread has let go of the surface (it must not draw after this returns). */
    fun surfaceDestroyed() = runBlocking(1000) { surface = null; holder = null; stopFrames() }

    fun onResume() = post {
        resumed = true
        last = -1.0
        schedule()
    }

    /** Pause the run, persist the save and stop rendering (the activity then waits on the store). */
    fun onPause() = runBlocking(1000) {
        resumed = false
        stopFrames()
        if (!booted) return@runBlocking
        Input.releaseAll()
        ui.cancelAllPointers()
        if (Game.state == GameState.PLAYING) Game.pause()
        Save.flush()
    }

    fun insetsChanged() = post { if (booted) ui.resize(World.dpW.toFloat(), World.dpH.toFloat(), World.density.toFloat(), navL, navR) }

    fun densityChanged() = post { if (booted) applySize() }

    fun refreshChanged(hz: Float, panelHz: Float) = post {
        if (hz > 1f) Perf.setRefresh(hz.toDouble())
        if (panelHz > 1f) Perf.setPanel(panelHz.toDouble())
    }

    /** Returns through [result] (on the game thread) whether the game consumed Back. */
    fun back(result: (Boolean) -> Unit) = post { result(booted && ui.back()) }

    private fun schedule() {
        if (scheduled || !resumed || surface == null || !booted) return
        val c = choreographer ?: return
        scheduled = true
        c.postFrameCallback(this)
    }

    private fun stopFrames() {
        if (!scheduled) return
        choreographer?.removeFrameCallback(this)
        scheduled = false
    }

    /* ─────────────────────────── boot ─────────────────────────── */

    private fun boot() {
        if (surfaceW <= 0 || surfaceH <= 0) return
        Fonts.load(res)
        Save.load()
        val s = Save.data.settings
        World.quality = Quality.of(s.quality) ?: Quality.HIGH
        World.resize(surfaceW, surfaceH, density.toDouble())
        lastW = surfaceW; lastH = surfaceH
        Fx.configure(World.quality)
        Light.resize(World.w, World.h)
        Lattice.build(World.w, World.h)

        renderer = Renderer()
        renderer.reducedMotion = host.reducedMotion()
        Env.nebula = renderer.background
        ui = Ui(renderer)
        ui.reducedMotion = host.reducedMotion()
        Game.ui = ui
        Perf.onToast = { ui.toast(it, Tone.ION) }
        Perf.setRefresh(host.refreshRate().toDouble())
        Perf.setPanel(host.panelRate().toDouble())
        Input.onTouchModeChanged = { ui.onTouchModeChanged() }
        ui.resize(World.dpW.toFloat(), World.dpH.toFloat(), density, navL, navR)
        if (host.hasTouchscreen()) Input.setTouchMode(true)

        val palette = NEBULA_PALETTES[0]
        Bg.adopt(Rng.vis.int(1, 1_000_000), palette)
        Lattice.color = palette.lattice
        Fx.clear(false)   // a re-created activity in a live process starts from an empty field
        val motes = intArrayOf(Pal.ION, Pal.PLASMA, Pal.VIOLET, Pal.WHITE)
        for (i in 0 until World.quality.motes) Fx.mote(Rng.vis.range(0.0, World.w), Rng.vis.range(0.0, World.h), Rng.vis.pick(motes))

        Game.enterAttract()
        Game.state = GameState.TITLE
        ui.show(Screen.TITLE)
        Audio.applyVolumes()
        Audio.music(Game.musicMode())
        Env.platform.setFrameRateHint(s.fpsCap)
        if (Save.restoredFromBackup) ui.toast("Save restored from its backup copy", Tone.SOLAR)
        else if (Save.recoveredFromCorruption) ui.toast("Save data was unreadable · starting fresh", Tone.CRIMSON)
        booted = true
        host.onBooted()
    }

    private fun applySize() {
        if (surfaceW <= 0 || surfaceH <= 0) return
        val arena = World.resize(surfaceW, surfaceH, density.toDouble())
        val viewport = surfaceW != lastW || surfaceH != lastH
        lastW = surfaceW; lastH = surfaceH
        Game.onResize(arena, viewport)
        if (viewport) ui.cancelAllPointers()
        ui.resize(World.dpW.toFloat(), World.dpH.toFloat(), density, navL, navR)
    }

    /* ─────────────────────────── frame ─────────────────────────── */

    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        if (!resumed || surface == null || !booted) return
        scheduled = true
        choreographer?.postFrameCallback(this)

        val now = frameTimeNanos / 1e6
        // Optional frame-rate cap: skip vsyncs until the next capped frame is due. The schedule
        // advances in fixed increments (resyncing after a stall), so a 60 cap on a 144 Hz panel
        // averages exactly 60 fps instead of drifting. 1.5 ms of slack absorbs vsync jitter.
        val cap = Perf.capHz()
        if (cap > 0 && Perf.autoCap > 0 && Save.data.settings.fpsCap == 0) {
            // The automatic half-rate fallback renders every second refresh, locked to real vsyncs.
            if (now - lastFrame < 1.5 * (1000.0 / Perf.refreshHz)) return
            lastFrame = now
        } else if (cap > 0) {
            val interval = 1000.0 / cap
            if (now - lastFrame < interval - 1.5) return
            lastFrame += interval
            if (now - lastFrame > interval) lastFrame = now
        }

        var dt = if (last < 0) 1.0 / Perf.refreshHz else (now - last) / 1000
        last = now
        // A gap of a second or more means frames stopped being delivered: come back paused.
        if (dt >= 1 && Game.state == GameState.PLAYING) Game.pause()
        if (!(dt > 0)) dt = 0.0
        if (dt > 0.25) dt = 0.25

        val t0 = System.nanoTime()
        guard("input") { drainInput() }
        guard("update") {
            ui.frameInput()
            Game.frame(dt)
            ui.update(dt)
            renderer.prepare(dt)
        }
        guard("render") { draw(dt) }
        Input.endFrame()
        Perf.sample(dt, (System.nanoTime() - t0) / 1e6)
    }

    private fun draw(dt: Double) {
        val s = surface ?: return
        if (!s.isValid) return
        val c: Canvas = try { s.lockHardwareCanvas() } catch (e: Exception) { Log.w(TAG, "lockHardwareCanvas", e); return }
        try {
            // The menus and HUD still draw if the world fails (a fault there is logged once).
            guard("world") { renderer.drawWorld(c) }
            ui.draw(c, dt)
        } finally {
            try { s.unlockCanvasAndPost(c) } catch (e: Exception) { Log.w(TAG, "unlockCanvasAndPost", e) }
        }
        if (firstFrame) { firstFrame = false; host.onFirstFrame() }
    }

    /** Update and render are isolated: a fault in one never stops the other; repeats are logged once. */
    private inline fun guard(where: String, block: () -> Unit) {
        try { block() } catch (e: RuntimeException) {
            val key = "$where: ${e.message}"
            val n = (errors[key] ?: 0) + 1
            errors[key] = n
            if (n == 1) Log.e(TAG, "$where failed (further repeats are suppressed)", e)
        }
    }

    /* ─────────────────────────── input replay ─────────────────────────── */

    private var mousePrimary = false
    private var mouseSecondary = false

    private fun drainInput() {
        input.drain { e ->
            when (e.type) {
                InputQueue.DOWN -> ui.pointerDown(e.id, e.x, e.y, e.flag, e.t.toDouble(), pen = e.flag2)
                InputQueue.MOVE -> ui.pointerMove(e.id, e.x, e.y, e.t.toDouble())
                InputQueue.UP -> ui.pointerUp(e.id, e.x, e.y, e.t.toDouble())
                InputQueue.CANCEL -> ui.pointerCancel(e.id)
                InputQueue.CANCEL_ALL -> ui.cancelAllPointers()
                InputQueue.HOVER -> { Input.mouseMove(e.x.toDouble(), e.y.toDouble()); ui.hover(e.x, e.y) }
                InputQueue.MOUSE_LEAVE -> Input.mouseLeave()
                InputQueue.MOUSE_BUTTONS -> {
                    // The playfield is "the canvas" only while a run is live with no menu over it.
                    val onCanvas = Game.state == GameState.PLAYING
                    Input.mouseButtons(e.flag, e.flag2, onCanvas, mousePrimary, mouseSecondary)
                    mousePrimary = e.flag; mouseSecondary = e.flag2
                }
                InputQueue.WHEEL -> ui.wheel(e.y)
                InputQueue.KEY_DOWN -> {
                    val code = e.code ?: return@drain
                    Input.keyDown(code, e.flag, e.flag2)
                    // Gamepad buttons drive menus through Ui.frameInput (as the original polled them).
                    if (!e.flag2) ui.key(code, e.flag)
                }
                InputQueue.KEY_UP -> e.code?.let { Input.keyUp(it) }
                InputQueue.PAD_AXES -> Input.padAxes(e.x.toDouble(), e.y.toDouble(), e.z.toDouble(), e.w.toDouble())
                InputQueue.FOCUS_LOST -> {
                    // Focus moved elsewhere: every control was just released, so stop the run
                    // instead of leaving the ship drifting under fire.
                    Input.releaseAll(); ui.cancelAllPointers()
                    mousePrimary = false; mouseSecondary = false
                    if (Game.state == GameState.PLAYING) Game.pause()
                }
                InputQueue.PAD_LINKED -> ui.toast("Gamepad linked", Tone.ION)
            }
        }
    }

    companion object { private const val TAG = "NebulaRequiem" }
}
