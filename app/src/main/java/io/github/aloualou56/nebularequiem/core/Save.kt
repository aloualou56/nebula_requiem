package io.github.aloualou56.nebularequiem.core

import kotlin.math.floor

/**
 * §3 SAVE / LOAD. The save is a versioned JSON document. Loading never trusts stored data: every
 * field is type-checked and clamped against the defaults, so a corrupted or hand-edited save can at
 * worst reset individual values; text that isn't JSON at all is rejected and the defaults are used.
 * Writes are debounced (250 ms) and flushed whenever the app is backgrounded.
 *
 * Version history: v1 `dust` currency · v2 hulls · v3 insight refit, quality tiers (the WebView
 * game's format) · v4 native: an optional mid-run checkpoint. (The game is landscape only; a
 * settings.orientation value left by an earlier native build is ignored and dropped on the next write.)
 * 0.4 and 0.4.1 added fields to v4 without changing its shape, so they need no migration: the
 * flight plan and difficulty last chosen, the requiems completed and the deepest endless sector,
 * and the checkpoint's mode, guardian seed and difficulty (a save without them reads as a story
 * run on Medium, with no endless record).
 */

/** Highest purchasable level per hangar refit (mirrors UPGRADES; used to clamp loaded saves). */
val UPGRADE_MAX = linkedMapOf(
    "hull" to 5, "reactor" to 6, "thrusters" to 5, "flux" to 4, "nova" to 3, "salvage" to 5,
    "magnet" to 4, "phase" to 4, "fortune" to 3, "genesis" to 2, "insight" to 4
)
val HULL_IDS = listOf("lancer", "wraith", "bastion")

class Settings {
    var master = 0.8; var music = 0.55; var sfx = 0.8; var shake = 1.0
    var quality = "high"; var aberration = true; var bloom = true; var autofire = true
    var damageNumbers = true; var showFps = false; var adaptive = true; var fpsCap = 0; var haptics = true
}

class Stats {
    var runs = 0.0; var bestScore = 0.0; var bestSector = 0.0; var bestWave = 0.0
    var totalKills = 0.0; var bossKills = 0.0; var playSeconds = 0.0; var grazes = 0.0
    /** Story runs won, and the deepest an endless run has reached. */
    var requiems = 0.0; var endlessBestSector = 0.0; var endlessBestWave = 0.0
}

/** Snapshot of a run taken as each wave begins, so a run survives Android killing the process. */
class RunCheckpoint {
    var hull = "lancer"
    var perks = LinkedHashMap<String, Int>()
    var hp = 1; var shield = 0; var bombs = 0; var flux = 0.0
    var score = 0.0; var kills = 0; var grazes = 0; var dust = 0.0; var level = 1; var xp = 0.0
    var time = 0.0; var bossKills = 0; var maxCombo = 0; var maxMult = 1.0
    var perksTaken = ArrayList<String>(); var rerolls = 0; var damage = 0.0; var hits = 0
    var sector = 1; var wave = 1; var ascension = 0; var mutators = ArrayList<String>()
    /** The run's [RunMode] id, and an endless run's guardian seed (Director.endlessGuardian). */
    var mode = RunMode.STORY.id; var seed = 0
    /** The run's [Difficulty] id. */
    var difficulty = Difficulty.MEDIUM.id
}

class SaveData {
    var version = Save.VERSION
    var stardust = 0.0
    var lifetimeStardust = 0.0
    val upgrades = LinkedHashMap<String, Int>().apply { for (k in UPGRADE_MAX.keys) put(k, 0) }
    var unlocked = arrayListOf("lancer")
    var selected = "lancer"
    var settings = Settings()
    val stats = Stats()
    var seenTutorial = false
    /** The flight plan chosen last ([RunMode] id): the plan screen offers it first. */
    var plan = RunMode.STORY.id
    /** The difficulty chosen last ([Difficulty] id). */
    var difficulty = Difficulty.MEDIUM.id
    var run: RunCheckpoint? = null

    fun up(id: String): Int = upgrades[id] ?: 0
}

object Save {
    const val VERSION = 4
    var data = SaveData()
        private set
    /** False when the last write failed (storage full or unavailable). */
    var available = true
        private set
    var store: SaveStore? = null
    /** True when the stored text existed but could not be read and the defaults were used. */
    var recoveredFromCorruption = false
        private set
    /** True when the main save was unreadable and the backup copy was loaded instead. */
    var restoredFromBackup = false
        private set

    private var commitAt = -1.0

    // ── untrusted-value helpers ──
    private fun num(v: Any?, def: Double, lo: Double = Double.NEGATIVE_INFINITY, hi: Double = Double.POSITIVE_INFINITY): Double =
        if (v is Number && v.toDouble().isFinite()) clamp(v.toDouble(), lo, hi) else def
    private fun bool(v: Any?, def: Boolean): Boolean = v as? Boolean ?: def
    private fun str(v: Any?, list: List<String>, def: String): String = if (v is String && v in list) v else def
    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    /** Merge an untrusted object onto fresh defaults, field by field. */
    fun sanitize(raw: Map<String, Any?>?): SaveData {
        val d = SaveData()
        if (raw == null) return d
        d.stardust = floor(num(raw["stardust"], 0.0, 0.0, 1e9))
        d.lifetimeStardust = floor(num(raw["lifetimeStardust"], d.stardust, 0.0, 1e10))
        obj(raw["upgrades"])?.let { u -> for ((k, max) in UPGRADE_MAX) d.upgrades[k] = floor(num(u[k], 0.0, 0.0, max.toDouble())).toInt() }
        obj(raw["hulls"])?.let { h ->
            val un = (h["unlocked"] as? List<*>)?.filterIsInstance<String>()?.filter { it in HULL_IDS } ?: emptyList()
            d.unlocked = ArrayList(LinkedHashSet(listOf("lancer") + un))
            val sel = h["selected"]
            d.selected = if (sel is String && sel in d.unlocked) sel else "lancer"
        }
        obj(raw["settings"])?.let { s ->
            val o = d.settings
            o.master = num(s["master"], o.master, 0.0, 1.0)
            o.music = num(s["music"], o.music, 0.0, 1.0)
            o.sfx = num(s["sfx"], o.sfx, 0.0, 1.0)
            o.shake = num(s["shake"], o.shake, 0.0, 1.5)
            o.quality = str(s["quality"], listOf("low", "medium", "high"), o.quality)
            o.aberration = bool(s["aberration"], o.aberration)
            o.bloom = bool(s["bloom"], o.bloom)
            o.autofire = bool(s["autofire"], o.autofire)
            o.damageNumbers = bool(s["damageNumbers"], o.damageNumbers)
            o.showFps = bool(s["showFps"], o.showFps)
            o.adaptive = bool(s["adaptive"], o.adaptive)
            val cap = s["fpsCap"]
            o.fpsCap = if (cap is Number && cap.toDouble() == Math.rint(cap.toDouble()) && cap.toInt() in Cfg.FPS_CAPS) cap.toInt() else o.fpsCap
            o.haptics = bool(s["haptics"], o.haptics)
        }
        obj(raw["stats"])?.let { s ->
            val t = d.stats
            t.runs = num(s["runs"], 0.0, 0.0, 1e12); t.bestScore = num(s["bestScore"], 0.0, 0.0, 1e12)
            t.bestSector = num(s["bestSector"], 0.0, 0.0, 1e12); t.bestWave = num(s["bestWave"], 0.0, 0.0, 1e12)
            t.totalKills = num(s["totalKills"], 0.0, 0.0, 1e12); t.bossKills = num(s["bossKills"], 0.0, 0.0, 1e12)
            t.playSeconds = num(s["playSeconds"], 0.0, 0.0, 1e12); t.grazes = num(s["grazes"], 0.0, 0.0, 1e12)
            t.requiems = floor(num(s["requiems"], 0.0, 0.0, 1e12))
            t.endlessBestSector = floor(num(s["endlessBestSector"], 0.0, 0.0, 1e12)); t.endlessBestWave = floor(num(s["endlessBestWave"], 0.0, 0.0, Cfg.BOSS_EVERY.toDouble()))
        }
        obj(raw["seen"])?.let { d.seenTutorial = bool(it["tutorial"], false) }
        d.plan = str(raw["plan"], RunMode.entries.map { it.id }, d.plan)
        d.difficulty = str(raw["difficulty"], Difficulty.entries.map { it.id }, d.difficulty)
        d.run = sanitizeRun(obj(raw["run"]), d)
        return d
    }

    private fun sanitizeRun(r: Map<String, Any?>?, d: SaveData): RunCheckpoint? {
        if (r == null) return null
        val c = RunCheckpoint()
        val hull = r["hull"]
        c.hull = if (hull is String && hull in d.unlocked) hull else return null
        obj(r["perks"])?.let { p -> for (perk in PERKS) { val l = floor(num(p[perk.id], 0.0, 0.0, perk.max.toDouble())).toInt(); if (l > 0) c.perks[perk.id] = l } }
        c.hp = floor(num(r["hp"], 1.0, 1.0, 64.0)).toInt()
        c.shield = floor(num(r["shield"], 0.0, 0.0, 2.0)).toInt()
        c.bombs = floor(num(r["bombs"], 0.0, 0.0, 6.0)).toInt()
        c.flux = num(r["flux"], 0.0, 0.0, 1.0)
        c.score = floor(num(r["score"], 0.0, 0.0, 1e12))
        c.kills = floor(num(r["kills"], 0.0, 0.0, 1e9)).toInt()
        c.grazes = floor(num(r["grazes"], 0.0, 0.0, 1e9)).toInt()
        c.dust = num(r["dust"], 0.0, 0.0, 1e9)
        c.level = floor(num(r["level"], 1.0, 1.0, 10000.0)).toInt()
        c.xp = num(r["xp"], 0.0, 0.0, 1e9)
        c.time = num(r["time"], 0.0, 0.0, 1e9)
        c.bossKills = floor(num(r["bossKills"], 0.0, 0.0, 1e6)).toInt()
        c.maxCombo = floor(num(r["maxCombo"], 0.0, 0.0, 1e9)).toInt()
        c.maxMult = num(r["maxMult"], 1.0, 1.0, 10.0)
        (r["perksTaken"] as? List<*>)?.filterIsInstance<String>()?.filter { id -> PERKS.any { it.id == id } }?.let { c.perksTaken = ArrayList(it.take(500)) }
        c.rerolls = floor(num(r["rerolls"], 0.0, 0.0, 99.0)).toInt()
        c.damage = num(r["damage"], 0.0, 0.0, 1e15)
        c.hits = floor(num(r["hits"], 0.0, 0.0, 1e9)).toInt()
        // A checkpoint without a mode (0.3, or 1.1.1 and earlier with five waves a sector and endless
        // sectors) resumes as a story run: 1.1.1's fourth wave and its guardian (wave 5) both resume
        // at the guardian, a sector past the last one at the last one; its ascension is kept.
        c.mode = str(r["mode"], RunMode.entries.map { it.id }, RunMode.STORY.id)
        c.seed = floor(num(r["seed"], 0.0, Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())).toInt()
        c.difficulty = str(r["difficulty"], Difficulty.entries.map { it.id }, Difficulty.MEDIUM.id)
        val lastSector = if (c.mode == RunMode.ENDLESS.id) 9999.0 else Cfg.CAMPAIGN_SECTORS.toDouble()
        c.sector = floor(num(r["sector"], 1.0, 1.0, lastSector)).toInt()
        c.wave = floor(num(r["wave"], 1.0, 1.0, Cfg.BOSS_EVERY.toDouble())).toInt()
        c.ascension = floor(num(r["ascension"], 0.0, 0.0, 9999.0)).toInt()
        (r["mutators"] as? List<*>)?.filterIsInstance<String>()?.filter { id -> MUTATORS.any { it.id == id } }?.distinct()?.let { c.mutators = ArrayList(it) }
        return c
    }

    /** Version migrations: each step upgrades the shape by one version. */
    @Suppress("UNCHECKED_CAST")
    fun migrate(raw0: Any?): SaveData {
        val src = raw0 as? Map<String, Any?> ?: return SaveData()
        val raw = LinkedHashMap(src)
        var v = (raw["version"] as? Number)?.toInt() ?: 1
        if (v < 2) {
            // v1 stored currency as `dust` and only one hull flag.
            if (raw["stardust"] == null) raw["stardust"] = raw["dust"] ?: 0.0
            if (raw["hulls"] == null) raw["hulls"] = mapOf("unlocked" to listOf("lancer"), "selected" to "lancer")
            v = 2
        }
        if (v < 3) {
            // v2 had no `insight` upgrade and a boolean `lowQuality` flag.
            val up = LinkedHashMap((raw["upgrades"] as? Map<String, Any?>) ?: emptyMap())
            if (up["insight"] == null) up["insight"] = 0.0
            raw["upgrades"] = up
            val s = raw["settings"] as? Map<String, Any?>
            if (s != null && s["lowQuality"] is Boolean) {
                val ns = LinkedHashMap(s); ns["quality"] = if (s["lowQuality"] == true) "low" else "high"; raw["settings"] = ns
            }
            v = 3
        }
        if (v < 4) {
            // v3 (WebView) had no run checkpoint; nothing to convert.
            v = 4
        }
        raw["version"] = v.toDouble()
        return sanitize(raw)
    }

    fun toMap(d: SaveData): Map<String, Any?> {
        val s = d.settings
        val st = d.stats
        val m = linkedMapOf<String, Any?>(
            "version" to VERSION,
            "stardust" to d.stardust,
            "lifetimeStardust" to d.lifetimeStardust,
            "upgrades" to LinkedHashMap(d.upgrades),
            "hulls" to linkedMapOf("unlocked" to ArrayList(d.unlocked), "selected" to d.selected),
            "settings" to linkedMapOf(
                "master" to s.master, "music" to s.music, "sfx" to s.sfx, "shake" to s.shake,
                "quality" to s.quality, "aberration" to s.aberration, "bloom" to s.bloom, "autofire" to s.autofire,
                "damageNumbers" to s.damageNumbers, "showFps" to s.showFps, "adaptive" to s.adaptive,
                "fpsCap" to s.fpsCap, "haptics" to s.haptics
            ),
            "stats" to linkedMapOf(
                "runs" to st.runs, "bestScore" to st.bestScore, "bestSector" to st.bestSector, "bestWave" to st.bestWave,
                "totalKills" to st.totalKills, "bossKills" to st.bossKills, "playSeconds" to st.playSeconds, "grazes" to st.grazes,
                "requiems" to st.requiems, "endlessBestSector" to st.endlessBestSector, "endlessBestWave" to st.endlessBestWave
            ),
            "seen" to linkedMapOf("tutorial" to d.seenTutorial),
            "plan" to d.plan,
            "difficulty" to d.difficulty
        )
        d.run?.let { c ->
            m["run"] = linkedMapOf(
                "hull" to c.hull, "perks" to LinkedHashMap(c.perks), "hp" to c.hp, "shield" to c.shield, "bombs" to c.bombs,
                "flux" to c.flux, "score" to c.score, "kills" to c.kills, "grazes" to c.grazes, "dust" to c.dust,
                "level" to c.level, "xp" to c.xp, "time" to c.time, "bossKills" to c.bossKills, "maxCombo" to c.maxCombo,
                "maxMult" to c.maxMult, "perksTaken" to ArrayList(c.perksTaken), "rerolls" to c.rerolls, "damage" to c.damage,
                "hits" to c.hits, "sector" to c.sector, "wave" to c.wave, "ascension" to c.ascension, "mutators" to ArrayList(c.mutators),
                "mode" to c.mode, "seed" to c.seed, "difficulty" to c.difficulty
            )
        }
        return m
    }

    fun encode(d: SaveData = data): String = Json.write(toMap(d))

    /** Parse stored text; returns null when it isn't a readable save at all. */
    fun decode(text: String?): SaveData? {
        if (text.isNullOrBlank()) return null
        return try { migrate(Json.parse(text)) } catch (e: JsonException) { null } catch (e: RuntimeException) { null }
    }

    fun load(): SaveData {
        val s = store
        recoveredFromCorruption = false; restoredFromBackup = false
        val text = try { s?.read() } catch (e: Exception) { available = false; null }
        var loaded = decode(text)
        if (loaded == null && s != null) {
            // Unreadable or missing main file: fall back to the previous good copy, if any.
            val backup = try { s.readBackup() } catch (e: Exception) { null }
            loaded = decode(backup)
            if (loaded != null) restoredFromBackup = true
        }
        if (loaded == null && !text.isNullOrBlank()) recoveredFromCorruption = true
        data = loaded ?: SaveData()
        if (restoredFromBackup) commit()   // rewrite the main file from the recovered copy
        return data
    }

    /** Debounced write: flushed 250 ms after the last change (see [tick]). */
    fun commit() { commitAt = Env.now() + 250 }

    /** Called every frame on the game thread; performs a due debounced write. */
    fun tick() { if (commitAt >= 0 && Env.now() >= commitAt) flush() }

    fun flush() {
        commitAt = -1.0
        val s = store ?: return
        val text = encode(data)
        available = try { s.write(text); true } catch (e: Exception) { false }
    }

    fun reset() {
        val keepSettings = data.settings
        data = SaveData()
        data.settings = keepSettings
        flush()
    }

    /** Replace the in-memory document (tests and legacy import). */
    fun replace(d: SaveData) { data = d }
}
