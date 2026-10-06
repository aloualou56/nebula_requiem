package io.github.aloualou56.nebularequiem.render

import android.content.res.Resources
import android.graphics.Typeface
import io.github.aloualou56.nebularequiem.R

/**
 * The original's three typefaces, bundled as static TTF instances (SIL OFL 1.1):
 * Tektur (display), Chakra Petch (UI) and JetBrains Mono (numbers and labels).
 */
object Fonts {
    var display400: Typeface = Typeface.DEFAULT; var display600: Typeface = Typeface.DEFAULT_BOLD; var display800: Typeface = Typeface.DEFAULT_BOLD; var display900: Typeface = Typeface.DEFAULT_BOLD
    var ui400: Typeface = Typeface.DEFAULT; var ui500: Typeface = Typeface.DEFAULT; var ui600: Typeface = Typeface.DEFAULT_BOLD; var ui700: Typeface = Typeface.DEFAULT_BOLD
    var mono400: Typeface = Typeface.MONOSPACE; var mono700: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private var loaded = false

    fun load(res: Resources) {
        if (loaded) return
        fun f(id: Int, fallback: Typeface): Typeface = try { res.getFont(id) } catch (e: Exception) { fallback }
        display400 = f(R.font.tektur_400, display400); display600 = f(R.font.tektur_600, display600); display800 = f(R.font.tektur_800, display800); display900 = f(R.font.tektur_900, display900)
        ui400 = f(R.font.chakra_petch_400, ui400); ui500 = f(R.font.chakra_petch_500, ui500); ui600 = f(R.font.chakra_petch_600, ui600); ui700 = f(R.font.chakra_petch_700, ui700)
        mono400 = f(R.font.jetbrains_mono_400, mono400); mono700 = f(R.font.jetbrains_mono_700, mono700)
        loaded = true
    }
}
