package io.github.aloualou56.nebularequiem.core

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * §20b PERFORMANCE MONITOR — EMA frame time with hysteresis-based adaptive quality. Natively the
 * display refresh rate is known (Display.getRefreshRate), so no calibration pass is needed.
 *
 * Degrade a tier after 2 s below 80% of the target rate; restore after 10 s of headroom (never
 * above the user's tier), backing off 30 s → 4 min between retries. If even the lowest tier can't
 * hold a fast (≥100 Hz) display, render every second refresh for even pacing and periodically retry.
 */
object Perf {
    var fps = 60.0
    private var frames = 0; private var acc = 0.0
    var ema = 16.7; private var jitter = 0.0; private var work = 8.0
    private var slow = 0; private var fast = 0
    /** Display refresh rate (Hz), reported by the platform. */
    var refreshHz = 60
    /** The fastest rate the panel offers at its resolution. A frame-rate cap may run the panel slower. */
    var panelHz = 60
    private var restoreBlockedUntil = 0.0; private var backoff = 30000.0
    /** A half-refresh cap adaptive quality falls back to; cleared when the player picks a rate. */
    var autoCap = 0.0
    private var capBlockedUntil = 0.0; private var capBackoff = 30000.0
    var onToast: ((String) -> Unit)? = null

    fun setRefresh(hz: Double) {
        val snapped = snapHz(hz)
        if (snapped != refreshHz) { refreshHz = snapped; autoCap = 0.0; capBackoff = 30000.0; retarget() }
    }

    fun setPanel(hz: Double) { panelHz = snapHz(hz) }

    /** Snap a measured rate to the nearest common panel rate when within 5%. */
    fun snapHz(hz: Double): Int {
        val r = intArrayOf(30, 48, 50, 60, 72, 75, 85, 90, 100, 120, 144, 165, 170, 180, 200, 240, 280, 360)
        var best = r[0]
        for (v in r) if (abs(v - hz) < abs(best - hz)) best = v
        return if (abs(best - hz) / hz < 0.05) best else hz.roundToInt()
    }

    /** The player's cap, or 0 when the display can't refresh faster than it anyway. */
    fun capHz(): Double {
        val c = if (Save.data.settings.fpsCap > 0) Save.data.settings.fpsCap.toDouble() else autoCap
        return if (c > 0 && c < refreshHz * 0.97) c else 0.0
    }

    /** The frame rate adaptive quality defends: the cap, else the display (bounded at 240). */
    fun targetHz(): Double {
        val d = min(240, refreshHz).toDouble()
        val c = if (Save.data.settings.fpsCap > 0) Save.data.settings.fpsCap.toDouble() else autoCap
        return if (c > 0) min(c, d) else d
    }

    fun retarget() { slow = 0; fast = 0; restoreBlockedUntil = 0.0; backoff = 30000.0 }

    /** The player chose a frame rate: drop the automatic cap and start over. */
    fun playerChoseRate() { autoCap = 0.0; capBackoff = 30000.0; retarget() }

    /** dt = interval since the last rendered frame (s); workMs = update + render time this frame. */
    fun sample(dt: Double, workMs: Double) {
        val ms = dt * 1000
        jitter = lerp(jitter, abs(ms - ema), 0.05)
        ema = lerp(ema, ms, 0.05)
        work = lerp(work, workMs, 0.05)
        acc += dt; frames++
        if (acc >= 0.5) { fps = frames / acc; frames = 0; acc = 0.0; check() }
    }

    private fun check() {
        val s = Save.data.settings
        if (!s.adaptive || Game.state != GameState.PLAYING) { slow = 0; fast = 0; return }
        val tiers = Quality.entries
        val cur = World.quality.ordinal
        val cap = (Quality.of(s.quality) ?: Quality.HIGH).ordinal
        val T = 1000 / targetHz()
        // A steady interval at a power-saving rate with spare frame time is the system throttling us,
        // not an overloaded renderer: lowering quality there costs looks and gains nothing.
        val throttled = (abs(ema - 33.3) < 3 || abs(ema - 16.7) < 1.5) && jitter < 3 && work < ema * 0.45 && ema > T * 1.2
        val isSlow = ema > T * 1.25 && !throttled
        val isFast = ema < T * 1.06 && work < T * 0.55
        if (isSlow) { slow++; fast = 0 } else if (isFast) { fast++; slow = 0 } else { slow = 0; fast = 0 }
        val now = Env.now()
        if (slow >= 4 && cur > 0) {
            slow = 0
            Game.applyQuality(tiers[cur - 1])
            onToast?.invoke("Adaptive quality · ${tiers[cur - 1].id} for ${targetHz().roundToInt()} fps")
            restoreBlockedUntil = now + backoff
            backoff = min(backoff * 2, 240000.0)
        } else if (slow >= 8 && s.fpsCap == 0 && autoCap == 0.0 && refreshHz >= 100) {
            slow = 0; fast = 0
            autoCap = refreshHz / 2.0
            capBlockedUntil = now + capBackoff
            capBackoff = min(capBackoff * 2, 240000.0)
            onToast?.invoke("Frame rate · ${autoCap.roundToInt()} fps for smooth pacing")
        } else if (autoCap > 0 && cur == cap && fast >= 20 && now > capBlockedUntil) {
            autoCap = 0.0; slow = 0; fast = 0
        }
        if (fast >= 20 && cur < cap && now > restoreBlockedUntil) { fast = 0; Game.applyQuality(tiers[cur + 1]) }
    }
}
