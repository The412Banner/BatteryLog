package com.the412banner.batterylog.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.the412banner.batterylog.ThemeMode

/** Colours for the chart metrics (kept apart from Material roles so they stay recognisable). */
@Immutable
data class ChartColors(
    val level: Color,
    val power: Color,
    val current: Color,
    val voltage: Color,
    val temp: Color,
    val charging: Color,
    val drain: Color,
    val warn: Color,
    val bad: Color,
)

private val DarkChart = ChartColors(
    level = Color(0xFF3DDC97),
    power = Color(0xFFB69CFF),
    current = Color(0xFF6CB2FF),
    voltage = Color(0xFFFFC45C),
    temp = Color(0xFFFF7A8A),
    charging = Color(0xFF3DDC97),
    drain = Color(0xFFFF9F5A),
    warn = Color(0xFFFFC45C),
    bad = Color(0xFFFF6B6B),
)

private val LightChart = ChartColors(
    level = Color(0xFF00965F),
    power = Color(0xFF6E4BE0),
    current = Color(0xFF1F6FD1),
    voltage = Color(0xFFB7791F),
    temp = Color(0xFFD6455A),
    charging = Color(0xFF00965F),
    drain = Color(0xFFDB6A1D),
    warn = Color(0xFFB7791F),
    bad = Color(0xFFD03B3B),
)

val LocalChartColors = staticCompositionLocalOf { DarkChart }

private val Dark = darkColorScheme(
    primary = Color(0xFF3DDC97),
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF0B5136),
    onPrimaryContainer = Color(0xFF9BF6C8),
    secondary = Color(0xFF8FC1FF),
    onSecondary = Color(0xFF00315C),
    secondaryContainer = Color(0xFF1C3A57),
    onSecondaryContainer = Color(0xFFD2E4FF),
    tertiary = Color(0xFFFFC45C),
    onTertiary = Color(0xFF412D00),
    tertiaryContainer = Color(0xFF5A4300),
    onTertiaryContainer = Color(0xFFFFDFA3),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0000),
    errorContainer = Color(0xFF5C1515),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0D1012),
    onBackground = Color(0xFFE2E6E8),
    surface = Color(0xFF0D1012),
    onSurface = Color(0xFFE2E6E8),
    surfaceVariant = Color(0xFF283033),
    onSurfaceVariant = Color(0xFFA6B0B5),
    surfaceContainerLowest = Color(0xFF080A0B),
    surfaceContainerLow = Color(0xFF131719),
    surfaceContainer = Color(0xFF171C1F),
    surfaceContainerHigh = Color(0xFF1F2528),
    surfaceContainerHighest = Color(0xFF293033),
    outline = Color(0xFF6C777C),
    outlineVariant = Color(0xFF353D41),
    inverseSurface = Color(0xFFE2E6E8),
    inverseOnSurface = Color(0xFF1A1F21),
)

private val Black = Dark.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF07090A),
    surfaceContainer = Color(0xFF0E1214),
    surfaceContainerHigh = Color(0xFF151A1C),
    surfaceContainerHighest = Color(0xFF1D2326),
)

private val Light = lightColorScheme(
    primary = Color(0xFF00895A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F2D6),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF2E64A8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E3FF),
    onSecondaryContainer = Color(0xFF001B3C),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDA8),
    onTertiaryContainer = Color(0xFF2B1700),
    error = Color(0xFFC62828),
    background = Color(0xFFF6F8F7),
    onBackground = Color(0xFF161A19),
    surface = Color(0xFFF6F8F7),
    onSurface = Color(0xFF161A19),
    surfaceVariant = Color(0xFFDCE4E0),
    onSurfaceVariant = Color(0xFF56615C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F3F2),
    surfaceContainer = Color(0xFFEAEFED),
    surfaceContainerHigh = Color(0xFFE4EAE7),
    surfaceContainerHighest = Color(0xFFDEE4E1),
    outline = Color(0xFF75807B),
    outlineVariant = Color(0xFFC4CDC9),
)

/** Tabular figures so live numbers don't jiggle as digits change. */
val NumberStyle = TextStyle(fontFeatureSettings = "tnum")

private val AppTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", letterSpacing = (-1).sp),
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun BatteryLogTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark, ThemeMode.Black -> true
    }
    val context = LocalContext.current
    var scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    if (mode == ThemeMode.Black) {
        scheme = scheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = lerp(Color.Black, scheme.surfaceContainerLow, 0.5f),
            surfaceContainer = lerp(Color.Black, scheme.surfaceContainer, 0.75f),
            surfaceContainerHigh = lerp(Color.Black, scheme.surfaceContainerHigh, 0.8f),
            surfaceContainerHighest = lerp(Color.Black, scheme.surfaceContainerHighest, 0.85f),
        )
        if (!dynamicColor) scheme = Black
    }
    CompositionLocalProvider(LocalChartColors provides if (dark) DarkChart else LightChart) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
