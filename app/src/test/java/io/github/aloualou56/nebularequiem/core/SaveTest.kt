package io.github.aloualou56.nebularequiem.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveTest {
    @Test
    fun roundTripPreservesEverything() {
        val d = SaveData()
        d.stardust = 1234.0; d.lifetimeStardust = 5000.0
        d.upgrades["reactor"] = 3; d.upgrades["genesis"] = 2
        d.unlocked = arrayListOf("lancer", "wraith"); d.selected = "wraith"
        d.settings.master = 0.33; d.settings.quality = "medium"; d.settings.fpsCap = 120; d.settings.autofire = false
        d.stats.bestScore = 99999.0; d.stats.bestSector = 3.0; d.stats.bestWave = 2.0
        d.seenTutorial = true
        d.run = RunCheckpoint().apply { hull = "wraith"; perks["prism"] = 2; hp = 2; sector = 2; wave = 3; mutators.add("swarm"); perksTaken.add("prism"); perksTaken.add("prism") }
        val back = Save.decode(Save.encode(d))!!
        assertEquals(Save.encode(d), Save.encode(back))
        assertEquals("wraith", back.selected)
        assertEquals(2, back.run!!.perks["prism"])
        assertEquals(120, back.settings.fpsCap)
    }

    @Test
    fun orientationFromEarlierNativeBuildsIsDropped() {
        // The game is landscape only; an earlier native build stored a Screen setting.
        val old = Save.decode("""{"version": 4, "stardust": 42, "settings": {"orientation": "auto", "music": 0.2}}""")!!
        assertEquals(42.0, old.stardust, 0.0)
        assertEquals(0.2, old.settings.music, 1e-9)
        assertFalse(Save.encode(old).contains("orientation"))
    }

    @Test
    fun corruptedTextIsRejectedSafely() {
        for (bad in listOf("{", "not json", "{\"version\": 3, \"stardust\": }", "[1,2", "\u0000\u0001", "{\"a\":\"\\q\"}")) {
            assertNull(bad, Save.decode(bad))
        }
        val store = TestEnv.MemStore("{garbage")
        Save.store = store
        val d = Save.load()
        assertTrue(Save.recoveredFromCorruption)
        assertEquals(0.0, d.stardust, 0.0)
    }

    @Test
    fun hostileValuesAreClamped() {
        val text = """{"version":3,"stardust":-50,"upgrades":{"hull":99,"reactor":"x"},"hulls":{"unlocked":["lancer","hacker"],"selected":"hacker"},
            "settings":{"master":7,"quality":"ultra","fpsCap":75,"shake":-1},"stats":{"bestScore":1e99},"run":{"hull":"bastion","hp":5}}"""
        val d = Save.decode(text)!!
        assertEquals(0.0, d.stardust, 0.0)
        assertEquals(5, d.up("hull"))
        assertEquals(0, d.up("reactor"))
        assertEquals(listOf("lancer"), d.unlocked)
        assertEquals("lancer", d.selected)
        assertEquals(1.0, d.settings.master, 0.0)
        assertEquals("high", d.settings.quality)
        assertEquals(0, d.settings.fpsCap)
        assertEquals(0.0, d.settings.shake, 0.0)
        assertEquals(1e12, d.stats.bestScore, 0.0)
        assertNull("checkpoint for a locked hull is dropped", d.run)
    }

    @Test
    fun migratesV1AndV2() {
        val v1 = Save.decode("""{"dust": 321, "upgrades": {"hull": 2}}""")!!
        assertEquals(321.0, v1.stardust, 0.0)
        assertEquals(2, v1.up("hull"))
        assertEquals(0, v1.up("insight"))
        val v2 = Save.decode("""{"version": 2, "stardust": 10, "settings": {"lowQuality": true}}""")!!
        assertEquals("low", v2.settings.quality)
        val v3 = Save.decode("""{"version": 3, "stardust": 10, "settings": {"quality": "medium", "fpsCap": 144}}""")!!
        assertEquals("medium", v3.settings.quality)
        assertEquals(144, v3.settings.fpsCap)
        assertFalse(v3.run != null)
    }

    /** A save written by 1.1.1: five waves a sector (the fifth the guardian), endless sectors and ascension. */
    private fun save111(sector: Int, wave: Int, ascension: Int) = Save.decode("""{"version":4,"stardust":4321,"lifetimeStardust":9000,
        "upgrades":{"reactor":3,"hull":2},"hulls":{"unlocked":["lancer","wraith"],"selected":"wraith"},
        "settings":{"music":0.3,"fpsCap":120,"autofire":false},"stats":{"runs":7,"bestScore":250000,"bestSector":12,"bestWave":5,"bossKills":11},
        "seen":{"tutorial":true},
        "run":{"hull":"wraith","perks":{"prism":2},"hp":3,"shield":0,"bombs":2,"flux":0.5,"score":180000,"kills":900,"grazes":40,"dust":55,
            "level":14,"xp":3,"time":1500,"bossKills":11,"maxCombo":30,"maxMult":1.75,"perksTaken":["prism","prism"],"rerolls":1,"damage":1e6,"hits":4,
            "sector":$sector,"wave":$wave,"ascension":$ascension,"mutators":["swarm","elite","eclipse","hyperflux","volatile","overclock"]}}""")!!

    private fun assertProgressUntouched(d: SaveData) {
        assertEquals(4321.0, d.stardust, 0.0)
        assertEquals(9000.0, d.lifetimeStardust, 0.0)
        assertEquals(3, d.up("reactor")); assertEquals(2, d.up("hull"))
        assertEquals(listOf("lancer", "wraith"), d.unlocked); assertEquals("wraith", d.selected)
        assertEquals(0.3, d.settings.music, 0.0); assertEquals(120, d.settings.fpsCap); assertFalse(d.settings.autofire)
        assertEquals(7.0, d.stats.runs, 0.0); assertEquals(250000.0, d.stats.bestScore, 0.0)
        assertEquals(12.0, d.stats.bestSector, 0.0); assertEquals(5.0, d.stats.bestWave, 0.0); assertEquals(11.0, d.stats.bossKills, 0.0)
        assertTrue(d.seenTutorial)
    }

    @Test
    fun checkpointFrom111AtTheOldFourthWaveResumesAtTheGuardian() {
        val d = save111(sector = 3, wave = 4, ascension = 0)
        assertProgressUntouched(d)
        val c = d.run!!
        assertEquals(3, c.sector); assertEquals(Cfg.BOSS_EVERY, c.wave); assertEquals(0, c.ascension)
        assertEquals(180000.0, c.score, 0.0); assertEquals(2, c.perks["prism"]); assertEquals(6, c.mutators.size)
    }

    @Test
    fun checkpointFrom111AtTheOldGuardianWaveResumesAtTheGuardian() {
        val d = save111(sector = 4, wave = 5, ascension = 0)
        assertProgressUntouched(d)
        val c = d.run!!
        assertEquals(4, c.sector); assertEquals(Cfg.BOSS_EVERY, c.wave)
    }

    @Test
    fun checkpointFrom111PastTheLastSectorResumesThereKeepingItsAscension() {
        val d = save111(sector = 12, wave = 2, ascension = 2)
        assertProgressUntouched(d)
        val c = d.run!!
        assertEquals(Cfg.CAMPAIGN_SECTORS, c.sector); assertEquals(2, c.wave); assertEquals(2, c.ascension)
        // and it survives the next write unchanged
        val again = Save.decode(Save.encode(d))!!
        assertEquals(Save.encode(d), Save.encode(again))
    }

    @Test
    fun theFlightPlanRecordsAndAnEndlessCheckpointRoundTrip() {
        val d = SaveData()
        d.plan = "endless"
        d.stats.requiems = 2.0; d.stats.endlessBestSector = 14.0; d.stats.endlessBestWave = 3.0
        d.run = RunCheckpoint().apply { hull = "lancer"; sector = 37; wave = 2; ascension = 3; mode = "endless"; seed = -123456789 }
        val back = Save.decode(Save.encode(d))!!
        assertEquals(Save.encode(d), Save.encode(back))
        assertEquals("endless", back.plan)
        assertEquals(2.0, back.stats.requiems, 0.0); assertEquals(14.0, back.stats.endlessBestSector, 0.0); assertEquals(3.0, back.stats.endlessBestWave, 0.0)
        val c = back.run!!
        assertEquals("endless", c.mode); assertEquals(-123456789, c.seed)
        assertEquals("an endless checkpoint keeps its sector past the campaign", 37, c.sector)
        assertEquals(3, c.ascension)
    }

    @Test
    fun aSaveFromBefore04ReadsAsStoryWithNoEndlessRecord() {
        // 0.3's format: no plan, no new records, a checkpoint with no mode or seed
        val d = save111(sector = 7, wave = 2, ascension = 0)
        assertProgressUntouched(d)
        assertEquals("story", d.plan)
        assertEquals(0.0, d.stats.requiems, 0.0); assertEquals(0.0, d.stats.endlessBestSector, 0.0)
        val c = d.run!!
        assertEquals("story", c.mode); assertEquals(0, c.seed); assertEquals(7, c.sector)
    }

    @Test
    fun badPlanModeAndRecordValuesAreRejected() {
        val d = Save.decode("""{"version":4,"plan":"hardcore","stats":{"requiems":-3,"endlessBestSector":"far","endlessBestWave":9},
            "run":{"hull":"lancer","sector":25,"wave":1,"mode":"marathon","seed":"x"}}""")!!
        assertEquals("story", d.plan)
        assertEquals(0.0, d.stats.requiems, 0.0); assertEquals(0.0, d.stats.endlessBestSector, 0.0)
        assertEquals("a wave is at most the guardian's", Cfg.BOSS_EVERY.toDouble(), d.stats.endlessBestWave, 0.0)
        val c = d.run!!
        assertEquals("an unknown mode is a story run", "story", c.mode); assertEquals(0, c.seed)
        assertEquals("so it is held to the campaign's sectors", Cfg.CAMPAIGN_SECTORS, c.sector)
    }

    @Test
    fun theDifficultyRoundTripsAndDefaultsToMedium() {
        val d = SaveData()
        d.difficulty = "maniac"
        d.run = RunCheckpoint().apply { hull = "lancer"; difficulty = "hard" }
        val back = Save.decode(Save.encode(d))!!
        assertEquals("maniac", back.difficulty); assertEquals("hard", back.run!!.difficulty)
        // saves from before 0.4.1 fly on Medium
        val old = save111(sector = 4, wave = 2, ascension = 0)
        assertEquals("medium", old.difficulty); assertEquals("medium", old.run!!.difficulty)
        // and nothing else gets in
        val bad = Save.decode("""{"version":4,"difficulty":"insane","run":{"hull":"lancer","difficulty":7}}""")!!
        assertEquals("medium", bad.difficulty); assertEquals("medium", bad.run!!.difficulty)
    }

    @Test
    fun resetKeepsSettings() {
        val store = TestEnv.MemStore()
        Save.store = store
        Save.replace(SaveData().apply { stardust = 500.0; settings.music = 0.1 })
        Save.reset()
        assertEquals(0.0, Save.data.stardust, 0.0)
        assertEquals(0.1, Save.data.settings.music, 0.0)
        assertTrue(store.writes >= 1)
    }

    @Test
    fun jsonEscapesAndNumbers() {
        val v = Json.parse("""{"s":"a\"b\\c\u00e9\n","n":-1.5e3,"b":[true,false,null]}""") as Map<*, *>
        assertEquals("a\"b\\cé\n", v["s"])
        assertEquals(-1500.0, v["n"])
        assertEquals(listOf(true, false, null), v["b"])
        assertEquals(v, Json.parse(Json.write(v)))
    }
}
