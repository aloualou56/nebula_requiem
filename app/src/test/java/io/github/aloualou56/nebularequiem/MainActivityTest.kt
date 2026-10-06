package io.github.aloualou56.nebularequiem

import android.view.KeyEvent
import android.view.WindowManager
import io.github.aloualou56.nebularequiem.audio.AudioEngine
import io.github.aloualou56.nebularequiem.core.Env
import io.github.aloualou56.nebularequiem.core.Save
import io.github.aloualou56.nebularequiem.core.Game
import io.github.aloualou56.nebularequiem.core.GameState
import io.github.aloualou56.nebularequiem.core.RunMode
import io.github.aloualou56.nebularequiem.core.Screen
import io.github.aloualou56.nebularequiem.platform.FileSaveStore
import io.github.aloualou56.nebularequiem.platform.GameLoop
import io.github.aloualou56.nebularequiem.platform.InputQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The activity's lifecycle runs end to end without a display: create, resume, keys, pause, destroy. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MainActivityTest {
    /** Robolectric shares one sandbox per config: a test class that ran earlier may have left its fake clock behind. */
    @Before fun realClock() { Env.clock = { System.nanoTime() / 1e6 } }

    @Test fun lifecycleWiresTheNativeServices() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertTrue("audio engine installed", Env.audio is AudioEngine)
        assertTrue("save store installed", Save.store is FileSaveStore)
        assertTrue("keeps the screen on", activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        assertEquals("cutouts used", WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, activity.window.attributes.layoutInDisplayCutoutMode)
        // Landscape only: locked in the manifest, never changed at runtime.
        val info = activity.packageManager.getActivityInfo(activity.componentName, 0)
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, info.screenOrientation)
        assertTrue("no runtime orientation request other than landscape", activity.requestedOrientation in
            setOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE))
        // Keys are consumed by the game (Esc must never fall back to a second, system Back).
        assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)))
        assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE)))
        assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W)))
        controller.pause().stop()
        controller.start().resume()
        controller.pause().stop().destroy()
    }

    /**
     * With a surface size, the game thread boots, frames run the full per-frame pipeline and keys
     * launch a run. Robolectric doesn't deliver vsync to a background Choreographer, so the test
     * feeds doFrame() simulated 120 Hz vsync timestamps on the game thread.
     */
    @Test fun gameThreadBootsAndRuns() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val loop = MainActivity::class.java.getDeclaredField("loop").apply { isAccessible = true }.get(activity) as GameLoop
        val holder = TestHolder(android.view.Surface(android.graphics.SurfaceTexture(false)))
        loop.surfaceCreated(holder)
        loop.surfaceChanged(holder, 2400, 1080)
        waitFor("boot") { Game.state == GameState.TITLE }
        vsync = { n -> loop.post { loop.doFrame(n) } }
        val t0 = Game.time
        waitFor("attract frames") { Game.time > t0 + 0.5 }
        // Enter opens the flight plan, and Enter again flies the plan it offers first (story)
        loop.input.post(InputQueue.KEY_DOWN, code = "Enter")
        loop.input.post(InputQueue.KEY_UP, code = "Enter")
        waitFor("flight plan") { Game.ui.current == Screen.PLAN }
        loop.input.post(InputQueue.KEY_DOWN, code = "Enter")
        loop.input.post(InputQueue.KEY_UP, code = "Enter")
        waitFor("launch") { Game.state == GameState.PLAYING && Game.player != null }
        assertTrue(Game.director.mode == RunMode.STORY)
        // Back mid-run pauses (handled by the game, not the system).
        var consumed: Boolean? = null
        loop.back { consumed = it }
        waitFor("back handled") { consumed != null }
        assertTrue(consumed == true)
        waitFor("paused") { Game.state == GameState.PAUSED }
        controller.pause()   // flushes the save on the way out
        controller.stop().destroy()
    }

    private var vsync: ((Long) -> Unit)? = null
    private var frameNanos = 1_000_000_000L

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what (state=${Game.state})")
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            vsync?.let { frameNanos += 8_333_333L; it(frameNanos) }
            Thread.sleep(5)
        }
    }

    /** Robolectric's SurfaceView holder has no Surface; this one wraps a SurfaceTexture-backed one. */
    private class TestHolder(private val s: android.view.Surface) : android.view.SurfaceHolder {
        override fun addCallback(callback: android.view.SurfaceHolder.Callback?) {}
        override fun removeCallback(callback: android.view.SurfaceHolder.Callback?) {}
        override fun isCreating() = false
        @Deprecated("Deprecated in Java") override fun setType(type: Int) {}
        override fun setFixedSize(width: Int, height: Int) {}
        override fun setSizeFromLayout() {}
        override fun setFormat(format: Int) {}
        override fun setKeepScreenOn(screenOn: Boolean) {}
        override fun lockCanvas(): android.graphics.Canvas? = null
        override fun lockCanvas(dirty: android.graphics.Rect?): android.graphics.Canvas? = null
        override fun unlockCanvasAndPost(canvas: android.graphics.Canvas?) {}
        override fun getSurfaceFrame() = android.graphics.Rect(0, 0, 2400, 1080)
        override fun getSurface() = s
    }
}
