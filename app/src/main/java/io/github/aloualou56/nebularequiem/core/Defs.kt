package io.github.aloualou56.nebularequiem.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/* ─────────────────────────────── hulls (§13) ─────────────────────────────── */

class HullDef(
    val id: String, val name: String, val tag: String, val cost: Int, val color: Int, val accent: Int, val desc: String,
    val hp: Int, val speed: Double, val fireRate: Double, val damage: Double, val shotSpeed: Double, val spread: Int,
    val pierce: Int, val dashCd: Double, val bombs: Int,
    /** Silhouette, nose → +x, flat [x0,y0,x1,y1,…]. */
    val body: FloatArray,
    /** Panel lines [x0,y0,x1,y1] each. */
    val detail: Array<FloatArray>,
    val engines: Array<DoubleArray>, val guns: Array<DoubleArray>
)

private fun pts(vararg v: Int) = FloatArray(v.size) { v[it].toFloat() }
private fun dpts(vararg p: Pair<Int, Int>) = Array(p.size) { doubleArrayOf(p[it].first.toDouble(), p[it].second.toDouble()) }

val HULLS: Map<String, HullDef> = linkedMapOf(
    "lancer" to HullDef(
        "lancer", "Lancer", "Balanced interceptor", 0, ColorUtil.hex("#3df2ff"), ColorUtil.hex("#c9fbff"),
        "Twin plasma coils and a forgiving hull. The reference frame every other ship is measured against.",
        hp = 5, speed = 345.0, fireRate = 9.0, damage = 10.0, shotSpeed = 1250.0, spread = 0, pierce = 0, dashCd = 1.4, bombs = 2,
        body = pts(24, 0, 8, -6, -2, -16, -12, -17, -8, -6, -15, 0, -8, 6, -12, 17, -2, 16, 8, 6),
        detail = arrayOf(pts(12, 0, -8, 0), pts(0, -9, -9, -13), pts(0, 9, -9, 13)),
        engines = dpts(-12 to -8, -12 to 8), guns = dpts(12 to -7, 12 to 7)
    ),
    "wraith" to HullDef(
        "wraith", "Wraith", "Phase-skiff glass cannon", 650, ColorUtil.hex("#a98bff"), ColorUtil.hex("#e3d8ff"),
        "Rapid needle-fire that pierces one extra target and a short dash cooldown, on a hull that cracks in three hits.",
        hp = 3, speed = 415.0, fireRate = 12.5, damage = 11.0, shotSpeed = 1450.0, spread = 0, pierce = 1, dashCd = 1.0, bombs = 2,
        body = pts(28, 0, 4, -4, -4, -19, -10, -19, -6, -4, -17, -2, -17, 2, -6, 4, -10, 19, -4, 19, 4, 4),
        detail = arrayOf(pts(18, 0, -12, 0), pts(-2, -8, -7, -16), pts(-2, 8, -7, 16)),
        engines = dpts(-16 to 0), guns = dpts(16 to 0)
    ),
    "bastion" to HullDef(
        "bastion", "Bastion", "Siege frigate", 900, ColorUtil.hex("#6bffb5"), ColorUtil.hex("#d6ffe9"),
        "A fanned three-bolt battery and eight hull plates. Slow, deliberate and very hard to kill.",
        hp = 8, speed = 300.0, fireRate = 7.0, damage = 12.0, shotSpeed = 1150.0, spread = 1, pierce = 0, dashCd = 1.8, bombs = 3,
        body = pts(20, 0, 14, -10, 2, -17, -14, -19, -19, -8, -15, 0, -19, 8, -14, 19, 2, 17, 14, 10),
        detail = arrayOf(pts(14, 0, -12, 0), pts(6, -10, -12, -12), pts(6, 10, -12, 12), pts(-4, -16, -4, 16)),
        engines = dpts(-18 to -11, -18 to 0, -18 to 11), guns = dpts(18 to 0)
    )
)
val HULL_ORDER = listOf("lancer", "wraith", "bastion")

/* ─────────────────────────────── enemies (§14) ─────────────────────────────── */

/** A star of [n] points: tips at radius 1, the notches between them at [inner]. */
fun starPoly(n: Int, inner: Double, rot: Double = 0.0): FloatArray {
    val out = FloatArray(n * 4)
    for (i in 0 until n * 2) {
        val a = rot + i * PI / n; val rr = if (i % 2 == 0) 1.0 else inner
        out[i * 2] = (cos(a) * rr).toFloat(); out[i * 2 + 1] = (sin(a) * rr).toFloat()
    }
    return out
}

/** A pinwheel of [n] swept blades. */
fun pinwheelPoly(n: Int): FloatArray {
    val out = FloatArray(n * 6)
    for (i in 0 until n) {
        val a = i * TAU / n
        val pts = doubleArrayOf(a, 0.38, a + 0.55, 1.0, a + TAU / n * 0.7, 0.42)
        for (k in 0 until 3) { out[(i * 3 + k) * 2] = (cos(pts[k * 2]) * pts[k * 2 + 1]).toFloat(); out[(i * 3 + k) * 2 + 1] = (sin(pts[k * 2]) * pts[k * 2 + 1]).toFloat() }
    }
    return out
}

fun regularPoly(n: Int, rot: Double = 0.0): FloatArray {
    val out = FloatArray(n * 2)
    for (i in 0 until n) { out[i * 2] = cos(rot + i.toDouble() / n * TAU).toFloat(); out[i * 2 + 1] = sin(rot + i.toDouble() / n * TAU).toFloat() }
    return out
}

private fun lensPoly(): FloatArray {
    val p = ArrayList<Float>()
    for (i in 0..8) { val t = -1 + 2.0 * i / 8; p.add((t * 1.25).toFloat()); p.add((-0.62 * (1 - t * t)).toFloat()) }
    for (i in 7 downTo 1) { val t = -1 + 2.0 * i / 8; p.add((t * 1.25).toFloat()); p.add((0.62 * (1 - t * t)).toFloat()) }
    return p.toFloatArray()
}

enum class Formation { EDGE, RING, CLUSTER, PINCER, LINE }

class EnemyDef(
    val id: String, val name: String, val hp: Double, val r: Double, val speed: Double, val score: Double, val xp: Double,
    val cost: Int, val color: Int, val minD: Int, val weight: Double, val groupLo: Int, val groupHi: Int,
    val formations: List<Formation>, val poly: FloatArray
)

val ENEMY_DEFS: Map<String, EnemyDef> = linkedMapOf(
    "mote" to EnemyDef("mote", "Mote", 14.0, 12.0, 170.0, 50.0, 1.0, 1, Pal.C_FF3AD9, 1, 10.0, 4, 9,
        listOf(Formation.EDGE, Formation.RING, Formation.CLUSTER, Formation.PINCER), floatArrayOf(1.25f, 0f, -0.85f, -0.8f, -0.4f, 0f, -0.85f, 0.8f)),
    "gyre" to EnemyDef("gyre", "Gyre", 48.0, 19.0, 58.0, 120.0, 3.0, 3, Pal.C_FFB238, 1, 6.0, 1, 3,
        listOf(Formation.EDGE, Formation.LINE, Formation.PINCER), regularPoly(6)),
    "dart" to EnemyDef("dart", "Dart", 22.0, 13.0, 125.0, 80.0, 2.0, 2, Pal.C_FF6A3D, 2, 7.0, 2, 5,
        listOf(Formation.EDGE, Formation.RING, Formation.LINE), floatArrayOf(1.6f, 0f, 0.1f, -0.55f, -1f, 0f, 0.1f, 0.55f)),
    "weaver" to EnemyDef("weaver", "Weaver", 34.0, 15.0, 125.0, 110.0, 3.0, 3, Pal.C_FF4D6D, 3, 6.0, 2, 4,
        listOf(Formation.LINE, Formation.PINCER), floatArrayOf(1.1f, 0f, 0.3f, -0.35f, 0f, -1.1f, -0.3f, -0.35f, -1.1f, 0f, -0.3f, 0.35f, 0f, 1.1f, 0.3f, 0.35f)),
    "mitosis" to EnemyDef("mitosis", "Mitosis", 95.0, 26.0, 52.0, 160.0, 3.0, 4, Pal.C_D36BFF, 4, 4.0, 1, 2,
        listOf(Formation.EDGE, Formation.CLUSTER), regularPoly(5, -HALF_PI)),
    "seer" to EnemyDef("seer", "Seer", 42.0, 16.0, 95.0, 150.0, 4.0, 4, Pal.C_FF8AE9, 5, 4.0, 1, 3,
        listOf(Formation.EDGE, Formation.PINCER), lensPoly()),
    "sower" to EnemyDef("sower", "Sower", 62.0, 18.0, 85.0, 140.0, 4.0, 4, Pal.C_FFE066, 6, 4.0, 1, 2,
        listOf(Formation.LINE, Formation.EDGE), regularPoly(4, PI / 4)),
    "aegis" to EnemyDef("aegis", "Aegis", 130.0, 23.0, 60.0, 230.0, 6.0, 6, Pal.C_FF5AC8, 7, 3.0, 1, 2,
        listOf(Formation.EDGE, Formation.CLUSTER), regularPoly(8, PI / 8)),
    // One more debuts in the first wave of each sector from 3 to 10, right after its guardian
    // (d = 4·(sector − 1) + 1); see Director.buildWave.
    "prism" to EnemyDef("prism", "Prism", 64.0, 17.0, 70.0, 170.0, 4.0, 4, Pal.C_B98CFF, 9, 4.5, 1, 3,
        listOf(Formation.EDGE, Formation.LINE, Formation.PINCER), regularPoly(3)),
    "comet" to EnemyDef("comet", "Comet", 50.0, 14.0, 520.0, 160.0, 3.0, 4, Pal.C_FF9466, 13, 4.0, 1, 3,
        listOf(Formation.EDGE, Formation.PINCER), floatArrayOf(1.4f, 0f, 0.35f, -0.62f, -0.55f, -0.48f, -1.05f, 0f, -0.55f, 0.48f, 0.35f, 0.62f)),
    "pulsar" to EnemyDef("pulsar", "Pulsar", 105.0, 20.0, 40.0, 200.0, 5.0, 5, Pal.C_FFCC4D, 17, 3.5, 1, 2,
        listOf(Formation.EDGE, Formation.LINE, Formation.CLUSTER), starPoly(6, 0.55)),
    "hive" to EnemyDef("hive", "Hive", 150.0, 24.0, 60.0, 240.0, 6.0, 6, Pal.C_E8963D, 21, 3.0, 1, 1,
        listOf(Formation.EDGE, Formation.CLUSTER), regularPoly(6, PI / 6)),
    "vortex" to EnemyDef("vortex", "Vortex", 115.0, 20.0, 55.0, 220.0, 5.0, 5, Pal.C_9F7BFF, 25, 3.0, 1, 2,
        listOf(Formation.EDGE, Formation.LINE, Formation.CLUSTER), pinwheelPoly(4)),
    "phantom" to EnemyDef("phantom", "Phantom", 75.0, 16.0, 70.0, 230.0, 5.0, 5, Pal.C_F2B8FF, 29, 3.0, 1, 2,
        listOf(Formation.EDGE, Formation.RING, Formation.CLUSTER), floatArrayOf(1.3f, 0f, 0.1f, -0.8f, -0.45f, -0.2f, -0.2f, 0f, -0.45f, 0.2f, 0.1f, 0.8f)),
    "carom" to EnemyDef("carom", "Carom", 90.0, 18.0, 110.0, 210.0, 5.0, 5, Pal.C_FF6F91, 33, 3.0, 1, 2,
        listOf(Formation.LINE, Formation.EDGE), floatArrayOf(1.2f, 0f, -0.2f, -0.95f, -0.9f, -0.95f, -0.35f, 0f, -0.9f, 0.95f, -0.2f, 0.95f)),
    "harbinger" to EnemyDef("harbinger", "Harbinger", 260.0, 28.0, 45.0, 400.0, 9.0, 8, Pal.C_FF2E63, 37, 2.2, 1, 1,
        listOf(Formation.EDGE, Formation.LINE), starPoly(8, 0.72, PI / 8))
)
val ENEMY_LIST: List<EnemyDef> = ENEMY_DEFS.values.toList()

/* ─────────────────────────────── guardians (§15) ─────────────────────────────── */

class BossDef(val id: String, val name: String, val title: String, val hp: Double, val r: Double, val color: Int, val score: Double, val xp: Double)

/** The campaign's guardians, one per sector in this order (Boss.create maps index i to the i-th). */
val BOSS_DEFS = listOf(
    BossDef("helix", "Helix Cantor", "The spiral choir", 2600.0, 46.0, Pal.C_FF3AD9, 8000.0, 40.0),
    BossDef("leviathan", "Lissajous Leviathan", "Serpent of the figure-eight", 3200.0, 30.0, Pal.C_FF4D6D, 10000.0, 50.0),
    BossDef("seraph", "Fractal Seraph", "Snowflake of endless wings", 3800.0, 44.0, Pal.C_C56BFF, 12000.0, 60.0),
    BossDef("entropy", "Entropy Engine", "The tesseract heart", 4600.0, 48.0, Pal.C_FFB238, 15000.0, 70.0),
    BossDef("fourier", "Fourier Orrery", "The clockwork of circles", 5400.0, 42.0, Pal.C_FF7A59, 18000.0, 80.0),
    BossDef("penrose", "Penrose Pentarch", "The tiling that never repeats", 6200.0, 48.0, Pal.C_FFD166, 22000.0, 92.0),
    BossDef("trefoil", "Trefoil Hierophant", "The knot that cannot be untied", 7000.0, 44.0, Pal.C_E05CFF, 26000.0, 104.0),
    BossDef("mandelbrot", "Mandelbrot Matriarch", "Garden of the escape time", 7800.0, 50.0, Pal.C_FF5C8A, 30000.0, 116.0),
    BossDef("automaton", "Automaton Augur", "Chaos grown from one cell", 8600.0, 46.0, Pal.C_D8FF5C, 35000.0, 128.0),
    BossDef("eidolon", "Euler Eidolon", "The sum of every proof", 9600.0, 56.0, Pal.C_FFF1D6, 40000.0, 140.0)
)

/* ─────────────────────────────── grafts (§16) ─────────────────────────────── */

enum class Rarity(val rank: Int, val w: Double, val color: Int, val label: String) {
    COMMON(0, 60.0, Pal.ION, "Common"), RARE(1, 28.0, Pal.RARE, "Rare"), EPIC(2, 10.0, Pal.VIOLET, "Epic"), LEGENDARY(3, 2.5, Pal.SOLAR, "Legendary")
}

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

class PerkDef(val id: String, val name: String, val rarity: Rarity, val max: Int, val pseudo: Boolean = false, val desc: (Int) -> String)

val PERKS = listOf(
    PerkDef("overclock", "Overclocked Coils", Rarity.COMMON, 5) { l -> "Fire rate +15% per level (+${15 * l}% at this level)." },
    PerkDef("density", "Plasma Density", Rarity.COMMON, 5) { l -> "Bolt damage +15% per level (+${15 * l}% at this level)." },
    PerkDef("prism", "Split Prism", Rarity.RARE, 3) { l -> "Each volley adds two angled bolts (${2 * l} extra at this level)." },
    PerkDef("phaseRounds", "Phase Rounds", Rarity.RARE, 3) { l -> "Bolts pierce ${plural(l, "more target")}." },
    PerkDef("seeker", "Seeker Swarm", Rarity.RARE, 4) { l -> "Launch ${plural(l, "homing missile")} every ${fmtFixed(max(0.7, 1.9 - 0.25 * l), 2)} s." },
    PerkDef("orbital", "Orbital Choir", Rarity.EPIC, 4) { l -> "${plural(l, "drone")} orbit you, absorbing bullets and firing on their own." },
    PerkDef("arc", "Arc Conduit", Rarity.EPIC, 3) { l -> "22% of hits chain lightning to ${l + 1} nearby hostiles." },
    PerkDef("ricochet", "Ricochet Matrix", Rarity.COMMON, 3) { l -> "Bolts bounce off the arena edge ${plural(l, "time")}." },
    PerkDef("singularity", "Singularity Rounds", Rarity.EPIC, 3) { l -> "Hits detonate for 40% splash damage in a ${50 + 16 * l}-unit radius." },
    PerkDef("magnet", "Graviton Lens", Rarity.COMMON, 3) { l -> "Pickup radius +45% per level (+${45 * l}% at this level)." },
    PerkDef("nanorepair", "Nano-Repair", Rarity.COMMON, 4) { _ -> "+1 max hull plate, and repair one plate now." },
    PerkDef("aegis", "Aegis Lattice", Rarity.RARE, 3) { l -> "A shield absorbs a hit and recharges in ${max(6, 15 - 3 * l)} s${if (l >= 3) ", holding 2 charges" else ""}." },
    PerkDef("siphon", "Flux Siphon", Rarity.COMMON, 3) { l -> "Graze charge +35% per level (+${35 * l}% at this level)." },
    PerkDef("crit", "Critical Lens", Rarity.RARE, 4) { l -> "${5 + 10 * l}% crit chance; crits deal ×${fmtFixed(2 + 0.25 * l, 2)}." },
    PerkDef("afterburner", "Afterburner", Rarity.COMMON, 3) { l -> "Move speed +10% per level (+${10 * l}% at this level)." },
    PerkDef("phaseEdge", "Phase Edge", Rarity.RARE, 3) { l -> "Dashing through hostiles cuts them. Dash cooldown −${15 * l}%." },
    PerkDef("corona", "Plasma Corona", Rarity.EPIC, 3) { l -> "A plasma field burns hostiles within ${78 + 18 * l} units." },
    PerkDef("rear", "Rear Battery", Rarity.COMMON, 2) { l -> "${plural(l, "rear-facing gun")} ${if (l == 1) "covers" else "cover"} your six." },
    PerkDef("chrono", "Chronoshift", Rarity.LEGENDARY, 1) { _ -> "Grazing a bullet triggers brief bullet-time (3.2 s cooldown)." },
    PerkDef("novaCache", "Nova Cache", Rarity.RARE, 2) { l -> "+1 nova now; novas deal +${50 * l}% damage." },
    PerkDef("velocity", "Velocity Shells", Rarity.COMMON, 3) { l -> "Bolt speed +20% and damage +5% per level (level $l)." },
    PerkDef("echo", "Echo Lance", Rarity.LEGENDARY, 1) { _ -> "Every sixth volley fires a piercing lance for ×4.5 damage." },
    PerkDef("supernova", "Supernova Heart", Rarity.EPIC, 1) { _ -> "Losing a hull plate releases a free nova." },
    PerkDef("midas", "Midas Protocol", Rarity.RARE, 2) { l -> "Stardust income +50% per level (+${50 * l}% at this level)." }
)
val PSEUDO_PERKS = listOf(
    PerkDef("repair", "Field Repair", Rarity.COMMON, Int.MAX_VALUE, pseudo = true) { _ -> "Repair one hull plate." },
    PerkDef("cache", "Stardust Cache", Rarity.RARE, Int.MAX_VALUE, pseudo = true) { _ -> "Bank 40 stardust immediately." }
)
val PERK_BY_ID: Map<String, PerkDef> = (PERKS + PSEUDO_PERKS).associateBy { it.id }

/* ─────────────────────────────── hangar refits (§17) ─────────────────────────────── */

class UpgradeDef(val id: String, val name: String, val max: Int, val base: Double, val growth: Double, val desc: (Int) -> String) {
    /** Cost(level) = base · growthˡᵉᵛᵉˡ (geometric). */
    fun cost(level: Int): Int = Math.round(base * Math.pow(growth, level.toDouble())).toInt()
}

val UPGRADES = listOf(
    UpgradeDef("hull", "Hull Plating", 5, 120.0, 1.6) { l -> "+1 max hull per level. Installed: +$l." },
    UpgradeDef("reactor", "Reactor Core", 6, 150.0, 1.55) { l -> "Bolt damage +8% per level. Installed: +${8 * l}%." },
    UpgradeDef("thrusters", "Vector Thrusters", 5, 100.0, 1.5) { l -> "Move speed +5% per level. Installed: +${5 * l}%." },
    UpgradeDef("flux", "Flux Capacitor", 4, 140.0, 1.6) { l -> "Graze charge +15% per level. Installed: +${15 * l}%." },
    UpgradeDef("nova", "Nova Reserve", 3, 260.0, 2.0) { l -> "+1 starting nova per level. Installed: +$l." },
    UpgradeDef("phase", "Phase Drive", 4, 130.0, 1.6) { l -> "Dash cooldown −8% per level. Installed: −${8 * l}%." },
    UpgradeDef("magnet", "Graviton Array", 4, 90.0, 1.5) { l -> "Pickup radius +15% per level. Installed: +${15 * l}%." },
    UpgradeDef("salvage", "Salvage Protocol", 5, 180.0, 1.7) { l -> "Stardust income +12% per level. Installed: +${12 * l}%." },
    UpgradeDef("insight", "Deep Insight", 4, 160.0, 1.6) { l -> "Experience gain +10% per level. Installed: +${10 * l}%." },
    UpgradeDef("fortune", "Fortune Matrix", 3, 220.0, 1.9) { l -> "+1 graft reroll per run. Installed: $l." },
    UpgradeDef("genesis", "Genesis Seed", 2, 400.0, 2.2) { l -> "+1 free common graft at launch per level. Installed: $l." }
)

/* ─────────────────────────────── mutators & palettes (§18, §7b) ─────────────────────────────── */

class MutatorDef(val id: String, val name: String, val desc: String)

val MUTATORS = listOf(
    MutatorDef("hyperflux", "Hyperflux", "Hostile bullets +15% speed · score +25%"),
    MutatorDef("swarm", "Swarm Tide", "+40% hostiles every wave"),
    MutatorDef("volatile", "Volatile Hulls", "Wrecks burst into bullet rings"),
    MutatorDef("elite", "Elite Surge", "Elite chance +15%"),
    MutatorDef("eclipse", "Eclipse", "Nebula light dims · stardust +30%"),
    MutatorDef("overclock", "Overclocked Foes", "Hostile fire rate +20%")
)
fun mutatorName(id: String) = MUTATORS.firstOrNull { it.id == id }?.name ?: id

class NebulaPalette(val name: String, val a: Int, val b: Int, val c: Int, val base: Int, val lattice: Int, val plasma: DoubleArray)

val NEBULA_PALETTES = listOf(
    NebulaPalette("Violet Choir", ColorUtil.hex("#3a1a8c"), ColorUtil.hex("#c21f7c"), ColorUtil.hex("#59e4ff"), ColorUtil.hex("#05030d"), ColorUtil.hex("#7a5cff"), doubleArrayOf(0.9, 0.25, 1.0)),
    NebulaPalette("Ember Reach", ColorUtil.hex("#5a1030"), ColorUtil.hex("#b8401c"), ColorUtil.hex("#e89a4a"), ColorUtil.hex("#080309"), ColorUtil.hex("#ff6a5c"), doubleArrayOf(1.0, 0.45, 0.2)),
    NebulaPalette("Tidal Abyss", ColorUtil.hex("#0a356b"), ColorUtil.hex("#17a89c"), ColorUtil.hex("#a6fff0"), ColorUtil.hex("#02060d"), ColorUtil.hex("#2bd4ff"), doubleArrayOf(0.2, 0.9, 1.0)),
    NebulaPalette("Aurum Halo", ColorUtil.hex("#47196b"), ColorUtil.hex("#e0559b"), ColorUtil.hex("#ffcf6b"), ColorUtil.hex("#07030b"), ColorUtil.hex("#ff8ae9"), doubleArrayOf(1.0, 0.6, 0.9))
)
