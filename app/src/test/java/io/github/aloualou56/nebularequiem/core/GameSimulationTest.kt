package io.github.aloualou56.nebularequiem.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless gameplay tests: the whole simulation (director, every hostile, all ten guardians,
 * grafts, pickups, collisions, the victory) runs on the JVM with no Android dependencies.
 */
class GameSimulationTest {

    private fun godMode() {
        val p = Game.player ?: return
        p.iframes = 5.0
        if (p.hp < 1) p.hp = 1
    }

    private fun pickFirstGraft() { if (Game.state == GameState.DRAFT) Game.pickPerk(0) }

    /** Wound a guardian once it has fought for a while, so a whole campaign stays quick to simulate. */
    private fun hurry(bossT: Double) {
        val b = Game.boss ?: return
        if (bossT > 14 && !b.invulnerable) b.hurt(b.maxHp * 0.005)
    }

    @Test
    fun fullCampaignWinsAfterTenGuardians() {
        val store = TestEnv.setup()
        val save = Save.data
        save.upgrades["reactor"] = 6; save.upgrades["hull"] = 5
        Game.startRun(null)
        assertEquals(GameState.PLAYING, Game.state)
        val seen = HashSet<String>()
        val guardians = ArrayList<String>()
        val normalWaves = HashMap<Int, MutableSet<Int>>()
        var lastBoss: Boss? = null
        var bossT = 0.0
        // the HP of everything each wave brought in (Mitosis daughters and elites included), and each guardian's
        val waveHp = HashMap<Int, DoubleArray>()
        val guardianHp = DoubleArray(Cfg.CAMPAIGN_SECTORS + 1); val guardianTempo = DoubleArray(Cfg.CAMPAIGN_SECTORS + 1)
        val metIds = HashSet<Int>()
        var maxBullets = 0
        var maxSector = 0
        var warpedAfterWin = false
        var steps = 0
        // Up to 90 simulated minutes at 60 Hz.
        while (steps < 60 * 60 * 90 && !(Game.state == GameState.VICTORY && Game.victoryStage == VictoryStage.ENDING)) {
            TestEnv.frames(1, 1.0 / 60) { godMode(); pickFirstGraft() }
            steps++
            val d = Game.director
            maxSector = maxOf(maxSector, d.sector)
            if (Game.state == GameState.VICTORY && d.state == DirState.WARP) warpedAfterWin = true
            if (Game.state == GameState.PLAYING && !d.bossWave && d.wave >= 1) normalWaves.getOrPut(d.sector) { HashSet() }.add(d.wave)
            if (!d.bossWave && d.wave in 1..3) for (e in Game.enemies) if (!e.isBoss && metIds.add(e.id)) waveHp.getOrPut(d.sector) { DoubleArray(4) }[d.wave] += e.maxHp
            val b = Game.boss
            if (b != null && b !== lastBoss) {
                lastBoss = b; bossT = 0.0
                guardians.add(b.bdef.id)
                assertEquals("the guardian is the sector's fourth wave", Cfg.BOSS_EVERY, d.wave)
                assertEquals("sector ${d.sector}'s guardian", BOSS_DEFS[d.sector - 1].id, b.bdef.id)
                guardianHp[d.sector] = b.maxHp; guardianTempo[d.sector] = b.tempo
            }
            if (b != null && b.introT <= 0) { bossT += 1.0 / 60; hurry(bossT) }
            seen.addAll(Game.enemies.map { it.type })
            maxBullets = maxOf(maxBullets, Game.ebullets.count)
        }
        assertEquals("the run reached the ending", VictoryStage.ENDING, Game.victoryStage)
        assertEquals(GameState.VICTORY, Game.state)
        assertEquals("the ten guardians in order", BOSS_DEFS.map { it.id }, guardians)
        for (sector in 1..Cfg.CAMPAIGN_SECTORS) assertEquals("sector $sector's normal waves", setOf(1, 2, 3), normalWaves[sector])
        assertTrue("all sixteen hostiles met: $seen", seen.containsAll(ENEMY_DEFS.keys))
        assertEquals("no sector after the last", Cfg.CAMPAIGN_SECTORS, maxSector)
        assertTrue("no warp after the final guardian", !warpedAfterWin)
        assertEquals("nothing raises the ascension", 0, Game.director.ascension)
        assertEquals("one mutator for each of sectors 2–7", 6, Game.director.mutators.size)
        // every guardian is its sector's toughest fight, and each is tougher than the one before
        for (sector in 1..Cfg.CAMPAIGN_SECTORS) {
            val biggest = waveHp.getValue(sector).max()
            assertTrue("sector $sector: guardian ${guardianHp[sector]} HP against waves of up to $biggest", guardianHp[sector] >= 1.25 * biggest)
            if (sector > 1) {
                assertTrue("sector $sector's guardian outlasts sector ${sector - 1}'s", guardianHp[sector] > guardianHp[sector - 1])
                assertTrue("sector $sector's guardian attacks faster", guardianTempo[sector] > guardianTempo[sector - 1])
            }
        }
        assertEquals(1.0, guardianTempo[1], 0.0)
        assertTrue(maxBullets <= Cfg.MAX_ENEMY_BULLETS)
        assertTrue(Fx.count <= Quality.HIGH.maxParticles + 200)
        // banked exactly once, with no resumable checkpoint
        val summary = Game.victorySummary!!
        assertEquals(10, summary.bosses)
        assertTrue(summary.dust > 0)
        assertEquals(summary.dust.toDouble(), Save.data.stardust, 0.0)
        assertEquals(1.0, Save.data.stats.runs, 0.0)
        assertEquals(10.0, Save.data.stats.bossKills, 0.0)
        assertEquals(10.0, Save.data.stats.bestSector, 0.0)
        assertEquals("one requiem complete", 1.0, Save.data.stats.requiems, 0.0)
        assertEquals("a story run sets no endless record", 0.0, Save.data.stats.endlessBestSector, 0.0)
        assertTrue(Save.data.run == null)
        assertTrue("the checkpoint is gone from storage too", !store.text!!.contains("\"run\""))
        // the ending plays on, then the pilot goes home: nothing is banked again
        TestEnv.seconds(5.0, 60)
        Game.toMenu(GameState.TITLE)
        TestEnv.seconds(1.0, 60)
        assertEquals(GameState.TITLE, Game.state)
        assertEquals(summary.dust.toDouble(), Save.data.stardust, 0.0)
        assertEquals(1.0, Save.data.stats.runs, 0.0)
        assertTrue(Game.run == null && Game.player == null)
    }

    /** Resume a checkpoint at the final guardian and break it as soon as it can be hurt. */
    private fun breakFinalGuardian(score: Double = 50000.0) {
        Save.data.run = RunCheckpoint().apply { hull = "lancer"; hp = 5; sector = Cfg.CAMPAIGN_SECTORS; wave = Cfg.BOSS_EVERY; bossKills = 9; this.score = score; kills = 900 }
        Game.resumeCheckpoint()
        assertEquals(GameState.PLAYING, Game.state)
        var guard = 0
        while ((Game.boss == null || Game.boss!!.invulnerable) && guard++ < 60 * 10) TestEnv.frames(1, 1.0 / 60) { godMode() }
        val b = Game.boss!!
        assertEquals("eidolon", b.bdef.id)
        b.hurt(1e9)
        assertEquals("won the moment it breaks", GameState.VICTORY, Game.state)
    }

    @Test
    fun theWinIsLockedInTheMomentTheFinalGuardianBreaks() {
        TestEnv.setup()
        // a straggler from the last wave and a live bullet are cleared at once, without rewards
        breakFinalGuardian()
        assertTrue(Game.ebullets.count == 0)
        val p = Game.player!!
        p.iframes = 0.0; p.shield = 0; p.hp = 1
        // a hostile on the ship and a bullet through it do nothing now
        Game.spawnEnemy("aegis", p.x, p.y, SpawnOpts().apply { instant = true })
        Game.ebullets.fire(p.x - 20, p.y, 0.0, 40.0, BulletOpts())
        TestEnv.seconds(0.5, 60)
        assertTrue("the pilot can't be hurt", p.alive && p.hp == 1)
        for (e in Game.enemies) if (!e.isBoss) e.dead = true
        assertEquals(GameState.VICTORY, Game.state)
        // no pause, no abandon
        Game.pause(); Game.togglePause(); Game.abandon()
        assertEquals(GameState.VICTORY, Game.state)
        assertTrue(Save.data.stats.runs == 0.0)
        // the guardian's death: its rewards drop and the run banks, but no draft and no warp
        Game.pendingDrafts = 0
        while (Game.victoryStage == VictoryStage.BREAKING) TestEnv.frames(1, 1.0 / 60)
        TestEnv.seconds(1.0, 60)
        assertEquals(VictoryStage.PAUSE, Game.victoryStage)
        assertTrue(Game.draft == null && Game.state == GameState.VICTORY)
        assertTrue(Game.director.state != DirState.BOSS_CLEAR && Game.director.state != DirState.WARP)
        assertEquals(1.0, Save.data.stats.runs, 0.0)
        assertEquals(MusicMode.MENU, Game.musicMode())
    }

    @Test
    fun appKillDuringTheVictoryPauseKeepsTheWin() {
        val store = TestEnv.setup()
        Save.data.stardust = 100.0
        breakFinalGuardian()
        // the guardian's death throes, then its rewards: the motes are still in flight when the run banks
        var inFlight = 0
        while (Game.victoryStage == VictoryStage.BREAKING) {
            TestEnv.frames(1, 1.0 / 60)
            if (Game.victoryStage == VictoryStage.PAUSE) inFlight = Game.pickups.list.count { it.kind == PickupKind.DUST }
        }
        assertTrue("stardust motes in flight when the run banked ($inFlight)", inFlight > 20)
        val summary = Game.victorySummary!!
        TestEnv.seconds(1.0, 60)
        assertEquals(VictoryStage.PAUSE, Game.victoryStage)
        // the process dies here: a fresh launch reads what reached storage
        val text = store.text!!
        TestEnv.setup()
        Save.store = TestEnv.MemStore(text)
        Save.load()
        assertEquals("the win's stardust was banked", 100.0 + summary.dust, Save.data.stardust, 0.0)
        assertEquals(1.0, Save.data.stats.runs, 0.0)
        assertEquals(10.0, Save.data.stats.bossKills, 0.0)
        assertTrue("no run to resume", Save.data.run == null)
    }

    @Test
    fun theVictoryBanksTheMotesStillInFlightExactlyOnce() {
        val store = TestEnv.setup()
        breakFinalGuardian(score = 77777.0)
        while (Game.victoryStage == VictoryStage.BREAKING) TestEnv.frames(1, 1.0 / 60)
        val summary = Game.victorySummary!!
        val banked = Save.data.stardust
        assertEquals(summary.dust.toDouble(), banked, 0.0)
        val writes = store.writes
        // let every mote land: what the run collects adds up to what was banked for it
        TestEnv.seconds(Game.VICTORY_PAUSE - 0.2, 60)
        assertEquals(VictoryStage.PAUSE, Game.victoryStage)
        assertEquals("all landed", 0, Game.pickups.list.count { it.kind == PickupKind.DUST || it.kind == PickupKind.GEM })
        val r = Game.run!!; val p = Game.player!!
        val formula = kotlin.math.floor(r.dust + (r.score / 220) * p.stats.dustMul * Game.director.dustMul + r.bossKills * 40).toInt()
        assertEquals("the banked stardust is what the landed motes add up to", formula, summary.dust)
        assertEquals("not banked again", banked, Save.data.stardust, 0.0)
        // the warp and the ending write nothing more
        TestEnv.seconds(3.0, 60)
        assertEquals(VictoryStage.ENDING, Game.victoryStage)
        assertTrue(Game.run == null)
        assertEquals(banked, Save.data.stardust, 0.0)
        assertEquals(writes, store.writes)
    }

    @Test
    fun aPilotGoingDownWhenTheFinalGuardianBreaksStillLoses() {
        TestEnv.setup()
        Save.data.run = RunCheckpoint().apply { hull = "lancer"; hp = 5; sector = Cfg.CAMPAIGN_SECTORS; wave = Cfg.BOSS_EVERY }
        Game.resumeCheckpoint()
        while (Game.boss == null || Game.boss!!.invulnerable) TestEnv.frames(1, 1.0 / 60) { godMode() }
        val p = Game.player!!
        p.iframes = 0.0; p.shield = 0; p.hp = 1; p.hurt()
        assertEquals(GameState.DYING, Game.state)
        Game.boss!!.hurt(1e9)
        TestEnv.seconds(4.0, 60)
        assertEquals(GameState.OVER, Game.state)
        assertTrue(Game.director.state != DirState.WARP && Game.director.sector == Cfg.CAMPAIGN_SECTORS)
        assertEquals(1.0, Save.data.stats.runs, 0.0)
    }

    /** Per-step state log of a 45 s run with auto-fire and auto-aim at the given display rate. */
    private fun scriptedRun(hz: Int, seconds: Double): List<String> {
        TestEnv.setup(seed = 777)
        val log = ArrayList<String>()
        Game.stepHook = {
            val r = Game.run!!; val p = Game.player!!
            log.add("${r.score}|${r.kills}|${r.grazes}|${r.level}|${p.hp}|${p.x}|${p.y}|${p.angle}|${Game.ebullets.count}|${Game.enemies.size}|${Game.director.wave}|${Rng.game.state()}")
        }
        Game.startRun(null)
        TestEnv.seconds(seconds, hz) { pickFirstGraft() }
        Game.stepHook = null
        return log
    }

    @Test
    fun simulationIsIdenticalAt60_90_120_144_240Hz() {
        val ref = scriptedRun(60, 45.0)
        assertTrue(ref.size > 4000)
        for (hz in intArrayOf(90, 120, 144, 165, 240)) {
            val other = scriptedRun(hz, 45.0)
            val n = minOf(ref.size, other.size)
            assertTrue("step counts agree within one at $hz Hz", kotlin.math.abs(ref.size - other.size) <= 1)
            for (i in 0 until n) assertEquals("step $i at $hz Hz", ref[i], other[i])
        }
    }

    @Test
    fun fixedStepNeverSpirals() {
        TestEnv.setup()
        Game.startRun(null)
        // A 2-second hitch is clamped to MAX_STEPS steps.
        TestEnv.frames(1, 0.25)
        assertTrue(Game.stepsThisFrame <= Cfg.MAX_STEPS)
        TestEnv.frames(1, 2.0)
        assertTrue(Game.stepsThisFrame <= Cfg.MAX_STEPS)
    }

    @Test
    fun everyHostileArchetypeBehaves() {
        for (type in ENEMY_DEFS.keys) {
            TestEnv.setup()
            Game.startRun(null)
            Game.enemies.clear(); Game.director.state = DirState.IDLE
            val o = SpawnOpts().apply { elite = true }
            Game.spawnEnemy(type, World.w * 0.5, World.h * 0.3, o)
            TestEnv.seconds(6.0, 60) { godMode() }
            assertTrue("$type fired or acted", Game.ebullets.count > 0 || Game.run!!.kills > 0 || Game.enemies.isNotEmpty())
        }
    }

    @Test
    fun mitosisDividesTwice() {
        TestEnv.setup()
        Game.startRun(null)
        Game.enemies.clear(); Game.director.state = DirState.IDLE
        val e = Game.spawnEnemy("mitosis", World.w * 0.5, World.h * 0.3, SpawnOpts().apply { instant = true })
        e.hurt(1e9)
        TestEnv.frames(2, 1.0 / 60)
        assertEquals(2, Game.enemies.count { it.type == "mitosis" && !it.dead })
    }

    @Test
    fun aegisShieldBlocksFrontalShots() {
        TestEnv.setup()
        Game.startRun(null)
        val a = AegisWarden()
        a.setup(ENEMY_DEFS.getValue("aegis"), 500.0, 300.0, SpawnOpts().apply { instant = true })
        a.shieldA = 0.0
        assertTrue(a.blocks(500.0 + a.r + 5, 300.0))
        assertTrue(!a.blocks(500.0 - a.r - 5, 300.0))
    }

    @Test
    fun guardiansRunEveryPhase() {
        for (i in BOSS_DEFS.indices) {
            TestEnv.setup()
            Save.data.settings.autofire = false   // only the test moves its health along
            Game.startRun(null)
            Game.enemies.clear(); Game.director.state = DirState.BOSS
            val b = Boss.create(i)
            assertEquals(BOSS_DEFS[i].id, b.bdef.id)
            Game.enemies.add(b); Game.boss = b
            var peak = 0
            TestEnv.seconds(b.introDur, 60) { godMode() }
            for (ph in b.phases.indices) {
                if (ph > 0) b.hp = b.maxHp * (b.phases[ph - 1].until - 0.02)
                val attacks = HashSet<Int>()
                val cycle = b.phases[ph].attacks.sumOf { it.dur + it.rest } + 3.0
                TestEnv.seconds(cycle, 60) {
                    godMode()
                    peak = maxOf(peak, Game.ebullets.count)
                    if (b.phase == ph && b.phaseShift <= 0 && b.rest <= 0) attacks.add(b.attackIdx)
                }
                assertEquals("${b.bdef.id} reached phase ${ph + 1}", ph, b.phase)
                assertEquals("${b.bdef.id} ran every attack of phase ${ph + 1}", b.phases[ph].attacks.indices.toSet(), attacks)
            }
            assertTrue("${b.bdef.id} emitted bullets", peak > 0)
            assertTrue("${b.bdef.id} stays well under the bullet cap ($peak)", peak < Cfg.MAX_ENEMY_BULLETS / 2)
            b.hurt(1e9)
            TestEnv.seconds(4.0, 60) { godMode(); pickFirstGraft() }
            assertTrue("${b.bdef.id} died", b.dead)
            assertEquals(1, Game.run!!.bossKills)
        }
    }

    /** Per-step log of a guardian's fight (the test moves it into each later phase at fixed steps). */
    private fun guardianFight(index: Int, hz: Int, seconds: Double): List<String> {
        TestEnv.setup(seed = 4242)
        Save.data.settings.autofire = false
        Game.startRun(null)
        Game.enemies.clear(); Game.director.state = DirState.BOSS
        val b = Boss.create(index)
        Game.enemies.add(b); Game.boss = b
        val log = ArrayList<String>()
        var steps = 0
        Game.stepHook = {
            steps++
            val p = Game.player!!
            p.iframes = 5.0
            for (ph in 1 until b.phases.size) if (steps == ph * 1800) b.hp = b.maxHp * (b.phases[ph - 1].until - 0.02)
            log.add("${b.x}|${b.y}|${b.hp}|${b.phase}|${b.attackIdx}|${b.attackT}|${b.angle}|${Game.ebullets.count}|${Game.lasers.size}|${p.x}|${p.y}|${p.hp}|${Rng.game.state()}")
        }
        TestEnv.seconds(seconds, hz)
        Game.stepHook = null
        return log
    }

    @Test
    fun newGuardiansAreIdenticalAt60And144Hz() {
        for (i in 4 until BOSS_DEFS.size) {
            val phases = if (i == BOSS_DEFS.size - 1) 3 else 2
            val seconds = 15.0 * (phases - 1) + 20.0
            val ref = guardianFight(i, 60, seconds)
            val other = guardianFight(i, 144, seconds)
            assertTrue("${BOSS_DEFS[i].id}: ${ref.size} steps", ref.size > 120 * 30)
            assertTrue("${BOSS_DEFS[i].id}: step counts agree within one", kotlin.math.abs(ref.size - other.size) <= 1)
            for (k in 0 until minOf(ref.size, other.size)) assertEquals("${BOSS_DEFS[i].id} step $k", ref[k], other[k])
        }
    }

    @Test
    fun deathEndsRunAndBanksStardust() {
        TestEnv.setup()
        Game.startRun(null)
        TestEnv.seconds(2.0, 60)
        val p = Game.player!!
        Game.run!!.score = 22000.0
        p.iframes = 0.0; p.shield = 0; p.hp = 1
        p.hurt()
        assertEquals(GameState.DYING, Game.state)
        TestEnv.seconds(3.0, 60)
        assertEquals(GameState.OVER, Game.state)
        assertTrue(Save.data.stardust >= 100)
        assertEquals(1.0, Save.data.stats.runs, 0.0)
        assertTrue(Save.data.run == null)
    }

    @Test
    fun checkpointResumesRun() {
        val store = TestEnv.setup()
        Game.startRun(null)
        TestEnv.seconds(20.0, 60) { godMode(); pickFirstGraft() }
        Save.flush()
        val text = store.text!!
        // a fresh process: load the save and resume
        TestEnv.setup()
        Save.store = TestEnv.MemStore(text)
        Save.load()
        val c = Save.data.run!!
        Game.resumeCheckpoint()
        assertEquals(GameState.PLAYING, Game.state)
        assertEquals(c.sector, Game.director.sector)
        assertEquals(c.wave, Game.director.wave)
        assertEquals(c.score, Game.run!!.score, 0.0)
    }

    @Test
    fun aCheckpointFrom111AtItsOldGuardianWaveResumesAtTheGuardian() {
        TestEnv.setup()
        val d = Save.decode("""{"version":4,"stardust":10,"run":{"hull":"lancer","hp":4,"sector":3,"wave":5,"ascension":1,"score":5000}}""")!!
        Save.replace(d)
        Game.resumeCheckpoint()
        assertEquals(GameState.PLAYING, Game.state)
        assertEquals(3, Game.director.sector)
        assertTrue("straight into the guardian", Game.director.bossWave && Game.director.state == DirState.WARNING)
        assertEquals(1, Game.director.ascension)
        TestEnv.seconds(4.0, 60) { godMode() }
        assertEquals("fractal seraph guards sector 3", "seraph", Game.boss?.bdef?.id)
    }

    @Test
    fun overdriveNovaAndDashWork() {
        TestEnv.setup()
        Game.startRun(null)
        TestEnv.seconds(1.0, 60)
        val p = Game.player!!
        p.flux = 1.0
        Input.keyDown("KeyQ", false)
        TestEnv.frames(1, 1.0 / 60)
        Input.keyUp("KeyQ")
        assertTrue(p.overdrive > 6)
        val bombs = p.bombs
        Input.keyDown("KeyE", false)
        TestEnv.frames(1, 1.0 / 60)
        Input.keyUp("KeyE")
        assertEquals(bombs - 1, p.bombs)
        assertTrue(Game.nova != null)
        Input.keyDown("Space", false)
        TestEnv.frames(1, 1.0 / 60)
        Input.keyUp("Space")
        assertTrue(p.dashCd > 0)
    }

    @Test
    fun grazingChargesFlux() {
        TestEnv.setup()
        Game.startRun(null)
        TestEnv.seconds(0.5, 60)
        val p = Game.player!!
        p.iframes = 0.0
        val o = BulletOpts(r = 6.0)
        // a bullet passing 20 units beside the core: a graze, not a hit
        Game.ebullets.fire(p.x - 200, p.y - 20, 0.0, 400.0, o)
        val before = p.flux
        TestEnv.seconds(1.0, 60)
        assertTrue(p.flux > before)
        assertTrue(Game.run!!.grazes >= 1)
    }

    @Test
    fun rngMatchesOriginalJavaScript() {
        val r = Rng(1)
        val expected = doubleArrayOf(0.6270739405881613, 0.002735721180215478, 0.5274470399599522, 0.9810509674716741, 0.9683778982143849)
        for (e in expected) assertEquals(e, r.next(), 0.0)
        val r2 = Rng(4000000000L.toInt())
        assertEquals(0.6919068221468478, r2.next(), 0.0)
        assertEquals(0.5511944761965424, r2.next(), 0.0)
    }

    /** Bullets [index]'s guardian fires in its first [seconds] of attacks in [sector] (the pilot holds fire). */
    private fun guardianVolume(index: Int, sector: Int, seconds: Double): Long {
        TestEnv.setup(seed = 77)
        Save.data.settings.autofire = false
        Game.startRun(null)
        Game.enemies.clear(); Game.ebullets.clear()
        val d = Game.director
        d.sector = sector; d.wave = Cfg.BOSS_EVERY; d.recompute(); d.state = DirState.BOSS
        val b = Boss.create(index)
        Game.enemies.add(b); Game.boss = b
        TestEnv.seconds(b.introDur, 60) { godMode() }
        val before = Game.ebullets.fired
        TestEnv.seconds(seconds, 60) { godMode() }
        assertEquals("${b.bdef.id} stayed in its first phase", 0, b.phase)
        return Game.ebullets.fired - before
    }

    @Test
    fun deeperGuardiansAttackFaster() {
        for (i in BOSS_DEFS.indices) {
            val early = guardianVolume(i, 1, 20.0)
            val late = guardianVolume(i, Cfg.CAMPAIGN_SECTORS, 20.0)
            assertTrue("${BOSS_DEFS[i].id}: $late bullets in sector 10 against $early in sector 1", late >= 1.25 * early)
        }
    }

    /**
     * Over many runs (seeded waves, the mutators a run would have by then, elites and whole Mitosis
     * families), every sector's guardian has at least twice the HP of the sector's biggest wave on
     * average, and more than all but the rarest few: in story and far into endless.
     */
    @Test
    fun guardiansOutweighTheirSectorsBiggestWave() {
        TestEnv.setup()
        val d = Game.director
        val runs = 120
        // a Hive's brood comes with it
        val hiveBrood = HiveCarrier.BROOD * ENEMY_DEFS.getValue("mote").hp * HiveCarrier.DRONE_HP
        for (level in Difficulty.entries) for (sector in 1..30) {
            val biggest = DoubleArray(runs)
            for (run in 0 until runs) {
                Rng.game.seed(run * 7919 + sector)
                d.reset(); d.sector = sector; d.difficulty = level
                d.mutators.addAll(Rng.game.shuffle(MUTATORS.map { it.id }.toMutableList()).take(minOf(sector - 1, MUTATORS.size)))
                for (w in 1..3) {
                    d.wave = w; d.recompute(); d.buildWave()
                    var hp = 0.0
                    for (e in d.queue) for (k in 0 until minOf(e.count, 32)) {
                        // a Mitosis splits twice: 1 + 2·0.45 + 4·0.45² of its HP in all
                        hp += ENEMY_DEFS.getValue(e.type).hp * d.hpMul * (if (Rng.game.chance(d.eliteChance)) 2.6 else 1.0) * (if (e.type == "mitosis") 2.71 else 1.0)
                        if (e.type == "hive") hp += hiveBrood * d.hpMul
                    }
                    biggest[run] = maxOf(biggest[run], hp)
                }
            }
            biggest.sort()
            d.reset(); d.sector = sector; d.difficulty = level; d.wave = Cfg.BOSS_EVERY; d.recompute()
            val g = d.guardianHp()
            assertTrue("${level.label}, sector $sector: guardian $g against a mean biggest wave of ${biggest.average()}", g >= 2 * biggest.average())
            assertTrue("${level.label}, sector $sector: guardian $g against a 95th-percentile wave of ${biggest[runs * 95 / 100]}", g > biggest[runs * 95 / 100])
        }
    }

    @Test
    fun difficultyLevelsScaleTheDangerAndMediumIsTheGameAsTuned() {
        TestEnv.setup()
        val d = Game.director
        fun knobs(level: Difficulty, sector: Int, wave: Int): DoubleArray {
            d.reset(); d.difficulty = level; d.sector = sector; d.wave = wave; d.recompute()
            return doubleArrayOf(d.hpMul, d.fireMul, Game.bulletSpeedMul, d.countMul, d.guardianHp(), d.bossTempo, d.scoreMul)
        }
        for (sector in listOf(1, 3, 6, 10, 14)) for (wave in 1..4) {
            val medium = knobs(Difficulty.MEDIUM, sector, wave)
            // Medium is exactly the game as tuned
            d.reset(); d.sector = sector; d.wave = wave; d.recompute()
            assertTrue("medium at sector $sector wave $wave", medium.contentEquals(doubleArrayOf(d.hpMul, d.fireMul, Game.bulletSpeedMul, d.countMul, d.guardianHp(), d.bossTempo, d.scoreMul)))
            var last = DoubleArray(medium.size)
            for (level in Difficulty.entries) {
                val now = knobs(level, sector, wave)
                if (level.ordinal > 0) for (k in now.indices) assertTrue("${level.label} is harder than the level below at sector $sector (knob $k)", now[k] > last[k])
                last = now
            }
        }
        // a guardian grows with its waves' HP and their numbers both
        for (level in Difficulty.entries) assertEquals(level.hp * level.count, level.guardianHp, 1e-12)
    }

    @Test
    fun aRunKeepsItsDifficultyAndPlanThroughACheckpoint() {
        val store = TestEnv.setup()
        Game.startRun(null, RunMode.ENDLESS, Difficulty.MANIAC)
        assertEquals(Difficulty.MANIAC, Game.director.difficulty)
        TestEnv.seconds(1.0, 60) { godMode() }
        Save.flush()
        val text = store.text!!
        assertTrue(text.contains("\"difficulty\":\"maniac\""))
        TestEnv.setup()
        Save.store = TestEnv.MemStore(text)
        Save.load()
        Game.resumeCheckpoint()
        assertEquals(Difficulty.MANIAC, Game.director.difficulty)
        assertEquals(RunMode.ENDLESS, Game.director.mode)
        assertEquals(Difficulty.MANIAC, Game.lastDifficulty)
        // the game over says how it was flown
        val p = Game.player!!
        p.iframes = 0.0; p.shield = 0; p.hp = 1
        p.hurt()
        TestEnv.seconds(3.0, 60)
        assertEquals(GameState.OVER, Game.state)
    }

    @Test
    fun eachSectorAfterAGuardianDebutsAHostileInItsFirstWave() {
        TestEnv.setup()
        val d = Game.director
        val debuts = mapOf(2 to setOf("mitosis", "seer"), 3 to setOf("prism"), 4 to setOf("comet"), 5 to setOf("pulsar"), 6 to setOf("hive"),
            7 to setOf("vortex"), 8 to setOf("phantom"), 9 to setOf("carom"), 10 to setOf("harbinger"))
        for (level in Difficulty.entries) for ((sector, fresh) in debuts) for (seed in 1..25) {
            Rng.game.seed(seed)
            d.reset(); d.difficulty = level; d.sector = sector; d.wave = 1; d.recompute(); d.buildWave()
            val types = d.queue.map { it.type }.toSet()
            assertTrue("${level.label}, sector $sector, seed $seed: its first wave brings ${fresh - types}", types.containsAll(fresh))
            // and nothing in it came before its time
            for (t in types) assertTrue(ENEMY_DEFS.getValue(t).minD <= d.d)
        }
        // every hostile debuts by the last sector: none is left for later
        assertTrue(ENEMY_DEFS.values.all { it.minD <= (Cfg.CAMPAIGN_SECTORS - 1) * DIFFICULTY_STEPS_PER_SECTOR + 1 })
    }

    /** One hostile alone, met in its debut sector, for [seconds] (the pilot can't be hurt). */
    private fun meet(type: String, seconds: Double, elite: Boolean = false, each: ((Enemy) -> Unit)? = null): Enemy {
        TestEnv.setup(seed = 99)
        Save.data.settings.autofire = false
        Game.startRun(null)
        Game.enemies.clear(); Game.ebullets.clear()
        val d = Game.director
        d.sector = (ENEMY_DEFS.getValue(type).minD - 1) / DIFFICULTY_STEPS_PER_SECTOR + 1; d.wave = 1; d.recompute(); d.state = DirState.IDLE
        val e = Game.spawnEnemy(type, World.w * 0.5, World.h * 0.3, SpawnOpts().apply { this.elite = elite })
        TestEnv.seconds(seconds, 60) { godMode(); each?.invoke(e) }
        return e
    }

    @Test
    fun newHostilesUseTheirMechanics() {
        // Prism: its shards stop, then re-aim and fly again
        var retargeted = 0
        meet("prism", 6.0) { retargeted = maxOf(retargeted, Game.ebullets.list.count { it.retargeted && it.retargetAt > 0 && it.speed > 50 }) }
        assertTrue("prism shards re-aim ($retargeted)", retargeted >= 3)
        // Comet: sights, streaks, leaves a wake, and comes back in
        var streaked = false; var wake = 0L
        meet("comet", 8.0) { e -> e as CometStreak; if (e.state == 1) { streaked = true; wake = Game.ebullets.fired } }
        assertTrue("the comet streaked and left a wake", streaked && wake > 5)
        // Pulsar: a dense ring with one gap where it showed
        var gapClear = false; var pulsed = false
        meet("pulsar", 6.0) { e ->
            e as PulsarStar
            if (!pulsed && Game.ebullets.count >= 20) {
                pulsed = true
                gapClear = Game.ebullets.list.none { b -> kotlin.math.abs(angleDiff(b.angle, e.gapA)) < 0.12 }
            }
        }
        assertTrue("the pulsar pulsed with a gap", pulsed && gapClear)
        // Hive: launches its brood of drones and no more
        val drones = HashSet<Int>()
        val hive = meet("hive", 30.0) { for (m in Game.enemies) if (m.type == "mote") drones.add(m.id) } as HiveCarrier
        assertEquals("the brood is spent", 0, hive.brood)
        assertEquals("all its drones flew", HiveCarrier.BROOD, drones.size)
        // Vortex: shards orbit it, then fly off together
        var orbiting = 0; var released = 0
        meet("vortex", 7.0) { orbiting = maxOf(orbiting, Game.ebullets.list.count { it.orbitT > 0 }); released = maxOf(released, Game.ebullets.list.count { it.orbitT == 0.0 && it.speed > 50 }) }
        assertTrue("the vortex wound ($orbiting) and released ($released)", orbiting >= 10 && released >= 10)
        // Phantom: can't be hit while away, reappears beside the pilot, never on top
        var vanished = false; var reappeared = false
        meet("phantom", 8.0) { e ->
            e as PhantomWisp
            if (!e.active && e.phase == 2) vanished = true
            if (vanished && e.phase == 0) { reappeared = true; val p = Game.player!!; assertTrue("not on top of the pilot", dist2(e.x, e.y, p.x, p.y) > 150.0 * 150.0) }
            if (e.phase != 0) assertEquals("untouchable while phased", 0.0, e.hurt(10.0), 0.0)
        }
        assertTrue(vanished && reappeared)
        // Carom: its shots bank off the walls
        var banked = false
        meet("carom", 9.0) { if (Game.ebullets.list.any { it.bounces < 2 }) banked = true }
        assertTrue("a carom shot rebounded", banked)
        // Harbinger: sweeps a beam, which dies with it
        var beam: Laser? = null
        val h = meet("harbinger", 5.0) { e -> if (beam == null) beam = Game.lasers.firstOrNull { it.owner === e } }
        assertTrue("the harbinger swept a beam", beam != null)
        h.hurt(1e9)
        TestEnv.seconds(0.2, 60)
        assertTrue("its beam went with it", Game.lasers.none { it.owner === h && !it.dead })
    }

    /** A late, crowded fight (every hostile available) logged per step. */
    private fun lateFight(hz: Int, seconds: Double): List<String> {
        TestEnv.setup(seed = 31337)
        Save.data.run = RunCheckpoint().apply { hull = "lancer"; hp = 5; sector = Cfg.CAMPAIGN_SECTORS; wave = 1 }
        Game.resumeCheckpoint()
        val log = ArrayList<String>()
        Game.stepHook = {
            Game.player!!.iframes = 5.0
            val e = Game.enemies
            log.add("${e.size}|${e.sumOf { it.x }}|${e.sumOf { it.y }}|${e.sumOf { it.hp }}|${Game.ebullets.count}|${Game.lasers.size}|${Game.player!!.x}|${Rng.game.state()}")
        }
        TestEnv.seconds(seconds, hz)
        Game.stepHook = null
        return log
    }

    @Test
    fun newHostilesAreIdenticalAt60And144Hz() {
        val ref = lateFight(60, 30.0)
        val other = lateFight(144, 30.0)
        assertTrue(ref.size > 120 * 25)
        assertTrue(kotlin.math.abs(ref.size - other.size) <= 1)
        for (k in 0 until minOf(ref.size, other.size)) assertEquals("step $k", ref[k], other[k])
    }

    @Test
    fun laterWavesAndGuardiansAreHarder() {
        TestEnv.setup()
        val d = Game.director
        fun at(sector: Int, wave: Int) { d.sector = sector; d.wave = wave; d.recompute() }
        var last = DoubleArray(5)
        for (step in 1..60) {
            at((step - 1) / 4 + 1, (step - 1) % 4 + 1)
            assertEquals(step, d.d)
            // waves are exactly what they were in 0.3
            val x = (step - 1).toDouble()
            assertEquals(1 + 0.11 * x + 0.0035 * x * x, d.hpMul, 1e-12)
            assertEquals(kotlin.math.min(2.2, 1 + 0.035 * x), d.fireMul, 1e-12)
            assertEquals(kotlin.math.min(1.55, 1 + 0.016 * x), Game.bulletSpeedMul, 1e-12)
            val now = doubleArrayOf(d.hpMul, d.fireMul, Game.bulletSpeedMul, d.guardianHp(), d.bossTempo)
            if (step > 1) for (k in now.indices) assertTrue("step $step, curve $k", now[k] >= last[k])
            last = now
        }
        // a guardian gets tougher every sector, and faster up to its cap
        for (sector in 2..20) {
            at(sector - 1, 4); val hp0 = d.guardianHp(); val t0 = d.bossTempo
            at(sector, 4)
            assertTrue("sector $sector", d.guardianHp() > hp0)
            assertTrue("sector $sector", d.bossTempo > t0 || d.bossTempo == GUARDIAN_TEMPO_MAX)
        }
        at(1, 4); assertEquals(1.0, d.bossTempo, 0.0)
        at(10, 4); assertEquals(1.45, d.bossTempo, 1e-9)
    }

    @Test
    fun endlessDealsAllTenGuardiansEveryTenSectorsInAShuffledOrder() {
        val n = BOSS_DEFS.size
        val firstCycles = HashSet<List<Int>>()
        for (seed in 1..40) {
            val order = (1..60).map { Director.endlessGuardian(seed, it) }
            for (c in 0 until 6) assertEquals("seed $seed, cycle $c", (0 until n).toSet(), order.subList(c * n, c * n + n).toSet())
            for (k in 1 until order.size) assertTrue("seed $seed: sectors $k and ${k + 1} differ", order[k] != order[k - 1])
            assertEquals("a pure function of the seed", order, (1..60).map { Director.endlessGuardian(seed, it) })
            firstCycles.add(order.subList(0, n))
        }
        assertTrue("the order varies between runs (${firstCycles.size} distinct)", firstCycles.size >= 30)
    }

    /** Resume an endless run at the guardian of [sector] and break it once it can be hurt. */
    private fun breakEndlessGuardian(sector: Int, seed: Int): Boss {
        Save.data.run = RunCheckpoint().apply { hull = "lancer"; hp = 5; this.sector = sector; wave = Cfg.BOSS_EVERY; bossKills = sector - 1; mode = RunMode.ENDLESS.id; this.seed = seed }
        Game.resumeCheckpoint()
        assertEquals(RunMode.ENDLESS, Game.director.mode)
        var guard = 0
        while ((Game.boss == null || Game.boss!!.invulnerable) && guard++ < 60 * 10) TestEnv.frames(1, 1.0 / 60) { godMode() }
        val b = Game.boss!!
        b.hurt(1e9)
        return b
    }

    @Test
    fun endlessRunsOnPastTheTenthGuardianAndAscends() {
        TestEnv.setup()
        val seed = 90210
        val d = Game.director
        val b = breakEndlessGuardian(Cfg.CAMPAIGN_SECTORS, seed)
        assertEquals(BOSS_DEFS[Director.endlessGuardian(seed, 10)].id, b.bdef.id)
        assertEquals("no ending in endless", GameState.PLAYING, Game.state)
        val hpMul10 = d.hpMul; val guardian10 = b.maxHp; val tempo10 = b.tempo
        // the guardian falls, a graft is drafted, and the run warps on to sector 11
        var guard = 0
        while (d.sector == Cfg.CAMPAIGN_SECTORS && guard++ < 60 * 30) TestEnv.frames(1, 1.0 / 60) { godMode(); pickFirstGraft() }
        assertEquals(11, d.sector)
        assertEquals("all ten guardians fell: the nebula ascends", 1, d.ascension)
        guard = 0
        while (d.state == DirState.WARP && guard++ < 60 * 10) TestEnv.frames(1, 1.0 / 60) { godMode() }
        assertTrue("sector 11's waves are harder than sector 10's", d.hpMul > hpMul10)
        // straight on to sector 11's guardian, drawn from the next ten
        Save.data.run = RunCheckpoint().apply { hull = "lancer"; hp = 5; sector = 11; wave = Cfg.BOSS_EVERY; mode = RunMode.ENDLESS.id; this.seed = seed; ascension = 1 }
        Game.resumeCheckpoint()
        guard = 0
        while (Game.boss == null && guard++ < 60 * 10) TestEnv.frames(1, 1.0 / 60) { godMode() }
        val b11 = Game.boss!!
        assertEquals(BOSS_DEFS[Director.endlessGuardian(seed, 11)].id, b11.bdef.id)
        assertTrue("a tougher guardian (${b11.maxHp} against $guardian10)", b11.maxHp > guardian10)
        assertTrue("a faster one", b11.tempo > tempo10)
        assertEquals(1, d.ascension)
    }

    @Test
    fun anEndlessCheckpointResumesWithTheSameGuardians() {
        val store = TestEnv.setup()
        Game.startRun(null, RunMode.ENDLESS)
        val seed = Game.director.guardianSeed
        assertTrue(seed != 0)
        TestEnv.seconds(1.0, 60) { godMode() }
        // a checkpoint deep into the run, written as the app would
        Save.data.run!!.apply { sector = 13; wave = Cfg.BOSS_EVERY }
        Save.flush()
        val text = store.text!!
        assertTrue(text.contains("\"mode\":\"endless\""))
        TestEnv.setup()
        Save.store = TestEnv.MemStore(text)
        Save.load()
        val c = Save.data.run!!
        assertEquals(RunMode.ENDLESS.id, c.mode); assertEquals(seed, c.seed); assertEquals(13, c.sector)
        Game.resumeCheckpoint()
        assertEquals(RunMode.ENDLESS, Game.director.mode)
        assertEquals(RunMode.ENDLESS, Game.lastMode)
        var guard = 0
        while (Game.boss == null && guard++ < 60 * 10) TestEnv.frames(1, 1.0 / 60) { godMode() }
        assertEquals(BOSS_DEFS[Director.endlessGuardian(seed, 13)].id, Game.boss!!.bdef.id)
    }

    @Test
    fun anEndlessRunEndsInAGameOverThatRecordsItsDepth() {
        TestEnv.setup()
        Save.data.run = RunCheckpoint().apply { hull = "lancer"; hp = 5; sector = 12; wave = 2; mode = RunMode.ENDLESS.id; seed = 5 }
        Game.resumeCheckpoint()
        TestEnv.seconds(2.0, 60)
        val p = Game.player!!
        p.iframes = 0.0; p.shield = 0; p.hp = 1
        p.hurt()
        TestEnv.seconds(3.0, 60)
        assertEquals(GameState.OVER, Game.state)
        val st = Save.data.stats
        assertEquals(12.0, st.endlessBestSector, 0.0); assertEquals(2.0, st.endlessBestWave, 0.0)
        assertEquals(12.0, st.bestSector, 0.0)
        assertEquals("only a story win is a requiem", 0.0, st.requiems, 0.0)
        // a shallower endless run doesn't lower the record
        Save.data.run = RunCheckpoint().apply { hull = "lancer"; hp = 5; sector = 3; wave = 1; mode = RunMode.ENDLESS.id; seed = 6 }
        Game.resumeCheckpoint()
        TestEnv.seconds(2.0, 60)
        Game.abandon()
        TestEnv.seconds(3.0, 60)
        assertEquals(12.0, Save.data.stats.endlessBestSector, 0.0)
    }

    @Test
    fun progressionCurvesMatchOriginal() {
        assertEquals(listOf(19, 29, 64, 137, 316), listOf(1, 2, 5, 10, 20).map { Game.xpCurve(it) })
        val hull = UPGRADES.first { it.id == "hull" }
        assertEquals(120, hull.cost(0)); assertEquals(786, hull.cost(4))
        assertEquals(880, UPGRADES.first { it.id == "genesis" }.cost(1))
        assertEquals(1342, UPGRADES.first { it.id == "reactor" }.cost(5))
        assertEquals(24, PERKS.size)
        assertEquals(11, UPGRADES.size)
        assertEquals(6, MUTATORS.size)
    }
}
