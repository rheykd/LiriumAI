package com.localmodels.app.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.localmodels.app.R

val Bg = Color(0xFF0A0A0A)
val Surface = Color(0xFF111111)
val SurfaceHigh = Color(0xFF191919)
val Border = Color(0xFF232323)
val TextPrimary = Color(0xFFF0F0F0)
val TextSecondary = Color(0xFF8C8C8C)
val TextMuted = Color(0xFF5A5A5A)

val MontserratBold = FontFamily(Font(R.font.montserrat_bold, FontWeight.Normal))
val MontserratRegular = FontFamily(Font(R.font.montserrat_regular, FontWeight.Normal))
val MontserratLight = FontFamily(Font(R.font.montserrat_light, FontWeight.Normal))
val Strejen = FontFamily(Font(R.font.aa_strejen, FontWeight.Normal))

data class FontChoice(val key: String, val label: String)

val FontChoices = listOf(
    FontChoice("montserrat_bold", "Montserrat Bold"),
    FontChoice("montserrat_regular", "Montserrat"),
    FontChoice("montserrat_light", "Montserrat Light"),
    FontChoice("strejen", "AA Strejen")
)

fun fontFor(key: String): FontFamily = when (key) {
    "montserrat_regular" -> MontserratRegular
    "montserrat_light" -> MontserratLight
    "strejen" -> Strejen
    else -> MontserratBold
}

private fun typography(font: FontFamily) = Typography(
    displayLarge = TextStyle(fontFamily = font, fontSize = 32.sp, lineHeight = 38.sp, color = TextPrimary),
    headlineMedium = TextStyle(fontFamily = font, fontSize = 22.sp, lineHeight = 28.sp, color = TextPrimary),
    titleLarge = TextStyle(fontFamily = font, fontSize = 18.sp, lineHeight = 24.sp, color = TextPrimary),
    titleMedium = TextStyle(fontFamily = font, fontSize = 16.sp, lineHeight = 22.sp, color = TextPrimary),
    bodyLarge = TextStyle(fontFamily = font, fontSize = 16.sp, lineHeight = 24.sp, color = TextPrimary),
    bodyMedium = TextStyle(fontFamily = font, fontSize = 14.sp, lineHeight = 20.sp, color = TextPrimary),
    bodySmall = TextStyle(fontFamily = font, fontSize = 12.sp, lineHeight = 16.sp, color = TextSecondary),
    labelLarge = TextStyle(fontFamily = font, fontSize = 13.sp, lineHeight = 16.sp, color = TextPrimary),
    labelMedium = TextStyle(fontFamily = font, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp, color = TextSecondary),
    labelSmall = TextStyle(fontFamily = font, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.6.sp, color = TextMuted)
)

@Composable
fun LiriumTheme(fontKey: String = "montserrat_bold", content: @Composable () -> Unit) {
    val typo = remember(fontKey) { typography(fontFor(fontKey)) }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color.White,
            onPrimary = Color.Black,
            background = Bg,
            onBackground = TextPrimary,
            surface = Surface,
            onSurface = TextPrimary,
            surfaceVariant = SurfaceHigh,
            onSurfaceVariant = TextSecondary,
            outline = Border
        ),
        typography = typo
    ) {
        CompositionLocalProvider(LocalTextStyle provides typo.bodyLarge) {
            content()
        }
    }
}
