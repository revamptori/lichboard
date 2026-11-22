package dev.lichboard.keyboard

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import dev.lichboard.data.BoardSettings
import dev.lichboard.data.BoardTheme

data class BoardPalette(
    val background: Int, val key: Int, val special: Int, val text: Int,
    val muted: Int, val accent: Int, val onAccent: Int, val border: Int
) {
    companion object {
        private fun c(hex: String) = Color.parseColor("#$hex")
        fun from(s: BoardSettings): BoardPalette = when (s.theme) {
            BoardTheme.MIDNIGHT -> colors("10151E", "252D3B", "1A2230", "EEF2F7", "8D9AAF", "C6F58B", "15200D", "354052")
            BoardTheme.AMOLED -> colors("000000", "151515", "0B0B0B", "F4F4F4", "969696", "C6F58B", "102000", "292929")
            BoardTheme.DRACULA -> colors("20212C", "383A4D", "2B2D3C", "F8F8F2", "AAA6C3", "BD93F9", "211434", "4B4E64")
            BoardTheme.NORD -> colors("242A35", "3B4658", "303949", "ECEFF4", "A6B5CB", "88C0D0", "172D35", "4C5A70")
            BoardTheme.LIGHT -> colors("E8EDF3", "FFFFFF", "D4DDE8", "1C293A", "5A6D82", "314F38", "FFFFFF", "C2CDDA")
            BoardTheme.CUSTOM -> {
                val bg = runCatching { c(s.customBackground) }.getOrDefault(c("151A24"))
                val accent = runCatching { c(s.customAccent) }.getOrDefault(c("C6F58B"))
                val light = ColorUtils.calculateLuminance(bg) > .5
                val fg = if (light) Color.BLACK else Color.WHITE
                BoardPalette(bg, ColorUtils.blendARGB(bg, fg, .10f), ColorUtils.blendARGB(bg, fg, .05f),
                    fg, ColorUtils.blendARGB(bg, fg, .63f), accent,
                    if (ColorUtils.calculateLuminance(accent) > .42) Color.BLACK else Color.WHITE,
                    ColorUtils.blendARGB(bg, fg, .19f))
            }
        }
        private fun colors(vararg hex: String) = hex.map(::c).let {
            BoardPalette(it[0], it[1], it[2], it[3], it[4], it[5], it[6], it[7])
        }
    }
}
