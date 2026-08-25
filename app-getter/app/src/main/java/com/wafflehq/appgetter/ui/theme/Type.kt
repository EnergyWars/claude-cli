package com.wafflehq.appgetter.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.wafflehq.appgetter.R

val GeistSans: FontFamily = FontFamily(
    Font(R.font.geist_light, weight = FontWeight.Light),
    Font(R.font.geist_regular, weight = FontWeight.Normal),
    Font(R.font.geist_medium, weight = FontWeight.Medium),
    Font(R.font.geist_semibold, weight = FontWeight.SemiBold),
    Font(R.font.geist_bold, weight = FontWeight.Bold),
)

val GeistMono: FontFamily = FontFamily(
    Font(R.font.geist_regular, weight = FontWeight.Normal),
    Font(R.font.geist_medium, weight = FontWeight.Medium),
    Font(R.font.geist_semibold, weight = FontWeight.SemiBold),
)

val AppTypography = Typography(
    displayLarge  = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Bold,     fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.025).em, fontFeatureSettings = "tnum"),
    displayMedium = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Bold,     fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = (-0.025).em, fontFeatureSettings = "tnum"),
    displaySmall  = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Bold,     fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.025).em, fontFeatureSettings = "tnum"),

    headlineLarge  = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Bold,    fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.022).em, fontFeatureSettings = "tnum"),
    headlineMedium = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Bold,    fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.022).em, fontFeatureSettings = "tnum"),
    headlineSmall  = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Bold,    fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.022).em, fontFeatureSettings = "tnum"),

    titleLarge  = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.SemiBold,   fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.008).em, fontFeatureSettings = "tnum"),
    titleMedium = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.SemiBold,   fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = (-0.004).em, fontFeatureSettings = "tnum"),
    titleSmall  = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.SemiBold,   fontSize = 14.sp, lineHeight = 20.sp,                              fontFeatureSettings = "tnum"),

    bodyLarge   = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Normal,     fontSize = 16.sp, lineHeight = 24.sp, fontFeatureSettings = "tnum"),
    bodyMedium  = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Normal,     fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum"),
    bodySmall   = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Normal,     fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum"),

    labelLarge  = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.SemiBold,   fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (0.01).em,  fontFeatureSettings = "tnum"),
    labelMedium = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.SemiBold,   fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = (0.01).em,  fontFeatureSettings = "tnum"),
    labelSmall  = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.SemiBold,   fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = (0.08).em,  fontFeatureSettings = "tnum"),
)
