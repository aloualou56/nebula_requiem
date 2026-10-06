package io.github.aloualou56.nebularequiem.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * §12 PROJECTILES & BULLET PATTERNS. Enemy bullets store a polar velocity (angle, speed)
 * integrated into an "anchor" point; behaviours (acceleration, curving, homing, sine weaving,
 * orbiting, chaotic attractors, splitting, wall bounces, stop-and-retarget) compose on top.
 */

enum class Shape(val rotates: Boolean) { ORB(false), RICE(true), BOLT(true), LANCE(true), STAR(false), DIAMOND(true), RING(false), MINE(false), BIG(false) }

/** Shared option set for a family of bullets (patterns reuse one instance per call site). */
class BulletOpts(
    var r: Double = 6.0,
    var color: Int = Pal.BULLET_COLORS[0],
    var shape: Shape = Shape.ORB,
    var accel: Double = 0.0,
    var minSpeed: Double = 0.0,
    var maxSpeed: Double = 4000.0,
    var curve: Double = 0.0,
    var delay: Double = 0.0,
    var life: Double = 14.0,
    var waveAmp: Double = 0.0, var waveFreq: Double = 0.0, var wavePhase: Double = 0.0,
    var homing: Double = 0.0, var homingTime: Double = 1.2,
    var splitAt: Double = 0.0, var splitN: Int = 0, var splitSpeed: Double = 0.0, var splitShape: Shape = Shape.ORB, var splitColor: Int = 0,
    var bounces: Int = 0,
    var retargetAt: Double = -1.0, var retargetSpeed: Double = 0.0, var retargetAccel: Double = 500.0,
    var spin: Double = 0.0
) {
    fun copy(): BulletOpts = BulletOpts(r, color, shape, accel, minSpeed, maxSpeed, curve, delay, life, waveAmp, waveFreq, wavePhase,
        homing, homingTime, splitAt, splitN, splitSpeed, splitShape, splitColor, bounces, retargetAt, retargetSpeed, retargetAccel, spin)
}

class EnemyBullet {
    @JvmField var x = 0.0; @JvmField var y = 0.0; @JvmField var ox = 0.0; @JvmField var oy = 0.0; @JvmField var px = 0.0; @JvmField var py = 0.0
    @JvmField var angle = 0.0; @JvmField var speed = 0.0; @JvmField var accel = 0.0; @JvmField var minSpeed = 0.0; @JvmField var maxSpeed = 4000.0; @JvmField var curve = 0.0
    @JvmField var r = 6.0; @JvmField var color = Pal.BULLET_COLORS[0]; @JvmField var shape = Shape.ORB
    @JvmField var age = 0.0; @JvmField var life = 14.0; @JvmField var delay = 0.0; @JvmField var grazed = false; @JvmField var dead = false
    @JvmField var waveAmp = 0.0; @JvmField var waveFreq = 0.0; @JvmField var wavePhase = 0.0
    @JvmField var homing = 0.0; @JvmField var homingTime = 0.0
    @JvmField var splitAt = 0.0; @JvmField var splitN = 0; @JvmField var splitSpeed = 0.0; @JvmField var splitShape = Shape.ORB; @JvmField var splitColor = 0
    @JvmField var bounces = 0
    @JvmField var retargetAt = 0.0; @JvmField var retargetSpeed = 0.0; @JvmField var retargetAccel = 0.0; @JvmField var retargeted = true
    @JvmField var orbitT = 0.0; @JvmField var orbitR = 0.0; @JvmField var orbitTargetR = 0.0; @JvmField var orbitW = 0.0; @JvmField var orbitA = 0.0
    @JvmField var orbitOwner: Enemy? = null; @JvmField var orbitRelease = 0.0; @JvmField var releaseSpeed = 0.0
    @JvmField var lorenz = false; @JvmField var lx = 0.0; @JvmField var ly = 0.0; @JvmField var lz = 0.0; @JvmField var lscale = 1.0
    @JvmField var lspeed = 0.35; @JvmField var lowner: Enemy? = null; @JvmField var lrelease = 0.0; @JvmField var lrot = 0.0
    @JvmField var spin = 0.0; @JvmField var rot = 0.0

    fun reset() {
        x = 0.0; y = 0.0; ox = 0.0; oy = 0.0; px = 0.0; py = 0.0
        angle = 0.0; speed = 0.0; accel = 0.0; minSpeed = 0.0; maxSpeed = 4000.0; curve = 0.0
        r = 6.0; color = Pal.BULLET_COLORS[0]; shape = Shape.ORB
        age = 0.0; life = 14.0; delay = 0.0; grazed = false; dead = false
        waveAmp = 0.0; waveFreq = 0.0; wavePhase = 0.0
        homing = 0.0; homingTime = 0.0
        splitAt = 0.0; splitN = 0; splitSpeed = 0.0; splitShape = Shape.ORB; splitColor = 0
        bounces = 0
        retargetAt = 0.0; retargetSpeed = 0.0; retargetAccel = 0.0; retargeted = true
        orbitT = 0.0; orbitR = 0.0; orbitTargetR = 0.0; orbitW = 0.0; orbitA = 0.0; orbitOwner = null; orbitRelease = 0.0; releaseSpeed = 0.0
        lorenz = false; lx = 0.0; ly = 0.0; lz = 0.0; lscale = 1.0; lspeed = 0.35; lowner = null; lrelease = 0.0; lrot = 0.0
        spin = 0.0; rot = 0.0
    }
}

class BulletPool(private val max: Int) {
    val list = ArrayList<EnemyBullet>(max)
    private val free = ArrayDeque<EnemyBullet>(max)
    private val splitOpts = BulletOpts()
    val count: Int get() = list.size
    /** Bullets fired since the pool was made (tests compare how hard patterns press). */
    var fired = 0L
        private set

    private fun spawn(): EnemyBullet? {
        if (list.size >= max) return null
        val b = free.removeLastOrNull() ?: EnemyBullet()
        b.reset()
        list.add(b)
        return b
    }

    /** Fire one enemy bullet with the shared option set `o`. */
    fun fire(x: Double, y: Double, angle: Double, speed: Double, o: BulletOpts): EnemyBullet? {
        val b = spawn() ?: return null
        fired++
        val mul = Game.bulletSpeedMul
        b.x = x; b.ox = x; b.px = x; b.y = y; b.oy = y; b.py = y
        b.angle = angle; b.speed = speed * mul
        b.r = o.r; b.color = o.color; b.shape = o.shape
        b.accel = o.accel * mul; b.minSpeed = o.minSpeed * mul; b.maxSpeed = o.maxSpeed * mul
        b.curve = o.curve; b.delay = o.delay; b.life = o.life
        if (o.waveAmp != 0.0) { b.waveAmp = o.waveAmp; b.waveFreq = o.waveFreq; b.wavePhase = o.wavePhase }
        if (o.homing != 0.0) { b.homing = o.homing; b.homingTime = o.homingTime }
        if (o.splitN > 0) { b.splitAt = o.splitAt; b.splitN = o.splitN; b.splitSpeed = o.splitSpeed; b.splitShape = o.splitShape; b.splitColor = if (o.splitColor != 0) o.splitColor else b.color }
        if (o.bounces > 0) b.bounces = o.bounces
        if (o.retargetAt >= 0) { b.retargeted = false; b.retargetAt = o.retargetAt; b.retargetSpeed = o.retargetSpeed * mul; b.retargetAccel = o.retargetAccel * mul }
        if (o.spin != 0.0) b.spin = o.spin
        return b
    }

    fun update(dt: Double) {
        val p = Game.player
        val px = p?.x ?: (World.w / 2); val py = p?.y ?: (World.h / 2); val palive = p != null && p.alive
        var i = list.size - 1
        while (i >= 0) {
            if (i >= list.size) { i = list.size - 1; continue }
            val b = list[i]
            step(b, dt, px, py, palive, World.w, World.h)
            if (b.dead) { list[i] = list[list.size - 1]; list.removeAt(list.size - 1); free.addLast(b) }
            i--
        }
    }

    private fun step(b: EnemyBullet, dt: Double, px: Double, py: Double, palive: Boolean, W: Double, H: Double) {
        b.px = b.x; b.py = b.y
        if (b.delay > 0) { b.delay -= dt; return }
        b.age += dt
        if (b.age > b.life) { b.dead = true; return }

        // Chaotic attractor (Lorenz σ=10, ρ=28, β=8/3), RK4 in its own time units; (x, z) projected around the owner.
        if (b.lorenz) {
            val o = b.lowner
            if (o == null || o.dead || b.age >= b.lrelease) {
                b.lorenz = false; b.speed = 150 * Game.bulletSpeedMul; b.ox = b.x; b.oy = b.y
            } else {
                val h = dt * b.lspeed; val B = 8.0 / 3; val x = b.lx; val y = b.ly; val z = b.lz
                val k1x = 10 * (y - x); val k1y = x * (28 - z) - y; val k1z = x * y - B * z
                var ax = x + 0.5 * h * k1x; var ay = y + 0.5 * h * k1y; var az = z + 0.5 * h * k1z
                val k2x = 10 * (ay - ax); val k2y = ax * (28 - az) - ay; val k2z = ax * ay - B * az
                ax = x + 0.5 * h * k2x; ay = y + 0.5 * h * k2y; az = z + 0.5 * h * k2z
                val k3x = 10 * (ay - ax); val k3y = ax * (28 - az) - ay; val k3z = ax * ay - B * az
                ax = x + h * k3x; ay = y + h * k3y; az = z + h * k3z
                val k4x = 10 * (ay - ax); val k4y = ax * (28 - az) - ay; val k4z = ax * ay - B * az
                b.lx = x + (h / 6) * (k1x + 2 * k2x + 2 * k3x + k4x)
                b.ly = y + (h / 6) * (k1y + 2 * k2y + 2 * k3y + k4y)
                b.lz = z + (h / 6) * (k1z + 2 * k2z + 2 * k3z + k4z)
                val lx = b.lx * b.lscale; val lz = (b.lz - 24) * b.lscale; val c = cos(b.lrot); val s = sin(b.lrot)
                val nx = o.x + lx * c - lz * s; val ny = o.y + lx * s + lz * c
                b.angle = atan2(ny - b.y, nx - b.x)
                b.x = nx; b.ox = nx; b.y = ny; b.oy = ny
                return
            }
        }

        // Orbit an owner, then release along orbitA + orbitRelease.
        if (b.orbitT > 0) {
            val o = b.orbitOwner
            if (o == null || o.dead || b.age >= b.orbitT) {
                b.orbitT = 0.0; b.angle = b.orbitA + b.orbitRelease; b.speed = b.releaseSpeed; b.ox = b.x; b.oy = b.y
            } else {
                b.orbitA += b.orbitW * dt
                b.orbitR = damp(b.orbitR, b.orbitTargetR, 4.0, dt)
                b.x = o.x + cos(b.orbitA) * b.orbitR; b.ox = b.x
                b.y = o.y + sin(b.orbitA) * b.orbitR; b.oy = b.y
                b.angle = b.orbitA + if (b.orbitW > 0) HALF_PI else -HALF_PI
                return
            }
        }

        if (b.curve != 0.0) b.angle += b.curve * dt
        if (b.accel != 0.0) b.speed = clamp(b.speed + b.accel * dt, b.minSpeed, b.maxSpeed)
        if (b.homing != 0.0 && b.age < b.homingTime && palive) {
            val want = atan2(py - b.y, px - b.x); val turn = b.homing * dt
            b.angle += clamp(angleDiff(b.angle, want), -turn, turn)
        }
        // Decelerate to rest, then lock onto the player ("heat death").
        if (!b.retargeted && b.age >= b.retargetAt) {
            b.retargeted = true
            if (palive) b.angle = atan2(py - b.y, px - b.x)
            b.accel = b.retargetAccel; b.minSpeed = 0.0; b.maxSpeed = b.retargetSpeed; b.speed = max(b.speed, 20.0)
            if (Rng.vis.chance(0.25 * Fx.mul)) Fx.flash(b.x, b.y, 12.0, 0.15, b.color)
        }

        // Integrate the anchor; the sine weave is applied perpendicular to the heading.
        val c = cos(b.angle); val s = sin(b.angle)
        b.ox += c * b.speed * dt; b.oy += s * b.speed * dt
        if (b.waveAmp != 0.0) {
            val off = b.waveAmp * sin(b.age * b.waveFreq + b.wavePhase)
            b.x = b.ox - s * off; b.y = b.oy + c * off
        } else { b.x = b.ox; b.y = b.oy }
        if (b.spin != 0.0) b.rot += b.spin * dt

        // Split into a ring.
        if (b.splitAt != 0.0 && b.age >= b.splitAt) {
            val n = b.splitN
            val o = splitOpts
            o.r = b.r * 0.85; o.color = b.splitColor; o.shape = b.splitShape
            for (k in 0 until n) fire(b.x, b.y, b.angle + (k.toDouble() / n) * TAU, b.splitSpeed / Game.bulletSpeedMul, o)
            Fx.flash(b.x, b.y, 22.0, 0.15, b.color)
            b.dead = true
            return
        }

        // Walls: reflect or cull.
        if (b.bounces > 0) {
            if ((b.x < 0 && c < 0) || (b.x > W && c > 0)) { b.angle = Math.PI - b.angle; b.bounces--; b.x = clamp(b.x, 0.0, W); b.ox = b.x; b.oy = b.y }
            else if ((b.y < 0 && s < 0) || (b.y > H && s > 0)) { b.angle = -b.angle; b.bounces--; b.y = clamp(b.y, 0.0, H); b.oy = b.y; b.ox = b.x }
        }
        val m = 48 + b.r
        if (b.x < -m || b.x > W + m || b.y < -m || b.y > H + m) b.dead = true
    }

    /** Erase bullets (optionally only within radius of a point), converting some into score gems. */
    fun cancel(cx: Double = 0.0, cy: Double = 0.0, radius: Double = Double.POSITIVE_INFINITY, toGems: Boolean = true): Int {
        val R2 = radius * radius
        var n = 0
        for (b in list) {
            if (b.dead) continue
            if (radius != Double.POSITIVE_INFINITY && dist2(b.x, b.y, cx, cy) > R2) continue
            b.dead = true; n++
            if (toGems && n % 2 == 0) Game.pickups.spawn(PickupKind.GEM, b.x, b.y, 1.0, 0.3)
            if (n % 3 == 0) Fx.ember(b.x, b.y, Rng.vis.range(-40.0, 40.0), Rng.vis.range(-40.0, 40.0), 0.35, b.color, 6.0, 2.0)
        }
        removeDead()
        return n
    }

    fun removeDead() {
        var i = list.size - 1
        while (i >= 0) {
            if (list[i].dead) { free.addLast(list[i]); list[i] = list[list.size - 1]; list.removeAt(list.size - 1) }
            i--
        }
    }

    fun clear() { for (b in list) free.addLast(b); list.clear() }
}

enum class ShotKind { BOLT, MISSILE, LANCE }

/** Player projectile: bolts, homing missiles, drone darts and the piercing lance. */
class PlayerShot {
    val hits = IntArray(12)
    @JvmField var x = 0.0; @JvmField var y = 0.0; @JvmField var px = 0.0; @JvmField var py = 0.0; @JvmField var vx = 0.0; @JvmField var vy = 0.0
    @JvmField var r = 5.0; @JvmField var dmg = 10.0; @JvmField var pierce = 0; @JvmField var bounces = 0; @JvmField var crit = false; @JvmField var explosive = 0
    @JvmField var life = 1.6; @JvmField var age = 0.0; @JvmField var kind = ShotKind.BOLT; @JvmField var color = Pal.ION
    @JvmField var hitCount = 0; @JvmField var dead = false; @JvmField var target: Enemy? = null; @JvmField var retarget = 0.0
    @JvmField var maxSpeed = 0.0; @JvmField var steer = 0.0

    fun reset() {
        x = 0.0; y = 0.0; px = 0.0; py = 0.0; vx = 0.0; vy = 0.0
        r = 5.0; dmg = 10.0; pierce = 0; bounces = 0; crit = false; explosive = 0
        life = 1.6; age = 0.0; kind = ShotKind.BOLT; color = Pal.ION
        hitCount = 0; dead = false; target = null; retarget = 0.0; maxSpeed = 0.0; steer = 0.0
    }
    fun hasHit(id: Int): Boolean { for (i in 0 until min(hitCount, hits.size)) if (hits[i] == id) return true; return false }
    fun markHit(id: Int) { if (hitCount < hits.size) hits[hitCount++] = id else hits[(hitCount++) % hits.size] = id }
}

class ShotPool(private val max: Int) {
    val list = ArrayList<PlayerShot>(max)
    private val free = ArrayDeque<PlayerShot>(max)

    fun spawn(kind: ShotKind, x: Double, y: Double, angle: Double, speed: Double, dmg: Double, color: Int): PlayerShot? {
        if (list.size >= max) return null
        val s = free.removeLastOrNull() ?: PlayerShot()
        s.reset()
        s.kind = kind; s.x = x; s.px = x; s.y = y; s.py = y
        s.vx = cos(angle) * speed; s.vy = sin(angle) * speed
        s.dmg = dmg; s.color = color
        list.add(s)
        return s
    }

    fun update(dt: Double) {
        val W = World.w; val H = World.h
        var i = list.size - 1
        while (i >= 0) {
            val s = list[i]
            s.px = s.x; s.py = s.y
            s.age += dt
            if (s.kind == ShotKind.MISSILE) steerMissile(s, dt)
            s.x += s.vx * dt; s.y += s.vy * dt
            if (s.bounces > 0) {
                if ((s.x < 0 && s.vx < 0) || (s.x > W && s.vx > 0)) { s.vx = -s.vx; s.bounces--; s.x = clamp(s.x, 0.0, W); Fx.sparks(s.x, s.y, 3.0, s.color, 220.0, 1.4, atan2(s.vy, s.vx), 0.2, 1.2) }
                if ((s.y < 0 && s.vy < 0) || (s.y > H && s.vy > 0)) { s.vy = -s.vy; s.bounces--; s.y = clamp(s.y, 0.0, H); Fx.sparks(s.x, s.y, 3.0, s.color, 220.0, 1.4, atan2(s.vy, s.vx), 0.2, 1.2) }
            }
            if (s.age > s.life || s.x < -60 || s.x > W + 60 || s.y < -60 || s.y > H + 60) s.dead = true
            if (s.dead) { list[i] = list[list.size - 1]; list.removeAt(list.size - 1); free.addLast(s) }
            i--
        }
    }

    /** Reynolds steering for homing missiles (limited steering force → natural looping arcs). */
    private fun steerMissile(s: PlayerShot, dt: Double) {
        s.retarget -= dt
        val tg = s.target
        if (tg == null || tg.dead || s.retarget <= 0) { s.target = Game.nearestEnemy(s.x, s.y, 900.0); s.retarget = 0.25 }
        s.maxSpeed = min(980.0, s.maxSpeed + 900 * dt)
        val t = s.target
        if (t != null) {
            var dx = t.x - s.x; var dy = t.y - s.y
            val dl0 = sqrt(dx * dx + dy * dy); val dl = if (dl0 == 0.0) 1.0 else dl0
            dx = (dx / dl) * s.maxSpeed - s.vx; dy = (dy / dl) * s.maxSpeed - s.vy
            val sl = sqrt(dx * dx + dy * dy); val F = s.steer * dt
            if (sl > F) { dx *= F / sl; dy *= F / sl }
            s.vx += dx; s.vy += dy
        }
        val v = sqrt(s.vx * s.vx + s.vy * s.vy)
        if (v > s.maxSpeed) { s.vx *= s.maxSpeed / v; s.vy *= s.maxSpeed / v }
        if (rateChance(0.6 * Fx.mul, dt)) Fx.ember(s.x, s.y, -s.vx * 0.1 + Rng.vis.range(-20.0, 20.0), -s.vy * 0.1 + Rng.vis.range(-20.0, 20.0), 0.3, Pal.SOLAR, 4.0, 3.0, true)
    }

    fun clear() { for (s in list) free.addLast(s); list.clear() }
}

/** Enemy beam: a telegraphed capsule. Hit test is point–segment distance against width/2. */
class Laser(
    val owner: Enemy?,
    var x: Double = 0.0, var y: Double = 0.0,
    var angle: Double = 0.0, val angVel: Double = 0.0,
    val length: Double = 2600.0, val width: Double = 22.0,
    val warmup: Double = 1.0, val duration: Double = 2.5,
    val color: Int = Pal.PLASMA,
    val offsetAngle: Double = 0.0, val offsetDist: Double = 0.0,
    val follow: Boolean = false,
    val rotateDuringWarmup: Boolean = true
) {
    val fade = 0.25
    var age = 0.0
    var dead = false
    var fired = false
    val live: Boolean get() = age >= warmup && age < warmup + duration

    init { if (owner != null) { x = owner.x; y = owner.y } }

    fun update(dt0: Double) {
        // a guardian's lasers keep time with its attacks (see Boss.tempo)
        val dt = dt0 * ((owner as? Boss)?.tempo ?: 1.0)
        age += dt
        if (owner != null && !owner.dead) {
            val a = (if (follow) owner.angle else 0.0) + offsetAngle
            x = owner.x + cos(a) * offsetDist
            y = owner.y + sin(a) * offsetDist
        } else if (owner != null && owner.dead) { dead = true; return }
        if (rotateDuringWarmup || age >= warmup) angle += angVel * dt
        if (!fired && age >= warmup) { fired = true; Audio.play(Sfx.LASER_FIRE, x); Cam.addTrauma(0.15) }
        if (age > warmup + duration + fade) dead = true
    }

    fun endX() = x + cos(angle) * length
    fun endY() = y + sin(angle) * length
    fun hits(px: Double, py: Double, pr: Double): Boolean {
        if (!live) return false
        val R = width * 0.42 + pr
        return Collide.pointSeg2(px, py, x, y, endX(), endY()) <= R * R
    }
}

/** Pattern library — pure geometry over a firing origin. */
object Patterns {
    fun aim(x: Double, y: Double): Double { val p = Game.player; return if (p != null) atan2(p.y - y, p.x - x) else HALF_PI }

    /** Uniform ring: θᵢ = θ₀ + 2πi/n. */
    fun ring(x: Double, y: Double, n: Int, speed: Double, rot: Double, o: BulletOpts) {
        for (i in 0 until n) Game.ebullets.fire(x, y, rot + (i.toDouble() / n) * TAU, speed, o)
    }

    /** Fan of n bullets spanning `arc` radians centred on θ. */
    fun spread(x: Double, y: Double, angle: Double, n: Int, arc: Double, speed: Double, o: BulletOpts) {
        if (n == 1) { Game.ebullets.fire(x, y, angle, speed, o); return }
        for (i in 0 until n) Game.ebullets.fire(x, y, angle - arc / 2 + (arc * i) / (n - 1), speed, o)
    }

    /** Rose (rhodonea) ring: speed ∝ a + (1 − a)|cos kθ| — the swarm traces the polar curve. */
    fun rose(x: Double, y: Double, n: Int, speed: Double, k: Double, rot: Double, a: Double, o: BulletOpts) {
        for (i in 0 until n) {
            val th = (i.toDouble() / n) * TAU
            Game.ebullets.fire(x, y, th + rot, speed * (a + (1 - a) * kotlin.math.abs(cos(k * th))), o)
        }
    }

    /** Regular polygon outline expanding as a homothety (exact shape kept). */
    fun polygon(x: Double, y: Double, sides: Int, perSide: Int, speed: Double, rot: Double, o: BulletOpts) {
        for (s in 0 until sides) {
            val a0 = rot + (s.toDouble() / sides) * TAU; val a1 = rot + ((s + 1).toDouble() / sides) * TAU
            val x0 = cos(a0); val y0 = sin(a0); val x1 = cos(a1); val y1 = sin(a1)
            for (j in 0 until perSide) {
                val t = j.toDouble() / perSide; val px = lerp(x0, x1, t); val py = lerp(y0, y1, t)
                Game.ebullets.fire(x, y, atan2(py, px), speed * sqrt(px * px + py * py), o)
            }
        }
    }

    /** Phyllotaxis (Vogel's sunflower): θᵢ = i·137.508°. */
    fun phyllo(x: Double, y: Double, i0: Int, n: Int, speed: Double, rot: Double, o: BulletOpts) {
        for (i in 0 until n) Game.ebullets.fire(x, y, (i0 + i) * GOLDEN_ANGLE + rot, speed, o)
    }

    private val cantorA = DoubleArray(64); private val cantorB = DoubleArray(64)
    /** Cantor-set wall: [0,1] minus middle thirds to `depth`, shifted by `phase`; the removed thirds are the gaps. */
    fun cantor(depth: Int, phase: Double, speed: Double, vertical: Boolean, spacing: Double, o: BulletOpts) {
        var a = cantorA; var b = cantorB
        var n = 1
        a[0] = 0.0; a[1] = 1.0
        for (d in 0 until depth) {
            var m = 0
            for (i in 0 until n) {
                val lo = a[i * 2]; val hi = a[i * 2 + 1]; val t = (hi - lo) / 3
                b[m * 2] = lo; b[m * 2 + 1] = lo + t; m++
                b[m * 2] = hi - t; b[m * 2 + 1] = hi; m++
            }
            n = m
            val tmp = a; a = b; b = tmp
        }
        val span = if (vertical) World.h else World.w
        for (i in 0 until n) {
            val lo = a[i * 2]; val hi = a[i * 2 + 1]
            val len = (hi - lo) * span; val cnt = max(1, kotlin.math.floor(len / spacing).toInt())
            for (j in 0..cnt) {
                val u = mod(lo + ((hi - lo) * j) / cnt + phase, 1.0) * span
                if (vertical) Game.ebullets.fire(-10.0, u, 0.0, speed, o)
                else Game.ebullets.fire(u, -10.0, HALF_PI, speed, o)
            }
        }
    }

    /** Helix arm: θ(t) = ω·t·(1 + β·sin(νt)) + 2πk/arms — a "breathing" double helix. */
    fun helix(x: Double, y: Double, t: Double, arms: Int, omega: Double, beta: Double, nu: Double, speed: Double, o: BulletOpts) {
        val base = omega * t * (1 + beta * sin(nu * t))
        for (k in 0 until arms) Game.ebullets.fire(x, y, base + (k.toDouble() / arms) * TAU, speed, o)
    }
}
