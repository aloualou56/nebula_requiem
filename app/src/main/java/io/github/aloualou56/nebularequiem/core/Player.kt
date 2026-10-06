package io.github.aloualou56.nebularequiem.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A tiny allocation-free set of ints (enemy ids hit by one dash / one nova). */
class IntSet(cap: Int = 64) {
    private var a = IntArray(cap)
    var size = 0
        private set
    fun clear() { size = 0 }
    fun has(v: Int): Boolean { for (i in 0 until size) if (a[i] == v) return true; return false }
    fun add(v: Int) { if (has(v)) return; if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
}

class Orbital { var x = 0.0; var y = 0.0; var px = 0.0; var py = 0.0; var fireT = 0.0; var hitT = 0.0 }

/** Live stats derived from hull × hangar refits × run grafts. */
class PlayerStats {
    var maxHp = 5; var speed = 345.0; var damage = 10.0; var fireRate = 9.0; var shotSpeed = 1250.0
    var extraShots = 0; var pierce = 0; var bounces = 0; var critChance = 0.05; var critMult = 2.0
    var magnet = 120.0; var fluxGain = 1.0; var dashCd = 1.4; var dashDamage = 0; var missiles = 0
    var orbitals = 0; var chain = 0; var explosive = 0; var corona = 0; var rear = 0
    var shieldMax = 0; var shieldTime = 15.0; var chrono = 0; var echo = 0; var supernova = 0
    var xpMul = 1.0; var dustMul = 1.0; var bombDmg = 1.0; var bombs = 2
}

enum class Act { NONE, PRESS, RETRY }

class Player(val hullId: String) {
    val hull: HullDef = HULLS.getValue(hullId)
    val color = hull.color
    var x = World.w / 2; var y = World.h * 0.72
    /** Start-of-step position (continuous hit test and render interpolation). */
    var px = x; var py = y
    var vx = 0.0; var vy = 0.0
    var angle = -HALF_PI; var prevAngle = angle; var bank = 0.0; var thrust = 0.0
    /** The true hitbox (white core). */
    val r = 4.5
    /** Body collisions with ships. */
    val contactR = 10.0
    val perks = LinkedHashMap<String, Int>()
    val stats = PlayerStats()
    val orbitals = ArrayList<Orbital>()
    var hp: Int; var shield: Int; var shieldRegen = 0.0; var bombs: Int
    var flux = 0.0; var overdrive = 0.0; var iframes = 2.0
    var dashT = 0.0; var dashCd = 0.0; var dashDx = 0.0; var dashDy = 0.0
    private val dashHit = IntSet()
    private var ghostT = 0.0
    var fireT = 0.0; var volley = 0
    var missileT = 1.2
    var orbitA = 0.0
    var chronoCd = 0.0
    var alive = true
    var t = 0.0
    var aimManual = false
    private var manualAim = Double.NaN
    private var lastMoveX = 0.0; private var lastMoveY = 0.0; private var lastMoveT = -1e9
    private var fluxAnnounced = false
    private val mv = Vec2(); private val aimV = Vec2()

    init {
        recompute()
        hp = stats.maxHp
        shield = stats.shieldMax
        bombs = stats.bombs
    }

    fun level(id: String): Int = perks[id] ?: 0

    fun recompute() {
        val h = hull; val save = Save.data; val s = stats
        fun L(id: String) = level(id)
        s.maxHp = h.hp + save.up("hull") + L("nanorepair")
        s.speed = h.speed * (1 + 0.05 * save.up("thrusters")) * (1 + 0.1 * L("afterburner"))
        s.damage = h.damage * (1 + 0.08 * save.up("reactor")) * (1 + 0.15 * L("density")) * (1 + 0.05 * L("velocity"))
        s.fireRate = h.fireRate * (1 + 0.15 * L("overclock"))
        s.shotSpeed = h.shotSpeed * (1 + 0.2 * L("velocity"))
        s.extraShots = h.spread + L("prism")
        s.pierce = h.pierce + L("phaseRounds")
        s.bounces = L("ricochet")
        s.critChance = 0.05 + 0.1 * L("crit")
        s.critMult = 2 + 0.25 * L("crit")
        s.magnet = 120 * (1 + 0.15 * save.up("magnet")) * (1 + 0.45 * L("magnet"))
        s.fluxGain = (1 + 0.15 * save.up("flux")) * (1 + 0.35 * L("siphon"))
        s.dashCd = h.dashCd * (1 - 0.08 * save.up("phase")) * (1 - 0.15 * L("phaseEdge"))
        s.dashDamage = L("phaseEdge")
        s.missiles = L("seeker")
        s.orbitals = L("orbital")
        s.chain = L("arc")
        s.explosive = L("singularity")
        s.corona = L("corona")
        s.rear = L("rear")
        s.shieldMax = if (L("aegis") > 0) (if (L("aegis") >= 3) 2 else 1) else 0
        s.shieldTime = max(6.0, 15.0 - 3 * L("aegis"))
        s.chrono = L("chrono")
        s.echo = L("echo")
        s.supernova = L("supernova")
        s.xpMul = 1 + 0.1 * save.up("insight")
        s.dustMul = (1 + 0.12 * save.up("salvage")) * (1 + 0.5 * L("midas"))
        s.bombDmg = 1 + 0.5 * L("novaCache")
        s.bombs = h.bombs + save.up("nova")
        while (orbitals.size < s.orbitals) orbitals.add(Orbital().also { it.x = x; it.y = y; it.px = x; it.py = y; it.fireT = Rng.game.range(0.0, 0.5) })
    }

    fun update(dt: Double, act: Array<Act>) {
        t += dt
        val s = stats
        prevAngle = angle
        iframes = max(0.0, iframes - dt)
        dashCd = max(0.0, dashCd - dt)
        chronoCd = max(0.0, chronoCd - dt)
        if (overdrive > 0) { overdrive = max(0.0, overdrive - dt); if (overdrive == 0.0) Game.ui.toast("Overdrive spent", Tone.ION) }

        px = x; py = y
        // Movement: exponential velocity relaxation toward the input target (λ = 14/s steering, 8/s coasting).
        val mv = Input.moveVector(mv)
        val mvm = sqrt(mv.x * mv.x + mv.y * mv.y)
        if (mvm > 0.04) { lastMoveX = mv.x / mvm; lastMoveY = mv.y / mvm; lastMoveT = Game.time }
        if (dashT > 0) {
            dashT -= dt
            vx = dashDx * 1300; vy = dashDy * 1300
            ghostT -= dt
            if (ghostT <= 0) { ghostT = rearm(ghostT, 0.025, dt); Fx.afterimage(this) }
            if (s.dashDamage > 0) dashStrike()
            if (dashT <= 0) { vx *= 0.3; vy *= 0.3 }
        } else {
            val sp = s.speed * if (overdrive > 0) 1.08 else 1.0
            val tvx = mv.x * sp; val tvy = mv.y * sp; val k = exp(-(if (mv.x != 0.0 || mv.y != 0.0) 14.0 else 8.0) * dt)
            vx = tvx + (vx - tvx) * k; vy = tvy + (vy - tvy) * k
        }
        x += vx * dt; y += vy * dt
        val pad = 16.0
        if (x < pad) { x = pad; vx = 0.0 } else if (x > World.w - pad) { x = World.w - pad; vx = 0.0 }
        if (y < pad) { y = pad; vy = 0.0 } else if (y > World.h - pad) { y = World.h - pad; vy = 0.0 }

        // Aim
        val target = aimTarget()
        angle = dampAngle(angle, target, 26.0, dt)
        // Bank = lateral velocity relative to heading (the sine of the slip angle).
        val hx = cos(angle); val hy = sin(angle)
        val lateral = (hx * vy - hy * vx) / max(1.0, s.speed)
        bank = damp(bank, clamp(lateral, -1.0, 1.0), 10.0, dt)
        val spd = sqrt(vx * vx + vy * vy)
        thrust = damp(thrust, clamp(spd / s.speed, 0.2, 1.6), 8.0, dt)

        // Actions (edge-triggered, latched per frame)
        if (act[0] != Act.NONE) dash(mv, act[0] == Act.RETRY)
        if (act[1] != Act.NONE) bomb(act[1] == Act.RETRY)
        if (act[2] != Act.NONE) startOverdrive(act[2] == Act.RETRY)

        // Weapons
        val enemiesPresent = Game.enemies.isNotEmpty()
        val st = Input.aimStick
        val wantFire = (Save.data.settings.autofire && enemiesPresent) || Input.mouseDown || Input.held(InputAction.FIRE) || Input.isDown(Input.PAD_FIRE) ||
            (st.id != -1 && hypot(st.dx, st.dy) > 0.25) || (abs(Input.padRx) + abs(Input.padRy) > 0.3)
        val rate = s.fireRate * if (overdrive > 0) 2 else 1
        fireT -= dt
        if (wantFire && dashT <= 0) {
            var guard = 0
            while (fireT <= 0 && guard++ < 4) { fireT += 1 / rate; fireVolley() }
        }
        if (fireT < 0) fireT = 0.0

        if (s.missiles > 0) {
            missileT -= dt
            if (missileT <= 0 && enemiesPresent) { missileT = rearm(missileT, max(0.7, 1.9 - 0.25 * s.missiles), dt); launchMissiles() }
        }
        updateOrbitals(dt)
        if (s.shieldMax > 0 && shield < s.shieldMax) {
            shieldRegen += dt
            if (shieldRegen >= s.shieldTime) { shieldRegen = 0.0; shield++; Fx.ring(x, y, 10.0, 40.0, 0.4, Pal.WHITE, 2.0); Audio.play(Sfx.PICKUP, pitch = 1.5) }
        }

        for (e in hull.engines) {
            val wx = x + e[0] * hx - e[1] * hy; val wy = y + e[0] * hy + e[1] * hx
            Fx.engine(wx, wy, angle, color, thrust, dt)
        }
    }

    /** Aim priority: active mouse → touch aim stick → right stick → auto-target nearest threat. */
    private fun aimTarget(): Double {
        val now = Env.now()
        aimManual = true
        if (!Input.touchMode && Input.lastAimSource == AimSource.MOUSE && now - Input.mouseLastMove < 10000 && Input.mouseInside) {
            val m = World.toWorld(Input.mouseX, Input.mouseY, aimV)
            return atan2(m.y - y, m.x - x)
        }
        val st = Input.aimStick
        if (st.id != -1 && hypot(st.dx, st.dy) > 0.25) {
            // Manual aim with a little assist: snap to a hostile within ±10° (and 1000 units).
            var a = atan2(st.dy, st.dx); var best = 0.1745
            for (e in Game.enemies) {
                if (e.dead || !e.active) continue
                val dx = e.x - x; val dy = e.y - y
                if (dx * dx + dy * dy > 1000.0 * 1000.0) continue
                val ia = interceptAngle(x, y, e.x, e.y, e.vx, e.vy, stats.shotSpeed)
                val diff = abs(angleDiff(a, ia))
                if (diff < best) { best = diff; a = ia }
            }
            manualAim = a
            return a
        }
        if (st.heldAim && !manualAim.isNaN() && now - st.releasedAt < 300) return manualAim
        if (abs(Input.padRx) + abs(Input.padRy) > 0.25) return atan2(Input.padRy, Input.padRx)
        aimManual = false
        val e = Game.nearestEnemy(x, y, 2400.0, true)
        if (e != null) return interceptAngle(x, y, e.x, e.y, e.vx, e.vy, stats.shotSpeed)
        return angle
    }

    private fun mk(kind: ShotKind, lx: Double, ly: Double, ang: Double, speed: Double, dmg: Double, r: Double, color: Int, c: Double, sn: Double) {
        val s = stats
        val sx = x + lx * c - ly * sn; val sy = y + lx * sn + ly * c
        val crit = Rng.game.chance(s.critChance)
        val shot = Game.pshots.spawn(kind, sx, sy, ang, speed, if (crit) dmg * s.critMult else dmg, if (crit) Pal.SOLAR else color) ?: return
        shot.crit = crit; shot.r = r; shot.pierce = s.pierce; shot.bounces = s.bounces; shot.explosive = s.explosive
        shot.life = 1.5
        Fx.muzzle(sx, sy, ang, color)
    }

    private fun fireVolley() {
        val s = stats; val c = cos(angle); val sn = sin(angle)
        val od = overdrive > 0
        val dmgBase = s.damage * if (od) 1.25 else 1.0
        volley++
        val col = if (od) Pal.WHITE else color
        for (g in hull.guns) mk(ShotKind.BOLT, g[0], g[1], angle, s.shotSpeed, dmgBase, 5.0, col, c, sn)
        for (k in 1..s.extraShots) {
            val off = 0.09 + 0.07 * k
            mk(ShotKind.BOLT, 8.0, 0.0, angle - off, s.shotSpeed * 0.95, dmgBase * 0.8, 4.5, col, c, sn)
            mk(ShotKind.BOLT, 8.0, 0.0, angle + off, s.shotSpeed * 0.95, dmgBase * 0.8, 4.5, col, c, sn)
        }
        for (k in 0 until s.rear) {
            val off = (k - (s.rear - 1) / 2.0) * 0.22
            mk(ShotKind.BOLT, -10.0, 0.0, angle + Math.PI + off, s.shotSpeed * 0.85, dmgBase * 0.7, 4.5, col, c, sn)
        }
        if (s.echo > 0 && volley % 6 == 0) {
            val lx = x + c * 18; val ly = y + sn * 18
            val lance = Game.pshots.spawn(ShotKind.LANCE, lx, ly, angle, s.shotSpeed * 1.15, dmgBase * 4.5, Pal.WHITE)
            if (lance != null) { lance.r = 10.0; lance.pierce = 99; lance.life = 1.4 }
            Audio.play(Sfx.LANCE, lx)
            Cam.kick(angle + Math.PI, 40.0)
        }
        Cam.kick(angle + Math.PI, 5.0)
        Audio.play(Sfx.SHOOT, x, if (od) 1.25 else Rng.game.range(0.94, 1.06))
    }

    private fun launchMissiles() {
        val n = stats.missiles
        for (i in 0 until n) {
            val side = if (i % 2 == 1) 1 else -1
            val spread = (i / 2 + 1) * 0.35
            val a = angle + side * (HALF_PI + spread * 0.3)
            val m = Game.pshots.spawn(ShotKind.MISSILE, x, y, a, 260.0, stats.damage * 2.2, Pal.SOLAR) ?: continue
            m.r = 6.0; m.life = 3.2; m.steer = 2600.0; m.maxSpeed = 260.0; m.retarget = 0.0; m.explosive = max(1, stats.explosive)
        }
        Audio.play(Sfx.MISSILE, x)
    }

    /** Orbitals sit on a rotating ring: θᵢ(t) = ω·t + 2πi/n, radius 48 + 5n. */
    private fun updateOrbitals(dt: Double) {
        val n = stats.orbitals
        if (n == 0) return
        orbitA += dt * 2.6
        val R = 48.0 + 5 * n
        for (i in 0 until n) {
            val o = orbitals[i]; val a = orbitA + (i.toDouble() / n) * TAU
            o.px = o.x; o.py = o.y
            o.x = x + cos(a) * R; o.y = y + sin(a) * R
            o.fireT -= dt; o.hitT -= dt
            if (o.fireT <= 0) {
                val e = Game.nearestEnemy(o.x, o.y, 600.0)
                if (e != null) {
                    o.fireT = rearm(o.fireT, 0.55, dt)
                    val sh = Game.pshots.spawn(ShotKind.BOLT, o.x, o.y, atan2(e.y - o.y, e.x - o.x), 1100.0, stats.damage * 0.6, Pal.MINT)
                    if (sh != null) { sh.r = 3.5; sh.life = 1.2 }
                } else o.fireT = rearm(o.fireT, 0.2, dt)
            }
        }
    }

    /** Whether an ability press would fire. A dash pressed up to 150 ms before the cooldown ends counts. */
    fun canUse(key: Int): Boolean = when (key) {
        0 -> dashCd <= Game.simAhead(0.15) && dashT <= 0
        1 -> bombs > 0 && Game.nova == null
        2 -> flux >= 1 && overdrive <= 0
        else -> false
    }

    private fun dash(mv: Vec2, retry: Boolean) {
        if (dashCd > 0 || dashT > 0) { if (!retry && dashCd > 0.25) Audio.play(Sfx.DENY); return }
        Game.actionBuf[0] = -1e9
        var dx = mv.x; var dy = mv.y
        val m = sqrt(dx * dx + dy * dy)
        if (m > 0.04) { dx /= m; dy /= m }
        else if (aimManual) { dx = cos(angle); dy = sin(angle) }
        else if (Game.time - lastMoveT < 1.5) { dx = lastMoveX; dy = lastMoveY }
        else {
            // Standing still: dash away from the nearest threat rather than along the auto-aimed facing.
            val e = Game.nearestEnemy(x, y, 2400.0, true)
            val ex = if (e != null) x - e.x else 0.0; val ey = if (e != null) y - e.y else 0.0; val el = hypot(ex, ey)
            if (el > 1) { dx = ex / el; dy = ey / el } else { dx = cos(angle); dy = sin(angle) }
        }
        dashDx = dx; dashDy = dy
        dashT = 0.17; dashCd = stats.dashCd
        iframes = max(iframes, 0.24)
        dashHit.clear()
        ghostT = 0.0
        Audio.play(Sfx.DASH, x)
        Fx.ring(x, y, 6.0, 54.0, 0.3, color, 3.0)
        Lattice.impulse(x, y, 140.0, 420.0)
        Cam.kick(atan2(dy, dx), 70.0)
        PostFx.pulseCA(3.0)
    }

    /** Phase Edge: the dash is a blade — enemies it passes through take damage once per dash. */
    private fun dashStrike() {
        val near = Game.grid.query(x, y, 40.0, Game.near)
        for (i in near.indices) {
            val e = near[i]
            if (e.dead || dashHit.has(e.id)) continue
            if (!Collide.circle(x, y, 24.0, e.x, e.y, e.r)) continue
            dashHit.add(e.id)
            val dmg = stats.damage * 3.2 * stats.dashDamage
            Game.damageEnemy(e, dmg, x, y, true, color)
            Fx.sparks(e.x, e.y, 10.0, color, 500.0, TAU, 0.0, 0.35, 2.0)
        }
    }

    private fun bomb(retry: Boolean) {
        if (bombs <= 0 || Game.nova != null) {
            if (!retry) { Audio.play(Sfx.DENY); Game.ui.toast(if (bombs <= 0) "No nova charges" else "Nova in progress", Tone.CRIMSON) }
            return
        }
        Game.actionBuf[1] = -1e9
        Haptics.play(Haptic.HEAVY)
        bombs--
        Game.startNova(x, y, stats.bombDmg)
        iframes = max(iframes, 2.4)
    }

    private fun startOverdrive(retry: Boolean) {
        if (flux < 1 || overdrive > 0) { if (!retry && flux < 1) Audio.play(Sfx.DENY); return }
        Game.actionBuf[2] = -1e9
        Haptics.play(Haptic.DOUBLE)
        flux = 0.0
        overdrive = 7.0
        Audio.play(Sfx.OVERDRIVE)
        Game.ui.banner("Overdrive", "Fire rate ×2 · enemy time dilated", Tone.ION, 1.4)
        Fx.ring(x, y, 10.0, 260.0, 0.8, Pal.ION, 6.0)
        Lattice.impulse(x, y, 400.0, 900.0)
        PostFx.flash(Pal.ION, 0.25)
        Cam.addTrauma(0.3)
    }

    /** Grazing fuels flux and score. */
    fun graze(b: EnemyBullet) {
        flux = min(1.0, flux + Cfg.FLUX_PER_GRAZE * stats.fluxGain)
        if (flux >= 1 && !fluxAnnounced) { fluxAnnounced = true; Game.ui.toast(if (Input.touchMode) "Overdrive ready · tap FLUX" else "Overdrive ready · Q", Tone.ION) }
        if (flux < 1) fluxAnnounced = false
        Game.run!!.grazes++
        Game.addScore(12.0, Double.NaN, Double.NaN, true)
        Game.comboT = max(Game.comboT, Cfg.COMBO_WINDOW * 0.5)
        Fx.graze(b.x, b.y, atan2(b.y - y, b.x - x))
        Audio.play(Sfx.GRAZE, x)
        if (stats.chrono > 0 && chronoCd <= 0) { chronoCd = 3.2; Game.slowmo(0.4, 0.55); Audio.play(Sfx.CHRONO); PostFx.pulseCA(4.0) }
    }

    /** Returns true if the hit landed (shield or hull). */
    fun hurt(): Boolean {
        if (!alive || iframes > 0 || dashT > 0) return false
        if (shield > 0) {
            shield--; shieldRegen = 0.0
            iframes = 1.1
            Audio.play(Sfx.SHIELD_BREAK)
            Fx.ring(x, y, 20.0, 120.0, 0.5, Pal.WHITE, 4.0)
            Fx.sparks(x, y, 22.0, Pal.WHITE, 420.0, TAU, 0.0, 0.4, 1.6)
            Game.ebullets.cancel(x, y, Cfg.MERCY_RADIUS * 0.8, false)
            Cam.addTrauma(0.35); PostFx.pulseCA(6.0)
            Game.ui.flashHull()
            return true
        }
        hp--
        iframes = 1.7
        Haptics.play(Haptic.HEAVY)
        Game.onPlayerHit(this)
        if (stats.supernova > 0) Game.startNova(x, y, 0.6 * stats.bombDmg, true)
        if (hp <= 0) die()
        return true
    }

    private val deathVerts = DoubleArray(32)
    private fun die() {
        alive = false
        Fx.explosion(x, y, color, 2.6)
        Fx.explosion(x, y, Pal.WHITE, 1.4)
        val c = cos(angle); val s = sin(angle)
        val n = hull.body.size / 2
        for (i in 0 until n) {
            val bx = hull.body[i * 2].toDouble(); val by = hull.body[i * 2 + 1].toDouble()
            deathVerts[i * 2] = x + bx * 1.4 * c - by * 1.4 * s
            deathVerts[i * 2 + 1] = y + bx * 1.4 * s + by * 1.4 * c
        }
        Fx.shatter(deathVerts, n, x, y, vx, vy, color, 1.6)
        Audio.play(Sfx.BIG_EXPLODE)
        Game.onPlayerDeath()
    }

    /** Blink while invulnerable after a hit (not during a dash). */
    fun blink(): Boolean = iframes > 0 && dashT <= 0 && floor(iframes * 18).toInt() % 2 == 0
}
