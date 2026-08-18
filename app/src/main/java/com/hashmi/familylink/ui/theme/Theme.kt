package com.hashmi.familylink.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = TealDeep,
    onPrimary = Color.White,
    primaryContainer = TealSoft,
    onPrimaryContainer = Color(0xFF063333),
    secondary = Coral,
    onSecondary = Color.White,
    secondaryContainer = CoralSoft,
    onSecondaryContainer = Color(0xFF3B1603),
    tertiary = Color(0xFF4C5C9B),
    background = Cream,
    onBackground = Ink,
    surface = CreamCard,
    onSurface = Ink,
    surfaceVariant = Mist,
    onSurfaceVariant = Color(0xFF3D4F4B),
    outline = Color(0xFF6D7F7A),
    error = Danger
)

private val DarkColorScheme = darkColorScheme(
    primary = TealBright,
    onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF0C4F4C),
    onPrimaryContainer = TealSoft,
    secondary = CoralNight,
    onSecondary = Color(0xFF4A1C04),
    secondaryContainer = Color(0xFF6A3110),
    onSecondaryContainer = CoralSoft,
    tertiary = Color(0xFFB4C0FF),
    background = Night,
    onBackground = Color(0xFFE6F0EC),
    surface = NightCard,
    onSurface = Color(0xFFE6F0EC),
    surfaceVariant = NightMist,
    onSurfaceVariant = Color(0xFFC5D4CF),
    outline = Color(0xFF8A9C97),
    error = Color(0xFFFFB4AB)
)

@Composable
fun FamilyLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
