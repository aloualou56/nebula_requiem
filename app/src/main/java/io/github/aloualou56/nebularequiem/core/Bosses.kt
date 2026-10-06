package io.github.aloualou56.nebularequiem.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * §15 GUARDIANS. A guardian runs a phase table. Each phase is a playlist of attacks; an attack is
 * a pure function of its local clock t, so emission schedules are written with every(t, dt, I) and
 * crossed(t, dt, mark) and are independent of the step size. Crossing an HP threshold triggers a
 * phase shift: invulnerable, bullets converted to score, a shockwave and a new playlist.
 * The attack clock (and the guardian's lasers) runs at [Boss.tempo], which rises with the sector:
 * deeper guardians play the same attacks faster, so they fire denser patterns with shorter rests.
 */

class Attack(val name: String, val dur: Double, val rest: Double, val run: (Boss, Double, Double) -> Unit)
class Phase(val until: Double, val attacks: List<Attack>)
class Part(@JvmField var x: Double = 0.0, @JvmField var y: Double = 0.0, @JvmField var r: Double = 0.0, @JvmField var mul: Double = 1.0, @JvmField var head: Boolean = false)

/** Shared option sets for boss patterns (allocated once, reused every emission). */
object O {
    val helixA = BulletOpts(color = Pal.C_FF3AD9, r = 6.0)
    val helixB = BulletOpts(color = Pal.C_FFB238, r = 6.0)
    val helixC = BulletOpts(color = Pal.C_FF8AE9, r = 5.0, shape = Shape.RICE)
    val rose = BulletOpts(color = Pal.C_FFB238, r = 6.0)
    val roseInner = BulletOpts(color = Pal.C_FF3AD9, r = 5.0, shape = Shape.STAR, spin = 5.0)
    val cantor = BulletOpts(color = Pal.C_FF8AE9, r = 6.0, shape = Shape.DIAMOND)
    val aimRice = BulletOpts(color = Pal.C_FF3AD9, r = 5.0, shape = Shape.RICE)
    val spine = BulletOpts(color = Pal.C_FF4D6D, r = 5.0, shape = Shape.RICE)
    val spine2 = BulletOpts(color = Pal.C_FFB238, r = 6.0)
    val torrentA = BulletOpts(color = Pal.C_FFB238, r = 6.0, waveAmp = 30.0, waveFreq = 8.0, wavePhase = 0.0)
    val torrentB = BulletOpts(color = Pal.C_FF4D6D, r = 6.0, waveAmp = 30.0, waveFreq = 8.0, wavePhase = PI)
    val coil = BulletOpts(color = Pal.C_FF8AE9, r = 6.0)
    val mine = BulletOpts(color = Pal.C_FFE066, r = 9.0, shape = Shape.MINE, life = 4.0, splitAt = 1.6, splitN = 7, splitSpeed = 140.0, splitColor = Pal.C_FF4D6D)
    val phyllo = arrayOf(BulletOpts(color = Pal.C_C56BFF, r = 5.5), BulletOpts(color = Pal.C_FF8AE9, r = 5.5), BulletOpts(color = Pal.C_FFE066, r = 5.5))
    val poly = BulletOpts(color = Pal.C_C56BFF, r = 6.0, shape = Shape.DIAMOND)
    val poly2 = BulletOpts(color = Pal.C_FF8AE9, r = 6.0)
    val halo = BulletOpts(color = Pal.C_FFE066, r = 6.0, shape = Shape.STAR, spin = 4.0)
    val vertex = BulletOpts(color = Pal.C_FFB238, r = 5.5, shape = Shape.RICE)
    val vertexHot = BulletOpts(color = Pal.C_FFE066, r = 6.0)
    val heat = BulletOpts(color = Pal.C_FFE066, r = 6.0, shape = Shape.STAR, spin = 6.0, accel = -340.0, minSpeed = 0.0, retargetAt = 1.45, retargetSpeed = 330.0, retargetAccel = 520.0)
    val heat2 = BulletOpts(color = Pal.C_FF3AD9, r = 6.0, shape = Shape.STAR, spin = -6.0, accel = -300.0, minSpeed = 0.0, retargetAt = 1.7, retargetSpeed = 300.0, retargetAccel = 480.0)
    val lorenz = BulletOpts(color = Pal.C_FFB238, r = 5.5, life = 16.0)
    val spread = BulletOpts(color = Pal.C_FF3AD9, r = 6.0)
    val volatile = BulletOpts(color = Pal.C_FF4D6D, r = 5.0, delay = 0.15)
    // V Fourier Orrery
    val harmonic = arrayOf(BulletOpts(color = Pal.C_FF7A59, r = 5.5), BulletOpts(color = Pal.C_FFB238, r = 5.5), BulletOpts(color = Pal.C_FF8AE9, r = 5.5))
    val phasor = BulletOpts(color = Pal.C_FF7A59, r = 6.0)
    val phasor2 = BulletOpts(color = Pal.C_FFE066, r = 6.0, shape = Shape.DIAMOND)
    val wheelCw = BulletOpts(color = Pal.C_FFB238, r = 5.5, shape = Shape.RICE, curve = 1.0, accel = 55.0, maxSpeed = 420.0)
    val wheelCcw = BulletOpts(color = Pal.C_FF7A59, r = 5.5, shape = Shape.RICE, curve = -1.0, accel = 55.0, maxSpeed = 420.0)
    val pen = BulletOpts(color = Pal.C_FFE066, r = 5.0)
    val gibbs = BulletOpts(color = Pal.C_FF4D6D, r = 5.5, shape = Shape.RICE)
    // VI Penrose Pentarch
    val pentagrid = BulletOpts(color = Pal.C_FFD166, r = 5.5, shape = Shape.RICE)
    val goldA = BulletOpts(color = Pal.C_FFD166, r = 6.0)
    val goldB = BulletOpts(color = Pal.C_FF8AE9, r = 5.5)
    val goldC = BulletOpts(color = Pal.C_FFB238, r = 5.5, shape = Shape.STAR, spin = 4.0)
    val kite = BulletOpts(color = Pal.C_FFD166, r = 8.0, shape = Shape.DIAMOND, splitAt = 1.25, splitN = 5, splitSpeed = 120.0, splitShape = Shape.DIAMOND, splitColor = Pal.C_FF8AE9)
    val dart = BulletOpts(color = Pal.C_FF4D6D, r = 5.0, shape = Shape.LANCE)
    // VII Trefoil Hierophant
    val braid3 = Array(3) { k -> BulletOpts(color = if (k == 1) Pal.C_FF8AE9 else Pal.C_E05CFF, r = 5.5, waveAmp = 30.0, waveFreq = 5.0, wavePhase = k * TAU / 3) }
    val braid5 = Array(5) { k -> BulletOpts(color = if (k % 2 == 1) Pal.C_FF8AE9 else Pal.C_E05CFF, r = 5.5, waveAmp = 26.0, waveFreq = 5.5, wavePhase = k * TAU / 5) }
    val writheCw = BulletOpts(color = Pal.C_E05CFF, r = 5.5, shape = Shape.RICE, curve = 0.85, accel = 30.0, maxSpeed = 380.0)
    val writheCcw = BulletOpts(color = Pal.C_FF8AE9, r = 5.5, shape = Shape.RICE, curve = -0.85, accel = 30.0, maxSpeed = 380.0)
    val spool = BulletOpts(color = Pal.C_FFB238, r = 5.5)
    // VIII Mandelbrot Matriarch
    val julia = arrayOf(BulletOpts(color = Pal.C_FF5C8A, r = 5.5), BulletOpts(color = Pal.C_FF8AE9, r = 5.5))
    val bulb = BulletOpts(color = Pal.C_FFB238, r = 6.0)
    val cardioid = BulletOpts(color = Pal.C_FF5C8A, r = 6.0, shape = Shape.DIAMOND)
    val seahorseCw = BulletOpts(color = Pal.C_FF8AE9, r = 5.5, curve = 1.6, accel = -45.0, minSpeed = 35.0, life = 6.0)
    val seahorseCcw = BulletOpts(color = Pal.C_FF5C8A, r = 5.5, curve = -1.6, accel = -45.0, minSpeed = 35.0, life = 6.0)
    val bud = BulletOpts(color = Pal.C_FF5C8A, r = 9.0, shape = Shape.BIG, splitAt = 1.1, splitN = 6, splitSpeed = 125.0, splitColor = Pal.C_FFB238)
    // IX Automaton Augur
    val cell = arrayOf(BulletOpts(color = Pal.C_FFE066, r = 5.5, shape = Shape.DIAMOND), BulletOpts(color = Pal.C_FFB238, r = 5.5, shape = Shape.DIAMOND))
    val glider = BulletOpts(color = Pal.C_FF6A3D, r = 6.0)
    val row = BulletOpts(color = Pal.C_FFE066, r = 5.0, shape = Shape.RICE)
    // X Euler Eidolon
    val roseHalf = BulletOpts(color = Pal.C_FF8AE9, r = 5.5)
    val identity = BulletOpts(color = Pal.C_FFE066, r = 6.5, splitAt = 1.0, splitN = 2, splitSpeed = 140.0, splitColor = Pal.C_FF3AD9)
    val sigil = BulletOpts(color = Pal.C_FFB238, r = 5.5)
}

abstract class Boss(val bdef: BossDef) : Enemy() {
    override val isBoss: Boolean get() = true
    var title = bdef.title
    var introDur = 2.8; var introT = 2.8
    var x0 = 0.0; var y0 = 0.0; var anchorX = 0.0; var anchorY = 0.0
    var phase = 0; var attackIdx = 0; var attackT = -1e-4; var rest = 1.2
    var phaseShift = 0.0; var dyingT = 0.0; var boomT = 0.0
    var mt = 0.0
    /** How fast the attack clock runs (Director.bossTempo when it spawned): 1 in sector 1. */
    var tempo = 1.0
    protected val partList = ArrayList<Part>().apply { add(Part()) }
    abstract val phases: List<Phase>

    fun setupBoss(): Boss {
        val ed = EnemyDef(bdef.id, bdef.name, bdef.hp, bdef.r, 0.0, bdef.score, bdef.xp, 0, bdef.color, 0, 0.0, 1, 1, emptyList(), regularPoly(12))
        setup(ed, World.w / 2, -220.0, SpawnOpts().apply { instant = true })
        title = bdef.title
        hp = Game.director.guardianHp(); maxHp = hp
        tempo = Game.director.bossTempo
        introDur = 2.8; introT = 2.8
        x0 = World.w / 2; y0 = -220.0
        anchorX = World.w / 2; anchorY = World.h * 0.25
        phase = 0; attackIdx = 0; attackT = -1e-4; rest = 1.2
        phaseShift = 0.0; dyingT = 0.0; boomT = 0.0
        contain = false; mt = 0.0
        return this
    }

    val invulnerable: Boolean get() = introT > 0 || phaseShift > 0 || dyingT > 0
    override val active: Boolean get() = !dead

    open fun parts(): List<Part> { val p = partList[0]; p.x = x; p.y = y; p.r = r; p.mul = 1.0; return partList }

    abstract fun move(dt: Double)
    open fun introPose(dt: Double) {}
    open fun onPhase(i: Int) {}

    override fun update(dt: Double) {
        px = x; py = y; prevAngle = angle
        t += dt
        hitFlash = max(0.0, hitFlash - dt * 7)
        val ox = x; val oy = y
        if (dyingT > 0) { updateDying(dt); return }
        if (introT > 0) {
            introT -= dt
            val k = Ease.outCubic(clamp(1 - introT / introDur, 0.0, 1.0))
            x = lerp(x0, anchorX, k); y = lerp(y0, anchorY, k)
            introPose(dt)
            return
        }
        if (phaseShift > 0) {
            phaseShift -= dt
            move(dt * 0.35)
            if (rateChance(0.4, dt)) Fx.sparks(x, y, 2.0, color, 600.0, TAU, 0.0, 0.5, 2.0)
            return
        }
        move(dt)
        vx = (x - ox) / max(dt, 1e-4); vy = (y - oy) / max(dt, 1e-4)
        val atks = phases[phase].attacks
        val adt = dt * tempo
        if (rest > 0) rest -= adt
        else {
            val atk = atks[attackIdx]
            attackT += adt
            atk.run(this, attackT, adt)
            if (attackT >= atk.dur) {
                attackIdx = (attackIdx + 1) % atks.size
                attackT = -1e-4
                rest = atk.rest
            }
        }
        val ph = phases[phase]
        if (phase < phases.size - 1 && hp / maxHp <= ph.until) enterPhase(phase + 1)
    }

    /** Steady light + spacetime gravity well; called once per rendered frame. */
    open fun emitLight() {
        Light.add(x, y, 280.0, color, 0.85 + 0.3 * hitFlash)
        Lattice.well(x, y, 300.0, 240.0)
    }

    fun enterPhase(i: Int) {
        phase = i; attackIdx = 0; attackT = -1e-4; rest = 0.6
        phaseShift = 2.1
        for (l in Game.lasers) if (l.owner === this) l.dead = true
        val n = Game.ebullets.cancel()
        Game.addScore(n * 15.0, x, y)
        Audio.play(Sfx.BOSS_PHASE)
        Game.ui.banner(arrayOf("", "Phase II", "Phase III", "Phase IV").getOrElse(i) { "Phase shift" }.ifEmpty { "Phase shift" }, name, Tone.WARN, 1.6)
        Fx.explosion(x, y, color, 2.2)
        Fx.ring(x, y, 20.0, max(World.w, World.h), 1.2, Pal.WHITE, 6.0)
        Lattice.impulse(x, y, 900.0, 1400.0)
        Cam.addTrauma(0.7); Cam.punch(0.6)
        PostFx.flash(color, 0.35); PostFx.pulseCA(10.0); PostFx.glitch = 1.0
        onPhase(i)
    }

    override fun hurt(amount: Double): Double {
        if (dead || invulnerable) return 0.0
        hp -= amount
        hitFlash = 0.6   // guardians take constant fire: a softer flash keeps their silhouette readable
        if (hp <= 0) { hp = 0.0; startDying() }
        return amount
    }

    private fun startDying() {
        dyingT = 2.6
        for (l in Game.lasers) if (l.owner === this) l.dead = true
        Game.ebullets.cancel()
        Game.slowmo(0.3, 1.4)
        Audio.play(Sfx.BOSS_PHASE)
        Game.ui.banner("Guardian broken", name, Tone.SOLAR, 2.2)
        Cam.addTrauma(0.8)
        // The campaign's last guardian: the run is won from this moment (a pilot who is already
        // going down still loses).
        if (Game.director.finalSector && Game.state == GameState.PLAYING) Game.winRun()
    }

    private val deathVerts = DoubleArray(28)
    private fun updateDying(dt: Double) {
        dyingT -= dt
        angle += dt * 4
        boomT -= dt
        if (boomT <= 0) {
            boomT = rearm(boomT, 0.11, dt)
            val a = Rng.vis.angle(); val d = Rng.vis.range(0.0, r * 1.6)
            Fx.explosion(x + cos(a) * d, y + sin(a) * d, if (Rng.vis.chance(0.5)) color else Pal.WHITE, Rng.vis.range(0.8, 1.6))
            Audio.play(Sfx.EXPLODE, x, size = 1.4)
            Cam.addTrauma(0.2)
        }
        if (dyingT <= 0) {
            dead = true
            Fx.explosion(x, y, Pal.WHITE, 4.0)
            Fx.explosion(x, y, color, 3.4)
            for (i in 0 until 14) { val a = (i / 14.0) * TAU; val rr = r * Rng.vis.range(1.2, 2.0); deathVerts[i * 2] = x + cos(a) * rr; deathVerts[i * 2 + 1] = y + sin(a) * rr }
            Fx.shatter(deathVerts, 14, x, y, 0.0, 0.0, color, 2.4)
            Fx.ring(x, y, 30.0, max(World.w, World.h) * 1.2, 1.6, color, 10.0)
            Lattice.impulse(x, y, 1400.0, 2200.0)
            PostFx.flash(Pal.WHITE, 0.9); PostFx.pulseCA(16.0)
            Cam.addTrauma(1.0); Cam.punch(1.2)
            Audio.play(Sfx.BIG_EXPLODE)
            Game.onBossDefeated(this)
        }
    }

    companion object {
        /** The guardian of BOSS_DEFS[index] (the sector's guardian is index sector − 1). */
        fun create(index: Int): Boss = when (index % BOSS_DEFS.size) {
            0 -> HelixCantor()
            1 -> LissajousLeviathan()
            2 -> FractalSeraph()
            3 -> EntropyEngine()
            4 -> FourierOrrery()
            5 -> PenrosePentarch()
            6 -> TrefoilHierophant()
            7 -> MandelbrotMatriarch()
            8 -> AutomatonAugur()
            else -> EulerEidolon()
        }.also { it.setupBoss(); it.initBoss() }
    }

    open fun initBoss() {}
}

/** I — HELIX CANTOR. A three-lobed rose r(θ) = R(0.72 + 0.28·cos 3θ) wrapped in a double helix. */
class HelixCantor : Boss(BOSS_DEFS[0]) {
    var kRose = 0; var kIdx = -1; var cross = false
    override val phases get() = PHASES

    override fun move(dt: Double) {
        mt += dt
        // Lissajous figure-eight drift, frequency ratio 1:2
        val tx = World.w / 2 + World.w * 0.24 * sin(0.42 * mt)
        val ty = World.h * 0.24 + 42 * sin(0.84 * mt + 0.6)
        x = damp(x, tx, 3.0, dt); y = damp(y, ty, 3.0, dt)
        angle += dt * 0.6
    }

    companion object {
        val PHASES = listOf(
            Phase(0.5, listOf(
                Attack("Double Helix", 5.5, 0.8) { b, t, dt ->
                    val n = every(t, dt, 0.075)
                    for (i in 0 until n) Patterns.helix(b.x, b.y, t, 2, 1.9, 0.45, 0.9, 205.0, if (i % 2 == 1) O.helixA else O.helixB)
                },
                Attack("Rose Bloom", 4.6, 0.9) { b0, t, dt ->
                    val b = b0 as HelixCantor
                    if (every(t, dt, 1.15) > 0) {
                        b.kRose = if (b.kRose == 3) 4 else if (b.kRose == 4) 5 else 3
                        Patterns.rose(b.x, b.y, 64, 255.0, b.kRose.toDouble(), Rng.game.angle(), 0.42, O.rose)
                        Fx.ring(b.x, b.y, 10.0, 80.0, 0.4, b.color, 3.0); Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.7)
                    }
                    if (every(t, dt, 0.55, 0.3) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 3, 0.4, 210.0, O.aimRice)
                },
                Attack("Cantor Rain", 6.4, 1.0) { b, t, dt ->
                    if (every(t, dt, 1.6) > 0) { Patterns.cantor(2, Rng.game.next(), 150.0, false, 26.0, O.cantor); Audio.play(Sfx.LASER_CHARGE, World.w / 2) }
                    if (every(t, dt, 0.9, 0.45) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 3, 0.42, 230.0, O.aimRice)
                }
            )),
            Phase(0.0, listOf(
                Attack("Counter Helix", 6.0, 0.8) { b, t, dt ->
                    val n = every(t, dt, 0.085)
                    for (i in 0 until n) {
                        Patterns.helix(b.x, b.y, t, 2, 2.2, 0.4, 0.8, 215.0, O.helixA)
                        Patterns.helix(b.x, b.y, t, 2, -1.7, 0.3, 1.1, 185.0, O.helixB)
                    }
                    if (every(t, dt, 1.5, 0.7) > 0) Patterns.ring(b.x, b.y, 14, 150.0, Patterns.aim(b.x, b.y), O.helixC)
                },
                Attack("Rose Storm", 5.0, 0.9) { b0, t, dt ->
                    val b = b0 as HelixCantor
                    if (every(t, dt, 0.78) > 0) {
                        b.kIdx = (b.kIdx + 1) % 4
                        Patterns.rose(b.x, b.y, 72, 245.0, intArrayOf(5, 7, 4, 6)[b.kIdx].toDouble(), Rng.game.angle(), 0.4, O.rose)
                        Patterns.ring(b.x, b.y, 24, 115.0, Rng.game.angle(), O.roseInner)
                        Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.6)
                    }
                },
                Attack("Cantor Cross", 6.6, 1.0) { b0, t, dt ->
                    val b = b0 as HelixCantor
                    if (every(t, dt, 1.85) > 0) { b.cross = !b.cross; Patterns.cantor(2, Rng.game.next(), 145.0, b.cross, 26.0, O.cantor) }
                    if (every(t, dt, 1.1, 0.5) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 5, 0.8, 220.0, O.aimRice)
                }
            ))
        )
    }
}

class Segment(@JvmField var x: Double, @JvmField var y: Double, @JvmField val r: Double, @JvmField var a: Double) {
    @JvmField var px = x; @JvmField var py = y; @JvmField var pa = a
}

/**
 * II — LISSAJOUS LEVIATHAN. The head traces x = A·sin(at + π/2), y = B·sin(bt); the body is a
 * follow-the-leader chain (a one-pass distance constraint, as in FABRIK).
 */
class LissajousLeviathan : Boss(BOSS_DEFS[1]) {
    val segs = ArrayList<Segment>()
    var speedMul = 1.0; var ratioA = 1.0; var ratioB = 2.0; var spineI = 0; var lunge = 0.0; var lx = 0.0; var ly = 0.0
    override val phases get() = PHASES

    override fun initBoss() {
        segs.clear()
        for (i in 0 until 16) segs.add(Segment(x, y - i * 26, lerp(20.0, 10.0, i / 15.0), -HALF_PI))
        speedMul = 1.0; ratioA = 1.0; ratioB = 2.0; spineI = 0; lunge = 0.0; lx = 0.0; ly = 0.0
    }

    override fun onPhase(i: Int) {
        // Shed the last five segments in a cascade of explosions.
        var k = 0
        while (k < 5 && segs.size > 6) {
            val s = segs.removeAt(segs.size - 1)
            Fx.explosion(s.x, s.y, color, 1.2)
            for (j in 0 until 6) { val a = (j / 6.0) * TAU; shedVerts[j * 2] = s.x + cos(a) * s.r; shedVerts[j * 2 + 1] = s.y + sin(a) * s.r }
            Fx.shatter(shedVerts, 6, s.x, s.y, 0.0, 0.0, color, 1.2)
            k++
        }
        speedMul = 1.45; ratioA = 3.0; ratioB = 2.0
    }
    private val shedVerts = DoubleArray(12)

    override fun introPose(dt: Double) { follow() }

    override fun move(dt: Double) {
        mt += dt * speedMul
        val A = World.w * 0.36; val B = World.h * 0.2; val cx = World.w / 2; val cy = World.h * 0.36
        if (lunge > 0) {
            lunge -= dt
            x += lx * 640 * dt; y += ly * 640 * dt
            x = clamp(x, 40.0, World.w - 40); y = clamp(y, 40.0, World.h - 40)
        } else {
            val tx = cx + A * sin(ratioA * mt * 0.5 + HALF_PI); val ty = cy + B * sin(ratioB * mt * 0.5)
            x = damp(x, tx, 2.6, dt); y = damp(y, ty, 2.6, dt)
        }
        follow()
    }

    private fun follow() {
        var px0 = x; var py0 = y; var pa = angle
        for (i in segs.indices) {
            val s = segs[i]; val L = if (i == 0) 30.0 else 24.0
            s.px = s.x; s.py = s.y; s.pa = s.a
            val dx = s.x - px0; val dy = s.y - py0; val d0 = sqrt(dx * dx + dy * dy); val d = if (d0 == 0.0) 1.0 else d0
            s.x = px0 + (dx / d) * L; s.y = py0 + (dy / d) * L
            s.a = atan2(py0 - s.y, px0 - s.x)
            if (i == 0) pa = s.a
            px0 = s.x; py0 = s.y
        }
        angle = pa
    }

    override fun parts(): List<Part> {
        while (partList.size < segs.size + 1) partList.add(Part())
        while (partList.size > segs.size + 1) partList.removeAt(partList.size - 1)
        val h = partList[0]; h.x = x; h.y = y; h.r = 28.0; h.mul = 1.25; h.head = true
        for (i in segs.indices) { val p = partList[i + 1]; val s = segs[i]; p.x = s.x; p.y = s.y; p.r = s.r; p.mul = 0.55; p.head = false }
        return partList
    }

    override fun emitLight() {
        super.emitLight()
        var i = 0
        while (i < segs.size) { Light.add(segs[i].x, segs[i].y, 90.0, color, 0.4); i += 3 }
    }

    companion object {
        val PHASES = listOf(
            Phase(0.5, listOf(
                Attack("Spinal Volley", 5.0, 0.6) { b0, t, dt ->
                    val b = b0 as LissajousLeviathan
                    val n = every(t, dt, 0.11)
                    for (i in 0 until n) {
                        val s = b.segs[b.spineI++ % b.segs.size]
                        Game.ebullets.fire(s.x, s.y, s.a + HALF_PI, 165.0, O.spine)
                        Game.ebullets.fire(s.x, s.y, s.a - HALF_PI, 165.0, O.spine)
                    }
                },
                Attack("Sine Torrent", 4.4, 0.7) { b, t, dt ->
                    if (every(t, dt, 0.1) > 0) {
                        val a = Patterns.aim(b.x, b.y)
                        Game.ebullets.fire(b.x, b.y, a, 235.0, O.torrentA)
                        Game.ebullets.fire(b.x, b.y, a, 235.0, O.torrentB)
                        Audio.play(Sfx.ENEMY_SHOOT, b.x)
                    }
                },
                Attack("Coil Bloom", 4.0, 0.8) { b0, t, dt ->
                    val b = b0 as LissajousLeviathan
                    if (every(t, dt, 0.55) > 0) for (k in 0 until 3) {
                        val s = Rng.game.pick(b.segs)
                        Patterns.ring(s.x, s.y, 9, 140.0, Rng.game.angle(), O.coil)
                        Fx.flash(s.x, s.y, 26.0, 0.15, b.color)
                    }
                }
            )),
            Phase(0.0, listOf(
                Attack("Spinal Volley+", 5.0, 0.5) { b0, t, dt ->
                    val b = b0 as LissajousLeviathan
                    val n = every(t, dt, 0.075)
                    for (i in 0 until n) {
                        val s = b.segs[b.spineI++ % b.segs.size]
                        val o = if (i % 2 == 1) O.spine else O.spine2
                        Game.ebullets.fire(s.x, s.y, s.a + HALF_PI, 175.0, o)
                        Game.ebullets.fire(s.x, s.y, s.a - HALF_PI, 175.0, o)
                    }
                },
                Attack("Constrictor", 5.6, 0.8) { b0, t, dt ->
                    val b = b0 as LissajousLeviathan
                    if (crossed(t, dt, 0.4) || crossed(t, dt, 2.3) || crossed(t, dt, 4.2)) {
                        val a = Patterns.aim(b.x, b.y)
                        b.lunge = 0.55; b.lx = cos(a); b.ly = sin(a)
                        Audio.play(Sfx.DASH, b.x); Fx.ring(b.x, b.y, 10.0, 90.0, 0.4, b.color, 4.0)
                    }
                    if (every(t, dt, 0.34) > 0) { val tail = b.segs[b.segs.size - 1]; Game.ebullets.fire(tail.x, tail.y, Rng.game.angle(), 0.0, O.mine) }
                },
                Attack("Twin Torrent", 4.6, 0.7) { b0, t, dt ->
                    val b = b0 as LissajousLeviathan
                    if (every(t, dt, 0.11) > 0) {
                        val tail = b.segs[b.segs.size - 1]
                        for (k in 0 until 2) {
                            val sx = if (k == 0) b.x else tail.x; val sy = if (k == 0) b.y else tail.y
                            val a = Patterns.aim(sx, sy)
                            Game.ebullets.fire(sx, sy, a, 225.0, O.torrentA)
                            Game.ebullets.fire(sx, sy, a, 225.0, O.torrentB)
                        }
                    }
                }
            ))
        )
    }
}

/**
 * III — FRACTAL SERAPH. Three nested Koch snowflakes (depth 2: 48 vertices each).
 * Koch rule: replace AB with A→P₁→P₂→P₃→B, P₂ = P₁ rotated 60° outward about the segment.
 */
class FractalSeraph : Boss(BOSS_DEFS[2]) {
    var phylloI = 0; var polyI = 0; var laserDir = 1.0
    override val phases get() = PHASES

    override fun initBoss() { phylloI = 0; polyI = 0; laserDir = 1.0 }

    override fun move(dt: Double) {
        mt += dt
        val tx = World.w / 2 + World.w * 0.18 * sin(0.3 * mt); val ty = World.h * 0.26 + 30 * sin(0.6 * mt)
        x = damp(x, tx, 2.5, dt); y = damp(y, ty, 2.5, dt)
        angle += dt * 0.25
    }

    companion object {
        /** Depth-2 Koch snowflake in unit space, flat [x0,y0,…]. */
        val KOCH: FloatArray = run {
            var pts = ArrayList<DoubleArray>()
            val base = regularPoly(3, -HALF_PI)
            for (i in 0 until 3) pts.add(doubleArrayOf(base[i * 2].toDouble(), base[i * 2 + 1].toDouble()))
            for (depth in 0 until 2) {
                val next = ArrayList<DoubleArray>()
                for (i in pts.indices) {
                    val a = pts[i]; val b = pts[(i + 1) % pts.size]
                    val dx = (b[0] - a[0]) / 3; val dy = (b[1] - a[1]) / 3
                    val p1x = a[0] + dx; val p1y = a[1] + dy; val p3x = a[0] + 2 * dx; val p3y = a[1] + 2 * dy
                    val c = cos(-PI / 3); val s = sin(-PI / 3)
                    val p2x = p1x + dx * c - dy * s; val p2y = p1y + dx * s + dy * c
                    next.add(a); next.add(doubleArrayOf(p1x, p1y)); next.add(doubleArrayOf(p2x, p2y)); next.add(doubleArrayOf(p3x, p3y))
                }
                pts = next
            }
            FloatArray(pts.size * 2) { if (it % 2 == 0) pts[it / 2][0].toFloat() else pts[it / 2][1].toFloat() }
        }

        val PHASES = listOf(
            Phase(0.5, listOf(
                Attack("Phyllotaxis Choir", 5.0, 0.7) { b0, t, dt ->
                    val b = b0 as FractalSeraph
                    val n = every(t, dt, 0.028)
                    for (i in 0 until n) { Patterns.phyllo(b.x, b.y, b.phylloI, 1, 165.0, t * 0.15, O.phyllo[b.phylloI % 3]); b.phylloI++ }
                    if (every(t, dt, 0.4) > 0) Audio.play(Sfx.ENEMY_SHOOT, b.x, 1.2)
                },
                Attack("Seraphic Lances", 6.0, 1.0) { b0, t, dt ->
                    val b = b0 as FractalSeraph
                    if (crossed(t, dt, 0.0)) {
                        b.laserDir *= -1
                        val base = Patterns.aim(b.x, b.y) + PI / 3
                        for (k in 0 until 3) Game.lasers.add(Laser(b, angle = base + (k / 3.0) * TAU, angVel = 0.38 * b.laserDir, warmup = 1.1, duration = 3.8, width = 20.0, color = b.color))
                        Audio.play(Sfx.LASER_CHARGE, b.x)
                    }
                    if (every(t, dt, 0.9, 1.2) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 3, 0.35, 200.0, O.poly2)
                },
                Attack("Polygon Nova", 4.6, 0.8) { b0, t, dt ->
                    val b = b0 as FractalSeraph
                    if (every(t, dt, 0.72) > 0) { val sides = 3 + (b.polyI++ % 4); Patterns.polygon(b.x, b.y, sides, 9, 185.0, t * 0.7, O.poly); Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.8) }
                }
            )),
            Phase(0.0, listOf(
                Attack("Halo Release", 5.4, 0.6) { b, t, dt ->
                    if (crossed(t, dt, 0.1) || crossed(t, dt, 1.9) || crossed(t, dt, 3.7)) {
                        val dir = Rng.game.sign(); val tangential = Rng.game.chance(0.5)
                        for (i in 0 until 24) {
                            val a = (i / 24.0) * TAU
                            val bl = Game.ebullets.fire(b.x, b.y, a, 0.0, O.halo) ?: break
                            bl.orbitT = 1.4; bl.orbitOwner = b; bl.orbitA = a; bl.orbitR = 30.0; bl.orbitTargetR = 150.0
                            bl.orbitW = 2.2 * dir; bl.orbitRelease = if (tangential) HALF_PI * dir * 0.7 else 0.0; bl.releaseSpeed = 210 * Game.bulletSpeedMul
                        }
                        Fx.ring(b.x, b.y, 30.0, 150.0, 0.6, Pal.C_FFE066, 3.0); Audio.play(Sfx.WARP, b.x)
                    }
                },
                Attack("Lance Choir", 6.5, 1.0) { b0, t, dt ->
                    val b = b0 as FractalSeraph
                    if (crossed(t, dt, 0.0)) {
                        b.laserDir *= -1
                        val base = Rng.game.angle()
                        for (k in 0 until 5) Game.lasers.add(Laser(b, angle = base + (k / 5.0) * TAU, angVel = 0.3 * b.laserDir, warmup = 1.2, duration = 4.4, width = 18.0, color = b.color))
                        Audio.play(Sfx.LASER_CHARGE, b.x)
                    }
                    val n = every(t, dt, 0.07)
                    for (i in 0 until n) { Patterns.phyllo(b.x, b.y, b.phylloI, 1, 130.0, 0.0, O.phyllo[b.phylloI % 3]); b.phylloI++ }
                },
                Attack("Fractal Bloom", 4.8, 0.8) { b0, t, dt ->
                    val b = b0 as FractalSeraph
                    if (every(t, dt, 0.82) > 0) {
                        val s = 3 + (b.polyI++ % 3)
                        Patterns.polygon(b.x, b.y, s, 8, 200.0, t * 0.8, O.poly)
                        Patterns.polygon(b.x, b.y, s + 2, 6, 145.0, -t * 0.8, O.poly2)
                        Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.7)
                    }
                }
            ))
        )
    }
}

/**
 * IV — ENTROPY ENGINE. A tesseract: the 16 vertices of {−1,1}⁴ joined by the 32 edges whose
 * endpoints differ in one coordinate, rotated in the XW, YZ and ZW planes and projected
 * 4-D → 3-D (k₄ = 1/(d₄ − w)) → 2-D (k₃ = 1/(d₃ − z)).
 */
class EntropyEngine : Boss(BOSS_DEFS[3]) {
    val proj = DoubleArray(32)
    val depth = DoubleArray(16)
    var rotSpeed = 1.0; var laserDir = 1.0; var hd = false
    override val phases get() = PHASES

    override fun initBoss() { rotSpeed = 1.0; laserDir = 1.0; hd = false; project(0.0, proj, depth) }

    /** Project the tesseract at time t into the given buffers (the renderer re-projects at render time). */
    fun project(t: Double, outProj: DoubleArray, outDepth: DoubleArray) {
        val a = 0.7 * t * rotSpeed; val b = 0.45 * t * rotSpeed; val c = 0.3 * t * rotSpeed
        val ca = cos(a); val sa = sin(a); val cb = cos(b); val sb = sin(b); val cc = cos(c); val sc = sin(c)
        val S = r * 2.2
        for (i in 0 until 16) {
            val x = if (i and 1 != 0) 1.0 else -1.0; val y = if (i and 2 != 0) 1.0 else -1.0
            val z = if (i and 4 != 0) 1.0 else -1.0; val w = if (i and 8 != 0) 1.0 else -1.0
            val x1 = x * ca - w * sa; val w1 = x * sa + w * ca
            val y1 = y * cb - z * sb; val z1 = y * sb + z * cb
            val z2 = z1 * cc - w1 * sc; val w2 = z1 * sc + w1 * cc
            val k4 = 1 / (2.6 - w2); val X = x1 * k4; val Y = y1 * k4; val Z = z2 * k4
            val k3 = 1 / (3.2 - Z)
            outProj[i * 2] = X * k3 * S; outProj[i * 2 + 1] = Y * k3 * S
            outDepth[i] = w2
        }
    }

    override fun onPhase(i: Int) { rotSpeed = 1.7 }

    override fun move(dt: Double) {
        mt += dt
        val tx = World.w / 2 + World.w * 0.22 * sin(0.25 * mt); val ty = World.h * 0.27 + 26 * sin(0.5 * mt)
        x = damp(x, tx, 2.2, dt); y = damp(y, ty, 2.2, dt)
        project(t, proj, depth)
    }

    override fun introPose(dt: Double) { project(t, proj, depth) }

    companion object {
        /** The 32 tesseract edges as index pairs. */
        val EDGES: IntArray = run {
            val e = ArrayList<Int>()
            for (i in 0 until 16) for (b in 0 until 4) { val j = i xor (1 shl b); if (j > i) { e.add(i); e.add(j) } }
            e.toIntArray()
        }

        private fun vertexRain(b: EntropyEngine, t: Double, dt: Double, interval: Double) {
            if (every(t, dt, interval) == 0) return
            for (i in 0 until 16) {
                if (b.depth[i] < -0.2) continue
                val px = b.proj[i * 2]; val py = b.proj[i * 2 + 1]
                Game.ebullets.fire(b.x + px, b.y + py, atan2(py, px), 175.0, if (b.depth[i] > 0.6) O.vertexHot else O.vertex)
            }
            Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.9)
        }

        private val lorenzOpts = O.lorenz.copy()
        private fun lorenzSwarm(b: EntropyEngine, count: Int) {
            val rot = Rng.game.range(-0.3, 0.3)
            for (i in 0 until count) {
                // Pre-integrate so every bullet starts at a different point ON the attractor.
                var lx = 0.1 + i * 0.001; var ly = 0.0; var lz = 0.0
                val steps = 300 + i * 9
                for (k in 0 until steps) {
                    val dx = 10 * (ly - lx); val dy = lx * (28 - lz) - ly; val dz = lx * ly - (8.0 / 3) * lz
                    lx += dx * 0.006; ly += dy * 0.006; lz += dz * 0.006
                }
                val scale = 5.2; val X = lx * scale; val Z = (lz - 24) * scale; val c = cos(rot); val s = sin(rot)
                lorenzOpts.delay = 0.5 + i * 0.006
                val bl = Game.ebullets.fire(b.x + X * c - Z * s, b.y + X * s + Z * c, 0.0, 0.0, lorenzOpts) ?: break
                bl.lorenz = true; bl.lx = lx; bl.ly = ly; bl.lz = lz; bl.lscale = scale; bl.lspeed = 0.32; bl.lowner = b; bl.lrelease = 5.4; bl.lrot = rot
            }
            Fx.ring(b.x, b.y, 20.0, 200.0, 0.7, b.color, 3.0); Audio.play(Sfx.WARP, b.x)
        }

        val PHASES = listOf(
            Phase(0.5, listOf(
                Attack("Vertex Rain", 5.0, 0.7) { b, t, dt -> vertexRain(b as EntropyEngine, t, dt, 0.2) },
                Attack("Heat Death", 5.4, 0.8) { b, t, dt ->
                    if (every(t, dt, 1.35) > 0) { Patterns.ring(b.x, b.y, 28, 270.0, Rng.game.angle(), O.heat); Fx.ring(b.x, b.y, 10.0, 90.0, 0.4, Pal.C_FFE066, 3.0); Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.6) }
                },
                Attack("Lorenz Swarm", 6.6, 1.0) { b, t, dt ->
                    if (crossed(t, dt, 0.0)) lorenzSwarm(b as EntropyEngine, 70)
                    if (every(t, dt, 0.65, 0.8) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 1, 0.0, 230.0, O.spread)
                }
            )),
            Phase(0.0, listOf(
                Attack("Entropic Cross", 6.6, 1.0) { b0, t, dt ->
                    val b = b0 as EntropyEngine
                    if (crossed(t, dt, 0.0)) {
                        b.laserDir *= -1
                        for (k in 0 until 4) Game.lasers.add(Laser(b, angle = (k / 4.0) * TAU + PI / 4, angVel = 0.42 * b.laserDir, warmup = 1.2, duration = 4.6, width = 20.0, color = b.color))
                        Audio.play(Sfx.LASER_CHARGE, b.x)
                    }
                    vertexRain(b, t, dt, 0.34)
                },
                Attack("Heat Death+", 5.6, 0.8) { b0, t, dt ->
                    val b = b0 as EntropyEngine
                    if (every(t, dt, 1.1) > 0) {
                        b.hd = !b.hd
                        Patterns.ring(b.x, b.y, if (b.hd) 24 else 32, 280.0, Rng.game.angle(), if (b.hd) O.heat else O.heat2)
                        Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.6)
                    }
                },
                Attack("Lorenz Swarm+", 7.0, 1.0) { b, t, dt ->
                    if (crossed(t, dt, 0.0)) lorenzSwarm(b as EntropyEngine, 90)
                    if (every(t, dt, 1.1, 0.9) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 5, 0.7, 210.0, O.spread)
                }
            ))
        )
    }
}

/** A Fourier series of five epicycles: frequencies [k] and signed amplitudes [a] (unit scale). */
class FourierSeries(val k: IntArray, val a: DoubleArray) {
    /** 72 samples of the curve z(θ) = Σ aⱼ·e^{ikⱼθ} as flat (x, y) pairs, and its largest radius. */
    val pts = DoubleArray(144)
    val rMax: Double

    init {
        var m = 0.0
        for (i in 0 until 72) {
            val th = i / 72.0 * TAU
            var zx = 0.0; var zy = 0.0
            for (j in k.indices) { zx += a[j] * cos(k[j] * th); zy += a[j] * sin(k[j] * th) }
            pts[i * 2] = zx; pts[i * 2 + 1] = zy
            m = max(m, hypot(zx, zy))
        }
        rMax = m
    }
}

/**
 * V — FOURIER ORRERY. Every closed curve is a sum of turning circles, its Fourier series
 * z(θ) = Σₖ aₖ·e^{ikθ}. The orrery builds the sum as a chain of epicycles: arm k turns k times per
 * revolution and is pinned to the tip of the arm before it, so the last tip (the pen) traces the
 * curve. Phase I sums the frequencies 1, −4, 6, −9, 11 (all ≡ 1 mod 5, so the curve has five-fold
 * symmetry: a star); phase II re-tunes to 1, −3, 5, −7, 9 with amplitudes ±1/k, the first terms of
 * a square, which overshoots into small loops at each corner (the Gibbs phenomenon).
 */
class FourierOrrery : Boss(BOSS_DEFS[4]) {
    var series = STAR
    /** The series parameter θ (the arms' phase) and its rate; prevTheta is the last step's, for drawing. */
    var theta = 0.0; var prevTheta = 0.0; var omega = 0.85
    /** 0..1 arm length while the orrery unfolds during its intro. */
    var unfold = 0.0
    /** Joint offsets from the hub this step: index 0 is the hub, 1..5 the arm tips (5 is the pen). */
    val jx = DoubleArray(6); val jy = DoubleArray(6)
    var bloomFlip = false
    override val phases get() = PHASES

    /** World units per unit of amplitude. */
    val armScale: Double get() = r * 1.55

    override fun initBoss() { series = STAR; theta = 0.0; prevTheta = 0.0; omega = 0.85; unfold = 0.0; bloomFlip = false; joints(theta, jx, jy) }

    /** Joint offsets for phase [th] (the renderer calls this with an interpolated phase). */
    fun joints(th: Double, ox: DoubleArray, oy: DoubleArray) {
        val L = armScale * unfold
        ox[0] = 0.0; oy[0] = 0.0
        for (j in 0 until 5) {
            val a = series.k[j] * th; val len = series.a[j] * L
            ox[j + 1] = ox[j] + cos(a) * len; oy[j + 1] = oy[j] + sin(a) * len
        }
    }

    /** Direction arm [j] points in (a negative amplitude is a half-turn). */
    fun armAngle(j: Int): Double = series.k[j] * theta + if (series.a[j] < 0) PI else 0.0

    private fun advance(dt: Double) { prevTheta = theta; theta += omega * dt; joints(theta, jx, jy) }

    override fun introPose(dt: Double) { unfold = Ease.outCubic(clamp(1 - introT / introDur, 0.0, 1.0)); advance(dt) }

    override fun move(dt: Double) {
        mt += dt
        unfold = 1.0
        // the hub rides an epicycle of its own: one circle turning forwards, one twice as fast backwards
        val u = 0.23 * mt
        val tx = World.w / 2 + World.w * 0.19 * cos(u) + World.w * 0.05 * cos(-2 * u)
        val ty = World.h * 0.27 + World.h * 0.07 * sin(u) + World.h * 0.03 * sin(-2 * u)
        x = damp(x, tx, 2.6, dt); y = damp(y, ty, 2.6, dt)
        angle += dt * 0.35   // the hub's gear
        advance(dt)
    }

    override fun onPhase(i: Int) { series = SQUARE; omega = -1.15 }

    override fun emitLight() {
        super.emitLight()
        Light.add(x + jx[5], y + jy[5], 110.0, color, 0.5)
    }

    companion object {
        val STAR = FourierSeries(intArrayOf(1, -4, 6, -9, 11), doubleArrayOf(1.0, 0.42, 0.2, 0.1, 0.06))
        val SQUARE = FourierSeries(intArrayOf(1, -3, 5, -7, 9), doubleArrayOf(1.0, -1.0 / 3, 1.0 / 5, -1.0 / 7, 1.0 / 9))

        /**
         * The curve's homothety: bullet i heads for curve point zᵢ (turned by [rot]) at a speed that grows
         * with |zᵢ| (from a floor, so no bullet crawls), and the swarm spreads in the curve's shape.
         */
        fun bloom(x: Double, y: Double, s: FourierSeries, speed: Double, rot: Double, o: BulletOpts) {
            val c = cos(rot); val sn = sin(rot); val p = s.pts
            for (i in 0 until p.size / 2) {
                val qx = p[i * 2] * c - p[i * 2 + 1] * sn; val qy = p[i * 2] * sn + p[i * 2 + 1] * c
                Game.ebullets.fire(x, y, atan2(qy, qx), speed * (0.3 + 0.7 * hypot(qx, qy) / s.rMax), o)
            }
        }

        /** Two interlaced rings that curve opposite ways as they speed up: epicycles unwinding. */
        fun wheel(x: Double, y: Double, n: Int, speed: Double, a0: Double) {
            for (i in 0 until n) {
                Game.ebullets.fire(x, y, a0 + i.toDouble() / n * TAU, speed, O.wheelCw)
                Game.ebullets.fire(x, y, a0 + (i + 0.5) / n * TAU, speed, O.wheelCcw)
            }
        }

        /**
         * A fan whose speeds follow the partial sum (4/π)·Σ sin(kx)/k, k = 1, 3, 5, 7, of a square wave
         * across two periods: fast and slow blocks, rippling where the wave jumps.
         */
        fun gibbsFan(x: Double, y: Double, aim: Double) {
            val n = 21; val arc = 1.9
            for (j in 0 until n) {
                val u = j.toDouble() / (n - 1)
                val s = 2 * TAU * u - TAU
                var sum = 0.0
                for (m in 0 until 4) { val k = 2 * m + 1; sum += sin(k * s) / k }
                Game.ebullets.fire(x, y, aim - arc / 2 + arc * u, 150.0 * (1 + 0.3 * sum * 4 / PI), O.gibbs)
            }
        }

        /** Every arm tip fires one bullet along its own arm: five spirals turning at 1, 4, 6, 9 and 11 times the base rate. */
        private fun harmonics(b: FourierOrrery, speed: Double) {
            for (j in 0 until 5) Game.ebullets.fire(b.x + b.jx[j + 1], b.y + b.jy[j + 1], b.armAngle(j), speed, O.harmonic[j % 3])
        }

        val PHASES = listOf(
            Phase(0.5, listOf(
                Attack("Harmonic Series", 5.4, 0.8) { b0, t, dt ->
                    val b = b0 as FourierOrrery
                    val n = every(t, dt, 0.11)
                    for (i in 0 until n) harmonics(b, 140.0)
                    if (every(t, dt, 0.44) > 0) Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.9)
                },
                Attack("Phasor Bloom", 4.8, 0.9) { b0, t, dt ->
                    val b = b0 as FourierOrrery
                    if (every(t, dt, 1.15) > 0) {
                        bloom(b.x, b.y, STAR, 165.0, b.theta, O.phasor)
                        Fx.ring(b.x, b.y, 10.0, 90.0, 0.4, b.color, 3.0); Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.7)
                    }
                    if (every(t, dt, 0.6, 0.3) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 3, 0.4, 200.0, O.aimRice)
                },
                Attack("Epicycle Wheel", 6.0, 1.0) { b, t, dt ->
                    if (every(t, dt, 1.25) > 0) { wheel(b.x, b.y, 14, 115.0, Rng.game.angle()); Audio.play(Sfx.WARP, b.x) }
                    if (every(t, dt, 0.9, 0.45) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 3, 0.42, 210.0, O.aimRice)
                }
            )),
            Phase(0.0, listOf(
                Attack("Square Wave", 5.2, 0.8) { b0, t, dt ->
                    val b = b0 as FourierOrrery
                    if (every(t, dt, 0.95) > 0) {
                        b.bloomFlip = !b.bloomFlip
                        bloom(b.x, b.y, SQUARE, 160.0, b.theta + if (b.bloomFlip) PI / 4 else 0.0, O.phasor2)
                        Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.7)
                    }
                    // the pen leaves its stroke behind: slow bullets drifting out from the hub
                    val n = every(t, dt, 0.07)
                    for (i in 0 until n) Game.ebullets.fire(b.x + b.jx[5], b.y + b.jy[5], atan2(b.jy[5], b.jx[5]), 55.0, O.pen)
                },
                Attack("Gibbs Overshoot", 5.6, 0.9) { b0, t, dt ->
                    val b = b0 as FourierOrrery
                    if (every(t, dt, 1.05) > 0) { gibbsFan(b.x, b.y, Patterns.aim(b.x, b.y)); Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.8) }
                    val n = every(t, dt, 0.22)
                    for (i in 0 until n) harmonics(b, 130.0)
                },
                Attack("Phasor Storm", 6.2, 1.0) { b0, t, dt ->
                    val b = b0 as FourierOrrery
                    if (every(t, dt, 1.5) > 0) {
                        val a0 = Rng.game.angle()
                        for (j in 1..5) {
                            val o = if (j % 2 == 1) O.wheelCw else O.wheelCcw
                            for (i in 0 until 9) Game.ebullets.fire(b.x + b.jx[j], b.y + b.jy[j], a0 + j * 0.7 + i / 9.0 * TAU, 100.0, o)
                        }
                        Audio.play(Sfx.WARP, b.x)
                    }
                    if (every(t, dt, 0.8, 0.4) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 3, 0.4, 215.0, O.aimRice)
                }
            ))
        )
    }
}

/**
 * VI — PENROSE PENTARCH. A Penrose rhombus tiling: thick (72°) and thin (36°) rhombs that cover the
 * plane with five-fold symmetry yet never repeat. It grows by deflation: each Robinson half-tile
 * splits along the golden ratio φ = (1 + √5)/2 (a thin half into two, a thick half into three), and
 * thick tiles outnumber thin ones by φ to one. Its dual is de Bruijn's pentagrid, five families of
 * parallel lines, and the spacing along each family follows the Fibonacci word, which never
 * repeats either. The Pentarch walks the pentagram {5/2}, turning its tiling a tenth of a turn per leg.
 */
class PenrosePentarch : Boss(BOSS_DEFS[5]) {
    var gridK = 0; var gridPhase = 0; var flip = false; var laserDir = 1.0
    /** Tiling turn in tenths of a revolution (eased per leg); prevTurn is the last step's, for drawing. */
    var turn = 0.0; var prevTurn = 0.0
    /** 0..1: how far out the tiling has assembled (intro). */
    var reveal = 0.0
    /** Deflations shown: three in phase I, four in phase II. */
    var depth = 3
    override val phases get() = PHASES

    override fun initBoss() { gridK = 0; gridPhase = 0; flip = false; laserDir = 1.0; turn = 0.0; prevTurn = 0.0; reveal = 0.0; depth = 3 }

    override fun introPose(dt: Double) { reveal = Ease.outCubic(clamp(1 - introT / introDur, 0.0, 1.0)) }

    override fun move(dt: Double) {
        mt += dt
        reveal = 1.0
        // Walk the pentagram: leg i runs from vertex 2i to vertex 2i + 2, gliding 1.7 s and holding 1.1 s.
        val leg = 2.8
        val i = floor(mt / leg).toInt()
        val e = Ease.inOutCubic(clamp((mt - i * leg) / 1.7, 0.0, 1.0))
        val a0 = -HALF_PI + (2 * i) * TAU / 5; val a1 = a0 + 2 * TAU / 5
        val rx = World.w * 0.24; val ry = World.h * 0.17; val cy = World.h * 0.31
        val tx = World.w / 2 + lerp(cos(a0), cos(a1), e) * rx; val ty = cy + lerp(sin(a0), sin(a1), e) * ry
        x = damp(x, tx, 6.0, dt); y = damp(y, ty, 6.0, dt)
        prevTurn = turn
        turn = i + e
    }

    override fun onPhase(i: Int) { depth = 4 }

    companion object {
        /** Fibonacci word 0100101001001…: 1 at n ≥ 1 where 2 + ⌊nφ⌋ − ⌊(n + 1)φ⌋ = 1. Its 1s never touch. */
        fun fibonacci(n: Int): Boolean = 2 + floor(n * PHI).toLong() - floor((n + 1) * PHI).toLong() == 1L

        /**
         * One pentagrid wall: [slots] bullets on a line through (x, y) across [dir], all moving along [dir].
         * A slot is left empty where the Fibonacci word has a 1 (from index [phase]), so every gap is a single
         * slot and the gaps never fall into a repeating rhythm.
         */
        fun wall(x: Double, y: Double, dir: Double, slots: Int, spacing: Double, phase: Int, speed: Double, o: BulletOpts) {
            val nx = -sin(dir); val ny = cos(dir)
            for (n in 0 until slots) {
                if (fibonacci(n + phase)) continue
                val off = (n - (slots - 1) / 2.0) * spacing
                Game.ebullets.fire(x + nx * off, y + ny * off, dir, speed, o)
            }
        }

        /** Rings at speeds v, v/φ and v/φ², the middle one offset half a step: nested golden decagons. */
        fun goldenBloom(x: Double, y: Double, rot: Double, speed: Double) {
            Patterns.ring(x, y, 20, speed, rot, O.goldA)
            Patterns.ring(x, y, 20, speed / PHI, rot + PI / 20, O.goldB)
            Patterns.ring(x, y, 10, speed / (PHI * PHI), rot, O.goldC)
        }

        /** Sun: ten rays, each three bullets at speeds v, v/φ, v/φ². */
        fun sun(x: Double, y: Double, rot: Double, speed: Double) {
            for (i in 0 until 10) {
                val a = rot + i * TAU / 10
                Game.ebullets.fire(x, y, a, speed, O.goldA)
                Game.ebullets.fire(x, y, a, speed / PHI, O.goldA)
                Game.ebullets.fire(x, y, a, speed / (PHI * PHI), O.goldA)
            }
        }

        /** Star: the pentagram {5/2} (vertex i joined to vertex i + 2) grown outward as a homothety. */
        fun star(x: Double, y: Double, rot: Double, speed: Double, perEdge: Int) {
            for (i in 0 until 5) {
                val a0 = rot + i * TAU / 5; val a1 = rot + (i + 2) * TAU / 5
                val x0 = cos(a0); val y0 = sin(a0); val x1 = cos(a1); val y1 = sin(a1)
                for (j in 0 until perEdge) {
                    val f = j.toDouble() / perEdge
                    val px = lerp(x0, x1, f); val py = lerp(y0, y1, f)
                    Game.ebullets.fire(x, y, atan2(py, px), speed * hypot(px, py), O.goldB)
                }
            }
        }

        private fun walls(b: PenrosePentarch, families: Int, slots: Int) {
            for (f in 0 until families) {
                val dir = HALF_PI + b.gridK * TAU / 5
                b.gridK = (b.gridK + 2) % 5
                b.gridPhase += 7
                wall(b.x, b.y, dir, slots, 30.0, b.gridPhase, 150.0, O.pentagrid)
                wall(b.x, b.y, dir + PI, slots, 30.0, b.gridPhase + 13, 150.0, O.pentagrid)
            }
            Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.75)
        }

        val PHASES = listOf(
            Phase(0.5, listOf(
                Attack("Pentagrid", 5.6, 0.8) { b, t, dt -> if (every(t, dt, 0.85) > 0) walls(b as PenrosePentarch, 1, 25) },
                Attack("Golden Bloom", 4.6, 0.8) { b, t, dt ->
                    if (every(t, dt, 0.95) > 0) {
                        goldenBloom(b.x, b.y, Rng.game.angle(), 190.0)
                        Fx.ring(b.x, b.y, 10.0, 90.0, 0.4, b.color, 3.0); Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.7)
                    }
                },
                Attack("Deflation", 6.0, 1.0) { b, t, dt ->
                    if (every(t, dt, 1.3) > 0) { Patterns.ring(b.x, b.y, 10, 85.0, Rng.game.angle(), O.kite); Audio.play(Sfx.WARP, b.x) }
                    if (every(t, dt, 0.7, 0.35) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 3, 0.36, 250.0, O.dart)
                }
            )),
            Phase(0.0, listOf(
                Attack("Pentagrid+", 5.6, 0.7) { b, t, dt -> if (every(t, dt, 1.1) > 0) walls(b as PenrosePentarch, 2, 21) },
                Attack("Star Lances", 6.4, 1.0) { b0, t, dt ->
                    val b = b0 as PenrosePentarch
                    if (crossed(t, dt, 0.0)) {
                        b.laserDir *= -1
                        val base = Patterns.aim(b.x, b.y) + PI / 5
                        for (k in 0 until 5) Game.lasers.add(Laser(b, angle = base + k * TAU / 5, angVel = 0.2 * b.laserDir, warmup = 1.2, duration = 4.4, width = 18.0, color = b.color))
                        Audio.play(Sfx.LASER_CHARGE, b.x)
                    }
                    if (every(t, dt, 0.9, 1.2) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 3, 0.3, 240.0, O.dart)
                },
                Attack("Sun and Star", 5.4, 0.8) { b0, t, dt ->
                    val b = b0 as PenrosePentarch
                    if (every(t, dt, 0.9) > 0) {
                        b.flip = !b.flip
                        val rot = Rng.game.angle()
                        if (b.flip) sun(b.x, b.y, rot, 200.0) else star(b.x, b.y, rot, 200.0, 7)
                        Fx.ring(b.x, b.y, 10.0, 100.0, 0.4, b.color, 3.0); Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.7)
                    }
                }
            ))
        )
    }
}

/**
 * VII — TREFOIL HIEROPHANT. A (p, q) torus knot winds p times around a torus's axis and q times
 * through its hole: (x, y, z) = ((R + r·cos qφ)·cos pφ, (R + r·cos qφ)·sin pφ, r·sin qφ). The (2, 3)
 * knot is the trefoil, the simplest knot no amount of pulling can undo (three crossings); phase II
 * re-ties it as the (2, 5) cinquefoil. Its body turns in three dimensions, and it flies the trefoil's
 * own shadow: the planar curve (sin u + 2 sin 2u, cos u − 2 cos 2u).
 */
class TrefoilHierophant : Boss(BOSS_DEFS[6]) {
    var p = 2; var q = 3
    /** The knot projected this step: offsets from the body (x, y pairs) and depth (+ is nearer). */
    val proj = DoubleArray(N * 2); val depth = DoubleArray(N)
    /** The knot's turn about its vertical axis and the phase of its tilt (integrated, so a faster phase II doesn't jump). */
    var turnY = 0.0; var tiltPh = 0.0
    var twist = 1.0; var pace = 1.0; var laserDir = 1.0
    /** 0..1 of the knot tied so far (intro). */
    var tie = 0.0
    override val phases get() = PHASES

    override fun initBoss() { p = 2; q = 3; turnY = 0.0; tiltPh = 0.0; twist = 1.0; pace = 1.0; laserDir = 1.0; tie = 0.0; project(proj, depth) }

    /** Turn the knot in 3-D and project it with perspective (k = 6/(6 − z)). */
    fun project(outP: DoubleArray, outD: DoubleArray) {
        val ax = 0.55 + 0.35 * sin(tiltPh); val ay = turnY
        val cx = cos(ax); val sx = sin(ax); val cy = cos(ay); val sy = sin(ay)
        val S = r * 0.78
        for (i in 0 until N) {
            val ph = i.toDouble() / N * TAU
            val rr = 2 + cos(q * ph)
            val X = rr * cos(p * ph); val Y = rr * sin(p * ph); val Z = sin(q * ph)
            val x1 = X * cy + Z * sy; val z1 = -X * sy + Z * cy
            val y2 = Y * cx - z1 * sx; val z2 = Y * sx + z1 * cx
            val k = 6.0 / (6.0 - z2)
            outP[i * 2] = x1 * k * S; outP[i * 2 + 1] = y2 * k * S; outD[i] = z2
        }
    }

    private fun turn(dt: Double) { turnY += 0.5 * twist * dt; tiltPh += 0.37 * twist * dt; project(proj, depth) }

    override fun introPose(dt: Double) { tie = Ease.outCubic(clamp(1 - introT / introDur, 0.0, 1.0)); turn(dt) }

    override fun move(dt: Double) {
        mt += dt * pace
        tie = 1.0
        // the planar trefoil, normalised to x ∈ [−1, 1] and y ∈ [0, 1], across the upper arena
        val u = 0.2 * mt
        val nx = (sin(u) + 2 * sin(2 * u)) / 2.74; val ny = (2 * cos(2 * u) - cos(u) + 2.06) / 5.06
        val tx = World.w / 2 + nx * World.w * 0.27; val ty = World.h * (0.21 + 0.3 * ny)
        x = damp(x, tx, 2.4, dt); y = damp(y, ty, 2.4, dt)
        turn(dt)
    }

    override fun onPhase(i: Int) { q = 5; twist = 1.5; pace = 1.3 }

    override fun emitLight() {
        super.emitLight()
        var i = 0
        while (i < N) { if (depth[i] > 0.3) Light.add(x + proj[i * 2], y + proj[i * 2 + 1], 80.0, color, 0.35); i += 20 }
    }

    companion object {
        const val N = 120

        /** Strands aimed at the player, each weaving across its neighbours (phases 2πk/n): a braid. */
        fun braid(x: Double, y: Double, aim: Double, strands: Array<BulletOpts>, spread: Double, speed: Double) {
            val n = strands.size
            for (k in 0 until n) Game.ebullets.fire(x, y, aim + (k - (n - 1) / 2.0) * spread, speed, strands[k])
        }

        /** Writhe: pairs that leave together and twist apart, one curving each way, crossing their neighbours. */
        fun writhe(x: Double, y: Double, pairs: Int, a0: Double, speed: Double) {
            for (i in 0 until pairs) {
                val a = a0 + i.toDouble() / pairs * TAU
                Game.ebullets.fire(x, y, a, speed, O.writheCw)
                Game.ebullets.fire(x, y, a, speed, O.writheCcw)
            }
        }

        /** Every [stride]-th point of the knot shoots along the knot's tangent, near strands faster than far ones. */
        fun unspool(b: TrefoilHierophant, stride: Int, speed: Double) {
            var i = 0
            while (i < N) {
                val j = (i + 1) % N
                val tx = b.proj[j * 2] - b.proj[i * 2]; val ty = b.proj[j * 2 + 1] - b.proj[i * 2 + 1]
                val k = clamp((b.depth[i] + 1.5) / 3.0, 0.0, 1.0)
                Game.ebullets.fire(b.x + b.proj[i * 2], b.y + b.proj[i * 2 + 1], atan2(ty, tx), speed * (0.6 + 0.5 * k), O.spool)
                i += stride
            }
        }

        val PHASES = listOf(
            Phase(0.5, listOf(
                Attack("Braid Word", 5.2, 0.7) { b, t, dt ->
                    val n = every(t, dt, 0.09)
                    for (i in 0 until n) braid(b.x, b.y, Patterns.aim(b.x, b.y), O.braid3, 0.13, 205.0)
                    if (every(t, dt, 0.36) > 0) Audio.play(Sfx.ENEMY_SHOOT, b.x)
                },
                Attack("Unspool", 5.0, 0.8) { b0, t, dt ->
                    val b = b0 as TrefoilHierophant
                    if (every(t, dt, 0.3) > 0) { unspool(b, 15, 150.0); Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.8) }
                    if (every(t, dt, 1.2, 0.6) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 3, 0.4, 210.0, O.aimRice)
                },
                Attack("Writhe", 6.0, 1.0) { b, t, dt ->
                    if (every(t, dt, 1.2) > 0) { writhe(b.x, b.y, 16, Rng.game.angle(), 140.0); Audio.play(Sfx.WARP, b.x) }
                    if (every(t, dt, 0.8, 0.4) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 3, 0.4, 215.0, O.aimRice)
                }
            )),
            Phase(0.0, listOf(
                Attack("Braid Word+", 5.4, 0.6) { b, t, dt ->
                    val n = every(t, dt, 0.085)
                    for (i in 0 until n) braid(b.x, b.y, Patterns.aim(b.x, b.y), O.braid5, 0.12, 200.0)
                    if (every(t, dt, 0.34) > 0) Audio.play(Sfx.ENEMY_SHOOT, b.x)
                },
                Attack("Unknotting Lances", 6.4, 1.0) { b0, t, dt ->
                    val b = b0 as TrefoilHierophant
                    if (crossed(t, dt, 0.0)) {
                        b.laserDir *= -1
                        val base = Patterns.aim(b.x, b.y) + PI / 3
                        for (k in 0 until 3) Game.lasers.add(Laser(b, angle = base + k * TAU / 3, angVel = 0.28 * b.laserDir, warmup = 1.2, duration = 4.4, width = 20.0, color = b.color))
                        Audio.play(Sfx.LASER_CHARGE, b.x)
                    }
                    if (every(t, dt, 0.45, 1.2) > 0) unspool(b, 20, 140.0)
                },
                Attack("Writhe+", 6.0, 0.9) { b, t, dt ->
                    if (every(t, dt, 1.05) > 0) { writhe(b.x, b.y, 20, Rng.game.angle(), 145.0); Audio.play(Sfx.WARP, b.x) }
                    if (every(t, dt, 1.1, 0.5) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 5, 0.7, 210.0, O.aimRice)
                }
            ))
        )
    }
}

/**
 * VIII — MANDELBROT MATRIARCH. The Mandelbrot set holds every c for which z → z² + c, started at 0,
 * stays bounded. Its main cardioid is c(θ) = e^{iθ}/2 − e^{2iθ}/4, the disc |c + 1| ≤ 1/4 hangs off
 * its back, and a bulb of period q buds at each internal angle 2πp/q (radius ≈ sin(πp/q)/q²). Each c
 * also has a Julia set, the edge between the starting points that escape and those that don't;
 * running the map backwards (z ← ±√(z − c)) settles onto it, which is how the Matriarch grows her
 * garden: each volley is one Julia set, spreading in its own shape. She flies her cardioid.
 */
class MandelbrotMatriarch : Boss(BOSS_DEFS[7]) {
    var gardenI = 0; var bulbI = 0; var mirror = false
    /** Render zoom (a slow breath) and the escape bands' progress (intro: they fall in from far out). */
    var zoom = 1.0; var bands = 0.0
    /** Where the backward iteration has got to (it carries on from volley to volley). */
    val chain = doubleArrayOf(0.5, 0.5)
    /** Where on its cardioid the Matriarch flies (integrated at the rate [swirl]). */
    var orbit = 0.0; var swirl = 1.0
    override val phases get() = PHASES

    /** World units per unit of c (the set is drawn turned so its head points up: screen x = Im c, y = Re c − C0). */
    val unit: Double get() = r * 1.15 * zoom

    override fun initBoss() { gardenI = 0; bulbI = 0; mirror = false; zoom = 1.0; bands = 0.0; chain[0] = 0.5; chain[1] = 0.5; orbit = 0.0; swirl = 1.0 }

    override fun parts(): List<Part> {
        while (partList.size < 2) partList.add(Part())
        val body = partList[0]; body.x = x; body.y = y; body.r = r; body.mul = 1.0; body.head = false
        // the period-2 disc |c + 1| ≤ 1/4 above the cardioid
        val head = partList[1]; head.x = x; head.y = y + (-1.0 - C0) * unit; head.r = 0.25 * unit + 6; head.mul = 0.6; head.head = true
        return partList
    }

    override fun introPose(dt: Double) { bands = Ease.outCubic(clamp(1 - introT / introDur, 0.0, 1.0)) }

    override fun move(dt: Double) {
        mt += dt
        bands = 1.0
        // ride the main cardioid c(θ) = e^{iθ}/2 − e^{2iθ}/4 (head up), heart-shaped across the arena
        orbit += 0.19 * swirl * dt
        val th = orbit
        val re = cos(th) / 2 - cos(2 * th) / 4; val im = sin(th) / 2 - sin(2 * th) / 4
        val tx = World.w / 2 + im * World.w * 0.4; val ty = World.h * (0.36 + 0.28 * re)
        x = damp(x, tx, 2.2, dt); y = damp(y, ty, 2.2, dt)
        zoom = 1 + 0.06 * sin(t * 0.9)
    }

    override fun onPhase(i: Int) { swirl = 1.35 }

    override fun emitLight() {
        super.emitLight()
        Light.add(x, y + (-1.0 - C0) * unit, 110.0, Pal.C_FFB238, 0.45)
    }

    companion object {
        /** The c the hub sits at (inside the cardioid). */
        const val C0 = -0.15
        /** Julia parameters (re, im): the Douady rabbit, San Marco, a dendrite, spirals, the Siegel disc… */
        val GARDEN = doubleArrayOf(-0.123, 0.745, -0.75, 0.0, 0.285, 0.01, -0.8, 0.156, -0.4, 0.6, 0.355, 0.355, -0.70176, -0.3842, 0.0, 0.8)

        /** The cardioid's bulbs as (Re centre, Im centre, radius, period): the period-2 disc and the 1/3 … 4/5 bulbs. */
        val BULBS: DoubleArray = run {
            val out = ArrayList<Double>()
            out.add(-1.0); out.add(0.0); out.add(0.25); out.add(2.0)
            for ((p, q) in listOf(1 to 3, 2 to 3, 1 to 4, 3 to 4, 1 to 5, 2 to 5, 3 to 5, 4 to 5)) {
                val th = TAU * p / q
                val rx = cos(th) / 2 - cos(2 * th) / 4; val ry = sin(th) / 2 - sin(2 * th) / 4
                // outward normal: the tangent ½i(e^{iθ} − e^{2iθ}) turned a quarter, pointing away from the interior
                val tx = -(sin(th) - sin(2 * th)) / 2; val ty = (cos(th) - cos(2 * th)) / 2
                var nx = ty; var ny = -tx
                val nl = hypot(nx, ny); nx /= nl; ny /= nl
                if (hypot(rx + nx * 0.01, ry + ny * 0.01) < hypot(rx, ry)) { nx = -nx; ny = -ny }
                val rad = sin(PI * p / q) / (q * q)
                out.add(rx + nx * rad); out.add(ry + ny * rad); out.add(rad); out.add(q.toDouble())
            }
            out.toDoubleArray()
        }

        /**
         * One Julia set by backward iteration: z ← ±√(z − c), the sign picked at random, falls onto J(c).
         * [n] bullets head for successive points (turned like the body: x = Im z, y = Re z), with speed
         * growing with |z|, so the volley spreads in the set's shape.
         */
        fun julia(x: Double, y: Double, chain: DoubleArray, cre: Double, cim: Double, n: Int, speed: Double, mirror: Boolean, o: BulletOpts) {
            var zr = chain[0]; var zi = chain[1]
            for (i in 0 until n + 8) {
                val ar = zr - cre; val ai = zi - cim
                val m = hypot(ar, ai)
                var wr = sqrt(max(0.0, (m + ar) / 2)); var wi = sqrt(max(0.0, (m - ar) / 2))
                if (ai < 0) wi = -wi
                if (Rng.game.chance(0.5)) { wr = -wr; wi = -wi }
                zr = wr; zi = wi
                if (i < 8) continue   // settle onto this c's set first
                val sx = if (mirror) -zi else zi; val sy = zr
                Game.ebullets.fire(x, y, atan2(sy, sx), speed * (0.3 + 0.55 * hypot(sx, sy)), o)
            }
            chain[0] = zr; chain[1] = zi
        }

        /** A cardioid r = (1 − cos θ)/2 grown outward with its cusp toward [aim]: the dent is the way through. */
        fun cardioid(x: Double, y: Double, aim: Double, n: Int, speed: Double, o: BulletOpts) {
            for (i in 0 until n) {
                val th = i.toDouble() / n * TAU
                Game.ebullets.fire(x, y, aim + th, speed * (0.28 + 0.72 * (1 - cos(th)) / 2), o)
            }
        }

        /** The next bulb buds: a ring of 4q bullets, its symmetry the bulb's period. */
        private fun bulbBurst(b: MandelbrotMatriarch) {
            val k = b.bulbI++ % (BULBS.size / 4)
            val bx = b.x + BULBS[k * 4 + 1] * b.unit; val by = b.y + (BULBS[k * 4] - C0) * b.unit
            val q = BULBS[k * 4 + 3].toInt()
            Patterns.ring(bx, by, 4 * q, 150.0, Rng.game.angle(), O.bulb)
            Fx.flash(bx, by, 30.0, 0.2, Pal.C_FFB238)
        }

        private fun garden(b: MandelbrotMatriarch, mirror: Boolean) {
            val g = b.gardenI++ % (GARDEN.size / 2)
            julia(b.x, b.y, b.chain, GARDEN[g * 2], GARDEN[g * 2 + 1], 64, 150.0, mirror, O.julia[g % 2])
            Fx.ring(b.x, b.y, 12.0, 110.0, 0.5, b.color, 3.0); Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.75)
        }

        val PHASES = listOf(
            Phase(0.5, listOf(
                Attack("Julia Garden", 5.6, 0.8) { b, t, dt -> if (every(t, dt, 0.85) > 0) garden(b as MandelbrotMatriarch, false) },
                Attack("Bulb Burst", 5.0, 0.8) { b, t, dt ->
                    if (every(t, dt, 0.55) > 0) { bulbBurst(b as MandelbrotMatriarch); Audio.play(Sfx.ENEMY_SHOOT, b.x, 1.1) }
                },
                Attack("Cardioid Bloom", 5.4, 0.9) { b, t, dt ->
                    if (every(t, dt, 1.2) > 0) { cardioid(b.x, b.y, Patterns.aim(b.x, b.y), 56, 230.0, O.cardioid); Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.6) }
                    if (every(t, dt, 0.8, 0.6) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 3, 0.4, 210.0, O.aimRice)
                }
            )),
            Phase(0.0, listOf(
                Attack("Julia Garden+", 5.6, 0.7) { b0, t, dt ->
                    val b = b0 as MandelbrotMatriarch
                    if (every(t, dt, 0.7) > 0) { b.mirror = !b.mirror; garden(b, b.mirror) }
                },
                Attack("Seahorse Valley", 6.0, 1.0) { b, t, dt ->
                    val n = every(t, dt, 0.08)
                    for (i in 0 until n) {
                        val a = Patterns.aim(b.x, b.y) + 0.9 * sin(t * 1.3)
                        Game.ebullets.fire(b.x, b.y, a + 1.25, 190.0, O.seahorseCw)
                        Game.ebullets.fire(b.x, b.y, a - 1.25, 190.0, O.seahorseCcw)
                    }
                    if (every(t, dt, 1.0, 0.5) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 3, 0.35, 215.0, O.aimRice)
                    if (every(t, dt, 0.4) > 0) Audio.play(Sfx.ENEMY_SHOOT, b.x, 1.2)
                },
                Attack("Self-Similarity", 5.6, 0.9) { b, t, dt ->
                    if (every(t, dt, 1.3) > 0) { Patterns.ring(b.x, b.y, 10, 110.0, Rng.game.angle(), O.bud); Audio.play(Sfx.WARP, b.x) }
                    if (every(t, dt, 0.65, 0.3) > 0) bulbBurst(b as MandelbrotMatriarch)
                }
            ))
        )
    }
}

/**
 * IX — AUTOMATON AUGUR. An elementary cellular automaton: a row of cells, each alive or dead, where a
 * cell's next state depends only on itself and its two neighbours. The eight neighbourhoods make
 * each rule an 8-bit number; Rule 30 (00011110) grows chaos from a single live cell, random enough
 * that its centre column has served as a random-number generator. The Augur keeps a ring of 40
 * cells, wears its last generations as rings, and steers by its centre cell (alive: right, dead:
 * left). In phase II it rewrites itself to Rule 110, which is Turing-complete.
 */
class AutomatonAugur : Boss(BOSS_DEFS[8]) {
    var rule = 30
    /** The ring, one bit per cell, and its last generations (history[0] is the newest). */
    var cells = 1L
    val history = LongArray(HIST)
    var gen = 0; var firedGen = 0; var genEvery = 0.36
    var targetX = 0.0; var gunSide = 1; var lanceI = 0
    /** 0..1 of the cells booted (intro). */
    var boot = 0.0
    override val phases get() = PHASES

    override fun initBoss() {
        rule = 30; cells = 1L; history.fill(0L); history[0] = cells; gen = 0; firedGen = 0; genEvery = 0.36
        targetX = x; gunSide = 1; lanceI = 0; boot = 0.0
    }

    fun alive(i: Int): Boolean = (cells shr i) and 1L != 0L

    /** One generation: each cell reads (left, self, right) as a 3-bit index into the rule. */
    fun step() {
        var next = 0L
        for (i in 0 until N) {
            val l = (cells shr ((i + N - 1) % N)) and 1L; val c = (cells shr i) and 1L; val r = (cells shr ((i + 1) % N)) and 1L
            val idx = ((l shl 2) or (c shl 1) or r).toInt()
            if ((rule shr idx) and 1 == 1) next = next or (1L shl i)
        }
        if (next == 0L) next = 1L shl (gen % N)   // a dead ring reseeds
        for (h in HIST - 1 downTo 1) history[h] = history[h - 1]
        cells = next; history[0] = next; gen++
    }

    override fun introPose(dt: Double) { boot = clamp(1 - introT / introDur, 0.0, 1.0) }

    override fun move(dt: Double) {
        // generations tick on the attack clock's tempo: its ring volleys and printed rows are attacks
        val gdt = dt * tempo
        mt += gdt
        boot = 1.0
        val n = every(mt, gdt, genEvery)
        for (i in 0 until n) {
            step()
            // the centre cell is the coin: alive hops right, dead hops left (turning back at the walls)
            val hop = if (alive(0)) 70.0 else -70.0
            targetX += hop
            if (targetX < World.w * 0.18 || targetX > World.w * 0.82) targetX -= 2 * hop
        }
        x = damp(x, targetX, 7.0, dt)
        y = damp(y, World.h * 0.25 + 18 * sin(mt * 1.1), 3.0, dt)
        angle += dt * 0.25
    }

    override fun onPhase(i: Int) { rule = 110; genEvery = 0.27 }

    companion object {
        const val N = 40
        const val HIST = 8
        /** The glider's five cells in its 3 × 3 box; this way up it flies toward (+1, +1). */
        val GLIDER = doubleArrayOf(1.0, 0.0, 2.0, 1.0, 0.0, 2.0, 1.0, 2.0, 2.0, 2.0)

        /** Every live cell of the newest generation fires outward from its place on the ring. */
        fun ringVolley(b: AutomatonAugur, speed: Double) {
            val R = b.r * 1.35
            for (i in 0 until N) {
                if (!b.alive(i)) continue
                val a = b.angle + i.toDouble() / N * TAU
                Game.ebullets.fire(b.x + cos(a) * R, b.y + sin(a) * R, a, speed, O.cell[b.gen % 2])
            }
            Audio.play(Sfx.ENEMY_SHOOT, b.x, 1.1)
        }

        /** A glider of Conway's Life: five cells flying as one rigid shape along [dir] (a diagonal). */
        fun glider(x: Double, y: Double, dir: Double, speed: Double) {
            val c = cos(dir - PI / 4); val s = sin(dir - PI / 4); val cs = 15.0
            for (k in 0 until 5) {
                val gx = (GLIDER[k * 2] - 1) * cs; val gy = (GLIDER[k * 2 + 1] - 1) * cs
                Game.ebullets.fire(x + gx * c - gy * s, y + gx * s + gy * c, dir, speed, O.glider)
            }
        }

        /** The diagonal nearest [a]: gliders only fly diagonally. */
        fun diagonal(a: Double): Double = Math.round((a - PI / 4) / HALF_PI) * HALF_PI + PI / 4

        /** The space-time diagram: the newest generation printed as a falling row under the Augur. */
        fun printRow(b: AutomatonAugur, speed: Double) {
            val cw = 18.0
            for (i in 0 until N) if (b.alive(i)) Game.ebullets.fire(b.x + (i - (N - 1) / 2.0) * cw, b.y + b.r, HALF_PI, speed, O.row)
            Audio.play(Sfx.ENEMY_SHOOT, b.x, 1.3)
        }

        private fun gliders(b: AutomatonAugur, both: Boolean) {
            b.gunSide = -b.gunSide
            val a = diagonal(Patterns.aim(b.x, b.y))
            glider(b.x + b.gunSide * b.r * 1.2, b.y + 10, a, 170.0)
            // the second gun aims along the other diagonal on the player's side
            if (both) glider(b.x - b.gunSide * b.r * 1.2, b.y + 10, if (sin(a) > 0) PI - a else -a, 170.0)
            Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.9)
        }

        val PHASES = listOf(
            Phase(0.5, listOf(
                Attack("Rule Thirty", 5.6, 0.7) { b0, _, _ ->
                    val b = b0 as AutomatonAugur
                    if (b.gen != b.firedGen) { b.firedGen = b.gen; ringVolley(b, 150.0) }
                },
                Attack("Glider Gun", 5.0, 0.8) { b, t, dt ->
                    if (every(t, dt, 0.5) > 0) gliders(b as AutomatonAugur, true)
                    if (every(t, dt, 1.0, 0.5) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 3, 0.4, 205.0, O.aimRice)
                },
                Attack("Space-Time", 6.0, 1.0) { b0, _, _ ->
                    val b = b0 as AutomatonAugur
                    if (b.gen != b.firedGen) { b.firedGen = b.gen; printRow(b, 115.0) }
                }
            )),
            Phase(0.0, listOf(
                Attack("Rule One-Ten", 5.4, 0.7) { b0, _, _ ->
                    val b = b0 as AutomatonAugur
                    if (b.gen != b.firedGen) { b.firedGen = b.gen; ringVolley(b, 160.0) }
                },
                Attack("Glider Fleet", 5.4, 0.8) { b, t, dt ->
                    if (every(t, dt, 0.42) > 0) gliders(b as AutomatonAugur, true)
                    if (every(t, dt, 0.9, 0.45) > 0) Patterns.spread(b.x, b.y, Patterns.aim(b.x, b.y), 3, 0.4, 210.0, O.aimRice)
                },
                Attack("Halting Oracle", 6.0, 1.0) { b0, t, dt ->
                    val b = b0 as AutomatonAugur
                    if (every(t, dt, 1.6) > 0) {
                        // four live cells, picked with a stride coprime to the ring, light short beams
                        var placed = 0
                        for (k in 0 until N) {
                            val i = (b.lanceI + k * 7) % N
                            if (!b.alive(i)) continue
                            Game.lasers.add(Laser(b, angle = b.angle + i.toDouble() / N * TAU, warmup = 0.75, duration = 0.55, width = 16.0, color = b.color))
                            if (++placed == 4) break
                        }
                        b.lanceI += 11
                        Audio.play(Sfx.LASER_CHARGE, b.x)
                    }
                    if (b.gen != b.firedGen) { b.firedGen = b.gen; if (b.gen % 2 == 0) printRow(b, 110.0) }
                }
            ))
        )
    }
}

/**
 * X — EULER EIDOLON. e^{iθ} = cos θ + i·sin θ turns a point by θ around the unit circle, and a half
 * turn lands on −1: e^{iπ} + 1 = 0 binds e, i, π, 1 and 0. Every guardian before it was built from
 * such turns (rose petals, Lissajous phases, Koch's folds, the tesseract's plane rotations, phasors,
 * fifth roots of unity, knotted windings, z² + c and the automaton's ring), and the Eidolon carries
 * a sigil of each on its unit circle while it orbits the arena. Its nine attacks quote the nine in
 * order, three per phase, each with a half-turn of its own; in phase III it makes half-turns too.
 */
class EulerEidolon : Boss(BOSS_DEFS[9]) {
    /** Orbit phase on e^{iφ} and its rate; phase III adds a half-turn (φ += π) every 7 s. */
    var orbit = 0.0; var orbitW = 0.32; var halfTurns = false
    /** The sigils' angle on the ring, the phasor arm's angle and (intro) how far in the sigils have flown. */
    var sigilA = 0.0; var gather = 0.0
    var kRose = 3; var roseRot = 0.0; var laserDir = 1.0; var phylloI = 0; var gridK = 0; var gridPhase = 0; var sigilI = 0; var gardenI = 0
    /** Nine cells for the automaton's quote: one per sigil (Rule 30 on a ring of nine). */
    var cells = 1L shl 4
    val chain = doubleArrayOf(0.5, 0.5)
    override val phases get() = PHASES

    val ringR: Double get() = r * 2.2

    override fun initBoss() {
        orbit = 0.0; orbitW = 0.32; halfTurns = false; sigilA = 0.0; gather = 0.0; kRose = 3; roseRot = 0.0; laserDir = 1.0
        phylloI = 0; gridK = 0; gridPhase = 0; sigilI = 0; gardenI = 0; cells = 1L shl 4; chain[0] = 0.5; chain[1] = 0.5
    }

    fun sigilX(k: Int): Double = x + cos(sigilA + k * TAU / SIGILS) * ringR
    fun sigilY(k: Int): Double = y + sin(sigilA + k * TAU / SIGILS) * ringR
    fun sigilAngle(k: Int): Double = sigilA + k * TAU / SIGILS

    override fun introPose(dt: Double) { gather = Ease.outCubic(clamp(1 - introT / introDur, 0.0, 1.0)); sigilA += dt * 0.5; angle += dt * 1.2 }

    override fun move(dt: Double) {
        mt += dt
        gather = 1.0
        if (halfTurns && every(mt, dt, 7.0) > 0) { orbit += PI; Fx.ring(x, y, 20.0, 160.0, 0.6, color, 4.0); Audio.play(Sfx.WARP, x) }
        orbit += orbitW * dt
        val R = min(World.w * 0.2, World.h * 0.14)
        val tx = World.w / 2 + cos(orbit) * R * 1.6; val ty = World.h * 0.34 + sin(orbit) * R
        x = damp(x, tx, 2.5, dt); y = damp(y, ty, 2.5, dt)
        sigilA += dt * 0.5
        angle += dt * 1.2   // the phasor arm
    }

    override fun onPhase(i: Int) {
        orbitW = if (i == 1) -0.42 else 0.5
        if (i == 2) halfTurns = true
    }

    override fun emitLight() {
        super.emitLight()
        for (k in 0 until SIGILS) Light.add(sigilX(k), sigilY(k), 60.0, BOSS_DEFS[k].color, 0.35)
    }

    companion object {
        const val SIGILS = 9

        /** The automaton's quote: Rule 30 steps the nine sigils, and every live one fires outward. */
        private fun automaton(b: EulerEidolon) {
            var next = 0L
            for (i in 0 until SIGILS) {
                val l = (b.cells shr ((i + SIGILS - 1) % SIGILS)) and 1L; val c = (b.cells shr i) and 1L; val r = (b.cells shr ((i + 1) % SIGILS)) and 1L
                if ((30 shr ((l shl 2) or (c shl 1) or r).toInt()) and 1 == 1) next = next or (1L shl i)
            }
            b.cells = if (next == 0L) 1L shl 4 else next
            for (k in 0 until SIGILS) if ((b.cells shr k) and 1L != 0L) Patterns.spread(b.sigilX(k), b.sigilY(k), b.sigilAngle(k), 3, 0.3, 150.0, O.cell[k % 2])
        }

        val PHASES = listOf(
            Phase(0.66, listOf(
                // I — Helix Cantor's rose, followed by its half-turn: petals between the petals
                Attack("Rose of Identity", 5.0, 0.8) { b0, t, dt ->
                    val b = b0 as EulerEidolon
                    if (every(t, dt, 1.0) > 0) {
                        b.kRose = if (b.kRose >= 5) 3 else b.kRose + 1; b.roseRot = Rng.game.angle()
                        Patterns.rose(b.x, b.y, 60, 235.0, b.kRose.toDouble(), b.roseRot, 0.42, O.rose); Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.7)
                    }
                    if (every(t, dt, 1.0, 0.3) > 0) Patterns.rose(b.x, b.y, 60, 235.0, b.kRose.toDouble(), b.roseRot + PI / b.kRose, 0.42, O.roseHalf)
                },
                // II — the Leviathan's sine torrent, poured from each sigil in turn
                Attack("Lissajous Ribbon", 5.0, 0.7) { b0, t, dt ->
                    val b = b0 as EulerEidolon
                    if (every(t, dt, 0.12) > 0) {
                        val k = b.sigilI++ % SIGILS
                        val sx = b.sigilX(k); val sy = b.sigilY(k); val a = Patterns.aim(sx, sy)
                        Game.ebullets.fire(sx, sy, a, 230.0, O.torrentA); Game.ebullets.fire(sx, sy, a, 230.0, O.torrentB)
                    }
                    if (every(t, dt, 0.36) > 0) Audio.play(Sfx.ENEMY_SHOOT, b.x)
                },
                // III — the Seraph's lances over its phyllotaxis choir
                Attack("Snowflake Lances", 6.0, 1.0) { b0, t, dt ->
                    val b = b0 as EulerEidolon
                    if (crossed(t, dt, 0.0)) {
                        b.laserDir *= -1
                        val base = Patterns.aim(b.x, b.y) + PI / 3
                        for (k in 0 until 3) Game.lasers.add(Laser(b, angle = base + k * TAU / 3, angVel = 0.3 * b.laserDir, warmup = 1.2, duration = 4.2, width = 20.0, color = b.color))
                        Audio.play(Sfx.LASER_CHARGE, b.x)
                    }
                    val n = every(t, dt, 0.06)
                    for (i in 0 until n) { Patterns.phyllo(b.x, b.y, b.phylloI, 1, 135.0, 0.0, O.phyllo[b.phylloI % 3]); b.phylloI++ }
                }
            )),
            Phase(0.33, listOf(
                // IV — the Entropy Engine's vertex rain, from the nine sigils, under its heat death
                Attack("Tesseract Rain", 5.4, 0.8) { b0, t, dt ->
                    val b = b0 as EulerEidolon
                    if (every(t, dt, 0.24) > 0) {
                        for (k in 0 until SIGILS) Game.ebullets.fire(b.sigilX(k), b.sigilY(k), b.sigilAngle(k), 170.0, if (k % 2 == 0) O.vertexHot else O.vertex)
                        Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.9)
                    }
                    if (every(t, dt, 1.6, 0.8) > 0) Patterns.ring(b.x, b.y, 24, 260.0, Rng.game.angle(), O.heat)
                },
                // V — the Orrery's unwinding wheels and its star
                Attack("Phasor Requiem", 5.6, 0.9) { b, t, dt ->
                    if (every(t, dt, 1.3) > 0) { FourierOrrery.wheel(b.x, b.y, 12, 115.0, Rng.game.angle()); Audio.play(Sfx.WARP, b.x) }
                    if (every(t, dt, 1.3, 0.65) > 0) FourierOrrery.bloom(b.x, b.y, FourierOrrery.STAR, 160.0, Rng.game.angle(), O.phasor)
                },
                // VI — the Pentarch's pentagrid walls and its deflating kites
                Attack("Golden Pentagrid", 6.0, 1.0) { b0, t, dt ->
                    val b = b0 as EulerEidolon
                    if (every(t, dt, 1.0) > 0) {
                        val dir = HALF_PI + b.gridK * TAU / 5
                        b.gridK = (b.gridK + 2) % 5; b.gridPhase += 7
                        PenrosePentarch.wall(b.x, b.y, dir, 23, 30.0, b.gridPhase, 150.0, O.pentagrid)
                        PenrosePentarch.wall(b.x, b.y, dir + PI, 23, 30.0, b.gridPhase + 13, 150.0, O.pentagrid)
                        Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.75)
                    }
                    if (every(t, dt, 2.0, 1.0) > 0) Patterns.ring(b.x, b.y, 8, 85.0, Rng.game.angle(), O.kite)
                }
            )),
            Phase(0.0, listOf(
                // VII — the Hierophant's braid and its writhe
                Attack("Trefoil Braid", 5.0, 0.6) { b, t, dt ->
                    val n = every(t, dt, 0.1)
                    for (i in 0 until n) TrefoilHierophant.braid(b.x, b.y, Patterns.aim(b.x, b.y), O.braid3, 0.13, 205.0)
                    if (every(t, dt, 1.4, 0.7) > 0) TrefoilHierophant.writhe(b.x, b.y, 12, Rng.game.angle(), 140.0)
                    if (every(t, dt, 0.4) > 0) Audio.play(Sfx.ENEMY_SHOOT, b.x)
                },
                // VIII — the Matriarch's Julia garden, and bulbs budding from three sigils
                Attack("Julia Requiem", 5.4, 0.8) { b0, t, dt ->
                    val b = b0 as EulerEidolon
                    if (every(t, dt, 0.9) > 0) {
                        val g = b.gardenI++ % (MandelbrotMatriarch.GARDEN.size / 2)
                        MandelbrotMatriarch.julia(b.x, b.y, b.chain, MandelbrotMatriarch.GARDEN[g * 2], MandelbrotMatriarch.GARDEN[g * 2 + 1], 56, 150.0, g % 2 == 1, O.julia[g % 2])
                        Audio.play(Sfx.ENEMY_SHOOT, b.x, 0.75)
                    }
                    if (every(t, dt, 1.2, 0.45) > 0) {
                        for (j in 0 until 3) { val k = (b.sigilI + j * 3) % SIGILS; Patterns.ring(b.sigilX(k), b.sigilY(k), 8, 140.0, Rng.game.angle(), O.bulb) }
                        b.sigilI++
                    }
                },
                // IX — the Augur's automaton on the sigils, and the identity: every bullet splits into itself and its half-turn
                Attack("Identity", 6.4, 1.0) { b0, t, dt ->
                    val b = b0 as EulerEidolon
                    if (every(t, dt, 0.4) > 0) automaton(b)
                    if (every(t, dt, 1.6, 0.2) > 0) {
                        Patterns.ring(b.x, b.y, 18, 130.0, Rng.game.angle(), O.identity)
                        Fx.ring(b.x, b.y, 14.0, 120.0, 0.5, b.color, 4.0); Audio.play(Sfx.WARP, b.x)
                    }
                }
            ))
        )
    }
}
