package io.github.aloualou56.nebularequiem.core

import kotlin.math.max

/** Colour tones used by toasts and banners. */
enum class Tone(val color: Int) {
    ION(Pal.ION), PLASMA(Pal.PLASMA), SOLAR(Pal.SOLAR), MINT(Pal.MINT), CRIMSON(Pal.CRIMSON), WARN(Pal.CRIMSON)
}

/** Sound effects, with their minimum repeat gap in ms (keeps the mix legible). */
enum class Sfx(val gapMs: Int = 0) {
    SHOOT(55), MISSILE(70), LANCE, ENEMY_SHOOT(45), HIT(28), CRIT(40), DEFLECT(50), EXPLODE(40), BIG_EXPLODE,
    GRAZE(30), PICKUP(26), DUST(40), HEAL, LEVEL_UP, DASH, BOMB, OVERDRIVE, HURT, SHIELD_BREAK, BOSS_WARN,
    BOSS_PHASE, LASER_CHARGE(120), LASER_FIRE(120), LIGHTNING(60), WARP(90), WAVE_CLEAR, CHRONO, UI_HOVER(35),
    UI_CLICK, UI_BACK, PURCHASE, DENY, TRANSITION, GAME_OVER
}

enum class MusicMode { OFF, MENU, GAME, BOSS, GAMEOVER }

enum class Haptic { TICK, PRESS, CONFIRM, DENY, HEAVY, DOUBLE }

/** What the core asks of the audio engine (implemented natively by audio.AudioEngine). */
interface AudioSink {
    /** pan ∈ [−1, 1]. Called on the game thread; must not block. */
    fun play(sfx: Sfx, pan: Double, pitch: Double, size: Double)
    fun setMusic(mode: MusicMode)
    fun setVolumes(master: Double, music: Double, sfx: Double)
    /** Raw bass energy of the music bus, 0..1 (written by the audio thread). */
    val bassLevel: Double
    /** Count of kicks/bar markers that have reached the speakers. */
    val beatCount: Int
    val running: Boolean
}

interface HapticSink { fun play(kind: Haptic) }

/** Persistent storage for the save document (atomic file in the app, memory in tests). */
interface SaveStore {
    fun read(): String?
    fun write(text: String)
    /** The previous good save, kept beside the main one; used when the main file is unreadable. */
    fun readBackup(): String? = null
    /** Block until pending writes reach disk (called when the app is backgrounded). */
    fun sync() {}
}

/** Builds procedural nebulae off the game thread (render layer). */
interface NebulaBuilder {
    /** Start building the nebula for (seed, palette) in the background. */
    fun prepare(seed: Int, palette: NebulaPalette)
    /** Make (seed, palette) the live nebula, building it now if the background job hasn't finished. */
    fun adopt(seed: Int, palette: NebulaPalette, sync: Boolean)
}

/** Native app services (Back-gesture edge reservation, frame-rate hint). */
interface PlatformSink {
    fun setPlaying(playing: Boolean) {}
    fun setFrameRateHint(hz: Int) {}
}

/** Global service locator, wired by the platform layer; defaults are silent no-ops (tests). */
object Env {
    var audio: AudioSink = object : AudioSink {
        override fun play(sfx: Sfx, pan: Double, pitch: Double, size: Double) {}
        override fun setMusic(mode: MusicMode) {}
        override fun setVolumes(master: Double, music: Double, sfx: Double) {}
        override val bassLevel: Double get() = 0.0
        override val beatCount: Int get() = 0
        override val running: Boolean get() = false
    }
    var haptics: HapticSink = object : HapticSink { override fun play(kind: Haptic) {} }
    var nebula: NebulaBuilder = object : NebulaBuilder {
        override fun prepare(seed: Int, palette: NebulaPalette) {}
        override fun adopt(seed: Int, palette: NebulaPalette, sync: Boolean) {}
    }
    var platform: PlatformSink = object : PlatformSink {}

    /** Monotonic real-time clock in ms (performance.now()); overridable for deterministic tests. */
    var clock: () -> Double = { System.nanoTime() / 1e6 }
    fun now(): Double = clock()
}

/**
 * Audio facade used by gameplay code: throttles repeats, converts world x to stereo pan and keeps
 * the beat/bass-pulse values the visuals breathe with.
 */
object Audio {
    private val last = DoubleArray(Sfx.entries.size) { -1e9 }
    /** Smoothed bass energy 0..1. */
    var pulse = 0.0
    /** Decays after each kick reaches the speakers. */
    var beat = 0.0
    private var seenBeats = 0
    var mode = MusicMode.OFF
        private set

    fun play(sfx: Sfx, x: Double = Double.NaN, pitch: Double = 1.0, size: Double = 1.0) {
        val sink = Env.audio
        if (!sink.running) return
        val now = Env.now()
        if (sfx.gapMs > 0 && now - last[sfx.ordinal] < sfx.gapMs) return
        last[sfx.ordinal] = now
        val pan = if (!x.isNaN() && World.w > 0) clamp((x / World.w) * 2 - 1, -1.0, 1.0) * 0.65 else 0.0
        sink.play(sfx, pan, pitch, size)
    }

    fun music(m: MusicMode) {
        mode = m
        Env.audio.setMusic(m)
    }

    fun applyVolumes() {
        val s = Save.data.settings
        Env.audio.setVolumes(s.master, s.music, s.sfx)
    }

    /** Per-frame analysis: bass energy for visuals + beat events from the sequencer. */
    fun update(dt: Double) {
        beat = max(0.0, beat - dt * 3.2)
        val sink = Env.audio
        if (!sink.running) { pulse = damp(pulse, 0.0, 4.0, dt); return }
        val bass = sink.bassLevel
        pulse = if (bass > pulse) damp(pulse, bass, 30.0, dt) else damp(pulse, bass, 5.0, dt)
        val bc = sink.beatCount
        if (bc != seenBeats) { seenBeats = bc; beat = 1.0 }
    }
}

/** Haptic feedback on touch devices (Settings → Vibration). */
object Haptics {
    private var last = 0.0
    fun play(kind: Haptic) {
        if (!Input.touchMode || !Save.data.settings.haptics) return
        val now = Env.now()
        val strong = kind == Haptic.HEAVY || kind == Haptic.DOUBLE
        if (!strong && now - last < 40) return
        last = now
        Env.haptics.play(kind)
    }
}
