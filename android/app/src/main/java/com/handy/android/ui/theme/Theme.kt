package com.handy.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.handy.android.settings.ThemeMode

// Handy's palette (from the desktop app): pink logo, warm neutrals, moss accent.
private val Pink = Color(0xFFDA5893)
private val PinkLight = Color(0xFFFAA2CA)
private val PinkDark = Color(0xFFF28CBB)
private val Moss = Color(0xFF2F7D5B)
private val MossDark = Color(0xFF86C9A4)

private val Light = lightColorScheme(
    primary = Pink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E6),
    onPrimaryContainer = Color(0xFF5C0F34),
    secondary = Color(0xFF6F5A63),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF9DCE6),
    onSecondaryContainer = Color(0xFF28171F),
    tertiary = Moss,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDEEDB),
    onTertiaryContainer = Color(0xFF002114),
    background = Color(0xFFFBFBFB),
    onBackground = Color(0xFF0F0F0F),
    surface = Color(0xFFFBFBFB),
    onSurface = Color(0xFF0F0F0F),
    surfaceVariant = Color(0xFFF1E4E8),
    onSurfaceVariant = Color(0xFF524348),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F3F4),
    surfaceContainer = Color(0xFFF2EDEF),
    surfaceContainerHigh = Color(0xFFECE6E8),
    surfaceContainerHighest = Color(0xFFE5DEE1),
    outline = Color(0xFF847378),
    outlineVariant = Color(0xFFD6C2C8),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val Dark = darkColorScheme(
    primary = PinkDark,
    onPrimary = Color(0xFF5C0F34),
    primaryContainer = Color(0xFF7A2950),
    onPrimaryContainer = Color(0xFFFFD9E6),
    secondary = Color(0xFFDCBFCA),
    onSecondary = Color(0xFF3E2B33),
    secondaryContainer = Color(0xFF564049),
    onSecondaryContainer = Color(0xFFF9DCE6),
    tertiary = MossDark,
    onTertiary = Color(0xFF003824),
    tertiaryContainer = Color(0xFF1D5139),
    onTertiaryContainer = Color(0xFFCDEEDB),
    background = Color(0xFF2C2B29),
    onBackground = Color(0xFFFBFBFB),
    surface = Color(0xFF2C2B29),
    onSurface = Color(0xFFFBFBFB),
    surfaceVariant = Color(0xFF4A4245),
    onSurfaceVariant = Color(0xFFD6C9CD),
    surfaceContainerLowest = Color(0xFF1F1E1D),
    surfaceContainerLow = Color(0xFF282725),
    surfaceContainer = Color(0xFF32302E),
    surfaceContainerHigh = Color(0xFF3B3937),
    surfaceContainerHighest = Color(0xFF464341),
    outline = Color(0xFF9F8C92),
    outlineVariant = Color(0xFF524348),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val HandyTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    )
}

val HandyPink = Pink
val HandyPinkLight = PinkLight

@Composable
fun HandyTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = HandyTypography, content = content)
}
