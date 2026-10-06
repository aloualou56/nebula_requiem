package io.github.aloualou56.nebularequiem.core

/** §0 CONFIGURATION — all tunables live here so balancing never requires hunting through systems. */
object Cfg {
    /** The fixed simulation step (s). The original's longest sub-step; every pattern was tuned for it. */
    const val SIM_STEP = 1.0 / 120
    /** Spiral-of-death guard: never simulate more than 10 steps (1/12 s) per rendered frame. */
    const val MAX_STEPS = 10
    /** Frame-rate settings; 0 = match the display's refresh rate. */
    val FPS_CAPS = intArrayOf(0, 60, 120, 144)
    /** The short screen edge always spans this many world units. */
    const val WORLD_SHORT_SIDE = 760.0
    const val MAX_ENEMY_BULLETS = 2600
    const val MAX_PLAYER_SHOTS = 800
    const val MAX_PICKUPS = 420
    /** World units beyond the hitbox where a passing bullet counts as a graze. */
    const val GRAZE_RADIUS = 30.0
    const val FLUX_PER_GRAZE = 0.011
    /** Seconds a kill chain survives without a new kill. */
    const val COMBO_WINDOW = 2.6
    /** Bullets inside this radius are erased when the player is hit. */
    const val MERCY_RADIUS = 150.0
    /** Enemies never warp in closer than this to the player. */
    const val SPAWN_SAFE_RADIUS = 210.0
    /** World units per lighting-grid cell. */
    const val LIGHT_CELL = 26.0
    /** World units between spacetime lattice nodes. */
    const val LATTICE_SPACING = 54.0
    /** The fourth wave of a sector is its guardian: three waves, then the guardian. */
    const val BOSS_EVERY = 4
    /** Sectors in the campaign, one guardian each; breaking the last guardian wins the run. */
    const val CAMPAIGN_SECTORS = 10
}

/** Render quality tiers (the user's choice; adaptive quality may lower the effective tier). */
enum class Quality(val id: String, val maxParticles: Int, val particleMul: Double, val pixelBudget: Double, val densityCap: Double, val motes: Int) {
    LOW("low", 900, 0.4, 1.0e6, 1.0, 30),
    MEDIUM("medium", 1900, 0.7, 1.8e6, 1.5, 55),
    HIGH("high", 3400, 1.0, 2.6e6, 2.0, 80);

    companion object {
        fun of(id: String?): Quality? = entries.firstOrNull { it.id == id }
    }
}
