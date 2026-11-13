package uk.maynotfunction.ping.ui.theme

import android.graphics.Typeface
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import uk.maynotfunction.ping.R

/*
 * Matches the system type exactly on Pixels: their /product/etc/fonts_customization.xml defines a
 * named Google Sans Flex family per M3 Expressive type style ("variable-body-large",
 * "variable-title-medium-emphasized", ...) with the system's weights, optical sizes and, for the
 * emphasized styles, ROND 100. The framework's own text styles use these with letterSpacing 0.
 *
 * Elsewhere, a bundled Google Sans Flex (Google Fonts, SIL OFL 1.1, see assets/licenses) stands
 * in with the same weights and rounding. It's subset to Latin; Cyrillic and Greek fall back to
 * the system font.
 */

private val weights = listOf(400, 500, 600, 700, 800).map { FontWeight(it) }

private fun systemHas(family: String): Boolean =
    Typeface.create(family, Typeface.NORMAL) != Typeface.DEFAULT

@OptIn(ExperimentalTextApi::class)
private fun bundledFlex(rounded: Boolean) = FontFamily(
    weights.map { w ->
        Font(
            R.font.google_sans_flex, w,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(w.weight),
                FontVariation.Setting("ROND", if (rounded) 100f else 0f),
            ),
        )
    }
)

private val fallbackRegular by lazy { bundledFlex(rounded = false) }
private val fallbackEmphasized by lazy { bundledFlex(rounded = true) }

/** The system family for a type style, e.g. `family("title-medium", emphasized = true)`. */
private fun family(style: String, emphasized: Boolean = false): FontFamily {
    val name = "variable-$style" + if (emphasized) "-emphasized" else ""
    return if (systemHas(name)) FontFamily(weights.map { Font(DeviceFontFamilyName(name), it) })
    else if (emphasized) fallbackEmphasized else fallbackRegular
}

@OptIn(ExperimentalTextApi::class)
val GoogleSansCode = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium).map {
        Font(R.font.google_sans_code, it, variationSettings = FontVariation.Settings(FontVariation.weight(it.weight)))
    }
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun bridgeTypography(): Typography = Typography().run {
    fun TextStyle.sys(style: String, emphasized: Boolean = false) =
        copy(fontFamily = family(style, emphasized), letterSpacing = 0.sp)
    Typography(
        displayLarge = displayLarge.sys("display-large"),
        displayMedium = displayMedium.sys("display-medium"),
        displaySmall = displaySmall.sys("display-small"),
        headlineLarge = headlineLarge.sys("headline-large"),
        headlineMedium = headlineMedium.sys("headline-medium"),
        headlineSmall = headlineSmall.sys("headline-small"),
        titleLarge = titleLarge.sys("title-large"),
        titleMedium = titleMedium.sys("title-medium"),
        titleSmall = titleSmall.sys("title-small"),
        bodyLarge = bodyLarge.sys("body-large"),
        bodyMedium = bodyMedium.sys("body-medium"),
        bodySmall = bodySmall.sys("body-small"),
        labelLarge = labelLarge.sys("label-large"),
        labelMedium = labelMedium.sys("label-medium"),
        labelSmall = labelSmall.sys("label-small"),
        displayLargeEmphasized = displayLargeEmphasized.sys("display-large", true),
        displayMediumEmphasized = displayMediumEmphasized.sys("display-medium", true),
        displaySmallEmphasized = displaySmallEmphasized.sys("display-small", true),
        headlineLargeEmphasized = headlineLargeEmphasized.sys("headline-large", true),
        headlineMediumEmphasized = headlineMediumEmphasized.sys("headline-medium", true),
        headlineSmallEmphasized = headlineSmallEmphasized.sys("headline-small", true),
        titleLargeEmphasized = titleLargeEmphasized.sys("title-large", true),
        titleMediumEmphasized = titleMediumEmphasized.sys("title-medium", true),
        titleSmallEmphasized = titleSmallEmphasized.sys("title-small", true),
        bodyLargeEmphasized = bodyLargeEmphasized.sys("body-large", true),
        bodyMediumEmphasized = bodyMediumEmphasized.sys("body-medium", true),
        bodySmallEmphasized = bodySmallEmphasized.sys("body-small", true),
        labelLargeEmphasized = labelLargeEmphasized.sys("label-large", true),
        labelMediumEmphasized = labelMediumEmphasized.sys("label-medium", true),
        labelSmallEmphasized = labelSmallEmphasized.sys("label-small", true),
    )
}
