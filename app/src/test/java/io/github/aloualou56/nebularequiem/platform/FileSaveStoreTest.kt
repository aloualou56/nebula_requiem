package io.github.aloualou56.nebularequiem.platform

import android.content.Context
import org.robolectric.RuntimeEnvironment
import io.github.aloualou56.nebularequiem.core.Save
import io.github.aloualou56.nebularequiem.core.SaveData
import io.github.aloualou56.nebularequiem.core.TestEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileSaveStoreTest {
    private lateinit var ctx: Context
    private val dir get() = File(ctx.filesDir, "saves")

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        dir.deleteRecursively()
        ctx.getSharedPreferences(FileSaveStore.LEGACY_SAVE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        TestEnv.setup()
    }

    @Test fun writesAtomicallyAndReadsBack() {
        val a = FileSaveStore(ctx)
        assertNull(a.read())
        a.write("{\"v\":1}")
        a.sync()
        assertTrue(File(dir, "nebula_save.json").isFile)
        val b = FileSaveStore(ctx)
        assertEquals("{\"v\":1}", b.read())
        assertFalse(b.importedLegacy)
    }

    @Test fun keepsThePreviousSaveAsBackup() {
        val a = FileSaveStore(ctx)
        a.write("first"); a.sync()
        a.write("second"); a.sync()
        val b = FileSaveStore(ctx)
        assertEquals("second", b.read())
        assertEquals("first", b.readBackup())
    }

    @Test fun coalescesBurstsOfWrites() {
        val a = FileSaveStore(ctx)
        for (i in 0 until 200) a.write("w$i")
        a.sync()
        assertEquals("w199", FileSaveStore(ctx).read())
    }

    @Test fun corruptMainFileFallsBackToBackup() {
        val store = FileSaveStore(ctx)
        Save.store = store
        Save.replace(SaveData().also { it.stardust = 1234.0 })
        Save.flush(); store.sync()
        Save.replace(SaveData().also { it.stardust = 5678.0 })
        Save.flush(); store.sync()
        // Simulate storage damage to the main file.
        File(dir, "nebula_save.json").writeText("{\"version\":4,\"dust\":")
        val fresh = FileSaveStore(ctx)
        Save.store = fresh
        Save.load()
        assertTrue(Save.restoredFromBackup)
        assertEquals(1234.0, Save.data.stardust, 0.0)
    }

    @Test fun corruptWithoutBackupStartsFresh() {
        dir.mkdirs()
        File(dir, "nebula_save.json").writeText("not json at all")
        Save.store = FileSaveStore(ctx)
        Save.load()
        assertTrue(Save.recoveredFromCorruption)
        assertFalse(Save.restoredFromBackup)
        assertEquals(0.0, Save.data.stardust, 0.0)
    }

    @Test fun importsTheWebViewEditionsSave() {
        val legacy = """{"version":3,"stardust":777,"lifetimeStardust":4200,"upgrades":{"hull":2,"reactor":1},
            "hulls":{"unlocked":["lancer","wraith"],"selected":"wraith"},
            "settings":{"master":0.5,"music":0.3,"sfx":0.9,"shake":1,"quality":"medium","aberration":true,"bloom":true,"autofire":true,
            "damageNumbers":true,"showFps":false,"adaptive":true,"fpsCap":0,"haptics":true},
            "stats":{"runs":9,"bestScore":4200,"bestSector":7,"bestWave":3,"totalKills":321,"bossKills":2,"playSeconds":1800,"grazes":50},
            "seen":{"tutorial":true}}"""
        ctx.getSharedPreferences(FileSaveStore.LEGACY_SAVE_PREFS, Context.MODE_PRIVATE).edit().putString(FileSaveStore.LEGACY_SAVE_KEY, legacy).commit()
        val store = FileSaveStore(ctx)
        Save.store = store
        Save.load()
        assertTrue(store.importedLegacy)
        assertEquals(777.0, Save.data.stardust, 0.0)
        assertEquals(2, Save.data.up("hull"))
        assertEquals(4200.0, Save.data.stats.bestScore, 0.0)
        assertEquals("wraith", Save.data.selected)
        assertEquals("medium", Save.data.settings.quality)
        // Once written natively, the native file wins.
        Save.flush(); store.sync()
        val again = FileSaveStore(ctx)
        assertTrue(again.read()!!.contains("\"version\":4"))
        assertFalse(again.importedLegacy)
    }
}
