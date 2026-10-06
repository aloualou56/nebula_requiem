package io.github.aloualou56.nebularequiem.core

/** Headless harness: a fake clock, an in-memory save store and a frame driver. */
object TestEnv {
    var nowMs = 0.0

    class MemStore(var text: String? = null) : SaveStore {
        var writes = 0
        override fun read(): String? = text
        override fun write(text: String) { this.text = text; writes++ }
    }

    fun setup(pxW: Int = 2400, pxH: Int = 1080, density: Double = 2.75, seed: Int = 1234): MemStore {
        nowMs = 0.0
        Env.clock = { nowMs }
        World.quality = Quality.HIGH
        World.resize(pxW, pxH, density)
        Light.resize(World.w, World.h)
        Lattice.build(World.w, World.h)
        Fx.configure(Quality.HIGH)
        Fx.clear(false)
        val store = MemStore()
        Save.store = store
        Save.replace(SaveData())
        Game.resetForTests()
        Game.seedOverride = seed
        Rng.game.seed(seed)
        Rng.vis.seed(seed * 7 + 1)
        Bg.adopt(1, NEBULA_PALETTES[0])
        Input.releaseAll()
        Input.endFrame()
        Cam.reset(); PostFx.reset()
        return store
    }

    /** Advance `n` frames of `dt` seconds through the same per-frame pipeline the app uses. */
    fun frames(n: Int, dt: Double, each: (() -> Unit)? = null) {
        for (i in 0 until n) {
            nowMs += dt * 1000
            each?.invoke()
            Game.frame(dt)
            Game.gatherFields()
            Light.upload(dt)
            Lattice.update(dt * Game.timeScale)
            Cam.build(1.0)
            Input.endFrame()
        }
    }

    fun seconds(s: Double, hz: Int, each: (() -> Unit)? = null) = frames((s * hz).toInt(), 1.0 / hz, each)
}
