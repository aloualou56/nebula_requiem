package io.github.aloualou56.nebularequiem.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import io.github.aloualou56.nebularequiem.core.AudioSink
import io.github.aloualou56.nebularequiem.core.MusicMode
import io.github.aloualou56.nebularequiem.core.Sfx
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The native audio engine: a low-latency AudioTrack fed by a dedicated synthesis thread.
 *
 * Graph (as in the original):
 *   voices → [pan] → sfx / ui buses ─┐
 *   music layers (pad, arp, bass, drums, lead) → music bus ─┼→ master → compressor → limiter → out
 *   sends → reverb ───────────────────┘        music bus → bass meter (drives the visuals' pulse)
 *
 * The game thread only enqueues sounds (a lock-free single-producer ring) and posts the score and
 * the volumes, which the audio thread reads every block, so no full queue can lose them. Voices,
 * the music sequencer and all mixing live on the audio thread, sample-accurately, with no allocation.
 *
 * The audio thread never waits on the output: it writes without blocking and naps while the buffer
 * is full, so it notices when the output stops taking sound. It then keeps the synth on real time
 * (the score and the queued sounds stay current) and, once the output has taken nothing for a
 * stall's length, replaces it with a new AudioTrack. (A blocking write can sleep on unwoken on
 * Android 13–15 when the system disables a track that underran just as the write began to wait
 * for room; until something else woke it, nothing played.)
 */
class AudioEngine(context: Context) : AudioSink {
    val sr: Int
    private val frames: Int
    /** Opens the output, or returns null while the system won't give one. Tests put their own here. */
    internal var openOutput: () -> PcmOutput? = { openTrack() }
    /** The output in use: the audio thread opens and replaces it; pause() silences it at once. */
    @Volatile private var output: PcmOutput? = null
    private var thread: Thread? = null
    @Volatile private var alive = false
    @Volatile private var paused = false
    private val lock = Object()

    @Volatile override var bassLevel = 0.0
        private set
    @Volatile override var beatCount = 0
        private set
    override val running: Boolean get() = alive && !paused
    /** Frames synthesized so far. */
    @Volatile internal var rendered = 0L
        private set

    // ── sounds (game thread → audio thread): a single-producer ring that drops when full ──
    private val CAP = 512
    private val cA = IntArray(CAP)
    private val cF0 = FloatArray(CAP); private val cF1 = FloatArray(CAP); private val cF2 = FloatArray(CAP)
    private val head = AtomicInteger(0); private val tail = AtomicInteger(0)
    /** Sounds waiting for the audio thread. */
    internal val queued: Int get() = (head.get() - tail.get() + CAP) % CAP

    override fun play(sfx: Sfx, pan: Double, pitch: Double, size: Double) {
        val h = head.get(); val n = (h + 1) % CAP
        if (n == tail.get()) return   // full: drop (never block the game thread)
        cA[h] = sfx.ordinal; cF0[h] = pan.toFloat(); cF1[h] = pitch.toFloat(); cF2[h] = size.toFloat()
        head.lazySet(n)
    }

    // ── the score and the volumes: posted by the game thread, applied by the audio thread each block ──
    @Volatile private var wantMode = MusicMode.OFF
    @Volatile private var volMaster = 0.8f
    @Volatile private var volMusic = 0.55f
    @Volatile private var volSfx = 0.8f

    override fun setMusic(mode: MusicMode) { wantMode = mode }
    override fun setVolumes(master: Double, music: Double, sfx: Double) {
        volMaster = master.toFloat(); volMusic = music.toFloat(); volSfx = sfx.toFloat()
    }

    // ── synthesis state (audio thread only) ──
    private val voices = Array(224) { Voice() }
    private val noise: NoiseBuffer
    private val reverb: Reverb
    private val comp: Compressor
    private val echo: EchoLine
    private val meter: BassMeter
    private var clock = 0L
    private val rng = java.util.Random(7)
    private val sfxL: FloatArray; private val sfxR: FloatArray; private val uiL: FloatArray; private val uiR: FloatArray
    private val layer: Array<FloatArray>; private val rev: FloatArray
    /** The block being played: interleaved stereo. */
    private val block: FloatArray
    private var gMaster = 0f; private var gSfx = 0f; private var gUi = 0f; private var gMusic = 0f
    private var tMaster = 0f; private var tSfx = 0f; private var tUi = 0f; private var tMusic = 0f
    private val kBus: Float; private val kLayer: Float
    private val layerG = FloatArray(5); private val layerT = FloatArray(5)

    init {
        val am = context.getSystemService(AudioManager::class.java)
        sr = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it in 22050..96000 } ?: 48000
        val burst = am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.takeIf { it in 32..4096 } ?: 256
        frames = max(128, min(512, burst))
        noise = NoiseBuffer(sr); reverb = Reverb(sr); comp = Compressor(sr); echo = EchoLine(sr); meter = BassMeter(sr)
        sfxL = FloatArray(frames); sfxR = FloatArray(frames); uiL = FloatArray(frames); uiR = FloatArray(frames)
        layer = Array(5) { FloatArray(frames) }; rev = FloatArray(frames); block = FloatArray(frames * 2)
        kBus = exp(-1.0 / (0.03 * sr)).toFloat(); kLayer = exp(-1.0 / (0.6 * sr)).toFloat()
    }

    /* ─────────────────────────── lifecycle ─────────────────────────── */

    fun start() {
        if (alive) return
        alive = true
        thread = Thread({ loop() }, "nebula-audio").apply { isDaemon = true; start() }
    }

    fun pause() {
        synchronized(lock) { paused = true }
        try { output?.pause() } catch (_: Exception) {}   // silent at once; the audio thread then holds it
    }

    fun resume() {
        if (!alive) { start(); return }
        synchronized(lock) { paused = false; lock.notifyAll() }
    }

    fun release() {
        alive = false
        synchronized(lock) { paused = false; lock.notifyAll() }
        val t = thread
        try { t?.join(500) } catch (_: InterruptedException) {}
        // The thread lets go of its output on the way out; if it hasn't finished, let go of it here.
        if (t != null && t.isAlive) try { output?.release() } catch (_: Exception) {}
        output = null
        thread = null
    }

    private fun loop() {
        // Audio priority where the system allows it; nothing may escape this thread (that would end the app).
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: Exception) {}
        }
        val blockNs = frames * 1_000_000_000L / sr
        val napNs = (blockNs / 2).coerceIn(500_000L, 2_000_000L)
        var out: PcmOutput? = null
        var pos = block.size                    // floats of the block already written (all of them: none pending)
        var openAt = 0L                         // when to open an output next
        var since = 0L; var written = 0L        // frames written since the output last started playing
        var progress = 0L                       // when the output last took sound
        var downNs = DOWN_NS; var stallNs = STALL_NS
        var down = false; var due = 0L          // while nothing plays: when the block's time is up
        var underruns = 0; var tuneAt = 0L
        try {
            while (alive) {
                try {
                    if (paused) {
                        try { out?.pause() } catch (_: Exception) {}
                        synchronized(lock) { while (paused && alive) try { lock.wait() } catch (_: InterruptedException) {} }
                        if (!alive) break
                        if (out != null) try { out.play() } catch (e: Exception) { Log.w(TAG, "audio output lost while paused", e); out = close(out) }
                        since = System.nanoTime(); written = 0; progress = since; down = false
                        continue
                    }
                    var now = System.nanoTime()
                    if (out == null && now >= openAt) {
                        // The game plays on silently while there is no output, and keeps asking for one.
                        out = open()
                        now = System.nanoTime()
                        if (out == null) openAt = now + backoff()
                        else {
                            since = now; written = 0; progress = now; down = false
                            // A working output takes sound at least once per buffer, however long its buffer.
                            val bufNs = out.capacityFrames * 1_000_000_000L / sr
                            downNs = max(DOWN_NS, 2 * bufNs); stallNs = max(STALL_NS, 4 * bufNs)
                            underruns = out.underruns; tuneAt = now + TUNE_NS
                        }
                    }
                    if (pos >= block.size) {
                        try { render() } catch (e: Throwable) { Log.e(TAG, "audio render", e); block.fill(0f) }
                        pos = 0
                    }
                    val o = out
                    if (o != null) {
                        // Never further ahead of real time than the buffer and some slack, even if an output takes everything.
                        val budget = ((now - since) / 1e9 * sr).toLong() + 2L * o.capacityFrames + sr / 10
                        if (written < budget) {
                            val w = try { o.write(block, pos, block.size - pos) } catch (e: Exception) { Log.w(TAG, "audio write", e); AudioTrack.ERROR }
                            if (w > 0) {
                                pos += w; written += w / 2; progress = now; retryNs = 0L; down = false
                                continue   // more room, or the next block
                            }
                            if (w < 0) {
                                Log.w(TAG, "audio output failed ($w); opening a new one")
                                out = close(o); openAt = now + backoff()
                                continue
                            }
                        } else progress = now
                        if (now - progress > stallNs) {
                            Log.w(TAG, "audio output took nothing for ${(now - progress) / 1_000_000} ms; opening a new one")
                            out = close(o); openAt = now + backoff()
                            continue
                        }
                        if (now >= tuneAt) {
                            tuneAt = now + TUNE_NS
                            // Underruns mean this device needs more cushion: let the buffer hold another block.
                            val u = o.underruns
                            if (u > underruns && o.grow(frames)) Log.i(TAG, "audio underran; buffer raised")
                            underruns = u
                        }
                    }
                    if (out == null || now - progress > downNs) {
                        // Nothing is playing: keep the synth on real time, so the score and the queued sounds stay current.
                        if (!down) { down = true; due = now + blockNs }
                        if (now >= due) {
                            pos = block.size
                            due = max(due + blockNs, now - 4 * blockNs)
                            continue
                        }
                        LockSupport.parkNanos(min(due - now, napNs))
                    } else LockSupport.parkNanos(napNs)
                } catch (e: Throwable) {
                    // Nothing may escape this thread (that would end the app): start over on a new output.
                    Log.e(TAG, "audio thread", e)
                    out = out?.let { close(it) }
                    openAt = System.nanoTime() + backoff()
                    LockSupport.parkNanos(blockNs)
                }
            }
        } finally {
            out?.let { close(it) }
        }
    }

    private var retryNs = 0L   // audio thread: the wait before the next attempt to open an output

    /** The wait before opening another output: none at first, then doubling from a quarter second to 4 s. */
    private fun backoff(): Long {
        val d = retryNs
        retryNs = (retryNs * 2).coerceIn(OPEN_RETRY_MIN, OPEN_RETRY_MAX)
        return d
    }

    private fun open(): PcmOutput? {
        val o = try { openOutput() } catch (e: Throwable) { Log.e(TAG, "Audio unavailable", e); null }
        output = o
        return o
    }

    private fun close(o: PcmOutput): PcmOutput? {
        if (output === o) output = null
        try { o.release() } catch (_: Throwable) {}
        return null
    }

    /** The AudioTrack the synth plays through: low latency, with room to grow if this device underruns. */
    private fun openTrack(): PcmOutput? {
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        // Four bursts, or the least the device asks for: the latency this engine has always had.
        val start = max(minBuf / FRAME_BYTES, frames * 4)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(start * FRAME_BYTES * GROWTH)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); return null }
            t.setBufferSizeInFrames(start)
            t.play()
        } catch (e: Exception) {
            t.release()
            throw e
        }
        return TrackOutput(t)
    }

    /* ─────────────────────────── voice construction ─────────────────────────── */

    private fun counted(): Int { var n = 0; for (v in voices) if (v.active && v.counted) n++; return n }
    private var countedCache = 0

    private fun alloc(counted: Boolean): Voice? {
        if (counted && countedCache >= MAX_VOICES) return null
        for (v in voices) if (!v.active) { v.reset(); v.active = true; v.counted = counted; if (counted) countedCache++; return v }
        return null
    }

    private fun s(sec: Double) = (sec * sr).toInt()

    /** Oscillator voice with an exponential AD envelope and exponential pitch glide (tone()). */
    private fun tone(type: Int, f0: Double, f1: Double = f0, dur: Double, vol: Double, attack: Double = 0.004, pan: Float = 0f,
                     lp: Double = 0.0, hp: Double = 0.0, q: Double = 0.8, delay: Double = 0.0, reverb: Double = 0.0, ui: Boolean = false, detune: Double = 0.0) {
        val v = alloc(true) ?: return
        v.start = clock + s(delay); v.stopAt = s(dur + 0.03); v.bus = if (ui) BUS_UI else BUS_SFX
        v.nOsc = 1; v.oscType[0] = type; v.oscPhase[0] = 0f; v.oscMul[0] = 2.0.pow(detune / 1200).toFloat(); v.oscGain[0] = 1f
        v.glide(f0, f1, s(dur))
        var fi = 0
        if (lp > 0) { v.filter(fi++, F_LOWPASS, lp, lp, q, 0, sr.toDouble()) }
        if (hp > 0) { v.filter(fi, F_HIGHPASS, hp, hp, q, 0, sr.toDouble()) }
        v.envelope(vol, s(attack), s(dur))
        v.pan(pan); v.reverb = reverb.toFloat()
    }

    /** Filtered noise voice with a sweeping cutoff (noise()). */
    private fun noiseV(dur: Double, vol: Double, type: Int = F_LOWPASS, f0: Double = 1200.0, f1: Double = f0, q: Double = 0.8, attack: Double = 0.003,
                       pan: Float = 0f, delay: Double = 0.0, reverb: Double = 0.0, ui: Boolean = false) {
        val v = alloc(true) ?: return
        v.start = clock + s(delay); v.stopAt = s(dur + 0.05); v.bus = if (ui) BUS_UI else BUS_SFX
        v.noise = true; v.noisePos = rng.nextInt(noise.data.size)
        v.filter(0, type, f0, f1, q, s(dur), sr.toDouble())
        v.envelope(vol, s(attack), s(dur))
        v.pan(pan); v.reverb = reverb.toFloat()
    }

    // The notes and partials of the sounds below, kept here so that playing a sound never allocates.
    private val HEAL_STEPS = intArrayOf(0, 4, 7, 12)
    private val LEVEL_STEPS = intArrayOf(0, 7, 12, 16, 19, 24)
    private val OVERDRIVE_STEPS = intArrayOf(0, 7, 12, 19)
    private val SHIELD_PARTIALS = doubleArrayOf(3200.0, 2700.0, 2240.0, 1880.0)
    private val PHASE_STEPS = intArrayOf(0, 3, 7)
    private val CLEAR_STEPS = intArrayOf(0, 4, 7, 11)
    private val OVER_STEPS = intArrayOf(12, 7, 3, 0, -5)

    private fun sfx(id: Int, pan: Float, pitch: Double, size: Double) {
        val p = pitch
        when (Sfx.entries[id]) {
            Sfx.SHOOT -> tone(OSC_SQUARE, 980 * p, 360 * p, 0.06, 0.022, pan = pan, lp = 3600.0)
            Sfx.MISSILE -> noiseV(0.18, 0.05, F_BANDPASS, 700.0, 2600.0, 2.2, pan = pan)
            Sfx.LANCE -> { tone(OSC_SAW, 220.0, 1760.0, 0.22, 0.06, lp = 5200.0, pan = pan); noiseV(0.25, 0.05, F_HIGHPASS, 3000.0, 6000.0, pan = pan) }
            Sfx.ENEMY_SHOOT -> tone(OSC_TRIANGLE, 620 * p, 240 * p, 0.09, 0.022, pan = pan)
            Sfx.HIT -> { noiseV(0.045, 0.045, F_HIGHPASS, 2600.0, 1600.0, 0.7, pan = pan); tone(OSC_SQUARE, 240.0, 120.0, 0.04, 0.016, pan = pan) }
            Sfx.CRIT -> tone(OSC_SAW, 1900.0, 640.0, 0.09, 0.03, pan = pan, lp = 6000.0)
            Sfx.DEFLECT -> tone(OSC_SINE, 2600.0, 1400.0, 0.08, 0.03, pan = pan)
            Sfx.EXPLODE -> {
                val sz = size.coerceIn(0.4, 3.0)
                noiseV(0.32 + 0.28 * sz, 0.07 + 0.045 * sz, F_LOWPASS, 2600.0, 110.0, 0.9, pan = pan, reverb = 0.15)
                tone(OSC_SINE, 150.0, 36.0, 0.28 + 0.22 * sz, 0.12 * sz, pan = pan)
            }
            Sfx.BIG_EXPLODE -> {
                noiseV(1.8, 0.24, F_LOWPASS, 3600.0, 50.0, 0.9, reverb = 0.55)
                tone(OSC_SINE, 92.0, 22.0, 1.7, 0.32)
                tone(OSC_SAW, 240.0, 28.0, 1.3, 0.06, lp = 900.0)
            }
            Sfx.GRAZE -> tone(OSC_SINE, 2300 + rng.nextDouble() * 700, 3300.0, 0.035, 0.016, pan = pan)
            Sfx.PICKUP -> tone(OSC_SINE, 1050 * p, 1600 * p, 0.055, 0.022)
            Sfx.DUST -> { tone(OSC_TRIANGLE, 1568.0, 1568.0, 0.09, 0.03); tone(OSC_TRIANGLE, 2349.0, 2349.0, 0.14, 0.026, delay = 0.05, reverb = 0.2) }
            Sfx.HEAL -> for (i in HEAL_STEPS.indices) tone(OSC_TRIANGLE, mtof(72.0 + HEAL_STEPS[i]), dur = 0.22, vol = 0.04, delay = i * 0.055, reverb = 0.3)
            Sfx.LEVEL_UP -> {
                for (i in LEVEL_STEPS.indices) tone(OSC_SQUARE, mtof(69.0 + LEVEL_STEPS[i]), dur = 0.2, vol = 0.03, lp = 3200.0, delay = i * 0.06, reverb = 0.35)
                noiseV(0.6, 0.04, F_HIGHPASS, 2000.0, 8000.0, delay = 0.1, reverb = 0.4)
            }
            Sfx.DASH -> { noiseV(0.24, 0.075, F_BANDPASS, 280.0, 3800.0, 1.3, pan = pan); tone(OSC_SINE, 160.0, 560.0, 0.16, 0.04, pan = pan) }
            Sfx.BOMB -> {
                tone(OSC_SINE, 70.0, 24.0, 1.6, 0.34)
                noiseV(1.4, 0.2, F_LOWPASS, 5000.0, 80.0, 1.1, reverb = 0.6)
                tone(OSC_SAW, 110.0, 880.0, 0.5, 0.05, lp = 2400.0, reverb = 0.4)
            }
            Sfx.OVERDRIVE -> {
                tone(OSC_SAW, 110.0, 1760.0, 0.7, 0.07, lp = 4200.0, reverb = 0.4)
                for (i in OVERDRIVE_STEPS.indices) tone(OSC_SQUARE, mtof(57.0 + OVERDRIVE_STEPS[i]), dur = 0.5, vol = 0.025, lp = 2600.0, delay = 0.25 + i * 0.04, reverb = 0.5)
            }
            Sfx.HURT -> { tone(OSC_SAW, 440.0, 55.0, 0.5, 0.12, lp = 1500.0); noiseV(0.35, 0.12, F_LOWPASS, 2200.0, 180.0) }
            Sfx.SHIELD_BREAK -> for (i in SHIELD_PARTIALS.indices) { val f = SHIELD_PARTIALS[i]; tone(OSC_SINE, f, f * 0.6, 0.35 + i * 0.05, 0.03, delay = i * 0.02, reverb = 0.4) }
            Sfx.BOSS_WARN -> for (i in 0 until 4) {
                tone(OSC_SAW, 440.0, 330.0, 0.42, 0.06, lp = 1800.0, delay = i * 0.5)
                tone(OSC_SAW, 466.0, 349.0, 0.42, 0.05, lp = 1800.0, delay = i * 0.5 + 0.01)
            }
            Sfx.BOSS_PHASE -> {
                tone(OSC_SINE, 120.0, 30.0, 1.1, 0.3)
                noiseV(0.9, 0.14, F_BANDPASS, 400.0, 4000.0, 0.8, reverb = 0.5)
                for (st in PHASE_STEPS) tone(OSC_SAW, mtof(45.0 + st), dur = 1.2, vol = 0.03, lp = 1400.0, reverb = 0.6)
            }
            Sfx.LASER_CHARGE -> tone(OSC_SAW, 90.0, 720.0, 0.85, 0.035, lp = 2000.0, pan = pan)
            Sfx.LASER_FIRE -> { tone(OSC_SAW, 82.0, 70.0, 0.6, 0.06, lp = 900.0, pan = pan); noiseV(0.5, 0.05, F_BANDPASS, 1200.0, 900.0, 3.0, pan = pan) }
            Sfx.LIGHTNING -> for (i in 0 until 3) noiseV(0.04, 0.05, F_HIGHPASS, 3000.0 + i * 900, 5000.0, delay = i * 0.03, pan = pan)
            Sfx.WARP -> tone(OSC_SINE, 260.0, 980.0, 0.5, 0.022, pan = pan, reverb = 0.3)
            Sfx.WAVE_CLEAR -> for (i in CLEAR_STEPS.indices) tone(OSC_TRIANGLE, mtof(64.0 + CLEAR_STEPS[i]), dur = 0.5, vol = 0.03, delay = i * 0.04, reverb = 0.45)
            Sfx.CHRONO -> tone(OSC_SINE, 1400.0, 260.0, 0.6, 0.05, reverb = 0.5)
            Sfx.UI_HOVER -> tone(OSC_SINE, 1900.0, 2200.0, 0.03, 0.012, ui = true)
            Sfx.UI_CLICK -> tone(OSC_SQUARE, 520.0, 1040.0, 0.06, 0.024, lp = 2800.0, ui = true)
            Sfx.UI_BACK -> tone(OSC_SQUARE, 760.0, 380.0, 0.07, 0.022, lp = 2400.0, ui = true)
            Sfx.PURCHASE -> {
                tone(OSC_SQUARE, mtof(83.0), dur = 0.08, vol = 0.03, lp = 4000.0, ui = true)
                tone(OSC_SQUARE, mtof(88.0), dur = 0.22, vol = 0.03, lp = 4000.0, delay = 0.07, ui = true, reverb = 0.3)
            }
            Sfx.DENY -> tone(OSC_SAW, 150.0, 110.0, 0.2, 0.05, lp = 900.0, ui = true)
            Sfx.TRANSITION -> noiseV(0.7, 0.06, F_BANDPASS, 180.0, 2600.0, 1.6, ui = true, reverb = 0.3)
            Sfx.GAME_OVER -> for (i in OVER_STEPS.indices) tone(OSC_SAW, mtof(57.0 + OVER_STEPS[i]), dur = 0.9, vol = 0.035, lp = 1600.0, delay = i * 0.16, reverb = 0.6)
        }
    }

    /* ─────────────────────────── music sequencer ─────────────────────────── */

    private class Chord(val root: Int, val notes: IntArray)
    private val PROG_CALM = arrayOf(Chord(45, intArrayOf(57, 60, 64, 67)), Chord(41, intArrayOf(57, 60, 64, 65)), Chord(48, intArrayOf(55, 60, 64, 67)), Chord(43, intArrayOf(55, 59, 62, 67)))
    private val PROG_BOSS = arrayOf(Chord(45, intArrayOf(57, 60, 64, 69)), Chord(46, intArrayOf(58, 62, 65, 70)), Chord(43, intArrayOf(55, 58, 62, 67)), Chord(40, intArrayOf(56, 59, 64, 68)))
    private val PROG_DIRGE = arrayOf(Chord(45, intArrayOf(57, 60, 64)), Chord(41, intArrayOf(53, 57, 60)), Chord(38, intArrayOf(53, 57, 62)), Chord(40, intArrayOf(52, 56, 59)))
    private var mode = MusicMode.OFF
    private var bpm = 96.0
    private var prog = PROG_CALM
    private val levels = FloatArray(5)
    private var step = 0
    private var nextStep = -1L
    private val ARP_BOSS = intArrayOf(0, 2, 4, 6, 1, 3, 5, 7, 0, 4, 2, 6, 1, 5, 3, 7)
    private val ARP_CALM = intArrayOf(0, 1, 2, 3, 4, 3, 2, 1, 0, 2, 4, 5, 4, 2, 1, 3)
    private val BASS_BOSS = intArrayOf(1, 0, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1)
    private val BASS_CALM = intArrayOf(1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 1)
    private val ext = IntArray(8)
    // Layer levels (pad, arp, bass, drums, lead) of each score.
    private val LV_OFF = floatArrayOf(0f, 0f, 0f, 0f, 0f)
    private val LV_MENU = floatArrayOf(0.55f, 0.32f, 0.16f, 0f, 0f)
    private val LV_GAME = floatArrayOf(0.32f, 0.38f, 0.55f, 0.62f, 0f)
    private val LV_BOSS = floatArrayOf(0.3f, 0.42f, 0.6f, 0.75f, 0.32f)
    private val LV_GAMEOVER = floatArrayOf(0.6f, 0.12f, 0f, 0f, 0f)
    private val PAD_DETUNE = intArrayOf(-9, 9)

    private fun setMode(m: MusicMode) {
        if (m == mode) return
        mode = m
        val lv: FloatArray
        when (m) {
            MusicMode.OFF -> { bpm = 96.0; prog = PROG_CALM; lv = LV_OFF }
            MusicMode.MENU -> { bpm = 92.0; prog = PROG_CALM; lv = LV_MENU }
            MusicMode.GAME -> { bpm = 112.0; prog = PROG_CALM; lv = LV_GAME }
            MusicMode.BOSS -> { bpm = 126.0; prog = PROG_BOSS; lv = LV_BOSS }
            MusicMode.GAMEOVER -> { bpm = 76.0; prog = PROG_DIRGE; lv = LV_GAMEOVER }
        }
        for (i in 0 until 5) { levels[i] = lv[i]; layerT[i] = lv[i] }
        echo.target = (60.0 / bpm) * 0.75 * sr
        if (m != MusicMode.OFF && nextStep < 0) nextStep = clock + s(0.06)
    }

    private fun musicVoice(layerIdx: Int, at: Long): Voice? {
        val v = alloc(false) ?: return null
        v.start = at; v.bus = BUS_LAYER0 + layerIdx
        return v
    }

    private fun schedule(blockEnd: Long) {
        if (nextStep < 0) return
        while (nextStep < blockEnd) {
            val s16 = 60.0 / bpm / 4
            playStep(step, nextStep, s16)
            nextStep += s(s16)
            step = (step + 1) % 64
        }
    }

    private fun playStep(step: Int, t: Long, s16: Double) {
        val bar = (step / 16) % 4; val i = step % 16
        val chord = prog[bar]
        val boss = mode == MusicMode.BOSS
        if (i == 0 && levels[0] > 0) pad(chord.notes, t, s16 * 16)
        if (levels[1] > 0) {
            val n = chord.notes.size
            for (k in 0 until n) { ext[k] = chord.notes[k]; ext[k + n] = chord.notes[k] + 12 }
            val arp = if (boss) ARP_BOSS else ARP_CALM
            if (mode != MusicMode.MENU || i % 2 == 0) arpNote(ext[arp[i] % (n * 2)] + 12, t, s16 * 0.9)
        }
        if (levels[2] > 0) {
            val pat = if (boss) BASS_BOSS else BASS_CALM
            if (pat[i] == 1) bass(chord.root + if (i % 4 == 2) 12 else 0, t, s16 * 1.6)
        }
        if (levels[3] > 0) {
            val kick = if (boss) i % 4 == 0 else (i == 0 || i == 8 || i == 10)
            if (kick) { kick(t); beatCount++ }
            if (i == 4 || i == 12) snare(t)
            if (if (boss) true else i % 2 == 0) hat(t, i == 14)
        } else if (mode == MusicMode.MENU && i == 0) beatCount++   // keep visuals breathing with the bar
        if (levels[4] > 0 && (i == 0 || i == 3 || i == 6 || i == 10 || i == 12)) {
            val n = chord.notes[(i + bar) % chord.notes.size] + 12
            lead(n, t, s16 * if (i == 12) 3.0 else 1.5)
        }
    }

    private fun pad(notes: IntArray, t: Long, dur: Double) {
        val v = musicVoice(0, t) ?: return
        v.nOsc = min(8, notes.size * 2)
        var k = 0
        for (n in notes) for (det in PAD_DETUNE) {   // two saws detuned ±9 cents → chorus shimmer
            if (k >= 8) break
            v.oscType[k] = OSC_SAW; v.oscPhase[k] = rng.nextFloat(); v.oscMul[k] = (mtof(n.toDouble()) / 100.0 * 2.0.pow(det / 1200.0)).toFloat(); v.oscGain[k] = 1f; k++
        }
        v.glide(100.0, 100.0, 0)
        v.filter(0, F_LOWPASS, 1150.0, 1150.0, 0.6, 0, sr.toDouble())
        v.envelope(0.11, s(dur * 0.35), s(dur * 1.05)); v.stopAt = s(dur * 1.1)
        v.reverb = 0.5f
    }

    private fun arpNote(midi: Int, t: Long, dur: Double) {
        val v = musicVoice(1, t) ?: return
        v.nOsc = 1; v.oscType[0] = OSC_SQUARE; v.oscMul[0] = 1f; v.oscGain[0] = 1f
        v.glide(mtof(midi.toDouble()), mtof(midi.toDouble()), 0)
        v.filter(0, F_LOWPASS, 2600.0, 2600.0, 1.0, 0, sr.toDouble())
        v.envelope(0.07, s(0.003), s(0.003 + dur)); v.stopAt = s(dur + 0.04)
    }

    private fun bass(midi: Int, t: Long, dur: Double) {
        val v = musicVoice(2, t) ?: return
        v.nOsc = 2
        v.oscType[0] = OSC_SAW; v.oscMul[0] = 1f; v.oscGain[0] = 1f
        v.oscType[1] = OSC_SQUARE; v.oscMul[1] = 0.5f; v.oscGain[1] = 0.35f
        v.glide(mtof(midi.toDouble()), mtof(midi.toDouble()), 0)
        // Filter envelope: cutoff falls from 1.9 kHz to 220 Hz — the plucky synthwave bass.
        v.filter(0, F_LOWPASS, 1900.0, 220.0, 6.0, s(dur * 0.9), sr.toDouble())
        v.envelope(0.42, s(0.004), s(0.004 + dur)); v.stopAt = s(dur + 0.05)
    }

    private fun kick(t: Long) {
        val v = musicVoice(3, t) ?: return
        v.nOsc = 1; v.oscType[0] = OSC_SINE; v.oscMul[0] = 1f; v.oscGain[0] = 1f
        v.glide(160.0, 40.0, s(0.13))
        v.envelope(0.95, s(0.002), s(0.302)); v.stopAt = s(0.36)
    }

    private fun snare(t: Long) {
        val v = musicVoice(3, t) ?: return
        v.noise = true; v.noisePos = rng.nextInt(noise.data.size)
        v.filter(0, F_BANDPASS, 1900.0, 1900.0, 0.7, 0, sr.toDouble())
        v.envelope(0.4, s(0.002), s(0.172)); v.stopAt = s(0.25)
        v.reverb = 0.25f
        val o = musicVoice(3, t) ?: return
        o.nOsc = 1; o.oscType[0] = OSC_TRIANGLE; o.oscMul[0] = 1f; o.oscGain[0] = 1f
        o.glide(230.0, 150.0, s(0.08))
        o.envelope(0.25, s(0.002), s(0.092)); o.stopAt = s(0.12)
    }

    private fun hat(t: Long, open: Boolean) {
        val v = musicVoice(3, t) ?: return
        v.noise = true; v.noisePos = rng.nextInt(noise.data.size)
        v.filter(0, F_HIGHPASS, 7200.0, 7200.0, 1.0, 0, sr.toDouble())
        v.envelope(if (open) 0.16 else 0.1, s(0.001), s(0.001 + if (open) 0.2 else 0.035)); v.stopAt = s(if (open) 0.26 else 0.06)
    }

    private fun lead(midi: Int, t: Long, dur: Double) {
        val v = musicVoice(4, t) ?: return
        v.nOsc = 2
        for (k in 0 until 2) { v.oscType[k] = OSC_SAW; v.oscMul[k] = 2.0.pow((if (k == 0) -6 else 6) / 1200.0).toFloat(); v.oscGain[k] = 1f }
        v.glide(mtof(midi.toDouble()), mtof(midi.toDouble()), 0)
        v.lfoRate = 5.5f; v.lfoCents = 9f   // ±9 cents vibrato
        v.filter(0, F_LOWPASS, 2800.0, 2800.0, 2.0, 0, sr.toDouble())
        v.envelope(0.09, s(0.01), s(0.01 + dur)); v.stopAt = s(dur + 0.05)
    }

    /* ─────────────────────────── rendering ─────────────────────────── */

    private fun drain() {
        while (tail.get() != head.get()) {
            val i = tail.get()
            sfx(cA[i], cF0[i], cF1[i].toDouble(), cF2[i].toDouble())
            tail.lazySet((i + 1) % CAP)
        }
    }

    /** Synthesizes the next block into [block] (the audio thread; tests call it directly). */
    internal fun render() {
        val n = frames
        val m = wantMode
        if (m != mode) setMode(m)
        // Perceived loudness is roughly logarithmic: squaring the slider gives a friendlier taper.
        val vm = volMaster; val vmu = volMusic; val vs = volSfx
        tMaster = vm * vm; tSfx = vs * vs * 0.9f; tUi = vs * vs * 0.7f; tMusic = vmu * vmu * 0.85f
        drain()
        countedCache = counted()
        schedule(clock + n)
        sfxL.fill(0f); sfxR.fill(0f); uiL.fill(0f); uiR.fill(0f); rev.fill(0f)
        for (l in layer) l.fill(0f)
        for (v in voices) if (v.active) renderVoice(v, n)
        for (i in 0 until n) {
            gMaster = tMaster + (gMaster - tMaster) * kBus
            gSfx = tSfx + (gSfx - tSfx) * kBus
            gUi = tUi + (gUi - tUi) * kBus
            gMusic = tMusic + (gMusic - tMusic) * kBus
            var music = 0f
            for (k in 0 until 5) { layerG[k] = layerT[k] + (layerG[k] - layerT[k]) * kLayer; music += layer[k][i] * layerG[k] }
            music += echo.process(layer[1][i] * layerG[1])
            val mus = music * gMusic
            meter.process(mus)
            var l = sfxL[i] * gSfx + uiL[i] * gUi + mus
            var r = sfxR[i] * gSfx + uiR[i] * gUi + mus
            reverb.process(rev[i] * 0.6f)
            l += reverb.outL; r += reverb.outR
            l *= gMaster; r *= gMaster
            val g = comp.gain(max(abs(l), abs(r)))
            block[i * 2] = Compressor.limit(l * g)
            block[i * 2 + 1] = Compressor.limit(r * g)
        }
        bassLevel = meter.level()
        clock += n
        rendered = clock
    }

    /** The block [render] made last (tests). */
    internal val lastBlock: FloatArray get() = block
    /** The score the sequencer plays (the audio thread's; tests read it with the thread stopped). */
    internal val musicMode: MusicMode get() = mode

    private fun renderVoice(v: Voice, n: Int) {
        val rel = (v.start - clock)
        if (rel >= n) return
        var from = if (rel > 0) rel.toInt() else 0
        val L: FloatArray; val R: FloatArray?
        when (v.bus) {
            BUS_SFX -> { L = sfxL; R = sfxR }
            BUS_UI -> { L = uiL; R = uiR }
            else -> { L = layer[v.bus - BUS_LAYER0]; R = null }
        }
        // In the original, reverb sends tap the voice before its bus gain, so muting a bus left its
        // reverb tails audible. Here sends follow the bus volume, normalised so defaults sound the same.
        val sendK = v.reverb * when (v.bus) {
            BUS_SFX -> gSfx / DEF_SFX
            BUS_UI -> gUi / DEF_UI
            else -> gMusic / DEF_MUSIC
        }
        val srF = sr.toFloat()
        val nd = noise.data
        while (from < n) {
            val t = v.pos
            if (t >= v.stopAt) { v.active = false; return }
            // envelope
            var g = v.g
            if (t < v.attack) { g *= v.gk }
            else if (t == v.attack) { g = v.peak; v.gk = (0.0001 / v.peak).pow(1.0 / (v.end - v.attack)) }
            else if (t < v.end) g *= v.gk
            else g = 0.0
            v.g = g
            // pitch glide
            if (v.glideLen > 0 && t < v.glideLen) v.freq *= v.freqK
            // sweeping filters: advance cutoff, refresh coefficients every 32 samples
            for (k in 0 until v.nFilt) {
                if (v.fSweep[k] > 0 && t < v.fSweep[k]) {
                    v.fFreq[k] *= v.fK[k]
                    if (t and 31 == 0) v.filt[k].set(v.filt[k].type, v.fFreq[k], v.fQ[k], sr.toDouble())
                }
            }
            // source
            var x = 0f
            if (v.noise) {
                x = nd[v.noisePos]; v.noisePos++; if (v.noisePos >= nd.size) v.noisePos = 0
            } else {
                var fm = 1f
                if (v.lfoRate > 0f) {
                    v.lfoPhase += v.lfoRate / srF; if (v.lfoPhase >= 1f) v.lfoPhase -= 1f
                    fm = 1f + v.lfoCents * SineTable.at(v.lfoPhase) * 0.000577623f
                }
                val base = (v.freq / srF).toFloat() * fm
                for (k in 0 until v.nOsc) {
                    val dt = base * v.oscMul[k]
                    var ph = v.oscPhase[k] + dt
                    if (ph >= 1f) ph -= 1f
                    v.oscPhase[k] = ph
                    val s = when (v.oscType[k]) {
                        OSC_SINE -> SineTable.at(ph)
                        OSC_SAW -> 2f * ph - 1f - blep(ph, dt)
                        OSC_SQUARE -> { var q = if (ph < 0.5f) 1f else -1f; q += blep(ph, dt); var p2 = ph + 0.5f; if (p2 >= 1f) p2 -= 1f; q -= blep(p2, dt); q }
                        else -> 4f * abs(ph - 0.5f) - 1f
                    }
                    x += s * v.oscGain[k]
                }
            }
            for (k in 0 until v.nFilt) x = v.filt[k].process(x)
            val y = x * g.toFloat()
            if (R != null) { L[from] += y * v.gainL; R[from] += y * v.gainR } else L[from] += y
            if (sendK > 0f) rev[from] += y * sendK
            v.pos++
            from++
        }
    }

    /** PolyBLEP residual for band-limited discontinuities. */
    private fun blep(t: Float, dt: Float): Float {
        if (dt <= 0f) return 0f
        return if (t < dt) { val x = t / dt; x + x - x * x - 1f }
        else if (t > 1f - dt) { val x = (t - 1f) / dt; x * x + x + x + 1f }
        else 0f
    }

    companion object {
        const val MAX_VOICES = 56
        private const val TAG = "NebulaRequiem"
        private const val FRAME_BYTES = 8   // a stereo float frame
        private const val GROWTH = 2        // an AudioTrack's buffer may grow to twice where it starts
        // Without any sound taken for DOWN_NS the output is down and the synth keeps real time on its
        // own; after STALL_NS it is replaced (both at least a few buffers' worth).
        private const val DOWN_NS = 150_000_000L
        private const val STALL_NS = 500_000_000L
        private const val OPEN_RETRY_MIN = 250_000_000L
        private const val OPEN_RETRY_MAX = 4_000_000_000L
        private const val TUNE_NS = 500_000_000L
        private const val BUS_SFX = 0; private const val BUS_UI = 1; private const val BUS_LAYER0 = 2
        // Bus gains at the default sliders (master .8, music .55, sfx .8), using the squared taper below.
        private const val DEF_SFX = 0.8f * 0.8f * 0.9f
        private const val DEF_UI = 0.8f * 0.8f * 0.7f
        private const val DEF_MUSIC = 0.55f * 0.55f * 0.85f
    }
}

/** Where the synth's blocks go: an AudioTrack on a device, a stand-in under test. */
internal interface PcmOutput {
    /** Writes interleaved stereo floats without blocking: how many it took (0 while the buffer is full), or a negative AudioTrack error. */
    fun write(data: FloatArray, offset: Int, size: Int): Int
    fun play()
    fun pause()
    fun release()
    /** The most frames its buffer can hold. */
    val capacityFrames: Int
    /** Underruns so far. */
    val underruns: Int
    /** Lets the buffer hold [frames] more, up to its capacity; false when it can't. */
    fun grow(frames: Int): Boolean
}

private class TrackOutput(private val t: AudioTrack) : PcmOutput {
    override fun write(data: FloatArray, offset: Int, size: Int) = t.write(data, offset, size, AudioTrack.WRITE_NON_BLOCKING)
    override fun play() = t.play()
    override fun pause() = t.pause()
    override fun release() = t.release()
    override val capacityFrames: Int get() = t.bufferCapacityInFrames
    override val underruns: Int get() = t.underrunCount
    override fun grow(frames: Int): Boolean {
        val size = t.bufferSizeInFrames
        return size < t.bufferCapacityInFrames && t.setBufferSizeInFrames(size + frames) > size
    }
}
