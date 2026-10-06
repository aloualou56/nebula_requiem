package io.github.aloualou56.nebularequiem.core

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * §18 DIRECTOR — sectors, waves, formations, difficulty curves and mutators.
 * Every sector is three waves and a guardian. A story run is Cfg.CAMPAIGN_SECTORS sectors with
 * the guardians in order and ends at the last; an endless run never ends, deals the guardians
 * from a shuffled bag, and ascends every ten sectors.
 * Difficulty index d = 4·(sector − 1) + min(wave, 4) (see DIFFICULTY_STEPS_PER_SECTOR). Curves:
 *   enemy HP      ×hpCurve(d)·(1 + 0.25·ascension), hpCurve(d) = 1 + 0.11(d−1) + 0.0035(d−1)²
 *   fire rate     ×min(2.2, 1 + 0.035(d−1))
 *   bullet speed  ×min(1.55, 1 + 0.016(d−1))·(1 + 0.05·ascension)
 *   wave budget   = round((6 + 2.5d) · countMul) points, spent on enemies by cost
 *   guardian HP   base × 1.3·hpCurve(4·sector)·(1 + 0.35·ascension), the base being the sector's
 *                 story guardian's HP (past the campaign, the last one's grown with the wave budget)
 *   guardian tempo  min(1.6, 1 + 0.05·(sector − 1) + 0.05·ascension): its attacks run that much faster
 * and the run's Difficulty multiplies them (Medium by 1: the curves above are Medium).
 */

/**
 * Difficulty steps per sector: NOT the number of waves per sector, so don't change it to match
 * Cfg.BOSS_EVERY. Every curve was tuned for four steps a sector. With three waves and a guardian,
 * waves 1–3 keep steps 1–3 and the guardian keeps step 4, exactly the difficulty they had when a
 * sector was four waves and a guardian (the guardian shared step 4 with the old fourth wave).
 */
const val DIFFICULTY_STEPS_PER_SECTOR = 4

/**
 * Guardians follow the hostile HP curve at their own step (d = 4·sector) with this bump on top, so
 * each stays its sector's toughest fight however deep the run goes. (Up to 0.3 their HP rose 6% a
 * sector while a wave's rose about tenfold over the campaign: from sector 8 on, a single wave
 * carried more HP than the guardian after it.)
 */
const val GUARDIAN_HP_BUMP = 1.3
/** How much faster a guardian's attacks run per sector past the first, and at most. */
const val GUARDIAN_TEMPO_STEP = 0.05
const val GUARDIAN_TEMPO_MAX = 1.6

/** Hostile HP growth over the difficulty index d (1 at d = 1). */
fun hpCurve(d: Double): Double = 1 + 0.11 * (d - 1) + 0.0035 * (d - 1) * (d - 1)

/** A wave's budget at difficulty index d, in hostile cost points (before the swarm mutator). */
fun waveBudget(d: Int): Double = 6 + d * 2.5

/**
 * How hard a run is. Medium is the game as tuned; the others scale the danger: hostile HP, how
 * many come (the wave budget), how fast they fire and their bullets fly, and how fast guardians
 * attack. A guardian's HP scales by the waves' HP and numbers together, so it keeps its lead over
 * its sector's waves at every level. Score, and so the stardust it converts to, pays more the
 * harder it gets.
 */
enum class Difficulty(val id: String, val label: String, val hp: Double, val count: Double, val fire: Double, val bulletSpeed: Double, val tempo: Double, val score: Double) {
    EASY("easy", "Easy", 0.75, 0.85, 0.75, 0.88, 0.85, 0.75),
    MEDIUM("medium", "Medium", 1.0, 1.0, 1.0, 1.0, 1.0, 1.0),
    HARD("hard", "Hard", 1.2, 1.15, 1.2, 1.1, 1.12, 1.3),
    MANIAC("maniac", "Maniac", 1.4, 1.3, 1.45, 1.2, 1.25, 1.7);
    /** Guardians grow by both the waves' HP and their numbers. */
    val guardianHp: Double get() = hp * count
    companion object { fun of(id: String?): Difficulty = entries.firstOrNull { it.id == id } ?: MEDIUM }
}

/** A run's flight plan: the ten-sector story, or endless. */
enum class RunMode(val id: String, val label: String) {
    STORY("story", "Story"), ENDLESS("endless", "Endless");
    companion object { fun of(id: String?): RunMode = entries.firstOrNull { it.id == id } ?: STORY }
}

class WaveEntry(val at: Double, val type: String, val count: Int, val formation: Formation)

enum class DirState { IDLE, ANNOUNCE, SPAWNING, FIGHTING, CLEARED, WARNING, BOSS, BOSS_CLEAR, WARP }

class Director {
    var sector = 1; var wave = 0; var ascension = 0
    var mode = RunMode.STORY
    var difficulty = Difficulty.MEDIUM
    /** Seeds an endless run's guardian order (see [endlessGuardian]). */
    var guardianSeed = 0
    var state = DirState.IDLE; var timer = 0.0; var waveTime = 0.0
    val queue = ArrayList<WaveEntry>()
    val mutators = ArrayList<String>()
    var warpSwapped = false
    private var nextSeed = 0
    private var nextPalette: NebulaPalette? = null

    var hpMul = 1.0; var fireMul = 1.0; var bossHpMul = 1.0; var bossTempo = 1.0; var scoreMul = 1.0
    var eliteChance = 0.0; var countMul = 1.0; var dustMul = 1.0

    init { reset() }

    fun reset() {
        sector = 1; wave = 0; ascension = 0
        mode = RunMode.STORY; difficulty = Difficulty.MEDIUM; guardianSeed = 0
        state = DirState.IDLE; timer = 0.0; waveTime = 0.0
        queue.clear(); mutators.clear()
        warpSwapped = false
        nextPalette = null
        recompute()
    }

    val d: Int get() = (sector - 1) * DIFFICULTY_STEPS_PER_SECTOR + clampI(wave, 1, DIFFICULTY_STEPS_PER_SECTOR)
    val bossWave: Boolean get() = wave == Cfg.BOSS_EVERY
    /** A story run's last sector: breaking its guardian wins the run. Endless has none. */
    val finalSector: Boolean get() = mode == RunMode.STORY && sector >= Cfg.CAMPAIGN_SECTORS
    val palette: NebulaPalette get() = NEBULA_PALETTES[(sector - 1) % NEBULA_PALETTES.size]
    fun has(id: String) = mutators.contains(id)

    fun recompute() {
        val d = d.toDouble(); val asc = ascension; val df = difficulty
        hpMul = hpCurve(d) * (1 + 0.25 * asc) * df.hp
        fireMul = min(2.2, 1 + 0.035 * (d - 1)) * (if (has("overclock")) 1.2 else 1.0) * df.fire
        Game.bulletSpeedMul = min(1.55, 1 + 0.016 * (d - 1)) * (if (has("hyperflux")) 1.15 else 1.0) * (1 + 0.05 * asc) * df.bulletSpeed
        bossHpMul = GUARDIAN_HP_BUMP * hpCurve((sector * DIFFICULTY_STEPS_PER_SECTOR).toDouble()) * (1 + 0.35 * asc) * df.guardianHp
        bossTempo = min(GUARDIAN_TEMPO_MAX, 1 + GUARDIAN_TEMPO_STEP * (sector - 1) + 0.05 * asc) * df.tempo
        scoreMul = (1 + 0.1 * (sector - 1)) * (if (has("hyperflux")) 1.25 else 1.0) * df.score
        eliteChance = min(0.35, 0.012 + 0.012 * d + (if (has("elite")) 0.15 else 0.0))
        countMul = (if (has("swarm")) 1.4 else 1.0) * df.count
        dustMul = if (has("eclipse")) 1.3 else 1.0
    }

    fun label(): String = if (bossWave) "Sector $sector · Guardian" else "Sector $sector · Wave $wave"

    /** The run's difficulty (unless Medium) and plan (if endless), each followed by [sep]: "Hard · Endless · ". */
    fun planTag(sep: String): String = buildString {
        if (difficulty != Difficulty.MEDIUM) append(difficulty.label).append(sep)
        if (mode == RunMode.ENDLESS) append("Endless").append(sep)
    }

    /** A guardian's HP for this sector, whichever guardian it is: [guardianBase] on the guardian curve. */
    fun guardianHp(): Double = guardianBase(sector) * bossHpMul

    /** Which of BOSS_DEFS guards [s]: the story's in order, endless from its shuffled bag. */
    fun guardianIndex(s: Int = sector): Int = if (mode == RunMode.STORY) (s - 1) % BOSS_DEFS.size else endlessGuardian(guardianSeed, s)

    fun start(mode: RunMode = RunMode.STORY, seed: Int = 0, difficulty: Difficulty = Difficulty.MEDIUM) {
        reset()
        this.mode = mode; this.difficulty = difficulty; guardianSeed = seed
        recompute()
        nextWave()
    }

    /** Resume a checkpointed run: the next wave started will be `wave`. */
    fun resume(c: RunCheckpoint) {
        reset()
        mode = RunMode.of(c.mode); difficulty = Difficulty.of(c.difficulty); guardianSeed = c.seed
        sector = c.sector; wave = c.wave - 1; ascension = c.ascension
        mutators.addAll(c.mutators)
        recompute()
        nextWave()
    }

    fun nextWave() {
        Bg.warp = 0.0
        wave++
        recompute()
        waveTime = 0.0
        Game.ui.setWave(label(), mutators)
        Game.checkpoint()
        if (bossWave) {
            state = DirState.WARNING; timer = 3.4
            Game.ui.banner("Warning", if (finalSector) "Final guardian signature inbound" else "Guardian signature inbound", Tone.WARN, 2.8)
            Audio.play(Sfx.BOSS_WARN)
            Audio.music(MusicMode.BOSS)
            PostFx.glitch = 1.2
        } else {
            buildWave()
            state = DirState.ANNOUNCE; timer = 1.5
            Game.ui.banner("Wave $wave", "${planTag(" · ")}Sector $sector · ${palette.name}${if (ascension > 0) " · Ascension $ascension" else ""}", Tone.ION, 1.3)
        }
    }

    fun buildWave() {
        val d = d
        var budget = (waveBudget(d) * countMul).roundToInt()
        val pool = ENEMY_LIST.filter { it.minD <= d }
        queue.clear()
        var at = 0.1
        // A hostile always shows up in the wave it debuts (from sector 3 on, one debuts right after
        // each guardian); the run's first wave keeps its own random mix.
        for (def in pool) {
            if (def.minD <= 1 || !debuts(def, d) || def.cost > budget) continue
            val count = max(1, min(budget / def.cost, def.groupLo + d / 6))
            queue.add(WaveEntry(at, def.id, count, Rng.game.pick(def.formations)))
            budget -= count * def.cost
            at += Rng.game.range(1.1, 2.4) * (if (d > 8) 0.8 else 1.0)
        }
        var guard = 0
        while (budget > 0 && guard++ < 40) {
            val affordable = pool.filter { it.cost <= budget }
            if (affordable.isEmpty()) break
            // newly unlocked types get a 1.8× weight so they're introduced promptly
            val def = Rng.game.weighted(affordable) { it.weight * if (debuts(it, d)) 1.8 else 1.0 }
            val count = max(1, min(budget / def.cost, Rng.game.int(def.groupLo, def.groupHi) + d / 6))
            queue.add(WaveEntry(at, def.id, count, Rng.game.pick(def.formations)))
            budget -= count * def.cost
            at += Rng.game.range(1.1, 2.4) * (if (d > 8) 0.8 else 1.0)
        }
    }

    private val pts = DoubleArray(64)

    /** Formation generator. Every point is pushed outside SPAWN_SAFE_RADIUS of the player. Returns count. */
    fun formation(kind: Formation, n0: Int): Int {
        val n = min(n0, 32)
        val W = World.w; val H = World.h; val m = 60.0
        when (kind) {
            Formation.RING -> {
                val cx = W / 2; val cy = H / 2; val R = min(W, H) * 0.38; val a0 = Rng.game.angle()
                for (i in 0 until n) { val a = a0 + (i.toDouble() / n) * TAU; pts[i * 2] = cx + cos(a) * R * (W / min(W, H)) * 0.9; pts[i * 2 + 1] = cy + sin(a) * R }
            }
            Formation.LINE -> {
                val y = Rng.game.range(60.0, H * 0.22)
                for (i in 0 until n) { pts[i * 2] = lerp(W * 0.15, W * 0.85, if (n == 1) 0.5 else i.toDouble() / (n - 1)); pts[i * 2 + 1] = y }
            }
            Formation.PINCER -> {
                for (i in 0 until n) {
                    val left = i % 2 == 0
                    pts[i * 2] = if (left) m + Rng.game.range(0.0, 60.0) else W - m - Rng.game.range(0.0, 60.0)
                    pts[i * 2 + 1] = H * 0.3 + Rng.game.range(-1.0, 1.0) * H * 0.2
                }
            }
            Formation.CLUSTER -> {
                val cx = Rng.game.range(W * 0.2, W * 0.8); val cy = Rng.game.range(H * 0.15, H * 0.45)
                for (i in 0 until n) { val a = Rng.game.angle(); val r = Rng.game.range(0.0, 70.0); pts[i * 2] = cx + cos(a) * r; pts[i * 2 + 1] = cy + sin(a) * r }
            }
            Formation.EDGE -> {
                val side = Rng.game.int(0, 3)
                for (i in 0 until n) {
                    val t = Rng.game.range(0.1, 0.9)
                    when (side) {
                        0 -> { pts[i * 2] = t * W; pts[i * 2 + 1] = m }
                        1 -> { pts[i * 2] = W - m; pts[i * 2 + 1] = t * H * 0.8 }
                        2 -> { pts[i * 2] = t * W; pts[i * 2 + 1] = H * 0.12 }
                        else -> { pts[i * 2] = m; pts[i * 2 + 1] = t * H * 0.8 }
                    }
                }
            }
        }
        val p = Game.player; val S = Cfg.SPAWN_SAFE_RADIUS
        for (i in 0 until n) {
            var x = clamp(pts[i * 2], m, W - m); var y = clamp(pts[i * 2 + 1], m, H - m)
            if (p != null) {
                val dx = x - p.x; val dy = y - p.y; val d = sqrt(dx * dx + dy * dy)
                if (d < S) {
                    // push out along the player→point ray; if that hits a wall, mirror through the centre
                    val k = (S + 20) / (if (d == 0.0) 1.0 else d)
                    x = p.x + dx * k; y = p.y + dy * k
                    if (x < m || x > W - m || y < m || y > H - m) { x = W - p.x + Rng.game.range(-40.0, 40.0); y = H - p.y + Rng.game.range(-40.0, 40.0) }
                    x = clamp(x, m, W - m); y = clamp(y, m, H - m)
                }
            }
            pts[i * 2] = x; pts[i * 2 + 1] = y
        }
        return n
    }

    private val spawnOpts = SpawnOpts()
    private fun dispatch(entry: WaveEntry) {
        val n = formation(entry.formation, entry.count)
        for (i in 0 until n) {
            spawnOpts.reset().elite = Rng.game.chance(eliteChance)
            Game.spawnEnemy(entry.type, pts[i * 2], pts[i * 2 + 1], spawnOpts)
        }
    }

    fun update(dt: Double) {
        timer -= dt
        waveTime += dt
        when (state) {
            DirState.ANNOUNCE -> if (timer <= 0) { state = DirState.SPAWNING; waveTime = 0.0 }
            DirState.SPAWNING -> {
                while (queue.isNotEmpty() && queue[0].at <= waveTime) dispatch(queue.removeAt(0))
                if (queue.isEmpty()) state = DirState.FIGHTING
            }
            DirState.FIGHTING -> {
                var alive = 0
                for (e in Game.enemies) if (!e.dead) alive++
                if (alive == 0 && Game.spawnQueue.isEmpty()) { state = DirState.CLEARED; timer = 1.9; onWaveCleared() }
                else if (waveTime > 75) { state = DirState.CLEARED; timer = 0.4 }
            }
            DirState.CLEARED -> if (timer <= 0) nextWave()
            DirState.WARNING -> if (timer <= 0) { spawnBoss(); state = DirState.BOSS }
            DirState.BOSS -> {}
            // The last sector never warps: its guardian ends the campaign (see Game.winRun).
            DirState.BOSS_CLEAR -> if (timer <= 0 && Game.pendingDrafts == 0 && Game.state == GameState.PLAYING && !finalSector) beginWarp()
            DirState.WARP -> {
                val k = 1 - timer / 2.6
                Bg.warp = sin(clamp(k, 0.0, 1.0) * Math.PI)
                if (!warpSwapped && k >= 0.5) {
                    warpSwapped = true
                    PostFx.flash(Pal.WHITE, 0.85)
                    val pal = palette
                    val prepared = nextPalette
                    // The background job usually finished during the victory pause; build now only if the warp outran it.
                    if (prepared === pal) Bg.adopt(nextSeed, pal, false)
                    else Bg.adopt(Rng.game.int(1, 1000000), pal, true)
                    nextPalette = null
                    Lattice.color = pal.lattice
                    val last = if (mutators.isNotEmpty()) " · ${mutatorName(mutators[mutators.size - 1])}" else ""
                    Game.ui.banner("Sector $sector", pal.name + last, Tone.SOLAR, 2.0)
                }
                if (timer <= 0) { Bg.warp = 0.0; nextWave() }
            }
            DirState.IDLE -> {}
        }
    }

    private fun onWaveCleared() {
        val bonus = 400.0 * wave * sector
        Game.addScore(bonus, Double.NaN, Double.NaN, true)
        Game.ui.toast("Wave clear · +${fmtInt(bonus * Game.mult * scoreMul)}", Tone.MINT)
        Audio.play(Sfx.WAVE_CLEAR)
        Game.pickups.magnetAll()
    }

    private fun spawnBoss() {
        val boss = Boss.create(guardianIndex())
        Game.enemies.add(boss)
        Game.boss = boss
        Game.ui.showBoss(boss)
        Game.ui.banner(boss.bdef.name, boss.bdef.title, Tone.WARN, 2.2)
    }

    fun onBossDefeated() {
        Haptics.play(Haptic.DOUBLE)
        state = DirState.BOSS_CLEAR
        timer = 3.2
        Audio.music(MusicMode.GAME)
        // The next sector's palette is known now, so its nebula is built during the victory pause.
        nextSeed = Rng.game.int(1, 1000000)
        val pal = NEBULA_PALETTES[sector % NEBULA_PALETTES.size]
        nextPalette = pal
        Env.nebula.prepare(nextSeed, pal)
    }

    private fun beginWarp() {
        sector++
        wave = 0
        // Endless ascends each time all ten guardians have fallen:
        // hostiles and guardians harden. A story run ends at its last guardian, so it never ascends
        // (one resumed from a 1.1.1 checkpoint keeps the ascension it had).
        if (mode == RunMode.ENDLESS) {
            val asc = (sector - 1) / BOSS_DEFS.size
            if (asc > ascension) { ascension = asc; Game.ui.toast("Ascension $asc · hostiles harden", Tone.SOLAR) }
        }
        val remaining = MUTATORS.filter { !mutators.contains(it.id) }
        if (remaining.isNotEmpty()) { val m = Rng.game.pick(remaining); mutators.add(m.id); Game.ui.toast("Mutator · ${m.name}", Tone.SOLAR) }
        recompute()
        state = DirState.WARP; timer = 2.6; warpSwapped = false
        Game.pickups.magnetAll()
        Audio.play(Sfx.TRANSITION)
    }

    companion object {
        /**
         * Whether [def] debuts in the wave at difficulty step [d]: at its own step, or, when that
         * step is a guardian's (the fourth of a sector), in the first wave after it.
         */
        fun debuts(def: EnemyDef, d: Int): Boolean =
            def.minD == d || (def.minD % DIFFICULTY_STEPS_PER_SECTOR == 0 && def.minD + 1 == d)

        /**
         * The base HP of [sector]'s guardian: the story guardian of that sector's own. Past the
         * campaign (endless) the waves keep growing, so the last guardian's grows with their budget.
         */
        fun guardianBase(sector: Int): Double {
            val n = Cfg.CAMPAIGN_SECTORS
            if (sector <= n) return BOSS_DEFS[max(1, sector) - 1].hp
            return BOSS_DEFS[n - 1].hp * waveBudget(sector * DIFFICULTY_STEPS_PER_SECTOR) / waveBudget(n * DIFFICULTY_STEPS_PER_SECTOR)
        }

        /**
         * An endless run's guardian for [sector]: every ten sectors deal all ten guardians once, in an
         * order shuffled from the run's [seed] and that cycle's number, never the same guardian twice
         * in a row (across cycles either). A pure function, so a resumed run meets the same ones.
         */
        fun endlessGuardian(seed: Int, sector: Int): Int {
            val n = BOSS_DEFS.size
            val s = max(1, sector)
            val order = IntArray(n)
            var last = -1
            for (cycle in 0..(s - 1) / n) {
                val rng = Rng(seed xor (cycle * -0x61c88647))   // a golden-ratio stride per cycle
                for (i in 0 until n) order[i] = i
                for (i in n - 1 downTo 1) { val j = rng.int(0, i); val t = order[i]; order[i] = order[j]; order[j] = t }
                if (order[0] == last) { order[0] = order[1]; order[1] = last }
                last = order[n - 1]
            }
            return order[(s - 1) % n]
        }
    }
}
