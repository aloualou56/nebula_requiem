package io.github.aloualou56.nebularequiem.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * §4 AUDIO — a native software synthesizer:
 * band-limited oscillators, a shared white-noise buffer, biquad filters, exponential
 * gain/frequency ramps, equal-power panning, an algorithmic reverb, a tempo-synced echo and a
 * compressor on the master. Everything here runs on the audio thread and never allocates.
 */

const val OSC_SINE = 0
const val OSC_SQUARE = 1
const val OSC_SAW = 2
const val OSC_TRIANGLE = 3

const val F_NONE = 0
const val F_LOWPASS = 1
const val F_HIGHPASS = 2
const val F_BANDPASS = 3

fun mtof(m: Double): Double = 440.0 * 2.0.pow((m - 69) / 12)

object SineTable {
    const val N = 4096
    val t = FloatArray(N + 1).also { for (i in 0..N) it[i] = sin(2 * PI * i / N).toFloat() }
    fun at(phase: Float): Float {
        val x = phase * N
        val i = x.toInt() and (N - 1)
        val f = x - x.toInt()
        return t[i] + (t[i + 1] - t[i]) * f
    }
}

/** 2 s of white noise shared by every noise voice. */
class NoiseBuffer(sampleRate: Int) {
    val data = FloatArray(sampleRate * 2).also { val r = java.util.Random(1234); for (i in it.indices) it[i] = r.nextFloat() * 2 - 1 }
}

/** One biquad section (transposed direct form II) with Audio EQ Cookbook-style coefficients. */
class Biquad {
    var type = F_NONE
    var b0 = 1f; var b1 = 0f; var b2 = 0f; var a1 = 0f; var a2 = 0f
    var z1 = 0f; var z2 = 0f
    fun reset() { z1 = 0f; z2 = 0f }

    /**
     * Lowpass/highpass interpret Q in dB (α = sin ω₀ / (2·10^(Q/20)));
     * bandpass uses the classic Q (α = sin ω₀ / 2Q, 0 dB peak).
     */
    fun set(type: Int, freq: Double, q: Double, sr: Double) {
        this.type = type
        val f = min(freq, sr * 0.49).coerceAtLeast(10.0)
        val w0 = 2 * PI * f / sr
        val cs = cos(w0); val sn = sin(w0)
        when (type) {
            F_LOWPASS, F_HIGHPASS -> {
                val alpha = sn / (2 * 10.0.pow(q / 20))
                val a0 = 1 + alpha
                if (type == F_LOWPASS) { b0 = ((1 - cs) / 2 / a0).toFloat(); b1 = ((1 - cs) / a0).toFloat(); b2 = b0 }
                else { b0 = ((1 + cs) / 2 / a0).toFloat(); b1 = (-(1 + cs) / a0).toFloat(); b2 = b0 }
                a1 = (-2 * cs / a0).toFloat(); a2 = ((1 - alpha) / a0).toFloat()
            }
            F_BANDPASS -> {
                val alpha = sn / (2 * max(q, 1e-4))
                val a0 = 1 + alpha
                b0 = (alpha / a0).toFloat(); b1 = 0f; b2 = (-alpha / a0).toFloat()
                a1 = (-2 * cs / a0).toFloat(); a2 = ((1 - alpha) / a0).toFloat()
            }
            else -> { b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f }
        }
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/**
 * A synth voice: up to 8 oscillators (or a noise source) → up to 2 filters (optionally sweeping)
 * → an exponential attack/decay envelope → pan → a bus, plus a reverb send. Covers every tone(),
 * noise() and music instrument in the game.
 */
class Voice {
    var active = false
    var start = 0L          // absolute sample index at which the voice begins
    var pos = 0             // samples since start
    var stopAt = 0          // osc.stop() time (samples)
    var counted = false     // counts toward the SFX voice cap
    var bus = 0
    var reverb = 0f
    var gainL = 1f; var gainR = 1f

    // sources
    var nOsc = 0
    val oscType = IntArray(8)
    val oscPhase = FloatArray(8)
    val oscMul = FloatArray(8)    // frequency multiplier (detune / sub-octave)
    val oscGain = FloatArray(8)
    var freq = 440.0; var freqK = 1.0; var glideLen = 0
    var lfoRate = 0f; var lfoCents = 0f; var lfoPhase = 0f
    var noise = false; var noisePos = 0

    // filters
    var nFilt = 0
    val filt = arrayOf(Biquad(), Biquad())
    val fFreq = DoubleArray(2); val fK = DoubleArray(2); val fSweep = IntArray(2); val fQ = DoubleArray(2)

    // envelope (exponential ramps: 0.0001 → peak over [0, attack] → 0.0001 at end)
    var g = 0.0001; var gk = 1.0
    var attack = 1; var end = 2; var peak = 0.1

    fun reset() {
        active = false; pos = 0; counted = false; reverb = 0f; gainL = 1f; gainR = 1f
        nOsc = 0; noise = false; nFilt = 0; lfoRate = 0f; lfoCents = 0f; lfoPhase = 0f
        glideLen = 0; freqK = 1.0
        filt[0].reset(); filt[1].reset()
    }

    fun pan(p: Float) {
        if (p == 0f) { gainL = 1f; gainR = 1f; return }   // no panner node: mono upmixes at full gain
        val x = (p.coerceIn(-1f, 1f) + 1) / 2
        gainL = cos(x * PI / 2).toFloat(); gainR = sin(x * PI / 2).toFloat()
    }

    fun envelope(peak: Double, attackS: Int, endS: Int) {
        this.peak = max(0.0002, peak); attack = max(1, attackS); end = max(attack + 1, endS)
        g = 0.0001
        gk = (this.peak / 0.0001).pow(1.0 / attack)
    }

    fun glide(f0: Double, f1: Double, lenS: Int) {
        freq = max(1.0, f0)
        if (f1 != f0 && lenS > 0) { glideLen = lenS; freqK = (max(1.0, f1) / freq).pow(1.0 / lenS) } else { glideLen = 0; freqK = 1.0 }
    }

    fun filter(i: Int, type: Int, f0: Double, f1: Double, q: Double, sweepS: Int, sr: Double) {
        fFreq[i] = max(20.0, f0); fQ[i] = q
        if (f1 != f0 && sweepS > 0) { fSweep[i] = sweepS; fK[i] = (max(20.0, f1) / fFreq[i]).pow(1.0 / sweepS) } else { fSweep[i] = 0; fK[i] = 1.0 }
        filt[i].set(type, fFreq[i], q, sr)
        filt[i].reset()
        nFilt = max(nFilt, i + 1)
    }
}

/**
 * Freeverb-style algorithmic reverb (8 parallel damped combs + 4 series allpasses per channel)
 * with a tail like a convolution with exponentially decaying noise (T = 2.6 s).
 */
class Reverb(sr: Int) {
    private val scale = sr / 44100.0
    private val combL = IntArray(8) { (COMB[it] * scale).toInt() }.map { FloatArray(it) }
    private val combR = IntArray(8) { ((COMB[it] + 23) * scale).toInt() }.map { FloatArray(it) }
    private val apL = IntArray(4) { (AP[it] * scale).toInt() }.map { FloatArray(it) }
    private val apR = IntArray(4) { ((AP[it] + 23) * scale).toInt() }.map { FloatArray(it) }
    private val ciL = IntArray(8); private val ciR = IntArray(8); private val aiL = IntArray(4); private val aiR = IntArray(4)
    private val fsL = FloatArray(8); private val fsR = FloatArray(8)
    private val feedback = 0.86f
    private val damp = 0.22f
    var outL = 0f; var outR = 0f

    fun process(input: Float) {
        val x = input * 0.015f
        var l = 0f; var r = 0f
        for (i in 0 until 8) {
            val bl = combL[i]; var k = ciL[i]
            val yl = bl[k]; fsL[i] = yl * (1 - damp) + fsL[i] * damp; bl[k] = x + fsL[i] * feedback; if (++k >= bl.size) k = 0; ciL[i] = k; l += yl
            val br = combR[i]; k = ciR[i]
            val yr = br[k]; fsR[i] = yr * (1 - damp) + fsR[i] * damp; br[k] = x + fsR[i] * feedback; if (++k >= br.size) k = 0; ciR[i] = k; r += yr
        }
        for (i in 0 until 4) {
            val bl = apL[i]; var k = aiL[i]; val vl = bl[k]; bl[k] = l + vl * 0.5f; l = vl - l; if (++k >= bl.size) k = 0; aiL[i] = k
            val br = apR[i]; k = aiR[i]; val vr = br[k]; br[k] = r + vr * 0.5f; r = vr - r; if (++k >= br.size) k = 0; aiR[i] = k
        }
        outL = l * 1.6f; outR = r * 1.6f
    }

    companion object {
        private val COMB = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val AP = intArrayOf(556, 441, 341, 225)
    }
}

/**
 * Master dynamics: a compressor (threshold −16 dB, knee 12 dB,
 * ratio 5, attack 4 ms, release 220 ms) including its automatic make-up gain, then a soft limiter
 * in place of a hard clip.
 */
class Compressor(sr: Int) {
    private val T = -16.0; private val W = 12.0; private val R = 5.0
    private val att = exp(-1.0 / (0.004 * sr)).toFloat()
    private val rel = exp(-1.0 / (0.22 * sr)).toFloat()
    private var grDb = 0f
    private val makeup: Float

    init {
        // Chromium: makeup = (1 / curve(0 dBFS))^0.6
        val atZero = curve(0.0)
        makeup = 10.0.pow(-atZero * 0.6 / 20).toFloat()
    }

    private fun curve(xDb: Double): Double {
        val y = when {
            xDb < T - W / 2 -> xDb
            xDb <= T + W / 2 -> xDb + (1 / R - 1) * (xDb - T + W / 2).pow(2) / (2 * W)
            else -> T + (xDb - T) / R
        }
        return y - xDb
    }

    private val lut = FloatArray(1201).also { for (i in it.indices) it[i] = curve(i / 10.0 - 100.0).toFloat() }

    fun gain(peak: Float): Float {
        val lvl = if (peak < 1e-5f) -100f else (20 * log10(peak.toDouble())).toFloat().coerceIn(-100f, 20f)
        val target = lut[((lvl + 100f) * 10).toInt().coerceIn(0, 1200)]
        grDb = if (target < grDb) target + (grDb - target) * att else target + (grDb - target) * rel
        return 10.0.pow(grDb / 20.0).toFloat() * makeup
    }

    companion object {
        /** Gentle limiter: linear below 0.9, smoothly saturating to ±1. */
        fun limit(x: Float): Float {
            val a = abs(x)
            if (a <= 0.9f) return x
            val s = 0.9f + 0.1f * kotlin.math.tanh(((a - 0.9f) / 0.1f).toDouble()).toFloat()
            return if (x < 0) -s else s
        }
    }
}

/** Mono delay line with a lowpassed feedback path (the arpeggio's dotted-eighth echo). */
class EchoLine(sr: Int) {
    private val buf = FloatArray((sr * 1.5).toInt() + 2)
    private var w = 0
    var delaySamples = sr * 0.4
    var target = delaySamples
    private val lp = Biquad().also { it.set(F_LOWPASS, 2400.0, 0.0, sr.toDouble()) }
    private val smooth = exp(-1.0 / (0.05 * sr))
    private var fbState = 0f

    /** Returns the wet output; `input` is the arp layer signal. */
    fun process(input: Float): Float {
        delaySamples = target + (delaySamples - target) * smooth
        val d = delaySamples.coerceIn(1.0, buf.size - 2.0)
        var rp = w - d
        while (rp < 0) rp += buf.size
        val i0 = rp.toInt(); val f = (rp - i0).toFloat()
        val i1 = if (i0 + 1 >= buf.size) 0 else i0 + 1
        val y = buf[i0] + (buf[i1] - buf[i0]) * f
        val toned = lp.process(y)
        fbState = toned
        buf[w] = input + toned * 0.38f
        if (++w >= buf.size) w = 0
        return toned * 0.45f
    }
}

/** RMS follower of the music bus below ~500 Hz (the analyser's bass bins). */
class BassMeter(sr: Int) {
    private val lp1 = Biquad().also { it.set(F_LOWPASS, 450.0, 0.0, sr.toDouble()) }
    private val lp2 = Biquad().also { it.set(F_LOWPASS, 450.0, 0.0, sr.toDouble()) }
    private val k = exp(-1.0 / (0.03 * sr)).toFloat()
    private var ms = 0f
    fun process(x: Float) { val y = lp2.process(lp1.process(x)); ms = y * y + (ms - y * y) * k }
    /** 0..1, as (bin0 + bin1 + 0.6·bin2) / (255·2.6) on the −100…−30 dB byte scale. */
    fun level(): Double {
        val db = 10 * log10(ms.toDouble() + 1e-12) + 6
        return ((db + 100) / 70).coerceIn(0.0, 1.0).let { (it - 0.35).coerceAtLeast(0.0) / 0.65 }
    }
}

