package io.github.aloualou56.nebularequiem.core

import kotlin.math.roundToInt

/** §2 COLOR — colours are opaque ARGB ints; alpha is applied at draw time. */
object Pal {
    const val VOID = 0xFF05030D.toInt()
    const val VOID2 = 0xFF0B0720.toInt()
    const val VOID3 = 0xFF170E36.toInt()
    const val INK = 0xFFECE7FF.toInt()
    const val INK_DIM = 0xFFA49BD0.toInt()
    const val INK_FAINT = 0xFF5F5690.toInt()
    const val ION = 0xFF3DF2FF.toInt()        // the player: cool cyan
    const val PLASMA = 0xFFFF3AD9.toInt()     // hostile fire: hot magenta
    const val SOLAR = 0xFFFFC145.toInt()      // stardust currency: gold
    const val MINT = 0xFF6BFFB5.toInt()       // repair / positive
    const val CRIMSON = 0xFFFF3B5C.toInt()    // damage / warnings
    const val VIOLET = 0xFFA98BFF.toInt()     // rarity: epic
    const val LINE = 0xFF9D93C9.toInt()       // --line-rgb
    const val AMBER = 0xFFFF9B3D.toInt()
    const val ROSE = 0xFFFF7AB6.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val ICE = 0xFFC9F6FF.toInt()
    const val RARE = 0xFF6BC4FF.toInt()

    // Frequently used hostile/boss colours
    const val C_FF3AD9 = 0xFFFF3AD9.toInt()
    const val C_FFB238 = 0xFFFFB238.toInt()
    const val C_FF4D6D = 0xFFFF4D6D.toInt()
    const val C_FF8AE9 = 0xFFFF8AE9.toInt()
    const val C_FFE066 = 0xFFFFE066.toInt()
    const val C_C56BFF = 0xFFC56BFF.toInt()
    const val C_FF6A3D = 0xFFFF6A3D.toInt()
    const val C_D36BFF = 0xFFD36BFF.toInt()
    const val C_FF5AC8 = 0xFFFF5AC8.toInt()
    // guardians V–X
    const val C_FF7A59 = 0xFFFF7A59.toInt()   // coral: Fourier Orrery
    const val C_FFD166 = 0xFFFFD166.toInt()   // sunflower: Penrose Pentarch
    const val C_E05CFF = 0xFFE05CFF.toInt()   // orchid: Trefoil Hierophant
    const val C_FF5C8A = 0xFFFF5C8A.toInt()   // rose: Mandelbrot Matriarch
    const val C_D8FF5C = 0xFFD8FF5C.toInt()   // chartreuse: Automaton Augur (its bullets stay warm)
    const val C_FFF1D6 = 0xFFFFF1D6.toInt()   // ivory: Euler Eidolon
    // hostiles that debut in sectors 3–10
    const val C_B98CFF = 0xFFB98CFF.toInt()   // lavender: Prism
    const val C_FF9466 = 0xFFFF9466.toInt()   // peach: Comet
    const val C_FFCC4D = 0xFFFFCC4D.toInt()   // gold: Pulsar
    const val C_E8963D = 0xFFE8963D.toInt()   // honey: Hive
    const val C_9F7BFF = 0xFF9F7BFF.toInt()   // violet: Vortex
    const val C_F2B8FF = 0xFFF2B8FF.toInt()   // pale orchid: Phantom
    const val C_FF6F91 = 0xFFFF6F91.toInt()   // coral: Carom
    const val C_FF2E63 = 0xFFFF2E63.toInt()   // deep rose: Harbinger
    const val SMOKE = 0xFF2A1840.toInt()

    /** Enemy projectile palette — all warm/magenta so hostile fire is never confused with yours. */
    val BULLET_COLORS = intArrayOf(C_FF3AD9, C_FFB238, C_FF4D6D, C_FF8AE9, C_FFE066, C_C56BFF)
}

object ColorUtil {
    fun hex(s: String): Int = (0xFF000000L or s.removePrefix("#").toLong(16)).toInt()
    fun r(c: Int) = (c shr 16) and 255
    fun g(c: Int) = (c shr 8) and 255
    fun b(c: Int) = c and 255
    fun withAlpha(c: Int, a: Double): Int = ((clamp(a, 0.0, 1.0) * 255).roundToInt() shl 24) or (c and 0xFFFFFF)
    fun withAlpha255(c: Int, a: Int): Int = (clampI(a, 0, 255) shl 24) or (c and 0xFFFFFF)

    /** Linear interpolation in sRGB space (adequate for neon UI tints). */
    fun mix(c1: Int, c2: Int, t: Double): Int {
        val r = lerp(r(c1).toDouble(), r(c2).toDouble(), t).roundToInt()
        val g = lerp(g(c1).toDouble(), g(c2).toDouble(), t).roundToInt()
        val b = lerp(b(c1).toDouble(), b(c2).toDouble(), t).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** HSL → colour, h in degrees. */
    fun hsl(h0: Double, s: Double, l: Double): Int {
        val h = mod(h0, 360.0) / 360
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun f(t0: Double): Double {
            val t = mod(t0, 1.0)
            if (t < 1.0 / 6) return p + (q - p) * 6 * t
            if (t < 1.0 / 2) return q
            if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3 - t) * 6
            return p
        }
        val r = (f(h + 1.0 / 3) * 255).roundToInt(); val g = (f(h) * 255).roundToInt(); val b = (f(h - 1.0 / 3) * 255).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
