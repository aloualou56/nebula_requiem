package io.github.aloualou56.nebularequiem.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * §14 ENEMIES — geometric hostiles with steering behaviours. Every hostile is a polygon in unit
 * space scaled by its radius; the same vertex list draws the ship and feeds the shatter effect.
 */

/** Spawn options (the original's `o` object literal). */
class SpawnOpts {
    var elite = false; var scale = 1.0; var hpScale = 1.0; var speedScale = 1.0; var rewardScale = 1.0
    var angle = Double.NaN; var instant = false; var gen = 0; var heading = Double.NaN
    var vx = 0.0; var vy = 0.0
    fun reset(): SpawnOpts { elite = false; scale = 1.0; hpScale = 1.0; speedScale = 1.0; rewardScale = 1.0; angle = Double.NaN; instant = false; gen = 0; heading = Double.NaN; vx = 0.0; vy = 0.0; return this }
}

private var ENEMY_SEQ = 1

abstract class Enemy {
    var id = 0
    lateinit var def: EnemyDef
    var type = ""; var name = ""
    @JvmField var x = 0.0; @JvmField var y = 0.0; @JvmField var px = 0.0; @JvmField var py = 0.0
    @JvmField var vx = 0.0; @JvmField var vy = 0.0
    var elite = false
    @JvmField var r = 10.0
    var maxHp = 1.0; var hp = 1.0
    var color = Pal.WHITE
    var speed = 0.0; var score = 0.0; var xp = 0.0
    @JvmField var angle = 0.0; @JvmField var prevAngle = 0.0; var spin = 0.0
    var t = 0.0; var stateT = 0.0
    var spawnT = 0.0; var spawnDur = 0.85
    var hitFlash = 0.0
    @JvmField var dead = false
    open val isBoss: Boolean get() = false
    var contain = true
    var fireMul = 1.0
    var poly: FloatArray = FloatArray(0)
    var verts = DoubleArray(0)
    /** Spatial-hash query stamp. */
    @JvmField var qs = 0
    // damage-number aggregation (per target over 0.14 s windows)
    var dmgAcc = 0.0; var dmgCrit = false; var dmgT = -1.0

    fun setup(d: EnemyDef, x: Double, y: Double, o: SpawnOpts): Enemy {
        id = ENEMY_SEQ++
        def = d; type = d.id; name = d.name
        this.x = x; this.y = y; px = x; py = y; vx = 0.0; vy = 0.0
        elite = o.elite
        val dir = Game.director
        r = d.r * (if (elite) 1.22 else 1.0) * o.scale
        hp = d.hp * dir.hpMul * (if (elite) 2.6 else 1.0) * o.hpScale; maxHp = hp
        color = d.color
        speed = d.speed * o.speedScale
        score = d.score * (if (elite) 3.0 else 1.0) * o.rewardScale
        xp = d.xp * (if (elite) 3.0 else 1.0) * o.rewardScale
        angle = if (o.angle.isNaN()) Rng.game.angle() else o.angle; prevAngle = angle; spin = 0.0
        t = 0.0; stateT = 0.0
        spawnT = if (o.instant) 0.0 else 0.85; spawnDur = 0.85
        hitFlash = 0.0; dead = false; contain = true
        fireMul = dir.fireMul
        poly = d.poly
        if (verts.size != poly.size) verts = DoubleArray(poly.size)
        dmgAcc = 0.0; dmgCrit = false; dmgT = -1.0
        if (!o.instant) { Fx.warpIn(x, y, color, r); Audio.play(Sfx.WARP, x) }
        init(o)
        return this
    }

    open fun init(o: SpawnOpts) {}
    open val active: Boolean get() = spawnT <= 0 && !dead

    open fun update(dt: Double) {
        px = x; py = y; prevAngle = angle
        t += dt
        if (spawnT > 0) {
            spawnT -= dt
            angle += dt * 4
            if (spawnT <= 0) Fx.ring(x, y, r, r * 3, 0.35, color, 2.0)
            return
        }
        stateT += dt
        think(dt)
        x += vx * dt; y += vy * dt
        angle += spin * dt
        hitFlash = max(0.0, hitFlash - dt * 7)
        if (contain) {
            // Soft containment: F = k·penetration pushes stragglers back inside.
            val m = r + 8; val W = World.w; val H = World.h; val k = 10.0
            if (x < m) vx += (m - x) * k * dt else if (x > W - m) vx -= (x - (W - m)) * k * dt
            if (y < m) vy += (m - y) * k * dt else if (y > H - m) vy -= (y - (H - m)) * k * dt
        }
    }

    open fun think(dt: Double) {}

    /** Reynolds seek: steer = clamp(normalize(target − p)·v_max − v, F·dt). */
    fun seek(tx: Double, ty: Double, maxSpeed: Double, maxForce: Double, dt: Double) {
        val dx = tx - x; val dy = ty - y
        val d0 = sqrt(dx * dx + dy * dy); val d = if (d0 == 0.0) 1.0 else d0
        var sx = (dx / d) * maxSpeed - vx; var sy = (dy / d) * maxSpeed - vy
        val sl = sqrt(sx * sx + sy * sy); val F = maxForce * dt
        if (sl > F) { sx *= F / sl; sy *= F / sl }
        vx += sx; vy += sy
    }

    /** Arrive: seek with desired speed ramping down inside slowRadius → no overshoot. */
    fun arrive(tx: Double, ty: Double, maxSpeed: Double, maxForce: Double, slowRadius: Double, dt: Double) {
        val dx = tx - x; val dy = ty - y; val d = sqrt(dx * dx + dy * dy)
        seek(tx, ty, maxSpeed * min(1.0, d / slowRadius), maxForce, dt)
    }

    /** Separation (boids): neighbours inside `radius` push away with strength ∝ (1 − d/r). */
    fun separate(radius: Double, strength: Double, dt: Double) {
        val near = Game.grid.query(x, y, radius, Game.sep)
        for (i in near.indices) {
            val o = near[i]
            if (o === this || o.isBoss) continue
            val dx = x - o.x; val dy = y - o.y; val d2 = dx * dx + dy * dy; val R = radius + o.r * 0.5
            if (d2 > R * R || d2 < 1e-4) continue
            val d = sqrt(d2); val push = (1 - d / R) * strength * dt
            vx += (dx / d) * push; vy += (dy / d) * push
        }
    }

    fun playerAngle(): Double { val p = Game.player; return if (p != null) atan2(p.y - y, p.x - x) else HALF_PI }
    fun shoot(angle: Double, speed: Double, o: BulletOpts): EnemyBullet? = Game.ebullets.fire(x, y, angle, speed, o)
    fun muzzle() { Fx.flash(x, y, r * 1.8, 0.12, color); Audio.play(Sfx.ENEMY_SHOOT, x) }

    open fun hurt(amount: Double): Double {
        if (!active) return 0.0
        hp -= amount
        hitFlash = 1.0
        if (hp <= 0) { dead = true; Game.onEnemyKilled(this) }
        return amount
    }

    /** World-space vertices: vᵂ = p + R(θ)·(r·vᵘ). */
    fun worldVerts(): DoubleArray {
        val c = cos(angle); val s = sin(angle); val r = r; val v = verts
        val n = poly.size / 2
        for (i in 0 until n) {
            val ux = poly[i * 2]; val uy = poly[i * 2 + 1]
            v[i * 2] = x + (ux * c - uy * s) * r
            v[i * 2 + 1] = y + (ux * s + uy * c) * r
        }
        return v
    }

    open fun onDeath() {}
    /** Aegis shield arc test. */
    open fun blocks(sx: Double, sy: Double): Boolean = false
}

private val MOTE_SHOT = BulletOpts(r = 5.0)
/** Mote — swarming chaser. Pure seek + separation; elites spit aimed shots. */
class MoteDrone : Enemy() {
    var fireT = 0.0; var wob = 0.0
    override fun init(o: SpawnOpts) { fireT = Rng.game.range(1.5, 3.0); wob = Rng.game.angle() }
    override fun think(dt: Double) {
        val p = Game.player; val tx = p?.x ?: (World.w / 2); val ty = p?.y ?: (World.h / 2)
        val sp = speed * (1 + 0.18 * sin(t * 3 + wob))
        seek(tx, ty, sp, 560.0, dt)
        separate(34.0, 900.0, dt)
        angle = atan2(vy, vx)
        if (elite || Game.director.d >= 9) {
            fireT -= dt * fireMul
            if (fireT <= 0) { fireT = rearm(fireT, 2.6, dt); MOTE_SHOT.color = color; shoot(playerAngle(), 190.0, MOTE_SHOT); muzzle() }
        }
    }
}

private val GYRE_A = BulletOpts(r = 6.0)
private val GYRE_B = BulletOpts(color = Pal.C_FF8AE9, r = 5.0, delay = 0.25)
/** Gyre — a spinning hexagonal turret that drifts between anchors and fires rotating rings. */
class GyreTurret : Enemy() {
    var fireT = 0.0; var ax = 0.0; var ay = 0.0; var anchorT = 0.0
    override fun init(o: SpawnOpts) { spin = Rng.game.sign() * 1.3; fireT = Rng.game.range(1.2, 2.2); pickAnchor() }
    private fun pickAnchor() { ax = Rng.game.range(0.12, 0.88) * World.w; ay = Rng.game.range(0.1, 0.55) * World.h; anchorT = Rng.game.range(4.0, 7.0) }
    override fun think(dt: Double) {
        arrive(ax, ay, speed, 140.0, 120.0, dt)
        anchorT -= dt
        if (anchorT <= 0) pickAnchor()
        fireT -= dt * fireMul
        if (fireT <= 0) {
            fireT = rearm(fireT, 2.7, dt)
            val n = 10 + min(10, Game.director.d / 2) + if (elite) 6 else 0
            GYRE_A.color = color
            Patterns.ring(x, y, n, 145.0, angle, GYRE_A)
            if (elite || Game.director.d >= 6) Patterns.ring(x, y, n, 105.0, angle + PI / n, GYRE_B)
            muzzle()
        }
    }
}

private val DART_SHOT = BulletOpts(shape = Shape.RICE, r = 5.0)
/** Dart — telegraphed lunge. Aims with lead targeting and charges. */
class DartLancer : Enemy() {
    var state = 0 // 0 drift · 1 aim · 2 charge · 3 recover
    var stateDur = 0.0; var aimA = 0.0; var wander = 0.0
    override fun init(o: SpawnOpts) { state = 0; stateDur = Rng.game.range(1.2, 2.2); aimA = 0.0; wander = Rng.game.angle() }
    private fun setState(s: Int, dur: Double) { state = s; stateT = 0.0; stateDur = dur }
    override fun think(dt: Double) {
        val p = Game.player
        when (state) {
            0 -> {
                // Brownian heading drift: increments ~ N(0, σ²·dt), σ = 0.158 rad/√s.
                wander += Rng.game.gauss() * 0.158 * sqrt(dt)
                val tx = (p?.x ?: (World.w / 2)) + cos(wander) * 260; val ty = (p?.y ?: (World.h / 2)) + sin(wander) * 260
                seek(tx, ty, speed, 300.0, dt)
                separate(30.0, 600.0, dt)
                angle = dampAngle(angle, atan2(vy, vx), 6.0, dt)
                if (stateT > stateDur) { setState(1, 0.72); Audio.play(Sfx.LASER_CHARGE, x) }
            }
            1 -> {
                val k = exp(-6 * dt); vx *= k; vy *= k
                if (p != null && stateT < stateDur - 0.16) aimA = interceptAngle(x, y, p.x, p.y, p.vx * 0.6, p.vy * 0.6, 780.0)
                angle = dampAngle(angle, aimA, 18.0, dt)
                if (stateT > stateDur) {
                    setState(2, 0.55)
                    vx = cos(aimA) * 780; vy = sin(aimA) * 780
                    Fx.sparks(x, y, 8.0, color, 300.0, 1.0, aimA + PI, 0.3, 1.6)
                }
            }
            2 -> {
                if (rateChance(0.7 * Fx.mul, dt)) Fx.spark(x, y, aimA + PI + Rng.vis.range(-0.3, 0.3), 200.0, 0.25, color, 2.0)
                val out = x < r || x > World.w - r || y < r || y > World.h - r
                if (stateT > stateDur || out) {
                    if (Game.director.d >= 6 || elite) { DART_SHOT.color = color; Patterns.spread(x, y, aimA + PI, 3, 0.6, 170.0, DART_SHOT); muzzle() }
                    setState(3, 0.55)
                }
            }
            else -> {
                val k = exp(-5 * dt); vx *= k; vy *= k
                if (stateT > stateDur) setState(0, Rng.game.range(1.2, 2.4))
            }
        }
    }
}

private val WEAVER_SHOT = BulletOpts(shape = Shape.RICE, r = 5.0)
/** Weaver — crosses the arena on a sine path: ṗ = ĥ·v + n̂·A·ω·cos(ωt + φ). */
class WeaverSkiff : Enemy() {
    var heading = 0.0; var A = 70.0; var omega = 2.4; var phase = 0.0; var fireT = 0.0
    override fun init(o: SpawnOpts) {
        contain = false
        val cx = World.w / 2; val cy = World.h * 0.45
        heading = if (o.heading.isNaN()) atan2(cy - y, cx - x) + Rng.game.range(-0.4, 0.4) else o.heading
        A = 70.0; omega = 2.4; phase = Rng.game.angle(); fireT = Rng.game.range(0.8, 1.8)
    }
    override fun think(dt: Double) {
        val hx = cos(heading); val hy = sin(heading); val nx = -hy; val ny = hx
        val w = A * omega * cos(omega * t + phase)
        vx = hx * speed + nx * w; vy = hy * speed + ny * w
        angle = atan2(vy, vx)
        val m = 90.0
        if (x < -m || x > World.w + m || y < -m || y > World.h + m) heading = atan2(World.h * 0.45 - y, World.w / 2 - x) + Rng.game.range(-0.5, 0.5)
        fireT -= dt * fireMul
        if (fireT <= 0 && x > 0 && x < World.w && y > 0 && y < World.h) {
            fireT = rearm(fireT, 1.9, dt)
            WEAVER_SHOT.color = color
            Patterns.spread(x, y, playerAngle(), if (elite) 5 else 3, 0.38, 215.0, WEAVER_SHOT)
            muzzle()
        }
    }
}

private val MITOSIS_SHOT = BulletOpts(r = 7.0, shape = Shape.BIG)
/** Mitosis — divides when destroyed: generation g has radius ∝ 0.68ᵍ and hp ∝ 0.45ᵍ. */
class MitosisCell : Enemy() {
    var gen = 0; var fireT = 0.0
    override fun init(o: SpawnOpts) {
        gen = o.gen
        spin = Rng.game.sign() * (0.6 + gen * 0.6)
        fireT = Rng.game.range(1.5, 3.0)
    }
    override fun think(dt: Double) {
        val p = Game.player
        seek(p?.x ?: (World.w / 2), p?.y ?: (World.h / 2), speed * (1 + 0.45 * gen), 160.0, dt)
        separate(r * 2.2, 600.0, dt)
        if (gen == 0) {
            fireT -= dt * fireMul
            if (fireT <= 0) { fireT = rearm(fireT, 3.0, dt); MITOSIS_SHOT.color = color; Patterns.spread(x, y, playerAngle(), 5, 0.9, 130.0, MITOSIS_SHOT); muzzle() }
        }
    }
    override fun onDeath() {
        if (gen >= 2) return
        val a = Rng.game.angle()
        for (i in 0 until 2) {
            val ang = a + i * PI
            val o = SpawnOpts()
            o.instant = true; o.gen = gen + 1; o.scale = 0.68.pow(gen + 1); o.hpScale = 0.45.pow(gen + 1)
            o.rewardScale = 0.5; o.elite = elite; o.vx = cos(ang) * 260; o.vy = sin(ang) * 260
            Game.queueSpawn("mitosis", x + cos(ang) * r * 0.5, y + sin(ang) * r * 0.5, o)
        }
    }
}

private val SEER_SHOT = BulletOpts(shape = Shape.RICE, r = 5.0)
/** Seer — a sniper eye. Keeps a ring distance (flee/arrive/strafe) and fires telegraphed volleys. */
class SeerEye : Enemy() {
    var fireT = 0.0; var aimA = 0.0; var volley = 0; var volleyT = 0.0; var strafe = 1.0
    override fun init(o: SpawnOpts) { fireT = Rng.game.range(2.0, 3.5); aimA = 0.0; volley = 0; volleyT = 0.0; strafe = Rng.game.sign() }
    override fun think(dt: Double) {
        val p = Game.player
        if (p != null) {
            val dx = p.x - x; val dy = p.y - y; val d0 = sqrt(dx * dx + dy * dy); val d = if (d0 == 0.0) 1.0 else d0
            if (d < 320) seek(x - dx, y - dy, speed, 260.0, dt)
            else if (d > 470) seek(p.x, p.y, speed, 220.0, dt)
            else seek(x - (dy / d) * 100 * strafe, y + (dx / d) * 100 * strafe, speed * 0.7, 200.0, dt)
        }
        separate(40.0, 500.0, dt)
        val want = playerAngle()
        fireT -= dt * fireMul
        if (volley > 0) {
            volleyT -= dt
            if (volleyT <= 0) { volleyT = rearm(volleyT, 0.07, dt); volley--; SEER_SHOT.color = color; shoot(aimA, 540.0, SEER_SHOT); Audio.play(Sfx.ENEMY_SHOOT, x, 1.4) }
        } else if (fireT <= 1) {
            // Lock-on: the aim lags behind the player with a finite angular response (λ = 4/s).
            aimA = dampAngle(aimA, want, 4.0, dt)
            if (fireT <= 0) { fireT = rearm(fireT, 3.4, dt); volley = if (elite) 8 else 5; volleyT = 0.0; Fx.flash(x, y, 30.0, 0.2, color) }
        } else aimA = want
        angle = dampAngle(angle, 0.0, 3.0, dt)
    }
}

private val SOWER_MINE = BulletOpts(shape = Shape.MINE, r = 9.0, life = 4.0, splitAt = 2.3, splitSpeed = 150.0, splitShape = Shape.ORB, splitColor = Pal.C_FFB238)
/** Sower — bounces across the arena seeding mines that bloom into rings. */
class SowerMiner : Enemy() {
    var dropT = 0.0
    override fun init(o: SpawnOpts) {
        val a = Rng.game.pick(intArrayOf(1, 3, 5, 7)) * PI / 4
        vx = cos(a) * speed; vy = sin(a) * speed
        dropT = 0.8; spin = 2.0
    }
    override fun think(dt: Double) {
        val m = r + 10
        if ((x < m && vx < 0) || (x > World.w - m && vx > 0)) vx = -vx
        if ((y < m && vy < 0) || (y > World.h * 0.85 - m && vy > 0)) vy = -vy
        val sp0 = sqrt(vx * vx + vy * vy); val sp = if (sp0 == 0.0) 1.0 else sp0
        vx *= speed / sp; vy *= speed / sp
        dropT -= dt * fireMul
        if (dropT <= 0) {
            dropT = rearm(dropT, 1.15, dt)
            SOWER_MINE.splitN = 6 + min(6, Game.director.d / 3) + if (elite) 4 else 0
            SOWER_MINE.color = color
            shoot(Rng.game.angle(), 0.0, SOWER_MINE)
        }
    }
}

private val AEGIS_A = BulletOpts(r = 6.0)
private val AEGIS_B = BulletOpts(color = Pal.C_FFE066, r = 6.0, delay = 0.3)
/** Aegis — a warden with a 140° frontal shield arc that turns slowly; flank it. */
class AegisWarden : Enemy() {
    var shieldA = 0.0; var fireT = 0.0; val halfArc = 70 * DEG
    override fun init(o: SpawnOpts) { shieldA = playerAngle(); fireT = Rng.game.range(1.5, 2.5) }
    override fun think(dt: Double) {
        val p = Game.player
        if (p != null) {
            val a = atan2(y - p.y, x - p.x)
            arrive(p.x + cos(a) * 300, p.y + sin(a) * 300, speed, 120.0, 150.0, dt)
        }
        separate(50.0, 600.0, dt)
        shieldA = dampAngle(shieldA, playerAngle(), 2.1, dt)
        angle = shieldA
        fireT -= dt * fireMul
        if (fireT <= 0) {
            fireT = rearm(fireT, 2.4, dt)
            AEGIS_A.color = color
            Patterns.spread(x, y, shieldA, 5, 0.75, 165.0, AEGIS_A)
            if (elite) Patterns.spread(x, y, shieldA, 4, 0.55, 120.0, AEGIS_B)
            muzzle()
        }
    }
    /** The shot must be in front (inside the arc) and near the hull. */
    override fun blocks(sx: Double, sy: Double): Boolean {
        val a = atan2(sy - y, sx - x)
        return Collide.inArc(a, shieldA, halfArc) && dist2(sx, sy, x, y) < (r + 18) * (r + 18)
    }
}

/* ── hostiles that debut after each guardian (sectors 3–10) ── */

private val PRISM_SHARD = BulletOpts(r = 6.0, shape = Shape.DIAMOND, accel = -300.0, minSpeed = 0.0, retargetAt = 1.15, retargetSpeed = 320.0, retargetAccel = 420.0)
/** Prism — a spinning crystal. Each vertex fans out shards that stop dead, hang, then all turn on you at once. */
class PrismShard : Enemy() {
    var fireT = 0.0; var ax = 0.0; var ay = 0.0; var anchorT = 0.0
    override fun init(o: SpawnOpts) { spin = Rng.game.sign() * 0.9; fireT = Rng.game.range(1.4, 2.4); pickAnchor() }
    private fun pickAnchor() { ax = Rng.game.range(0.12, 0.88) * World.w; ay = Rng.game.range(0.1, 0.5) * World.h; anchorT = Rng.game.range(4.0, 6.5) }
    override fun think(dt: Double) {
        arrive(ax, ay, speed, 150.0, 110.0, dt)
        separate(40.0, 500.0, dt)
        anchorT -= dt
        if (anchorT <= 0) pickAnchor()
        fireT -= dt * fireMul
        if (fireT <= 0) {
            fireT = rearm(fireT, 2.9, dt)
            PRISM_SHARD.color = color
            for (k in 0 until 3) {
                val va = angle + k * TAU / 3
                Patterns.spread(x + cos(va) * r, y + sin(va) * r, va, if (elite) 3 else 2, 0.35, 230.0, PRISM_SHARD)
            }
            muzzle()
        }
    }
}

private val COMET_WAKE = BulletOpts(r = 5.0, shape = Shape.RICE, accel = -60.0, minSpeed = 18.0, life = 4.0)
/** Comet — sights a line through you (it shows), streaks along it and leaves a drifting wake; re-enters at the edge and sights again. */
class CometStreak : Enemy() {
    var state = 0   // 0 sighting · 1 streaking
    var aimA = 0.0; var dropT = 0.0; var stateDur = SIGHT
    override fun init(o: SpawnOpts) { contain = false; aimA = playerAngle(); sight() }
    private fun sight() { state = 0; stateT = 0.0; stateDur = SIGHT }
    override fun think(dt: Double) {
        when (state) {
            0 -> {
                val k = exp(-8 * dt); vx *= k; vy *= k
                // the line settles a moment before it fires, so it can be read and dodged
                if (stateT < stateDur - 0.25) aimA = dampAngle(aimA, playerAngle(), 7.0, dt)
                angle = dampAngle(angle, aimA, 14.0, dt)
                if (stateT >= stateDur) { state = 1; stateT = 0.0; dropT = 0.0; angle = aimA; vx = cos(aimA) * speed; vy = sin(aimA) * speed; Audio.play(Sfx.DASH, x) }
            }
            else -> {
                dropT -= dt * fireMul
                if (dropT <= 0) {
                    dropT = rearm(dropT, if (elite) 0.05 else 0.08, dt)
                    COMET_WAKE.color = color
                    shoot(aimA + (if (Rng.game.chance(0.5)) HALF_PI else -HALF_PI) + Rng.game.range(-0.35, 0.35), Rng.game.range(30.0, 75.0), COMET_WAKE)
                }
                if (rateChance(0.6 * Fx.mul, dt)) Fx.spark(x, y, aimA + PI + Rng.vis.range(-0.25, 0.25), 160.0, 0.3, color, 2.0)
                val m = r + 6
                if (x < -m || x > World.w + m || y < -m || y > World.h + m || stateT > 2.6) {
                    // back in just inside the edge it left by, to sight the next pass
                    x = clamp(x, m, World.w - m); y = clamp(y, m, World.h - m); px = x; py = y
                    vx = 0.0; vy = 0.0
                    sight()
                }
            }
        }
    }
    companion object { const val SIGHT = 0.85 }
}

private val PULSAR_A = BulletOpts(r = 6.0)
private val PULSAR_B = BulletOpts(r = 5.0, shape = Shape.RICE, delay = 0.2)
/** Pulsar — charges (showing where it will leave a gap), then fires a dense double ring with that one gap: find it. */
class PulsarStar : Enemy() {
    var fireT = 0.0; var gapA = 0.0; var ax = 0.0; var ay = 0.0
    /** 0..1 through the charge before a pulse. */
    val charge: Double get() = if (fireT < CHARGE) 1 - fireT / CHARGE else 0.0
    override fun init(o: SpawnOpts) { fireT = Rng.game.range(1.6, 2.8); spin = Rng.game.sign() * 0.6; ax = x; ay = y; gapA = Rng.game.angle() }
    override fun think(dt: Double) {
        arrive(ax + cos(t * 0.4) * 40, ay + sin(t * 0.5) * 30, speed, 80.0, 60.0, dt)
        separate(50.0, 500.0, dt)
        val was = fireT
        fireT -= dt * fireMul
        if (was > CHARGE && fireT <= CHARGE) { gapA = Rng.game.angle(); Audio.play(Sfx.LASER_CHARGE, x) }
        if (fireT <= 0) {
            fireT = rearm(fireT, 3.4, dt)
            val n = 24 + min(12, Game.director.d / 4) + if (elite) 8 else 0
            val gapHalf = 2.2 * TAU / n
            PULSAR_A.color = color; PULSAR_B.color = color
            for (k in 0 until n) {
                val a = gapA + k * TAU / n
                if (abs(angleDiff(a, gapA)) < gapHalf) continue
                shoot(a, 150.0, PULSAR_A)
                shoot(a + PI / n, 112.0, PULSAR_B)
            }
            Fx.ring(x, y, r, r * 4, 0.4, color, 3.0); muzzle()
        }
    }
    companion object { const val CHARGE = 1.0 }
}

/** Hive — a carrier that keeps its distance and launches drones (small Motes) until its brood is spent. */
class HiveCarrier : Enemy() {
    var launchT = 0.0; var brood = 0; var strafe = 1.0
    override fun init(o: SpawnOpts) { launchT = Rng.game.range(1.0, 1.8); brood = BROOD + if (elite) 4 else 0; strafe = Rng.game.sign(); spin = 0.4 * Rng.game.sign() }
    override fun think(dt: Double) {
        val p = Game.player
        if (p != null) {
            val dx = p.x - x; val dy = p.y - y; val d0 = sqrt(dx * dx + dy * dy); val d = if (d0 == 0.0) 1.0 else d0
            if (d < 360) seek(x - dx, y - dy, speed, 200.0, dt)
            else if (d > 520) seek(p.x, p.y, speed, 160.0, dt)
            else seek(x - (dy / d) * 100 * strafe, y + (dx / d) * 100 * strafe, speed * 0.7, 160.0, dt)
        }
        separate(60.0, 500.0, dt)
        launchT -= dt * fireMul
        if (launchT <= 0 && brood > 0) {
            launchT = rearm(launchT, 3.6, dt)
            for (i in 0 until 2) {
                if (brood <= 0) break
                brood--
                val a = angle + i * PI
                val o = SpawnOpts()
                o.instant = true; o.scale = 0.72; o.hpScale = DRONE_HP; o.rewardScale = 0.35; o.vx = cos(a) * 180; o.vy = sin(a) * 180
                Game.queueSpawn("mote", x + cos(a) * r, y + sin(a) * r, o)
            }
            Fx.ring(x, y, r * 0.6, r * 2, 0.3, color, 2.0); Audio.play(Sfx.WARP, x)
        }
    }
    companion object {
        const val BROOD = 6
        const val DRONE_HP = 0.5
    }
}

private val VORTEX_SHARD = BulletOpts(r = 5.5, shape = Shape.STAR, spin = 6.0)
/** Vortex — winds a ring of shards around itself one by one, then flings them all outward, curling. */
class VortexCoil : Enemy() {
    var windT = 0.0; var wound = 0; var restT = 0.0; var dir = 1.0; var ax = 0.0; var ay = 0.0; var anchorT = 0.0
    override fun init(o: SpawnOpts) { windT = Rng.game.range(0.8, 1.6); wound = 0; restT = 0.0; dir = Rng.game.sign(); spin = 1.6 * dir; pickAnchor() }
    private fun pickAnchor() { ax = Rng.game.range(0.15, 0.85) * World.w; ay = Rng.game.range(0.12, 0.5) * World.h; anchorT = Rng.game.range(5.0, 8.0) }
    override fun think(dt: Double) {
        arrive(ax, ay, speed, 120.0, 100.0, dt)
        separate(50.0, 500.0, dt)
        anchorT -= dt
        if (anchorT <= 0) pickAnchor()
        val n = 10 + min(6, Game.director.d / 8) + if (elite) 4 else 0
        if (restT > 0) { restT -= dt; return }
        windT -= dt * fireMul
        if (windT <= 0) {
            val step = WIND / fireMul
            windT = rearm(windT, WIND, dt)
            val a = angle + wound * TAU / n
            val b = shoot(a, 0.0, VORTEX_SHARD.also { it.color = color })
            if (b != null) {
                // every shard is let go together, a beat after the last is wound
                b.orbitT = (n - 1 - wound) * step + 0.45; b.orbitOwner = this; b.orbitA = a; b.orbitR = r * 1.1; b.orbitTargetR = r * 2.3
                b.orbitW = 2.4 * dir; b.orbitRelease = HALF_PI * dir * 0.55; b.releaseSpeed = 175 * Game.bulletSpeedMul
            }
            if (++wound >= n) { wound = 0; restT = 0.45 + 1.4; dir = -dir; spin = 1.6 * dir; Audio.play(Sfx.ENEMY_SHOOT, x, 0.8) }
        }
    }
    companion object { const val WIND = 0.16 }
}

private val PHANTOM_SHOT = BulletOpts(r = 5.0, shape = Shape.BOLT)
/** Phantom — blinks out and reappears beside you (its arrival is marked first), then fires a quick burst. It can't be hit while phased. */
class PhantomWisp : Enemy() {
    var phase = 0   // 0 here · 1 fading out · 2 away · 3 fading in
    var phaseT = 0.0; var tx = 0.0; var ty = 0.0; var burst = 0; var burstT = 0.0
    /** How solid it looks, 0..1. */
    val presence: Double get() = when (phase) { 0 -> 1.0; 1 -> clamp(phaseT / FADE, 0.0, 1.0); 3 -> clamp(1 - phaseT / FADE, 0.0, 1.0); else -> 0.0 }
    override val active: Boolean get() = super.active && phase == 0
    override fun init(o: SpawnOpts) { phase = 0; phaseT = Rng.game.range(1.6, 2.6); burst = 0; burstT = 0.0; tx = x; ty = y }
    override fun think(dt: Double) {
        phaseT -= dt
        when (phase) {
            0 -> {
                val p = Game.player
                if (p != null) { val a = atan2(y - p.y, x - p.x); arrive(p.x + cos(a) * 280, p.y + sin(a) * 280, speed, 120.0, 100.0, dt) }
                separate(40.0, 500.0, dt)
                angle = dampAngle(angle, playerAngle(), 6.0, dt)
                if (burst > 0) {
                    burstT -= dt * fireMul
                    if (burstT <= 0) { burstT = rearm(burstT, 0.11, dt); burst--; PHANTOM_SHOT.color = color; shoot(playerAngle(), 300.0, PHANTOM_SHOT); Audio.play(Sfx.ENEMY_SHOOT, x, 1.2) }
                }
                if (phaseT <= 0 && burst == 0) { phase = 1; phaseT = FADE; Audio.play(Sfx.WARP, x, 1.4) }
            }
            1 -> {
                val k = exp(-6 * dt); vx *= k; vy *= k
                if (phaseT <= 0) {
                    phase = 2; phaseT = AWAY
                    // somewhere beside the pilot, never on top of them
                    val p = Game.player
                    val cx = p?.x ?: (World.w / 2); val cy = p?.y ?: (World.h / 2)
                    val a = Rng.game.angle(); val d = Rng.game.range(230.0, 320.0); val m = r + 30
                    tx = clamp(cx + cos(a) * d, m, World.w - m); ty = clamp(cy + sin(a) * d, m, World.h - m)
                    if (dist2(tx, ty, cx, cy) < 200.0 * 200.0) { tx = clamp(cx - cos(a) * d, m, World.w - m); ty = clamp(cy - sin(a) * d, m, World.h - m) }
                }
            }
            2 -> {
                vx = 0.0; vy = 0.0
                if (phaseT <= 0) { x = tx; y = ty; px = x; py = y; phase = 3; phaseT = FADE }
            }
            else -> {
                vx = 0.0; vy = 0.0
                if (phaseT <= 0) { phase = 0; phaseT = Rng.game.range(2.0, 2.8); burst = if (elite) 5 else 3; burstT = 0.25 }
            }
        }
    }
    companion object {
        const val FADE = 0.35
        const val AWAY = 0.9
    }
}

private val CAROM_SHOT = BulletOpts(r = 6.0, bounces = 2, life = 9.0)
/** Carom — patrols the upper arena and banks its shots off the walls: the rebound is the threat. */
class CaromSkiff : Enemy() {
    var fireT = 0.0; var dir = 1.0; var baseY = 0.0
    override fun init(o: SpawnOpts) { fireT = Rng.game.range(1.0, 2.0); dir = Rng.game.sign(); baseY = clamp(y, World.h * 0.12, World.h * 0.42) }
    override fun think(dt: Double) {
        val m = r + 14
        if (x < m) dir = 1.0 else if (x > World.w - m) dir = -1.0
        vx = damp(vx, dir * speed, 3.0, dt); vy = (baseY + sin(t * 1.3) * 26 - y) * 3
        angle = dampAngle(angle, if (dir > 0) 0.0 else PI, 6.0, dt)
        fireT -= dt * fireMul
        if (fireT <= 0) {
            fireT = rearm(fireT, 2.2, dt)
            CAROM_SHOT.color = color
            // aimed wide of the pilot on both sides, so they come back off the walls
            val a = playerAngle()
            for (k in 0 until if (elite) 4 else 2) shoot(a + (if (k % 2 == 0) 1 else -1) * (0.5 + 0.18 * (k / 2)), 250.0, CAROM_SHOT)
            muzzle()
        }
    }
}

private val HARBINGER_SHOT = BulletOpts(r = 6.5)
/** Harbinger — a slow heavy that sweeps a beam through where you stand (it warms up first), between aimed salvos. */
class HarbingerHeavy : Enemy() {
    var beamT = 0.0; var fireT = 0.0; var ax = 0.0; var ay = 0.0; var sweep = 1.0
    override fun init(o: SpawnOpts) { beamT = Rng.game.range(2.0, 3.0); fireT = Rng.game.range(1.0, 1.8); ax = x; ay = clamp(y, World.h * 0.12, World.h * 0.35); sweep = Rng.game.sign() }
    override fun think(dt: Double) {
        arrive(ax + sin(t * 0.3) * World.w * 0.08, ay, speed, 90.0, 80.0, dt)
        separate(70.0, 500.0, dt)
        angle = dampAngle(angle, playerAngle(), 1.5, dt)
        beamT -= dt * fireMul
        if (beamT <= 0) {
            beamT = rearm(beamT, 6.5, dt)
            sweep = -sweep
            Game.lasers.add(Laser(this, angle = playerAngle() - 0.65 * sweep, angVel = 0.55 * sweep, warmup = 1.1, duration = 2.4, width = 16.0, color = color, rotateDuringWarmup = false))
            Audio.play(Sfx.LASER_CHARGE, x)
        }
        fireT -= dt * fireMul
        if (fireT <= 0) {
            fireT = rearm(fireT, 1.7, dt)
            HARBINGER_SHOT.color = color
            Patterns.spread(x, y, playerAngle(), if (elite) 7 else 5, 0.7, 190.0, HARBINGER_SHOT)
            muzzle()
        }
    }
}

/** Pools hostiles per archetype so waves don't allocate. */
object EnemyPool {
    private val pools = HashMap<String, ArrayDeque<Enemy>>()
    fun obtain(type: String): Enemy {
        val q = pools[type]
        q?.removeLastOrNull()?.let { return it }
        return when (type) {
            "mote" -> MoteDrone(); "gyre" -> GyreTurret(); "dart" -> DartLancer(); "weaver" -> WeaverSkiff()
            "mitosis" -> MitosisCell(); "seer" -> SeerEye(); "sower" -> SowerMiner(); "aegis" -> AegisWarden()
            "prism" -> PrismShard(); "comet" -> CometStreak(); "pulsar" -> PulsarStar(); "hive" -> HiveCarrier()
            "vortex" -> VortexCoil(); "phantom" -> PhantomWisp(); "carom" -> CaromSkiff(); "harbinger" -> HarbingerHeavy()
            else -> MoteDrone()
        }
    }
    fun recycle(e: Enemy) {
        if (e.isBoss) return
        pools.getOrPut(e.type) { ArrayDeque() }.addLast(e)
    }
}
