package io.github.aloualou56.nebularequiem

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.aloualou56.nebularequiem.audio.AudioEngine
import io.github.aloualou56.nebularequiem.core.Env
import io.github.aloualou56.nebularequiem.core.Haptic
import io.github.aloualou56.nebularequiem.core.HapticSink
import io.github.aloualou56.nebularequiem.core.PlatformSink
import io.github.aloualou56.nebularequiem.core.Save
import io.github.aloualou56.nebularequiem.platform.CrashReport
import io.github.aloualou56.nebularequiem.platform.FileSaveStore
import io.github.aloualou56.nebularequiem.platform.GameLoop
import io.github.aloualou56.nebularequiem.platform.GameView
import io.github.aloualou56.nebularequiem.platform.InputQueue
import io.github.aloualou56.nebularequiem.platform.KeyMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The single activity. It owns the window (immersive full screen, cutouts, keep-screen-on, the
 * fastest refresh rate the panel offers), the lifecycle (pause + save on the way out, resume
 * audio on the way back), system Back (routed to the game first), haptics and keyboard/gamepad
 * keys. The game runs in landscape only (locked in the manifest). The game itself runs on [GameLoop]'s thread, drawing into [GameView].
 */
class MainActivity : Activity(), GameLoop.Host, PlatformSink, HapticSink {
    private lateinit var root: FrameLayout
    private lateinit var view: GameView
    private lateinit var loop: GameLoop
    private lateinit var store: FileSaveStore
    private var audio: AudioEngine? = null
    private val main = Handler(Looper.getMainLooper())

    private val createdAt = SystemClock.uptimeMillis()
    @Volatile private var firstFrame = false
    @Volatile private var refreshHz = 60f
    @Volatile private var panelHz = 60f
    @Volatile private var fpsCap = 0
    /** True while a run is being played: only then are the side edges kept from the Back gesture. */
    private var playing = false
    private var playingSent = false
    private var vibrator: Vibrator? = null
    private val padsSeen = HashSet<Int>()

    private val backCallback = OnBackInvokedCallback { onBack() }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val d = currentDisplay() ?: return
            if (d.displayId == displayId) { preferRefreshRate(); reportRefresh() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReport.install(this)
        super.onCreate(savedInstanceState)
        val window = window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        drawEdgeToEdge()

        store = FileSaveStore(this)
        Save.store = store
        audio = AudioEngine(this).also { Env.audio = it }
        Env.haptics = this
        Env.platform = this

        loop = GameLoop(this, resources)
        loop.density = resources.displayMetrics.density
        loop.start()
        // Only once the game thread is running: the refresh rate is reported to it.
        preferRefreshRate()
        reportRefresh()

        root = FrameLayout(this)
        root.setBackgroundColor(VOID)
        view = GameView(this, loop)
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Draw edge to edge, but keep the game clear of camera cutouts and of any bars the system
        // won't hide (freeform and desktop windows). Hidden or swipe-revealed bars report 0.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            // A 3-button nav bar sits on a side edge in landscape and slides in over the game on an
            // edge swipe; the HUD keeps its controls clear of it.
            val nav = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
            val d = resources.displayMetrics.density
            loop.navL = max(0, nav.left - safe.left) / d
            loop.navR = max(0, nav.right - safe.right) / d
            loop.insetsChanged()
            WindowInsets.CONSUMED
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGestureExclusion() }
        setContentView(root)
        hideSystemBars()
        view.requestFocus()

        // Hold the system splash screen until the first frame is up (at most 2.5 s) rather than
        // handing over to an empty black window.
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!firstFrame && SystemClock.uptimeMillis() - createdAt < 2500) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
        CrashReport.takePending(this)?.let { showCrashReport(it) }
    }

    /** The previous run crashed: show what happened, with a way to copy it for a bug report. */
    private fun showCrashReport(report: String) {
        val pad = dp(20f)
        val text = TextView(this).apply {
            this.text = report
            setTextIsSelectable(true)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Nebula Requiem stopped last time")
            .setMessage("Copy this report and send it to the developer to help fix it.")
            .setView(scroll)
            .setPositiveButton("Copy report") { _, _ ->
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Nebula Requiem crash report", report))
            }
            .setNegativeButton("Close", null)
            .setOnDismissListener { hideSystemBars() }
            .show()
    }

    override fun onStart() {
        super.onStart()
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, main)
        preferRefreshRate()   // the panel may have changed while hidden (fold/unfold)
        reportRefresh()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        audio?.resume()
        loop.onResume()
    }

    override fun onPause() {
        // Pause the run, flush the save and silence audio before going to the background.
        loop.onPause()
        store.sync()
        audio?.pause()
        super.onPause()
    }

    override fun onStop() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        super.onStop()
    }

    override fun onDestroy() {
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        loop.quit()
        audio?.release()
        audio = null
        store.close()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
        else { view.resetHeld(); loop.input.post(InputQueue.FOCUS_LOST) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val d = resources.displayMetrics.density
        if (d != loop.density) { loop.density = d; loop.densityChanged(); root.requestApplyInsets() }
        hideSystemBars()
    }

    /**
     * System Back: the game handles it first (pause, close a menu, return to the title screen).
     * On the title screen it declines, and the app goes to the background like a launcher root.
     */
    private fun onBack() {
        loop.back { consumed -> if (!consumed) main.post { if (!isFinishing) moveTaskToBack(true) } }
    }

    /* ─────────────────────────── window ─────────────────────────── */

    /** Android 15+ always draws apps targeting API 35+ edge to edge; 13 and 14 must opt in. */
    @Suppress("DEPRECATION")
    private fun drawEdgeToEdge() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    /** Immersive mode: bars reappear briefly on an edge swipe, then hide again. */
    private fun hideSystemBars() {
        val c = window.insetsController ?: return
        c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsets.Type.systemBars())
    }

    /**
     * Asks for the fastest refresh rate the panel offers at its current resolution (90, 120, 144 Hz)
     * — or, when the player capped the frame rate, the slowest mode that still reaches the cap, so a
     * 60 fps cap runs the panel at 60 Hz with perfectly even frames and less power. Mode ids belong
     * to one panel, so this re-runs when the display changes (foldables, resolution switch).
     */
    private fun preferRefreshRate() {
        val d = currentDisplay() ?: return
        val cur = d.mode
        val modes = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
        if (modes.isEmpty()) return
        val best = modes.maxByOrNull { it.refreshRate } ?: cur
        panelHz = best.refreshRate
        val cap = fpsCap
        val chosen = if (cap > 0) modes.filter { it.refreshRate >= cap * 0.97f }.minByOrNull { it.refreshRate } ?: best else best
        val lp = window.attributes
        if (lp.preferredDisplayModeId != chosen.modeId) {
            lp.preferredDisplayModeId = chosen.modeId
            window.attributes = lp
        }
    }

    /** The display this window is on (the default display when the context has none, as in tests). */
    private fun currentDisplay(): android.view.Display? = try { display } catch (e: UnsupportedOperationException) {
        getSystemService(DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
    }

    private fun reportRefresh() {
        val hz = currentDisplay()?.refreshRate ?: return
        if (abs(hz - refreshHz) > 0.5f || !firstFrame) { refreshHz = hz; loop.refreshChanged(hz, panelHz) }
    }

    /**
     * Twin-stick drags start near the screen edges, where gesture navigation would read them as
     * Back. While a run is played, reserve both side edges (measured from the real screen edges);
     * menus keep normal Back swipes.
     */
    private fun updateGestureExclusion() {
        if (!::root.isInitialized) return
        val w = root.width; val h = root.height
        if (w == 0 || h == 0) return
        val edge = dp(48f)
        val rects = if (playing) listOf(Rect(0, 0, edge, h), Rect(w - edge, 0, w, h)) else emptyList()
        if (rects != root.systemGestureExclusionRects) root.systemGestureExclusionRects = rects
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).roundToInt()

    /* ─────────────────────────── keys ─────────────────────────── */

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val kc = e.keyCode
        when (kc) {
            // System keys keep their usual meaning. Back is never mapped, so it falls through to the
            // system and reaches the game through OnBackInvokedCallback (predictive back).
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> return super.dispatchKeyEvent(e)
        }
        val act = e.action
        if (act != KeyEvent.ACTION_DOWN && act != KeyEvent.ACTION_UP) return super.dispatchKeyEvent(e)
        val q = loop.input
        if (KeyMap.fromGamepad(e) || KeyMap.isGamepadButton(kc)) {
            padSeen(e.deviceId)
            val code = KeyMap.pad(kc) ?: if (kc == KeyEvent.KEYCODE_DPAD_CENTER) "Pad_dash" else null
            if (code != null) {
                if (act == KeyEvent.ACTION_DOWN) { if (e.repeatCount == 0) q.post(InputQueue.KEY_DOWN, code = code, flag = false, flag2 = true) }
                else q.post(InputQueue.KEY_UP, code = code)
                return true
            }
            // Other controller buttons are unbound, but consumed: B must never double as system Back.
            if (KeyMap.isGamepadButton(kc)) return true
        }
        val code = KeyMap.keyboard(kc) ?: return super.dispatchKeyEvent(e)
        if (act == KeyEvent.ACTION_DOWN) q.post(InputQueue.KEY_DOWN, code = code, flag = e.repeatCount > 0, flag2 = false)
        else q.post(InputQueue.KEY_UP, code = code)
        // Consumed, including Esc and Tab: Esc would otherwise fall back to a second, system Back.
        return true
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) {
            padSeen(e.deviceId)
            if (view.joystick(e)) return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    /** "Gamepad linked" the first time a controller is used. */
    private fun padSeen(id: Int) { if (padsSeen.add(id)) loop.input.post(InputQueue.PAD_LINKED) }

    /* ─────────────────────────── GameLoop.Host (game thread) ─────────────────────────── */

    override fun hasTouchscreen(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) &&
            resources.configuration.touchscreen == Configuration.TOUCHSCREEN_FINGER

    override fun reducedMotion(): Boolean =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    override fun refreshRate(): Float = refreshHz
    override fun panelRate(): Float = panelHz

    override fun onBooted() {
        // First launch after importing a legacy save: write it in the current format.
        if (store.importedLegacy) Save.commit()
        val cap = Save.data.settings.fpsCap
        main.post { setCap(cap) }
    }

    override fun onFirstFrame() { firstFrame = true }

    /* ─────────────────────────── PlatformSink (game thread) ─────────────────────────── */

    override fun setPlaying(playing: Boolean) {
        if (playing == playingSent) return
        playingSent = playing
        main.post { this.playing = playing; updateGestureExclusion() }
    }

    override fun setFrameRateHint(hz: Int) { main.post { setCap(hz) } }

    private fun setCap(hz: Int) {
        fpsCap = hz
        preferRefreshRate()
        val s = view.holder.surface
        if (s != null && s.isValid) {
            try { s.setFrameRate(if (hz > 0) hz.toFloat() else 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) } catch (_: Exception) {}
        }
    }

    /* ─────────────────────────── HapticSink (game thread) ─────────────────────────── */

    /**
     * Light touches use the system's own touch feedback (no permission, follows the Touch feedback
     * setting); impacts use the vibrator with media usage, so they follow the media-haptics setting.
     */
    override fun play(kind: Haptic) {
        main.post {
            when (kind) {
                Haptic.TICK -> root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                Haptic.PRESS -> root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                Haptic.CONFIRM -> root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                Haptic.DENY -> root.performHapticFeedback(HapticFeedbackConstants.REJECT)
                Haptic.HEAVY -> vibrate(VibrationEffect.EFFECT_HEAVY_CLICK)
                Haptic.DOUBLE -> vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)
            }
        }
    }

    private fun vibrate(effect: Int) {
        val v = vibrator ?: getSystemService(VibratorManager::class.java)?.defaultVibrator?.also { vibrator = it } ?: return
        if (!v.hasVibrator()) return
        try { v.vibrate(VibrationEffect.createPredefined(effect), VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA)) } catch (_: Exception) {}
    }

    companion object {
        private const val VOID = 0xFF05030D.toInt()
    }
}
