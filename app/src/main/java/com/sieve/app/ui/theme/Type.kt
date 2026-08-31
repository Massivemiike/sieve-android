package com.sieve.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.sieve.app.R

@OptIn(ExperimentalTextApi::class)
private fun interWeight(w: Int, fw: FontWeight) =
    Font(R.font.inter_variable, weight = fw, variationSettings = FontVariation.Settings(FontVariation.weight(w)))

@OptIn(ExperimentalTextApi::class)
private fun monoWeight(w: Int, fw: FontWeight) =
    Font(R.font.jetbrains_mono_variable, weight = fw, variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Inter = FontFamily(
    interWeight(400, FontWeight.Normal),
    interWeight(500, FontWeight.Medium),
    interWeight(600, FontWeight.SemiBold),
    interWeight(700, FontWeight.Bold),
)

val JetBrainsMono = FontFamily(
    monoWeight(400, FontWeight.Normal),
    monoWeight(500, FontWeight.Medium),
    monoWeight(600, FontWeight.SemiBold),
)

/** Convenience alias for meta/data text (speeds, sizes, CRF, etc.). */
val MonoFamily = JetBrainsMono

/** Type scale anchored to Sieve's dense 13px UI base. */
val SieveTypography = Typography(
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.5.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 11.5.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
    labelSmall = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 14.sp),
)
