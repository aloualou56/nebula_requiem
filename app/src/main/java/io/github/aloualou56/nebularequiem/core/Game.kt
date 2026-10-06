package io.github.aloualou56.nebularequiem.core

import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * §21 GAME — state machine, fixed-step loop, collision resolution and run lifecycle.
 * States: title · hangar · playing · paused · draft · dying · ending · over · victory.
 *
 * The simulation advances in FIXED steps of Cfg.SIM_STEP (1/120 s) drawn from an accumulator of
 * real (time-scaled) frame time, capped at Cfg.MAX_STEPS per frame so a hitch runs slow instead of
 * spiralling. Rendering interpolates between the last two steps with [alpha], so 60, 90, 120, 144
 * and 240 Hz displays all show smooth motion while gameplay is bit-identical at every rate.
 */

/** ENDING is the transition out of a lost or abandoned run; VICTORY runs from the final guardian's break to the title. */
enum class GameState { BOOT, TITLE, HANGAR, PLAYING, PAUSED, DRAFT, DYING, ENDING, OVER, VICTORY }
enum class Screen { TITLE, HANGAR, SETTINGS, MANUAL, DRAFT, PAUSE, OVER, VICTORY, PLAN }

/**
 * The victory, in order: the final guardian's death throes (the run is already won), a pause while
 * its stardust flies home (the run is banked as it starts), the warp out of the arena, the ending.
 */
enum class VictoryStage { BREAKING, PAUSE, WARP, ENDING }

class RunStats {
    var score = 0.0; var kills = 0; var grazes = 0; var dust = 0.0; var level = 1; var xp = 0.0; var xpNext = 0
    var time = 0.0; var bossKills = 0; var maxCombo = 0; var maxMult = 1.0
    val perksTaken = ArrayList<String>(); var rerolls = 0; var damage = 0.0; var hits = 0
}

class RunSummary(
    val title: String, val eyebrow: String, val hull: String, val sector: String, val time: String,
    val kills: String, val bosses: Int, val grazes: String, val chain: String, val perks: List<String>,
    val score: String, val record: Boolean, val dust: Int,
    /** The difficulty's name, or empty on Medium. */
    val level: String = ""
)

class Draft(var options: List<PerkDef>, val guarantee: Rarity?)

class Nova {
    var x = 0.0; var y = 0.0; var r = 0.0; var pr = 0.0; var speed = 1500.0; var max = 0.0; var dmg = 0.0; var t = 0.0
    val hit = IntSet()
}

/** What the game asks of the UI layer (screens, HUD, banners); a no-op in headless tests. */
interface UiBridge {
    fun toast(text: String, tone: Tone) {}
    fun banner(title: String, sub: String, tone: Tone, dur: Double) {}
    fun flashHull() {}
    fun setWave(label: String, mutators: List<String>) {}
    fun showBoss(boss: Boss) {}
    fun hideBoss() {}
    fun show(screen: Screen?) {}
    val current: Screen? get() = null
    fun renderPause() {}
    fun openDraft(draft: Draft, rerolls: Int, eyebrow: String) {}
    fun refreshDraft(draft: Draft, rerolls: Int) {}
    /** Play the pick animation; call onDone ~520 ms later. */
    fun pickAnimation(index: Int, onDone: () -> Unit) { onDone() }
    fun renderGameOver(summary: RunSummary) {}
    fun renderVictory(summary: RunSummary) {}
    /** Cover → run `mid` while hidden → reveal. */
    fun transition(label: String, mid: () -> Unit) { mid() }
    val transitionBusy: Boolean get() = false
    fun draftArmed(): Boolean = true
    fun armPauseGuard() {}
    fun disarmAbandon() {}
    fun onSettingsChanged() {}
}

/** Real-time (delayed) callbacks, run on the game thread. */
object Timers {
    private class T(val at: Double, val fn: () -> Unit)
    private val list = ArrayList<T>()
    private val due = ArrayList<T>()
    fun after(ms: Double, fn: () -> Unit) { list.add(T(Env.now() + ms, fn)) }
    fun tick() {
        if (list.isEmpty()) return
        val now = Env.now()
        due.clear()
        val it = list.iterator()
        while (it.hasNext()) { val t = it.next(); if (t.at <= now) { due.add(t); it.remove() } }
        for (t in due) t.fn()
    }
    fun clear() { list.clear() }
}

object Game {
    var ui: UiBridge = object : UiBridge {}
    /** Fixed run seed (tests); null = random per run. */
    var seedOverride: Int? = null

    var state = GameState.BOOT
    var time = 0.0
    var slowT = 0.0; var slowDur = 0.0; var slowScale = 1.0; var hitstopT = 0.0; var timeScale = 1.0
    var enemyTimeScale = 1.0
    var bulletSpeedMul = 1.0
    var player: Player? = null
    val enemies = ArrayList<Enemy>()
    val spawnQueue = ArrayList<Triple<String, DoubleArray, SpawnOpts>>()
    val lasers = ArrayList<Laser>()
    var boss: Boss? = null
    private val novaObj = Nova()
    var nova: Nova? = null
    val ebullets = BulletPool(Cfg.MAX_ENEMY_BULLETS)
    val pshots = ShotPool(Cfg.MAX_PLAYER_SHOTS)
    val pickups = PickupSystem()
    val director by lazy { Director() }
    var run: RunStats? = null
    var combo = 0; var comboT = 0.0; var mult = 1.0
    var pendingDrafts = 0
    var draft: Draft? = null
    var draftOpen = false
    private var choosing = false
    private var bossReward = false
    /** The run's stardust and records are in the save: nothing may bank it again. */
    private var banked = false
    var victoryStage = VictoryStage.BREAKING
        private set
    /** Seconds into the current victory stage. */
    var victoryT = 0.0
        private set
    var victorySummary: RunSummary? = null
        private set
    /** Edge-triggered actions latched until a simulation step consumes them (dash, bomb, overdrive). */
    val actions = arrayOf(Act.NONE, Act.NONE, Act.NONE)
    /** When each ability was last pressed: a press during a cooldown is retried for 150 ms. */
    val actionBuf = doubleArrayOf(-1e9, -1e9, -1e9)
    private val actionWin = doubleArrayOf(150.0, 150.0, 150.0)
    private val actionInputs = arrayOf(InputAction.DASH, InputAction.BOMB, InputAction.OVERDRIVE)
    private var coronaT = 0.0
    var deathT = 0.0
    var hurtFade = 0.0

    // attract mode
    private var attractT = 0.0; private var attractI = 0
    var attractX = Double.NaN; var attractY = Double.NaN

    // fixed-step clock
    /** Banked simulation time not yet stepped. */
    var accumulator = 0.0
        private set
    /** Real time not yet consumed by the dilation clock. */
    private var realAcc = 0.0

    /** Advance hit-stop and slow-motion by one real-time quantum; returns this quantum's time scale. */
    private fun dilationTick(q: Double): Double {
        var scale = 1.0
        if (slowT > 0) {
            slowT -= q
            val k = clamp(slowT / (slowDur * 0.3), 0.0, 1.0)
            scale = lerp(1.0, slowScale, k)
            if (slowT <= 0) { slowScale = 1.0; slowDur = 0.0 }
        }
        if (hitstopT > 0) { hitstopT -= q; scale = 0.0 }
        return scale
    }
    /** Render interpolation factor between the previous and current simulation state. */
    var alpha = 0.0
        private set
    var stepsThisFrame = 0
        private set

    val grid = SpatialHash(96.0)
    val near = ArrayList<Enemy>(64)
    val sep = ArrayList<Enemy>(64)
    private val near2 = ArrayList<Enemy>(64)

    private val SIM_STATES = setOf(GameState.PLAYING, GameState.DYING, GameState.TITLE, GameState.HANGAR, GameState.OVER, GameState.VICTORY)

    /* ─────────────────────────── quality & resize ─────────────────────────── */

    /** Called by the platform when the surface (or the effective quality) changes size. */
    fun onResize(arenaChanged: Boolean, viewportChanged: Boolean) {
        if (viewportChanged) {
            Input.releaseSticks()
            if (state == GameState.PLAYING) pause()
        }
        if (arenaChanged) {
            Light.resize(World.w, World.h)
            Lattice.build(World.w, World.h)
            Bg.buildStars(Bg.seed)
            Env.nebula.adopt(Bg.seed, Bg.palette, true)
            player?.let { it.x = clamp(it.x, 16.0, World.w - 16); it.y = clamp(it.y, 16.0, World.h - 16); it.px = it.x; it.py = it.y }
        }
    }

    var onQualityChanged: ((Quality) -> Unit)? = null
    fun applyQuality(q: Quality) {
        World.quality = q
        Fx.configure(q)
        World.updateRenderScale()
        onQualityChanged?.invoke(q)
    }

    /* ─────────────────────────── run lifecycle ─────────────────────────── */

    /** The flight plan and difficulty of the run now playing, or of the last one (the game over's Relaunch flies them again). */
    var lastMode = RunMode.STORY
        private set
    var lastDifficulty = Difficulty.MEDIUM
        private set

    /** Fly [mode] at the difficulty chosen last. */
    fun launch(mode: RunMode, difficulty: Difficulty = Difficulty.of(Save.data.difficulty)) {
        if (ui.transitionBusy) return
        Save.data.plan = mode.id; Save.data.difficulty = difficulty.id; Save.commit()
        ui.transition("Launch") { startRun(null, mode, difficulty) }
    }

    fun resumeCheckpoint() {
        val c = Save.data.run ?: return
        if (ui.transitionBusy) return
        ui.transition("Resume") { startRun(c) }
    }

    fun startRun(restore: RunCheckpoint?, plan: RunMode = RunMode.STORY, level: Difficulty = Difficulty.MEDIUM) {
        val save = Save.data
        Rng.game.seed(seedOverride ?: Rng.randomSeed())
        val mode = if (restore != null) RunMode.of(restore.mode) else plan
        val difficulty = if (restore != null) Difficulty.of(restore.difficulty) else level
        lastMode = mode; lastDifficulty = difficulty
        clearWorld()
        val hullId = restore?.hull?.takeIf { it in save.unlocked } ?: save.selected
        val p = Player(hullId)
        player = p
        val r = RunStats()
        r.xpNext = xpCurve(1); r.rerolls = save.up("fortune")
        run = r
        combo = 0; comboT = 0.0; mult = 1.0
        pendingDrafts = 0; draftOpen = false; choosing = false; bossReward = false
        banked = false; victoryStage = VictoryStage.BREAKING; victoryT = 0.0; victorySummary = null
        enemyTimeScale = 1.0; slowT = 0.0; hitstopT = 0.0; accumulator = 0.0; realAcc = 0.0
        for (i in actions.indices) { actions[i] = Act.NONE; actionBuf[i] = -1e9 }
        if (restore != null) {
            p.perks.putAll(restore.perks)
            p.recompute()
            p.hp = clampI(restore.hp, 1, p.stats.maxHp); p.shield = min(restore.shield, p.stats.shieldMax)
            p.bombs = restore.bombs; p.flux = restore.flux
            r.score = restore.score; r.kills = restore.kills; r.grazes = restore.grazes; r.dust = restore.dust
            r.level = restore.level; r.xp = restore.xp; r.xpNext = xpCurve(r.level); r.time = restore.time
            r.bossKills = restore.bossKills; r.maxCombo = restore.maxCombo; r.maxMult = restore.maxMult
            r.perksTaken.addAll(restore.perksTaken); r.rerolls = restore.rerolls; r.damage = restore.damage; r.hits = restore.hits
        } else {
            // Genesis Seed: free common grafts at launch
            for (i in 0 until save.up("genesis")) {
                val commons = PERKS.filter { it.rarity == Rarity.COMMON && p.level(it.id) < it.max }
                if (commons.isNotEmpty()) applyPerk(Rng.game.pick(commons), true)
            }
        }
        state = GameState.PLAYING
        // an endless run draws the seed of its guardians' order (a story run draws nothing extra)
        if (restore != null) director.resume(restore) else director.start(mode, if (mode == RunMode.ENDLESS) Rng.game.int(1, Int.MAX_VALUE - 1) else 0, difficulty)
        Input.releaseSticks()
        Bg.adopt(Rng.game.int(1, 1000000), director.palette, true)
        Lattice.color = director.palette.lattice
        Bg.dim = 0.45
        ui.show(null)
        ui.hideBoss()
        Audio.music(MusicMode.GAME)
        if (!save.seenTutorial) {
            save.seenTutorial = true; Save.commit()
            Timers.after(2200.0) { if (run != null) ui.toast("Only the white core can be hit", Tone.ION) }
            Timers.after(5200.0) { if (run != null) ui.toast("Graze bullets to charge flux", Tone.ION) }
        }
    }

    fun clearWorld() {
        for (e in enemies) EnemyPool.recycle(e)
        enemies.clear(); spawnQueue.clear(); lasers.clear()
        ebullets.clear(); pshots.clear(); pickups.clear()
        Fx.clear(true)
        boss = null; nova = null
        Cam.reset()
    }

    fun xpCurve(L: Int): Int = (10 + 7 * L + 1.6 * Math.pow(L.toDouble(), 1.55)).roundToInt()

    /** The score that should be playing right now. */
    fun musicMode(): MusicMode {
        if (state == GameState.DYING || state == GameState.ENDING) return MusicMode.GAMEOVER
        if (state == GameState.VICTORY) return if (victoryStage == VictoryStage.BREAKING) MusicMode.BOSS else MusicMode.MENU
        if (run == null) return MusicMode.MENU
        return if ((boss != null && !boss!!.dead) || director.state == DirState.WARNING) MusicMode.BOSS else MusicMode.GAME
    }

    fun toMenu(target: GameState) {
        val label = if (target == GameState.HANGAR) "Hangar" else "Nebula Requiem"
        ui.transition(label) {
            if (state != GameState.TITLE && state != GameState.HANGAR) enterAttract()
            state = target
            ui.show(if (target == GameState.HANGAR) Screen.HANGAR else Screen.TITLE)
            Audio.music(MusicMode.MENU)
        }
    }

    fun enterAttract() {
        clearWorld()
        player = null; run = null
        ui.hideBoss()
        bulletSpeedMul = 1.0
        Bg.warp = 0.0
        Bg.dim = 0.1
        accumulator = 0.0; realAcc = 0.0
    }

    fun pause() {
        if (state != GameState.PLAYING) return
        state = GameState.PAUSED
        ui.armPauseGuard()
        Input.releaseSticks()
        ui.renderPause()
        ui.show(Screen.PAUSE)
    }

    fun resume() {
        if (state != GameState.PAUSED) return
        ui.disarmAbandon()
        state = GameState.PLAYING
        ui.show(null)
        accumulator = 0.0
    }

    fun togglePause() {
        if (state == GameState.PLAYING) { Audio.play(Sfx.UI_CLICK); pause() }
        else if (state == GameState.PAUSED) {
            if (ui.current == Screen.SETTINGS) ui.show(Screen.PAUSE) else { Audio.play(Sfx.UI_CLICK); resume() }
        }
    }

    fun abandon() {
        if (run == null || state == GameState.ENDING || state == GameState.VICTORY) return
        endRun(false)
    }

    fun onPlayerDeath() {
        state = GameState.DYING
        deathT = 2.6
        slowmo(0.3, 1.8)
        Cam.addTrauma(1.0); Cam.punch(1.0)
        PostFx.flash(Pal.CRIMSON, 0.6); PostFx.pulseCA(16.0); PostFx.glitch = 1.5
        Audio.music(MusicMode.GAMEOVER)
    }

    /** Snapshot taken as each wave begins (so a run survives the process being killed). */
    fun checkpoint() {
        val r = run ?: return
        val p = player ?: return
        if (!p.alive || state == GameState.DYING || state == GameState.ENDING || state == GameState.VICTORY || banked) return
        val c = RunCheckpoint()
        c.hull = p.hullId; c.perks.putAll(p.perks); c.hp = p.hp; c.shield = p.shield; c.bombs = p.bombs; c.flux = p.flux
        c.score = r.score; c.kills = r.kills; c.grazes = r.grazes; c.dust = r.dust; c.level = r.level; c.xp = r.xp
        c.time = r.time; c.bossKills = r.bossKills; c.maxCombo = r.maxCombo; c.maxMult = r.maxMult
        c.perksTaken.addAll(r.perksTaken); c.rerolls = r.rerolls; c.damage = r.damage; c.hits = r.hits
        c.sector = director.sector; c.wave = director.wave; c.ascension = director.ascension; c.mutators.addAll(director.mutators)
        c.mode = director.mode.id; c.seed = director.guardianSeed; c.difficulty = director.difficulty.id
        Save.data.run = c
        Save.commit()
    }

    fun endRun(destroyed: Boolean) {
        val r = run ?: return
        if (banked) return
        val summary = bankRun(r,
            if (destroyed) "Signal lost" else "Run abandoned",
            if (destroyed) "Telemetry recovered from the wreck" else "Pilot recalled to the drydock")
        state = GameState.ENDING
        Input.releaseSticks()
        Audio.play(Sfx.GAME_OVER)
        ui.transition(summary.title) {
            enterAttract()
            state = GameState.OVER
            ui.show(Screen.OVER)
            ui.renderGameOver(summary)
        }
    }

    /**
     * Bank a finished run, exactly once: stardust (dust + score/220 × the dust multiplier + 40 per
     * guardian), lifetime stats and records. Drops the resumable checkpoint and writes the save now.
     * [dustCredit] and [scoreCredit] count pickups still flying home (a victory banks before they land).
     */
    private fun bankRun(r: RunStats, title: String, eyebrow: String, dustCredit: Double = 0.0, scoreCredit: Double = 0.0, won: Boolean = false): RunSummary {
        banked = true
        val save = Save.data; val p = player
        val score = r.score + scoreCredit
        val sectorReached = director.sector; val waveReached = director.wave
        val dustMul = if (p != null) p.stats.dustMul * director.dustMul else 1.0
        val earned = floor(r.dust + dustCredit + (score / 220) * dustMul + r.bossKills * 40).toInt()
        val record = score > save.stats.bestScore
        save.stardust += earned
        save.lifetimeStardust += earned
        val st = save.stats
        st.runs++
        st.totalKills += r.kills
        st.bossKills += r.bossKills
        st.playSeconds += r.time
        st.grazes += r.grazes
        if (record) st.bestScore = floor(score)
        if (sectorReached > st.bestSector || (sectorReached.toDouble() == st.bestSector && waveReached > st.bestWave)) { st.bestSector = sectorReached.toDouble(); st.bestWave = waveReached.toDouble() }
        if (director.mode == RunMode.ENDLESS && (sectorReached > st.endlessBestSector || (sectorReached.toDouble() == st.endlessBestSector && waveReached > st.endlessBestWave))) {
            st.endlessBestSector = sectorReached.toDouble(); st.endlessBestWave = waveReached.toDouble()
        }
        if (won) st.requiems += 1
        save.run = null
        Save.flush()
        val counts = LinkedHashMap<String, Int>()
        for (id in r.perksTaken) counts[id] = (counts[id] ?: 0) + 1
        val roman = arrayOf("", "I", "II", "III", "IV", "V")
        return RunSummary(
            title = title, eyebrow = eyebrow,
            hull = HULLS.getValue(p?.hullId ?: "lancer").name,
            sector = "$sectorReached · ${if (director.bossWave) "guardian" else "wave " + max(1, waveReached)}${if (director.mode == RunMode.ENDLESS) " · endless" else ""}${if (director.difficulty != Difficulty.MEDIUM) " · " + director.difficulty.label.lowercase() else ""}${if (director.ascension > 0) " · ascension ${director.ascension}" else ""}",
            time = fmtTime(r.time),
            kills = fmtInt(r.kills), bosses = r.bossKills,
            grazes = fmtInt(r.grazes), chain = "×${fmtFixed(r.maxMult, 2)}",
            perks = counts.entries.map { (id, n) -> "${PERK_BY_ID.getValue(id).name} ${roman.getOrElse(n) { n.toString() }}" },
            score = fmtInt(score), record = record, dust = earned,
            level = if (director.difficulty == Difficulty.MEDIUM) "" else director.difficulty.label
        )
    }

    /* ─────────────────────────── victory ─────────────────────────── */

    /** Seconds of the victory pause (stardust flying home) and of the warp out of the arena. */
    const val VICTORY_PAUSE = 3.0
    const val VICTORY_WARP = 1.4

    /** Whether the HUD belongs on screen: during a run, until the victory warp lifts the ship out. */
    val hudShown: Boolean get() = run != null && !(state == GameState.VICTORY && (victoryStage == VictoryStage.WARP || victoryStage == VictoryStage.ENDING))

    /**
     * The final guardian just broke with the pilot alive: the run is won from here. Nothing can hurt
     * the ship any more, no graft draft opens, the sector never warps, and pausing or abandoning
     * can't end it. Leftover hostiles and bullets are cleared (without rewards).
     */
    fun winRun() {
        if (state != GameState.PLAYING || run == null) return
        state = GameState.VICTORY
        victoryStage = VictoryStage.BREAKING; victoryT = 0.0
        Input.releaseSticks()
        for (e in enemies) if (!e.isBoss && !e.dead) { e.dead = true; Fx.explosion(e.x, e.y, e.color, 0.9) }
        spawnQueue.clear()
        for (l in lasers) l.dead = true
        ebullets.cancel()
    }

    /**
     * The final guardian's rewards just dropped: bank the run now, counting the stardust and score still
     * flying home, so killing the app from here on loses nothing. The motes then stream in during the pause.
     */
    private fun startVictoryPause() {
        val r = run ?: return
        val p = player
        victoryStage = VictoryStage.PAUSE; victoryT = 0.0
        pickups.magnetAll()
        // what each pickup in flight will add as it lands (see onPickup); the combo stays frozen from here
        var dust = 0.0; var score = 0.0; var hp = p?.hp ?: 0; val maxHp = p?.stats?.maxHp ?: 0
        for (k in pickups.list) {
            if (k.dead) continue
            when (k.kind) {
                PickupKind.DUST -> if (p != null) dust += k.value * p.stats.dustMul * director.dustMul
                PickupKind.GEM -> score += Math.round(k.value * 20 * mult * director.scoreMul).toDouble()
                PickupKind.HEAL -> if (hp < maxHp) hp++ else score += Math.round(2000.0 * mult * director.scoreMul).toDouble()
                else -> {}
            }
        }
        victorySummary = bankRun(r, "Requiem complete", "All ten guardians broken", dust, score, won = true)
        Haptics.play(Haptic.DOUBLE)
        Audio.music(MusicMode.MENU)
        Audio.play(Sfx.WAVE_CLEAR)
        ui.banner("Requiem complete", "The nebula falls silent", Tone.SOLAR, 2.6)
    }

    /** The victory's clock, once per rendered frame (like the death timer). */
    private fun updateVictory(dt: Double) {
        victoryT += dt
        when (victoryStage) {
            VictoryStage.BREAKING -> {}
            VictoryStage.PAUSE -> if (victoryT >= VICTORY_PAUSE) {
                victoryStage = VictoryStage.WARP; victoryT = 0.0
                Audio.play(Sfx.OVERDRIVE)
                player?.let { Lattice.impulse(it.x, it.y, 420.0, 900.0); Fx.ring(it.x, it.y, 10.0, 160.0, 0.6, it.color, 4.0) }
            }
            VictoryStage.WARP -> {
                val k = clamp(victoryT / VICTORY_WARP, 0.0, 1.0)
                Bg.warp = Ease.inCubic(k)
                if (k >= 1) enterEnding()
            }
            // a slow cruise through the stars while the ending plays
            VictoryStage.ENDING -> Bg.warp = damp(Bg.warp, 0.05, 1.2, dt)
        }
    }

    /** During the warp the ship stops answering the sticks, settles, then leaps up and out of the arena. */
    private fun warpOut(p: Player, dt: Double) {
        p.px = p.x; p.py = p.y; p.prevAngle = p.angle
        p.angle = dampAngle(p.angle, -HALF_PI, 8.0, dt)
        p.bank = damp(p.bank, 0.0, 8.0, dt)
        val k = victoryT / VICTORY_WARP
        if (k < 0.2) { p.vx = damp(p.vx, 0.0, 10.0, dt); p.vy = damp(p.vy, 40.0, 6.0, dt) }
        else { p.vx = damp(p.vx, 0.0, 6.0, dt); p.vy -= 1900 * dt }
        p.x += p.vx * dt; p.y += p.vy * dt
        p.thrust = damp(p.thrust, if (k < 0.2) 0.4 else 1.6, 8.0, dt)
        val hx = kotlin.math.cos(p.angle); val hy = kotlin.math.sin(p.angle)
        for (e in p.hull.engines) Fx.engine(p.x + e[0] * hx - e[1] * hy, p.y + e[0] * hy + e[1] * hx, p.angle, p.color, p.thrust, dt)
        if (k > 0.2 && rateChance(0.25, dt)) Fx.afterimage(p)
    }

    /** The flash at the end of the warp: the arena is gone and the ending plays over the nebula. */
    private fun enterEnding() {
        val summary = victorySummary ?: return
        PostFx.flash(Pal.WHITE, 1.0); PostFx.pulseCA(10.0)
        enterAttract()
        Bg.warp = 1.0
        attractX = Double.NaN; attractY = Double.NaN
        victoryStage = VictoryStage.ENDING; victoryT = 0.0
        ui.show(Screen.VICTORY)
        ui.renderVictory(summary)
        Audio.play(Sfx.WARP)
    }

    /* ─────────────────────────── time control ─────────────────────────── */

    /** Simulation seconds the next `real` seconds will advance (hit-stop first, then the slow-motion ease). */
    fun simAhead(real: Double): Double {
        var st = slowT - hitstopT; var t = real - hitstopT; var sim = 0.0
        while (t > 1e-6) {
            val d = min(1.0 / 240, t)
            sim += d * if (st > 0 && slowDur > 0) lerp(1.0, slowScale, clamp(st / (slowDur * 0.3), 0.0, 1.0)) else 1.0
            st -= d; t -= d
        }
        return sim
    }
    fun slowmo(scale: Double, dur: Double) { slowScale = min(if (slowT > 0) slowScale else 1.0, scale); slowT = max(slowT, dur); slowDur = max(slowDur, dur) }
    fun hitstop(dur: Double) { hitstopT = max(hitstopT, dur) }

    /* ─────────────────────────── spawning ─────────────────────────── */

    fun spawnEnemy(type: String, x: Double, y: Double, o: SpawnOpts): Enemy {
        val e = EnemyPool.obtain(type)
        e.setup(ENEMY_DEFS.getValue(type), x, y, o)
        if (o.vx != 0.0) e.vx = o.vx
        if (o.vy != 0.0) e.vy = o.vy
        enemies.add(e)
        return e
    }
    fun queueSpawn(type: String, x: Double, y: Double, o: SpawnOpts) { spawnQueue.add(Triple(type, doubleArrayOf(x, y), o)) }
    private fun flushSpawns() {
        if (spawnQueue.isEmpty()) return
        for ((type, xy, o) in spawnQueue) spawnEnemy(type, xy[0], xy[1], o)
        spawnQueue.clear()
    }

    fun nearestEnemy(x: Double, y: Double, maxD: Double = 2000.0, preferBoss: Boolean = false): Enemy? {
        var best: Enemy? = null; var bd = maxD * maxD
        for (e in enemies) {
            if (e.dead || !e.active) continue
            if (e is Boss && e.invulnerable && e.introT > 0) continue
            if (e.spawnT > 0) continue
            var d = dist2(x, y, e.x, e.y)
            if (preferBoss && e.isBoss) d *= 0.6
            if (d < bd) { bd = d; best = e }
        }
        return best
    }

    /* ─────────────────────────── scoring & progression ─────────────────────────── */

    fun addScore(base: Double, x: Double, y: Double, silent: Boolean = false): Double {
        val r = run ?: return 0.0
        val v = Math.round(base * mult * director.scoreMul).toDouble()
        r.score += v
        if (!silent && !x.isNaN() && v >= 1500) Fx.text(x, y - 20, "+" + fmtInt(v), Pal.ION, 18.0, 1.1)
        return v
    }

    fun addXP(v: Double) {
        val r = run ?: return
        r.xp += v
        while (r.xp >= r.xpNext) {
            r.xp -= r.xpNext
            r.level++
            r.xpNext = xpCurve(r.level)
            pendingDrafts++
        }
    }

    fun onPickup(p: Pickup) {
        val pl = player ?: return
        val r = run ?: return
        when (p.kind) {
            PickupKind.XP -> { addXP(p.value * pl.stats.xpMul); Audio.play(Sfx.PICKUP, pitch = 1 + min(0.6, (combo % 30) * 0.02)) }
            PickupKind.DUST -> {
                val v = p.value * pl.stats.dustMul * director.dustMul
                r.dust += v
                Fx.text(p.x, p.y - 10, "+" + max(1, v.roundToInt()), Pal.SOLAR, 15.0, 0.8)
                Audio.play(Sfx.DUST)
            }
            PickupKind.HEAL -> {
                if (pl.hp < pl.stats.maxHp) { pl.hp++; ui.toast("Hull plate repaired", Tone.MINT) }
                else addScore(2000.0, p.x, p.y)
                Audio.play(Sfx.HEAL)
                Fx.ring(pl.x, pl.y, 10.0, 60.0, 0.4, Pal.MINT, 3.0)
            }
            PickupKind.BOMB -> { pl.bombs = min(6, pl.bombs + 1); ui.toast("+1 nova", Tone.PLASMA); Audio.play(Sfx.HEAL) }
            PickupKind.GEM -> { addScore(p.value * 20, Double.NaN, Double.NaN, true); Audio.play(Sfx.PICKUP, pitch = 1.8) }
        }
        if (p.kind != PickupKind.GEM) Fx.pickup(p.x, p.y, p.kind.color)
    }

    fun onEnemyKilled(e: Enemy) {
        val r = run ?: return
        r.kills++
        combo++
        comboT = Cfg.COMBO_WINDOW
        mult = 1 + min(combo, 160) * 0.025
        r.maxCombo = max(r.maxCombo, combo)
        r.maxMult = max(r.maxMult, mult)
        addScore(e.score, e.x, e.y)
        val size = clamp(e.r / 16, 0.6, 2.2)
        Fx.shatter(e.worldVerts(), e.poly.size / 2, e.x, e.y, e.vx, e.vy, e.color, 1.0)
        Fx.explosion(e.x, e.y, e.color, size)
        Audio.play(Sfx.EXPLODE, e.x, size = size)
        Cam.addTrauma(0.06 + 0.05 * size)
        PostFx.pulseCA(0.3 + 0.5 * size)
        val p = player
        pickups.dropXP(e.x, e.y, e.xp)
        if (e.elite) {
            val n = Rng.game.int(3, 6)
            for (i in 0 until n) pickups.spawn(PickupKind.DUST, e.x, e.y, 1.0, 1.4)
            if (Rng.game.chance(0.25)) pickups.spawn(PickupKind.HEAL, e.x, e.y, 1.0)
            if (Rng.game.chance(0.08)) pickups.spawn(PickupKind.BOMB, e.x, e.y, 1.0)
        } else {
            if (Rng.game.chance(0.05)) pickups.spawn(PickupKind.DUST, e.x, e.y, 1.0)
            if (p != null && p.hp < p.stats.maxHp && Rng.game.chance(0.013)) pickups.spawn(PickupKind.HEAL, e.x, e.y, 1.0)
            if (Rng.game.chance(0.005)) pickups.spawn(PickupKind.BOMB, e.x, e.y, 1.0)
        }
        if (director.has("volatile") && !e.isBoss) Patterns.ring(e.x, e.y, 6, 105.0, Rng.game.angle(), O.volatile)
        e.onDeath()
    }

    fun onBossDefeated(b: Boss) {
        val r = run ?: return
        r.bossKills++
        addScore(b.score, b.x, b.y)
        for (i in 0 until 26 + 6 * director.sector) pickups.spawn(PickupKind.DUST, b.x, b.y, if (Rng.game.chance(0.2)) 3.0 else 1.0, 2.4)
        pickups.dropXP(b.x, b.y, b.bdef.xp * (1 + 0.3 * (director.sector - 1)))
        pickups.spawn(PickupKind.HEAL, b.x, b.y, 1.0, 1.0)
        val p = player
        if (p != null && p.alive) { p.hp = min(p.stats.maxHp, p.hp + 1); p.iframes = max(p.iframes, 2.0) }
        boss = null
        ui.hideBoss()
        if (state == GameState.VICTORY) { startVictoryPause(); return }
        // the last sector's guardian never opens a draft or a warp (a pilot already going down loses)
        if (director.finalSector) return
        bossReward = true
        pendingDrafts++
        director.onBossDefeated()
    }

    fun onPlayerHit(p: Player) {
        run?.let { it.hits++ }
        combo = 0; comboT = 0.0; mult = 1.0
        ebullets.cancel(p.x, p.y, Cfg.MERCY_RADIUS, false)
        for (l in lasers) if (l.live && l.hits(p.x, p.y, 60.0)) l.age = max(l.age, l.warmup + l.duration)
        Audio.play(Sfx.HURT)
        Cam.addTrauma(0.65); Cam.kick(Rng.game.angle(), 120.0)
        PostFx.pulseCA(12.0); PostFx.glitch = 1.0; PostFx.flash(Pal.CRIMSON, 0.25)
        hitstop(0.09)
        slowmo(0.35, 0.5)
        Fx.explosion(p.x, p.y, Pal.CRIMSON, 1.2)
        ui.flashHull()
        hurtFade = 1.0
    }

    /* ─────────────────────────── damage pipeline ─────────────────────────── */

    fun damageEnemy(e: Enemy, dmg: Double, sx: Double, sy: Double, crit: Boolean = false, color: Int = 0, angle: Double = 0.0): Double {
        val fresh = e.hitFlash < 0.5
        val dealt = e.hurt(dmg)
        if (dealt <= 0) return 0.0
        run?.let { it.damage += dealt }
        if (fresh || crit) Fx.hit(sx, sy, angle, if (crit) Pal.SOLAR else e.color)
        else Fx.sparks(sx, sy, 2.0, e.color, 300.0, 1.2, angle + Math.PI, 0.18, 1.4)
        if (Save.data.settings.damageNumbers) {
            // Numbers aggregate per target over 0.14 s windows so streams of hits read as one tally.
            e.dmgAcc += dealt
            e.dmgCrit = e.dmgCrit || crit
            if (time - e.dmgT > 0.14 || e.dead) {
                if (Fx.layer(PLayer.TOP).size < 60) Fx.text(sx + Rng.vis.range(-10.0, 10.0), sy - 12, e.dmgAcc.roundToInt().toString(), if (e.dmgCrit) Pal.SOLAR else Pal.WHITE, if (e.dmgCrit) 19.0 else 14.0, 0.65)
                e.dmgAcc = 0.0; e.dmgCrit = false; e.dmgT = time
            }
        }
        Audio.play(if (crit) Sfx.CRIT else Sfx.HIT, sx)
        return dealt
    }

    /** Splash: everything (except the primary target) inside R takes damage. */
    private fun splash(x: Double, y: Double, R: Double, dmg: Double, except: Enemy) {
        Fx.ring(x, y, 6.0, R, 0.3, Pal.SOLAR, 3.0)
        Fx.flash(x, y, R * 0.8, 0.18, Pal.SOLAR)
        Light.add(x, y, R * 2, Pal.SOLAR, 1.0, true)
        val list = grid.query(x, y, R, near2)
        for (i in list.indices) { val e = list[i]; if (e !== except && e.active && Collide.circle(x, y, R, e.x, e.y, e.r)) damageEnemy(e, dmg, e.x, e.y) }
        val b = boss
        if (b != null && b !== except && !b.dead) for (part in b.parts()) if (Collide.circle(x, y, R, part.x, part.y, part.r)) { damageEnemy(b, dmg * part.mul, part.x, part.y); break }
    }

    /** Arc Conduit: greedy nearest-neighbour chain — each hop jumps to the closest unvisited target. */
    private val chainVisited = IntSet()
    private fun chain(from: Enemy, hops: Int, dmg: Double) {
        var cur = from
        chainVisited.clear(); chainVisited.add(from.id)
        for (h in 0 until hops) {
            var best: Enemy? = null; var bd = 240.0 * 240.0
            for (e in enemies) {
                if (!e.active || e.dead || chainVisited.has(e.id) || e.isBoss) continue
                val d = dist2(cur.x, cur.y, e.x, e.y)
                if (d < bd) { bd = d; best = e }
            }
            if (best == null) break
            chainVisited.add(best.id)
            Fx.bolt(cur.x, cur.y, best.x, best.y, Pal.ION)
            Light.add(best.x, best.y, 120.0, Pal.ION, 1.2, true)
            damageEnemy(best, dmg, best.x, best.y)
            cur = best
        }
        Audio.play(Sfx.LIGHTNING, from.x)
    }

    /* ─── nova bomb: an expanding wavefront r(t) = v·t that erases bullets it sweeps over ─── */
    fun startNova(x: Double, y: Double, power: Double, free: Boolean = false) {
        val p = player
        val n = novaObj
        n.x = x; n.y = y; n.r = 0.0; n.pr = 0.0; n.speed = 1500.0; n.max = sqrt(World.w * World.w + World.h * World.h) + 80
        n.dmg = 55 * power * (if (p != null) p.stats.damage / 10 else 1.0); n.hit.clear(); n.t = 0.0
        nova = n
        Audio.play(Sfx.BOMB)
        PostFx.flash(Pal.WHITE, if (free) 0.3 else 0.6); PostFx.pulseCA(12.0)
        Cam.addTrauma(if (free) 0.5 else 0.8); Cam.punch(-0.9)
        Lattice.impulse(x, y, 700.0, 2600.0)
        Fx.flash(x, y, 260.0, 0.6, Pal.PLASMA)
        Fx.sparks(x, y, 40.0, Pal.WHITE, 900.0, TAU, 0.0, 0.6, 2.4)
        if (!free) ui.toast("Nova", Tone.PLASMA)
    }

    private fun updateNova(dt: Double) {
        val n = nova ?: return
        n.t += dt
        n.pr = n.r
        n.r += n.speed * dt
        if (n.r > n.max) nova = null
    }

    /* ─── collision matrix handlers (see §11) ─── */

    private val hit = Vec2()
    /** Where a swept shot first touches a circle (ray from its start-of-step position, clamped to this step's travel). */
    private fun contactPoint(s: PlayerShot, cx: Double, cy: Double, cr: Double, out: Vec2): Vec2 {
        val dx = s.x - s.px; val dy = s.y - s.py; val len = sqrt(dx * dx + dy * dy); val R = cr + s.r
        if (len < 1e-6 || dist2(s.px, s.py, cx, cy) <= R * R) { out.x = s.px; out.y = s.py; return out }
        val ux = dx / len; val uy = dy / len; val t = Collide.ray(s.px, s.py, ux, uy, cx, cy, R)
        val tc = if (t < 0) len else min(t, len)
        out.x = s.px + ux * tc; out.y = s.py + uy * tc
        return out
    }

    /**
     * THE COLLISION MATRIX, walked in order each fixed step:
     *   PLAYER_SHOT × ENEMY (swept circle · spatial hash) · PLAYER × ENEMY_SHOT (circle + graze annulus)
     *   PLAYER × LASER (capsule) · PLAYER × ENEMY (circle · hash) · ORBITAL × ENEMY_SHOT (absorb)
     *   NOVA × ENEMY_SHOT (annulus wavefront) · CORONA × ENEMY (DoT tick) · PLAYER × PICKUP (magnet)
     */
    private fun collide(dt: Double) {
        collideShotsEnemies()
        // once the run is won nothing can hurt the pilot
        if (state != GameState.VICTORY) {
            collidePlayerBullets()
            collidePlayerLasers(dt)
            collidePlayerEnemies()
        }
        collideOrbitals(dt)
        collideNova()
        collideCorona(dt)
        pickups.collect()
    }

    private fun collideShotsEnemies() {
        val b = boss?.takeIf { !it.dead }
        val parts = b?.parts()
        for (s in pshots.list) {
            if (s.dead) continue
            val mx = (s.x + s.px) / 2; val my = (s.y + s.py) / 2
            val half = sqrt(dist2(s.x, s.y, s.px, s.py)) / 2 + s.r + 40
            val list = grid.query(mx, my, half, near)
            for (i in list.indices) {
                if (s.dead) break
                val e = list[i]
                if (!e.active || s.hasHit(e.id)) continue
                if (!Collide.swept(s.px, s.py, s.x, s.y, s.r, e.x, e.y, e.r)) continue
                val h = contactPoint(s, e.x, e.y, e.r, hit)
                if (e.blocks(h.x, h.y)) {
                    s.dead = true
                    Fx.sparks(h.x, h.y, 6.0, Pal.WHITE, 300.0, 1.6, atan2(-s.vy, -s.vx), 0.25, 1.4)
                    Audio.play(Sfx.DEFLECT, h.x)
                    break
                }
                shotHit(s, e, 1.0, h.x, h.y)
            }
            if (s.dead || parts == null || s.hasHit(b.id)) continue
            for (part in parts) {
                if (!Collide.swept(s.px, s.py, s.x, s.y, s.r, part.x, part.y, part.r)) continue
                val h = contactPoint(s, part.x, part.y, part.r, hit)
                if (b.invulnerable) {
                    s.dead = true
                    Fx.sparks(h.x, h.y, 3.0, Pal.WHITE, 240.0, 1.4, atan2(-s.vy, -s.vx), 0.2, 1.2)
                } else shotHit(s, b, part.mul, h.x, h.y)
                break
            }
        }
    }

    private fun shotHit(s: PlayerShot, e: Enemy, mul: Double, hx: Double, hy: Double) {
        val p = player; val dmg = s.dmg * mul
        damageEnemy(e, dmg, hx, hy, s.crit, 0, atan2(s.vy, s.vx))
        s.markHit(e.id)
        if (s.explosive > 0) splash(hx, hy, 50.0 + 16 * s.explosive, dmg * 0.4, e)
        if (p != null && p.stats.chain > 0 && Rng.game.chance(0.22)) chain(e, p.stats.chain + 1, dmg * 0.55)
        if (s.pierce > 0) s.pierce-- else s.dead = true
    }

    private fun collidePlayerBullets() {
        val p = player ?: return
        if (!p.alive) return
        val gr = Cfg.GRAZE_RADIUS; val list = ebullets.list
        // A hit cancels nearby bullets (shrinking the list), so the bound is re-read every iteration.
        var i = -1
        while (++i < list.size) {
            val b = list[i]
            if (b.dead || b.delay > 0) continue
            val dx = b.x - p.x; val dy = b.y - p.y
            if (dx > 60 || dx < -60 || dy > 60 || dy < -60) continue
            val d2 = dx * dx + dy * dy
            // bullet hitboxes are 70% of the visual radius — the genre's forgiving convention
            val R = b.r * 0.7 + p.r
            // Continuous test in the ship's frame: the relative motion this step passes within R of the origin.
            if (Collide.pointSeg2(0.0, 0.0, b.px - p.px, b.py - p.py, dx, dy) < R * R) { if (p.hurt()) b.dead = true; continue }
            if (!b.grazed) { val G = b.r + gr; if (d2 < G * G) { b.grazed = true; p.graze(b) } }
        }
    }

    private fun collidePlayerLasers(dt: Double) {
        val p = player ?: return
        if (!p.alive) return
        for (l in lasers) {
            if (!l.live) continue
            if (l.hits(p.x, p.y, p.r)) { p.hurt(); continue }
            // standing next to a live beam is a continuous graze
            if (l.hits(p.x, p.y, Cfg.GRAZE_RADIUS + 10)) {
                p.flux = min(1.0, p.flux + 0.35 * dt * p.stats.fluxGain)
                if (rateChance(0.3, dt)) Fx.graze(p.x, p.y, l.angle + HALF_PI)
            }
        }
    }

    private fun collidePlayerEnemies() {
        val p = player ?: return
        if (!p.alive) return
        val list = grid.query(p.x, p.y, 70.0, near)
        for (i in list.indices) {
            val e = list[i]
            if (!e.active || !Collide.circle(p.x, p.y, p.contactR, e.x, e.y, e.r * 0.8)) continue
            if (p.dashT > 0) continue
            if (p.hurt() && e.type == "mote") damageEnemy(e, 9999.0, e.x, e.y)
        }
        val b = boss
        if (b != null && !b.dead && b.introT <= 0 && b.dyingT <= 0 && p.dashT <= 0) {
            for (part in b.parts()) if (Collide.circle(p.x, p.y, p.contactR, part.x, part.y, part.r * 0.85)) { p.hurt(); break }
        }
    }

    private fun collideOrbitals(dt: Double) {
        val p = player ?: return
        if (!p.alive || p.stats.orbitals == 0) return
        val list = ebullets.list
        for (i in 0 until p.stats.orbitals) {
            val o = p.orbitals[i]
            for (b in list) {
                if (b.dead || b.delay > 0) continue
                if (Collide.circle(o.x, o.y, 11.0, b.x, b.y, b.r * 0.8)) { b.dead = true; Fx.sparks(b.x, b.y, 3.0, Pal.MINT, 200.0, TAU, 0.0, 0.2, 1.2) }
            }
            if (o.hitT > 0) continue
            val nl = grid.query(o.x, o.y, 30.0, near)
            for (k in nl.indices) { val e = nl[k]; if (e.active && Collide.circle(o.x, o.y, 10.0, e.x, e.y, e.r)) { o.hitT = rearm(o.hitT, 0.25, dt); damageEnemy(e, p.stats.damage * 0.9, o.x, o.y); break } }
        }
    }

    private fun collideNova() {
        val n = nova ?: return
        val r2 = n.r * n.r
        for (b in ebullets.list) {
            if (b.dead) continue
            if (dist2(b.x, b.y, n.x, n.y) < r2) {
                b.dead = true
                if (Rng.game.chance(0.5)) pickups.spawn(PickupKind.GEM, b.x, b.y, 1.0, 0.3)
            }
        }
        var i = 0
        while (i < enemies.size) {
            val e = enemies[i]; i++
            if (e.dead || n.hit.has(e.id)) continue
            if (e is Boss) {
                for (part in e.parts()) if (sqrt(dist2(part.x, part.y, n.x, n.y)) - part.r < n.r) { n.hit.add(e.id); damageEnemy(e, n.dmg * 0.3, part.x, part.y, true); break }
            } else if (e.active && sqrt(dist2(e.x, e.y, n.x, n.y)) - e.r < n.r) {
                n.hit.add(e.id)
                damageEnemy(e, n.dmg, e.x, e.y, true)
            }
        }
    }

    private fun collideCorona(dt: Double) {
        val p = player ?: return
        if (!p.alive || p.stats.corona == 0) return
        coronaT -= dt
        if (coronaT > 0) return
        coronaT = rearm(coronaT, 0.25, dt)
        val R = 78.0 + 18 * p.stats.corona; val dmg = p.stats.damage * 0.55 * p.stats.corona
        val list = grid.query(p.x, p.y, R, near)
        for (i in list.indices) { val e = list[i]; if (e.active && Collide.circle(p.x, p.y, R, e.x, e.y, e.r)) { damageEnemy(e, dmg, e.x, e.y); Fx.bolt(p.x, p.y, e.x, e.y, Pal.VIOLET) } }
        val b = boss
        if (b != null && !b.dead) for (part in b.parts()) if (Collide.circle(p.x, p.y, R, part.x, part.y, part.r)) { damageEnemy(b, dmg * part.mul, part.x, part.y); break }
    }

    /* ─────────────────────────── perk draft ─────────────────────────── */

    fun rollPerks(n: Int, minRarity: Rarity?): List<PerkDef> {
        val p = player ?: return emptyList()
        val avail = PERKS.filter { p.level(it.id) < it.max }
        val picks = ArrayList<PerkDef>()
        while (picks.size < n) {
            var pool = avail.filter { k -> k !in picks && (picks.isNotEmpty() || minRarity == null || k.rarity.rank >= minRarity.rank) }
            if (pool.isEmpty()) pool = avail.filter { it !in picks }
            if (pool.isEmpty()) break
            picks.add(Rng.game.weighted(pool) { it.rarity.w })
        }
        for (ps in PSEUDO_PERKS) if (picks.size < n) picks.add(ps)
        return picks
    }

    private fun openDraft() {
        val guarantee = if (bossReward) Rarity.RARE else null
        bossReward = false
        pendingDrafts--
        val d = Draft(rollPerks(3, guarantee), guarantee)
        draft = d
        draftOpen = true
        state = GameState.DRAFT
        Audio.play(Sfx.LEVEL_UP)
        ui.openDraft(d, run!!.rerolls, if (guarantee != null) "Guardian spoils · rare or better" else "Level ${run!!.level} · synaptic graft")
        Input.releaseSticks()
        Haptics.play(Haptic.CONFIRM)
    }

    fun rerollDraft() {
        if (!ui.draftArmed()) return
        val d = draft
        val r = run
        if (!draftOpen || d == null || r == null || r.rerolls <= 0) { Audio.play(Sfx.DENY); return }
        r.rerolls--
        d.options = rollPerks(3, d.guarantee)
        Audio.play(Sfx.PURCHASE)
        ui.refreshDraft(d, r.rerolls)
    }

    fun pickPerk(i: Int) {
        if (!draftOpen || choosing || !ui.draftArmed()) return
        val k = draft?.options?.getOrNull(i) ?: return
        choosing = true
        applyPerk(k)
        Audio.play(Sfx.PURCHASE)
        ui.pickAnimation(i) {
            choosing = false
            draftOpen = false
            ui.show(null)
            if (state == GameState.DRAFT) {
                state = GameState.PLAYING
                val p = player
                if (p != null) { p.iframes = max(p.iframes, 1.0); Fx.ring(p.x, p.y, 10.0, 140.0, 0.6, k.rarity.color, 4.0); Lattice.impulse(p.x, p.y, 300.0, 900.0) }
                slowmo(0.5, 0.6)
                accumulator = 0.0
            }
        }
    }

    fun applyPerk(k: PerkDef, silent: Boolean = false) {
        val p = player ?: return
        if (k.pseudo) {
            if (k.id == "repair") p.hp = min(p.stats.maxHp, p.hp + 1)
            if (k.id == "cache") run?.let { it.dust += 40 }
        } else {
            p.perks[k.id] = (p.perks[k.id] ?: 0) + 1
            p.recompute()
            if (k.id == "nanorepair") p.hp = min(p.stats.maxHp, p.hp + 1)
            if (k.id == "novaCache") p.bombs++
            if (k.id == "aegis") p.shield = p.stats.shieldMax
            run?.perksTaken?.add(k.id)
        }
        if (!silent) ui.toast("${k.name}${if (k.pseudo) "" else " · level " + (p.perks[k.id] ?: 1)}", if (k.rarity == Rarity.LEGENDARY) Tone.SOLAR else Tone.ION)
    }

    /* ─────────────────────────── main loop ─────────────────────────── */

    /**
     * One rendered frame of logic. `dt` is real seconds since the previous frame (already clamped
     * to 0.25 s by the caller). Input edge latches are consumed here; the simulation advances in
     * fixed steps from the accumulator.
     */
    fun frame(dt: Double) {
        Audio.update(dt)
        Timers.tick()
        Save.tick()
        var st = state

        if ((Input.pressed.contains(Input.PAD_PAUSE) || Input.pressed.contains(Input.TOUCH_PAUSE)) && (st == GameState.PLAYING || st == GameState.PAUSED)) { togglePause(); st = state }

        // latch edge-triggered gameplay actions until the next simulation step consumes them
        if (st == GameState.PLAYING) {
            val now = Env.now()
            for (a in 0 until 3) {
                if (Input.hit(actionInputs[a])) {
                    // A touch press that buzzed 'press' predicted the ability will be ready within the buffer.
                    actionBuf[a] = now; actionWin[a] = if (Input.btnOk[a]) 300.0 else 150.0; Input.btnOk[a] = false
                    actions[a] = Act.PRESS
                } else if (actions[a] == Act.NONE && now - actionBuf[a] < actionWin[a]) actions[a] = Act.RETRY
            }
        }

        // Real time is consumed in fixed quanta (one step's worth). Each quantum first advances the
        // time-dilation clocks (hit-stop, then slow-motion easing back to 1 over its final 30%), then
        // banks quantum·scale of simulation time; every full SIM_STEP banked runs one step. Both
        // clocks are therefore quantized identically at any refresh rate: fully deterministic.
        stepsThisFrame = 0
        if (st in SIM_STATES) {
            realAcc = min(realAcc + dt, Cfg.MAX_STEPS * Cfg.SIM_STEP)
            val q = Cfg.SIM_STEP
            // 1e-9 slack absorbs floating-point drift so a 60 Hz frame is exactly two quanta.
            while (realAcc >= q - 1e-9 && stepsThisFrame < Cfg.MAX_STEPS) {
                if (state !in SIM_STATES) break
                realAcc -= q
                timeScale = dilationTick(q)
                accumulator += q * timeScale
                while (accumulator >= Cfg.SIM_STEP - 1e-9 && stepsThisFrame < Cfg.MAX_STEPS) {
                    if (state !in SIM_STATES) break
                    when (st) {
                        GameState.PLAYING, GameState.DYING -> sim(Cfg.SIM_STEP)
                        // the victory's arena keeps running until the warp; the ending has only the nebula
                        GameState.VICTORY -> if (run != null) sim(Cfg.SIM_STEP) else simEnding(Cfg.SIM_STEP)
                        else -> simAttract(Cfg.SIM_STEP)
                    }
                    accumulator -= Cfg.SIM_STEP
                    stepsThisFrame++
                    // Drafts open on the step clock too, so the step on which play halts is rate-independent.
                    if (state == GameState.PLAYING && pendingDrafts > 0 && player?.alive == true && !ui.transitionBusy) openDraft()
                }
            }
            if (state !in SIM_STATES) { accumulator = 0.0; realAcc = 0.0 }
            if (accumulator < 0) accumulator = 0.0
            if (realAcc < 0) realAcc = 0.0
        } else { accumulator = 0.0; realAcc = 0.0 }
        alpha = clamp((accumulator + realAcc * timeScale) / Cfg.SIM_STEP, 0.0, 1.0)

        if (state == GameState.DYING) {
            deathT -= dt
            if (deathT <= 0) endRun(true)
        }
        if (state == GameState.VICTORY) updateVictory(dt)
        if (state == GameState.PLAYING && pendingDrafts > 0 && player?.alive == true && !ui.transitionBusy) openDraft()

        val p = player
        Cam.update(dt)
        PostFx.update(dt)
        Bg.update(dt, p?.x ?: (World.w / 2 + kotlin.math.sin(time * 0.2) * 200), p?.y ?: (World.h / 2 + kotlin.math.cos(time * 0.15) * 80))
        if (hurtFade > 0) hurtFade = max(0.0, hurtFade - dt * 1.8)
        Env.platform.setPlaying(state == GameState.PLAYING)
    }

    /** One fixed simulation step of the live game. */
    private fun sim(dt: Double) {
        val p0 = player
        // Overdrive dilates enemy time toward 0.62 (folded into the step so it is deterministic).
        enemyTimeScale = damp(enemyTimeScale, if (p0 != null && p0.overdrive > 0) 0.62 else 1.0, 6.0, dt)
        val et = dt * enemyTimeScale
        time += dt
        run?.let { it.time += dt }
        flushSpawns()
        val p = player ?: return
        if (p.alive) { if (state == GameState.VICTORY && victoryStage == VictoryStage.WARP) warpOut(p, dt) else p.update(dt, actions) }
        actions[0] = Act.NONE; actions[1] = Act.NONE; actions[2] = Act.NONE
        if (state == GameState.PLAYING) director.update(dt)

        var i = 0
        while (i < enemies.size) { enemies[i].update(et); i++ }
        i = enemies.size - 1
        while (i >= 0) { val e = enemies[i]; if (e.dead) { enemies.removeAt(i); EnemyPool.recycle(e) }; i-- }

        grid.clear()
        for (e in enemies) if (!e.isBoss && e.active) grid.insert(e, e.x, e.y, e.r)

        ebullets.update(et)
        pshots.update(dt)
        for (l in lasers) l.update(et)
        i = lasers.size - 1
        while (i >= 0) { if (lasers[i].dead) lasers.removeAt(i); i-- }
        updateNova(dt)
        pickups.update(dt)

        collide(dt)

        // after a victory banks the run the chain holds, so the pickups still landing add what was banked
        if (combo > 0 && !banked) {
            comboT -= dt
            if (comboT <= 0) { combo = 0; mult = 1.0 }
        }
        Fx.update(dt)
        stepHook?.invoke()
    }

    /** The ending: no arena, no attract-mode emitter; the stars, motes and nebula keep moving. */
    private fun simEnding(dt: Double) {
        time += dt
        Fx.update(dt)
    }

    /** Called after every live simulation step (determinism tests). */
    var stepHook: (() -> Unit)? = null

    /** Attract mode behind the menus: a harmless guardian-style emitter paints patterns. */
    private fun simAttract(dt: Double) {
        time += dt
        attractT += dt
        val a = attractT
        val cx = World.w * 0.5 + kotlin.math.sin(a * 0.23) * World.w * 0.18
        val cy = World.h * 0.5 + kotlin.math.cos(a * 0.31) * World.h * 0.12
        bulletSpeedMul = 0.55
        val phase = floor(a / 9).toInt() % 3
        if (phase == 0 && every(a, dt, 0.11) > 0) Patterns.helix(cx, cy, a, 3, 0.9, 0.5, 0.5, 160.0, O.helixA)
        if (phase == 1 && every(a, dt, 0.05) > 0) { Patterns.phyllo(cx, cy, attractI, 1, 140.0, 0.0, O.phyllo[attractI % 3]); attractI++ }
        if (phase == 2 && every(a, dt, 1.4) > 0) Patterns.rose(cx, cy, 60, 170.0, 3.0 + (floor(a).toInt() % 3), a, 0.42, O.rose)
        if (every(a, dt, 2.2) > 0) Fx.explosion(Rng.game.range(0.1, 0.9) * World.w, Rng.game.range(0.1, 0.9) * World.h, Rng.game.pick(Pal.BULLET_COLORS), 0.8)
        ebullets.update(dt)
        pickups.update(dt)
        Fx.update(dt)
        attractX = cx; attractY = cy
    }

    /** Steady light & gravity sources, gathered once per rendered frame. */
    fun gatherFields() {
        val p = player
        if (run == null && !attractX.isNaN()) { Lattice.well(attractX, attractY, 260.0, 200.0); Light.add(attractX, attractY, 220.0, Pal.PLASMA, 0.9) }
        if (p != null && p.alive) {
            Light.add(p.x, p.y, 150.0, p.color, 0.8 + if (p.overdrive > 0) 0.6 else 0.0)
            Lattice.well(p.x, p.y, 90.0, -120.0)
            for (i in 0 until p.stats.orbitals) Light.add(p.orbitals[i].x, p.orbitals[i].y, 60.0, Pal.MINT, 0.4)
            if (p.stats.corona > 0) Light.add(p.x, p.y, 78.0 + 18 * p.stats.corona, Pal.VIOLET, 0.5)
        }
        for (e in enemies) {
            if (e.dead) continue
            if (e is Boss) e.emitLight() else Light.add(e.x, e.y, e.r * 4.2, e.color, 0.32 + e.hitFlash * 0.8 + if (e.elite) 0.25 else 0.0)
        }
        val list = ebullets.list; val stride = max(1, (list.size + 259) / 260)
        var i = 0
        while (i < list.size) { val b = list[i]; if (b.delay <= 0) Light.add(b.x, b.y, 70.0, b.color, 0.1 * stride); i += stride }
        i = 0
        while (i < pshots.list.size) { val s = pshots.list[i]; Light.add(s.x, s.y, 50.0, s.color, 0.25); i += 3 }
        nova?.let { Light.add(it.x, it.y, it.r + 80, Pal.PLASMA, 0.8) }
    }

    /** Reset all global state (tests). */
    fun resetForTests() {
        clearWorld(); Timers.clear()
        state = GameState.BOOT; time = 0.0; player = null; run = null; pendingDrafts = 0; draft = null; draftOpen = false; choosing = false
        banked = false; victoryStage = VictoryStage.BREAKING; victoryT = 0.0; victorySummary = null
        slowT = 0.0; slowDur = 0.0; slowScale = 1.0; hitstopT = 0.0; accumulator = 0.0; realAcc = 0.0; enemyTimeScale = 1.0; bulletSpeedMul = 1.0
        director.reset()
    }
}
