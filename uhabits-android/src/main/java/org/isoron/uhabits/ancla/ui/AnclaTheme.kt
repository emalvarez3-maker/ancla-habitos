/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.ancla.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R

/** Ancla's palette: soft lavender ground, ink text, mint for habits, lilac for tasks, amber for key days. */
@Immutable
data class AnclaColors(
    val ground: Color,
    val card: Color,
    val cardMuted: Color,
    val ink: Color,
    val inkSoft: Color,
    val muted: Color,
    val track: Color,
    val mint: Color,
    val mintSoft: Color,
    val mintInk: Color,
    val lilac: Color,
    val lilacSoft: Color,
    val lilacInk: Color,
    val amber: Color,
    val amberSoft: Color,
    val amberInk: Color,
    val blush: Color,
    val onInk: Color
)

val LightAnclaColors = AnclaColors(
    ground = Color(0xFFF4F3FB),
    card = Color(0xFFFFFFFF),
    cardMuted = Color(0x8CFFFFFF),
    ink = Color(0xFF211F33),
    inkSoft = Color(0xFF3D3955),
    muted = Color(0xFF6E6A85),
    track = Color(0xFFE4E1F2),
    mint = Color(0xFF2FB57E),
    mintSoft = Color(0xFFE2F6EC),
    mintInk = Color(0xFF1F5C42),
    lilac = Color(0xFF8E7CF0),
    lilacSoft = Color(0xFFECE8FF),
    lilacInk = Color(0xFF4F3FB5),
    amber = Color(0xFFF2A93B),
    amberSoft = Color(0xFFFFE6BD),
    amberInk = Color(0xFF7A4A00),
    blush = Color(0xFFFFB4A2),
    onInk = Color(0xFFFFFFFF)
)

val DarkAnclaColors = AnclaColors(
    ground = Color(0xFF15131F),
    card = Color(0xFF221F31),
    cardMuted = Color(0x8C221F31),
    ink = Color(0xFFF1EFFA),
    inkSoft = Color(0xFFD6D2EA),
    muted = Color(0xFFA9A4C4),
    track = Color(0xFF34304A),
    mint = Color(0xFF4FCB95),
    mintSoft = Color(0xFF1C3B2E),
    mintInk = Color(0xFFBDEFD7),
    lilac = Color(0xFFA899F5),
    lilacSoft = Color(0xFF2E2853),
    lilacInk = Color(0xFFDCD5FF),
    amber = Color(0xFFF2B65A),
    amberSoft = Color(0xFF45331A),
    amberInk = Color(0xFFFFDDA6),
    blush = Color(0xFFFF9F8C),
    onInk = Color(0xFF15131F)
)

val LocalAnclaColors = staticCompositionLocalOf { LightAnclaColors }

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) = Font(
    res,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

val Fredoka = FontFamily(
    variable(R.font.fredoka, 500),
    variable(R.font.fredoka, 600),
    variable(R.font.fredoka, 700)
)

val Nunito = FontFamily(
    variable(R.font.nunito, 500),
    variable(R.font.nunito, 600),
    variable(R.font.nunito, 700),
    variable(R.font.nunito, 800)
)

private val AnclaTypography = Typography(
    displaySmall = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight(600), fontSize = 30.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight(600), fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight(600), fontSize = 19.sp),
    titleMedium = TextStyle(fontFamily = Nunito, fontWeight = FontWeight(800), fontSize = 15.5.sp),
    bodyLarge = TextStyle(fontFamily = Nunito, fontWeight = FontWeight(600), fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = Nunito, fontWeight = FontWeight(600), fontSize = 13.5.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = Nunito, fontWeight = FontWeight(800), fontSize = 13.5.sp),
    labelMedium = TextStyle(fontFamily = Nunito, fontWeight = FontWeight(800), fontSize = 12.5.sp),
    labelSmall = TextStyle(fontFamily = Nunito, fontWeight = FontWeight(800), fontSize = 11.sp, letterSpacing = 0.6.sp)
)

private val AnclaShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp)
)

object Ancla {
    val colors: AnclaColors
        @Composable get() = LocalAnclaColors.current
}

@Composable
fun AnclaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkAnclaColors else LightAnclaColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.mint,
            onPrimary = colors.onInk,
            secondary = colors.lilac,
            background = colors.ground,
            surface = colors.card,
            onBackground = colors.ink,
            onSurface = colors.ink
        )
    } else {
        lightColorScheme(
            primary = colors.mint,
            onPrimary = colors.onInk,
            secondary = colors.lilac,
            background = colors.ground,
            surface = colors.card,
            onBackground = colors.ink,
            onSurface = colors.ink
        )
    }
    CompositionLocalProvider(LocalAnclaColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = AnclaTypography, shapes = AnclaShapes, content = content)
    }
}
