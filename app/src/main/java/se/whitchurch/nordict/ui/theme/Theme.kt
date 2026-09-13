package se.whitchurch.nordict.ui.theme

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E5F96),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E4F7),
    onPrimaryContainer = Color(0xFF103A5F),
    inversePrimary = Color(0xFFA7C7EB),
    secondary = Color(0xFF55616E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E3F0),
    onSecondaryContainer = Color(0xFF121F2C),
    tertiary = Color(0xFF9A5210),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCBC),
    onTertiaryContainer = Color(0xFF371400),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FC),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2E8),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC3C6CE),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2E3135),
    inverseOnSurface = Color(0xFFF0F1F6),
    surfaceTint = Color(0xFF2E5F96),
    surfaceDim = Color(0xFFD8DADC),
    surfaceBright = Color(0xFFF8F9FC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3F6),
    surfaceContainer = Color(0xFFECEEF1),
    surfaceContainerHigh = Color(0xFFE7E8EB),
    surfaceContainerHighest = Color(0xFFE1E3E6)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA7C7EB),
    onPrimary = Color(0xFF0A2C4D),
    primaryContainer = Color(0xFF1E4668),
    onPrimaryContainer = Color(0xFFD7E4F7),
    inversePrimary = Color(0xFF2E5F96),
    secondary = Color(0xFFBCC7D5),
    onSecondary = Color(0xFF263341),
    secondaryContainer = Color(0xFF3C4957),
    onSecondaryContainer = Color(0xFFD9E3F0),
    tertiary = Color(0xFFF6BD7E),
    onTertiary = Color(0xFF5C2A00),
    tertiaryContainer = Color(0xFF7A3D08),
    onTertiaryContainer = Color(0xFFFFDCBC),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF111419),
    onBackground = Color(0xFFE1E2E9),
    surface = Color(0xFF111419),
    onSurface = Color(0xFFE1E2E9),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE1E2E9),
    inverseOnSurface = Color(0xFF2E3135),
    surfaceTint = Color(0xFFA7C7EB),
    surfaceDim = Color(0xFF111419),
    surfaceBright = Color(0xFF373A3F),
    surfaceContainerLowest = Color(0xFF0C0F14),
    surfaceContainerLow = Color(0xFF191C21),
    surfaceContainer = Color(0xFF1D2025),
    surfaceContainerHigh = Color(0xFF282A30),
    surfaceContainerHighest = Color(0xFF33353B)
)

/** Material 3 theme following the current system dark/light setting, with
 *  dynamic wallpaper-based color on Android 12+.
 */
@Composable
fun NordictTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}