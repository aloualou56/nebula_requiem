package io.github.aloualou56.nebularequiem.core

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Pickups: experience shards, stardust motes, repair kits, nova cells, score gems. */
enum class PickupKind(val color: Int, val life: Double) {
    XP(ColorUtil.hex("#7dfcd2"), 16.0),
    DUST(ColorUtil.hex("#ffc145"), 20.0),
    HEAL(ColorUtil.hex("#6bffb5"), 20.0),
    BOMB(ColorUtil.hex("#ff3ad9"), 20.0),
    GEM(ColorUtil.hex("#fff3c4"), 4.0)
}

class Pickup {
    @JvmField var kind = PickupKind.XP
    @JvmField var x = 0.0; @JvmField var y = 0.0; @JvmField var px = 0.0; @JvmField var py = 0.0
    @JvmField var vx = 0.0; @JvmField var vy = 0.0
    @JvmField var value = 1.0; @JvmField var age = 0.0; @JvmField var life = 1.0
    @JvmField var mag = false; @JvmField var magT = 0.0; @JvmField var dead = true; @JvmField var rot = 0.0
}

class PickupSystem {
    val list = ArrayList<Pickup>(Cfg.MAX_PICKUPS)
    private val free = ArrayDeque<Pickup>(Cfg.MAX_PICKUPS)

    fun spawn(kind: PickupKind, x: Double, y: Double, value: Double = 1.0, burst: Double = 1.0): Pickup? {
        if (list.size >= Cfg.MAX_PICKUPS) {
            if (kind != PickupKind.XP && kind != PickupKind.GEM) {
                // never drop rare loot: steal a gem slot
                val idx = list.indexOfFirst { it.kind == PickupKind.GEM && !it.dead }
                if (idx < 0) return null
                list[idx].dead = true
                removeDead()
            } else if (kind == PickupKind.XP) {
                // merge into the closest existing shard instead of allocating
                var best: Pickup? = null; var bd = Double.POSITIVE_INFINITY
                for (p in list) if (p.kind == PickupKind.XP) { val d = dist2(p.x, p.y, x, y); if (d < bd) { bd = d; best = p } }
                if (best != null) { best.value += value; return best }
                return null
            } else return null
        }
        val p = free.removeLastOrNull() ?: Pickup()
        val a = Rng.game.angle(); val s = Rng.game.range(60.0, 220.0) * burst
        p.kind = kind; p.x = x; p.y = y; p.px = x; p.py = y; p.vx = cos(a) * s; p.vy = sin(a) * s
        p.value = value; p.age = 0.0; p.life = kind.life; p.mag = false; p.magT = 0.0; p.dead = false; p.rot = Rng.game.angle()
        list.add(p)
        return p
    }

    /** Break an XP total into 25 / 5 / 1 shards; the fractional remainder becomes a probability. */
    fun dropXP(x: Double, y: Double, total: Double) {
        var left = total
        while (left >= 25) { spawn(PickupKind.XP, x, y, 25.0); left -= 25 }
        while (left >= 5) { spawn(PickupKind.XP, x, y, 5.0); left -= 5 }
        while (left >= 1) { spawn(PickupKind.XP, x, y, 1.0); left -= 1 }
        if (left > 0 && Rng.game.chance(left)) spawn(PickupKind.XP, x, y, 1.0)
    }

    fun magnetAll() { for (p in list) p.mag = true }

    fun update(dt: Double) {
        val pl = Game.player; val alive = pl != null && pl.alive
        var i = list.size - 1
        while (i >= 0) {
            val p = list[i]
            p.px = p.x; p.py = p.y
            p.age += dt; p.rot += dt * 3
            if (p.mag && alive) {
                // homing with a speed that ramps up the longer the shard has been magnetised
                p.magT += dt
                val dx = pl!!.x - p.x; val dy = pl.y - p.y; val d0 = sqrt(dx * dx + dy * dy); val d = if (d0 == 0.0) 1.0 else d0
                val vmax = 300 + p.magT * 1800
                p.vx = damp(p.vx, (dx / d) * vmax, 12.0, dt); p.vy = damp(p.vy, (dy / d) * vmax, 12.0, dt)
            } else { val k = exp(-3.2 * dt); p.vx *= k; p.vy *= k }
            p.x += p.vx * dt; p.y += p.vy * dt
            if (!p.mag) { p.x = clamp(p.x, 8.0, World.w - 8); p.y = clamp(p.y, 8.0, World.h - 8) }
            if (p.age > p.life && !p.mag) p.dead = true
            if (p.dead) { list[i] = list[list.size - 1]; list.removeAt(list.size - 1); free.addLast(p) }
            i--
        }
    }

    /** Collision-matrix entry PLAYER × PICKUP: the magnet radius attracts, the hull collects. */
    fun collect() {
        val pl = Game.player ?: return
        if (!pl.alive) return
        val M2 = pl.stats.magnet * pl.stats.magnet
        for (p in list) {
            if (p.dead) continue
            val d2 = dist2(p.x, p.y, pl.x, pl.y)
            if (!p.mag && (d2 < M2 || (p.kind == PickupKind.GEM && p.age > 0.35))) p.mag = true
            if (d2 < 22.0 * 22.0) { p.dead = true; Game.onPickup(p) }
        }
    }

    private fun removeDead() {
        var i = list.size - 1
        while (i >= 0) { if (list[i].dead) { free.addLast(list[i]); list[i] = list[list.size - 1]; list.removeAt(list.size - 1) }; i-- }
    }

    fun clear() { for (p in list) free.addLast(p); list.clear() }
}
