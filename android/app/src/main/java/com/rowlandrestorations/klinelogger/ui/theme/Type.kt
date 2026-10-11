package com.rowlandrestorations.klinelogger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Roboto ships on every Android device. "sans-serif-condensed" is Roboto Condensed, which fits
// big figures in narrow gauge cards; "tnum" gives every digit the same width so values don't jitter.
private val Sans = FontFamily.SansSerif
val Condensed = FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))
private const val TABULAR = "tnum"

/** Big gauge readouts. Size is set per gauge; this fixes face, weight and tabular figures. */
val GaugeNumerals = TextStyle(
    fontFamily = Condensed,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = TABULAR,
    letterSpacing = (-0.5).sp,
)

/** Small all-caps labels above values ("COOLANT", "RPM"). */
val GaugeLabel = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 13.sp,
    lineHeight = 16.sp,
    letterSpacing = 1.2.sp,
)

val Typography = Typography(
    displayLarge = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 68.sp, fontFeatureSettings = TABULAR),
    displayMedium = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 52.sp, fontFeatureSettings = TABULAR),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.3.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.8.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.8.sp),
)
