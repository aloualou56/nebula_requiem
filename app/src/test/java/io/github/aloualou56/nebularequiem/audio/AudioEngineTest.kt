package io.github.aloualou56.nebularequiem.audio

import android.media.AudioTrack
import io.github.aloualou56.nebularequiem.core.AudioSink
import io.github.aloualou56.nebularequiem.core.Env
import io.github.aloualou56.nebularequiem.core.Game
import io.github.aloualou56.nebularequiem.core.GameState
import io.github.aloualou56.nebularequiem.core.MusicMode
import io.github.aloualou56.nebularequiem.core.Save
import io.github.aloualou56.nebularequiem.core.Sfx
import io.github.aloualou56.nebularequiem.core.TestEnv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The audio engine against outputs that fail the way real ones do (one that stops taking sound, one
 * that dies, one that throws, none at all), and the score through a wave, a guardian and the next wave.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioEngineTest {

    /** An output that plays in real time: its buffer holds [capacityFrames] and its speaker drains it at the sample rate. */
    private class FakeOutput(private val sr: Int, override val capacityFrames: Int = 1024) : PcmOutput {
        private val born = System.nanoTime()
        @Volatile var taken = 0L                         // frames accepted
        @Volatile var firstAt = 0L                       // when it first and last took sound
        @Volatile var lastAt = 0L
        @Volatile var speakerStopsAt = Long.MAX_VALUE    // frames after which its speaker plays nothing more (a track the system dropped)
        @Volatile var failsAt = Long.MAX_VALUE           // frames after which write() reports a dead track
        @Volatile var throwsAt = Long.MAX_VALUE          // frames after which write() throws
        @Volatile var paused = false
        @Volatile var released = false
        @Volatile var underrunsNow = 0
        @Volatile var grown = 0
        private var energy = 0.0
        private var samples = 0L

        @Synchronized fun rms() = if (samples == 0L) 0.0 else sqrt(energy / samples)

        @Synchronized override fun write(data: FloatArray, offset: Int, size: Int): Int {
            check(!released) { "write after release" }
            if (taken >= throwsAt) throw IllegalStateException("simulated failure")
            if (taken >= failsAt) return AudioTrack.ERROR_DEAD_OBJECT
            if (paused) return 0
            val played = min(speakerStopsAt, (System.nanoTime() - born) * sr / 1_000_000_000L)
            val room = capacityFrames - max(0L, taken - played)
            val n = min(room, (size / 2).toLong()).toInt()
            if (n <= 0) return 0
            for (i in offset until offset + n * 2) { val x = data[i].toDouble(); energy += x * x }
            samples += n * 2
            val now = System.nanoTime()
            if (taken == 0L) firstAt = now
            lastAt = now
            taken += n
            return n * 2
        }

        override fun play() { paused = false }
        override fun pause() { paused = true }
        override fun release() { released = true }
        override val underruns: Int get() = underrunsNow
        override fun grow(frames: Int): Boolean { grown++; return true }
    }

    private val engines = ArrayList<AudioEngine>()
    private val defaultAudio = Env.audio

    @After
    fun tearDown() {
        for (e in engines) e.release()
        Env.audio = defaultAudio
    }

    private fun engine(): AudioEngine = AudioEngine(RuntimeEnvironment.getApplication()).also { engines.add(it) }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) {
            if (cond()) return true
            Thread.sleep(5)
        }
        return cond()
    }

    /** An engine whose outputs are [outputs] in turn (the last one again once they run out). */
    private fun playing(vararg outputs: FakeOutput): Pair<AudioEngine, List<FakeOutput>> {
        val eng = engine()
        val opened = CopyOnWriteArrayList<FakeOutput>()
        eng.openOutput = { outputs[min(opened.size, outputs.size - 1)].also { opened.add(it) } }
        eng.setMusic(MusicMode.GAME)
        eng.start()
        return eng to opened
    }

    @Test
    fun anOutputThatStopsTakingSoundIsReplacedAndTheScorePlaysOn() {
        val sr = engine().sr
        val first = FakeOutput(sr).apply { speakerStopsAt = sr / 2L }   // plays half a second, then nothing
        val second = FakeOutput(sr)
        val (_, opened) = playing(first, second)
        assertTrue("a new output takes over", waitFor(6000) { second.taken >= sr })
        assertTrue("the stuck one is let go", first.released)
        assertEquals(2, opened.size)
        val silentMs = (second.firstAt - first.lastAt) / 1e6
        assertTrue("nothing played for $silentMs ms", silentMs < 1500)
        assertTrue("the score plays on (rms ${second.rms()})", second.rms() > 0.005)
    }

    @Test
    fun aDeadOutputIsReplacedAtOnce() {
        val sr = engine().sr
        val first = FakeOutput(sr).apply { failsAt = sr / 4L }
        val second = FakeOutput(sr)
        playing(first, second)
        assertTrue("a new output takes over", waitFor(4000) { second.taken >= sr / 2 })
        assertTrue(first.released)
        val silentMs = (second.firstAt - first.lastAt) / 1e6
        assertTrue("nothing played for $silentMs ms", silentMs < 400)
    }

    @Test
    fun anOutputThatThrowsIsReplacedAndTheAudioThreadLivesOn() {
        val sr = engine().sr
        val first = FakeOutput(sr).apply { throwsAt = sr / 4L }
        val second = FakeOutput(sr)
        val (eng, _) = playing(first, second)
        assertTrue("a new output takes over", waitFor(4000) { second.taken >= sr / 2 })
        assertTrue(first.released)
        assertTrue(eng.running)
    }

    @Test
    fun withNoOutputTheSynthKeepsRealTimeAndKeepsAskingForOne() {
        val eng = engine()
        var asks = 0
        eng.openOutput = { asks++; null }
        eng.setMusic(MusicMode.GAME)
        eng.start()
        Thread.sleep(200)
        repeat(3000) { eng.play(Sfx.HIT, 0.0, 1.0, 1.0) }   // more than the queue holds: the rest are dropped
        val t0 = System.nanoTime(); val r0 = eng.rendered
        Thread.sleep(1000)
        val secs = (System.nanoTime() - t0) / 1e9
        val synthesized = (eng.rendered - r0) / eng.sr.toDouble()
        assertTrue("synthesized $synthesized s in $secs s", synthesized in secs * 0.7..secs * 1.3)
        assertTrue("queued sounds are played (into nothing), not piled up", waitFor(500) { eng.queued == 0 })
        assertTrue("asked for an output $asks times", asks >= 3)
    }

    @Test
    fun pauseSilencesTheOutputAndResumePlaysOn() {
        val sr = engine().sr
        val out = FakeOutput(sr)
        val (eng, _) = playing(out)
        assertTrue(waitFor(3000) { out.taken > sr / 10 })
        eng.pause()
        assertTrue("silenced at once", out.paused)
        assertFalse(eng.running)
        Thread.sleep(100)
        val held = out.taken
        Thread.sleep(300)
        assertEquals("nothing is written while paused", held, out.taken)
        eng.resume()
        assertTrue("playing again", waitFor(3000) { out.taken > held + sr / 10 })
        assertFalse(out.paused)
        assertTrue(eng.running)
    }

    @Test
    fun underrunsLetTheBufferGrow() {
        val sr = engine().sr
        val out = FakeOutput(sr)
        playing(out)
        assertTrue(waitFor(3000) { out.taken > sr / 10 })
        Thread.sleep(700)
        assertEquals("no underruns, no growth", 0, out.grown)
        out.underrunsNow = 3
        assertTrue("an underrun lets the buffer grow", waitFor(2000) { out.grown >= 1 })
        val grown = out.grown
        Thread.sleep(1200)
        assertEquals("and only while they keep coming", grown, out.grown)
    }

    @Test
    fun theScoreIsNeverLostToAFullQueueOfSounds() {
        val eng = engine()   // its thread never runs: blocks are rendered here
        repeat(3 * 512) { eng.play(Sfx.SHOOT, 0.0, 1.0, 1.0) }   // the queue is full; the rest are dropped
        eng.setMusic(MusicMode.BOSS)
        eng.setVolumes(0.8, 0.55, 0.0)   // effects muted: what plays is the score
        eng.render()
        assertEquals(MusicMode.BOSS, eng.musicMode)
        var e = 0.0; var n = 0
        while (eng.rendered < eng.sr * 2L) {
            eng.render()
            if (eng.rendered > eng.sr) for (x in eng.lastBlock) { e += x * x; n++ }
        }
        assertTrue("the guardian's score plays (rms ${sqrt(e / n)})", sqrt(e / n) > 0.005)
    }

    /**
     * Everything a run asks of the audio through a wave, the first guardian and the next wave, played
     * back through the engine with the effects muted: the score never falls quiet for a second.
     */
    @Test
    fun theScoreNeverFallsQuietThroughAWaveAGuardianAndTheNextWave() {
        class Ev(val t: Double, val music: MusicMode?, val sfx: Sfx?, val pan: Double, val pitch: Double, val size: Double)
        val evs = ArrayList<Ev>()
        TestEnv.setup()
        Env.audio = object : AudioSink {
            override fun play(sfx: Sfx, pan: Double, pitch: Double, size: Double) { evs.add(Ev(TestEnv.nowMs / 1000, null, sfx, pan, pitch, size)) }
            override fun setMusic(mode: MusicMode) { evs.add(Ev(TestEnv.nowMs / 1000, mode, null, 0.0, 0.0, 0.0)) }
            override fun setVolumes(master: Double, music: Double, sfx: Double) {}
            override val bassLevel: Double get() = 0.0
            override val beatCount: Int get() = 0
            override val running: Boolean get() = true
        }
        Save.data.upgrades["reactor"] = 6; Save.data.upgrades["hull"] = 5
        Game.startRun(null)
        var bossT = 0.0; var fellAt = -1.0
        while (TestEnv.nowMs < 150_000 && (fellAt < 0 || TestEnv.nowMs / 1000 < fellAt + 10)) {
            TestEnv.frames(1, 1.0 / 60) {
                val p = Game.player
                if (p != null) { p.iframes = 5.0; if (p.hp < 1) p.hp = 1 }
                if (Game.state == GameState.DRAFT) Game.pickPerk(0)
            }
            val b = Game.boss
            if (b != null && !b.dead) { bossT += 1.0 / 60; if (bossT > 14 && !b.invulnerable) b.hurt(b.maxHp * 0.005) }
            // the guardian has fallen once the score has gone from the guardian's back to the waves'
            if (fellAt < 0 && bossT > 0 && evs.lastOrNull { it.music != null }?.music == MusicMode.GAME) fellAt = TestEnv.nowMs / 1000
        }
        val modes = evs.mapNotNull { it.music }
        assertTrue("the run met its first guardian and went on: $modes", fellAt > 0 && MusicMode.BOSS in modes && modes.last() == MusicMode.GAME)

        val eng = engine()
        eng.setVolumes(0.8, 0.55, 0.0)   // effects muted: what plays is the score
        val sr = eng.sr.toDouble()
        val end = TestEnv.nowMs / 1000
        var i = 0
        var mode = MusicMode.OFF
        var winE = 0.0; var winN = 0; var winStart = 0.0
        var quiet = 0.0; var longest = 0.0; var longestAt = 0.0
        while (eng.rendered / sr < end) {
            val now = eng.rendered / sr
            while (i < evs.size && evs[i].t <= now) {
                val e = evs[i++]
                if (e.music != null) { mode = e.music; eng.setMusic(mode) } else eng.play(e.sfx!!, e.pan, e.pitch, e.size)
            }
            eng.render()
            for (x in eng.lastBlock) { winE += x * x; winN++ }
            if (eng.rendered / sr - winStart >= 0.25) {
                val rms = sqrt(winE / winN)
                if ((mode == MusicMode.GAME || mode == MusicMode.BOSS) && rms < 1e-3) {
                    quiet += 0.25
                    if (quiet > longest) { longest = quiet; longestAt = winStart }
                } else quiet = 0.0
                winE = 0.0; winN = 0; winStart = eng.rendered / sr
            }
        }
        assertTrue("the score fell quiet for $longest s at $longestAt s", longest < 1.0)
    }
}
