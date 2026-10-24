package `fun`.kirari.hanako.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import `fun`.kirari.hanako.data.ThemeColorStyle
import `fun`.kirari.hanako.data.ThemeMode

private val LightColorScheme = lightColorScheme(
    primary = HanakoLightPrimary,
    onPrimary = HanakoLightOnPrimary,
    primaryContainer = HanakoLightPrimaryContainer,
    onPrimaryContainer = HanakoLightOnPrimaryContainer,
    secondaryContainer = HanakoLightSecondaryContainer,
    surface = HanakoLightSurface,
    surfaceContainer = HanakoLightSurfaceContainer,
    surfaceContainerLow = HanakoLightSurfaceContainerLow
)

private val DarkColorScheme = darkColorScheme(
    primary = HanakoDarkPrimary,
    onPrimary = HanakoDarkOnPrimary,
    primaryContainer = HanakoDarkPrimaryContainer,
    onPrimaryContainer = HanakoDarkOnPrimaryContainer,
    secondaryContainer = HanakoDarkSecondaryContainer,
    surface = HanakoDarkSurface,
    surfaceContainer = HanakoDarkSurfaceContainer,
    surfaceContainerLow = HanakoDarkSurfaceContainerLow
)

private fun lightColorSchemeFor(style: ThemeColorStyle) = when (style) {
    ThemeColorStyle.DEFAULT -> LightColorScheme
    ThemeColorStyle.PINK -> lightColorScheme(
        primary = PinkLightPrimary,
        onPrimary = PinkLightOnPrimary,
        primaryContainer = PinkLightPrimaryContainer,
        onPrimaryContainer = PinkLightOnPrimaryContainer,
        secondaryContainer = PinkLightSecondaryContainer,
        surface = PinkLightSurface,
        surfaceContainer = PinkLightSurfaceContainer,
        surfaceContainerLow = PinkLightSurfaceContainerLow
    )
    ThemeColorStyle.FOREST -> lightColorScheme(
        primary = ForestLightPrimary,
        onPrimary = ForestLightOnPrimary,
        primaryContainer = ForestLightPrimaryContainer,
        onPrimaryContainer = ForestLightOnPrimaryContainer,
        secondaryContainer = ForestLightSecondaryContainer,
        surface = ForestLightSurface,
        surfaceContainer = ForestLightSurfaceContainer,
        surfaceContainerLow = ForestLightSurfaceContainerLow
    )
    ThemeColorStyle.MIDNIGHT -> lightColorScheme(
        primary = MidnightLightPrimary,
        onPrimary = MidnightLightOnPrimary,
        primaryContainer = MidnightLightPrimaryContainer,
        onPrimaryContainer = MidnightLightOnPrimaryContainer,
        secondaryContainer = MidnightLightSecondaryContainer,
        surface = MidnightLightSurface,
        surfaceContainer = MidnightLightSurfaceContainer,
        surfaceContainerLow = MidnightLightSurfaceContainerLow
    )
}

private fun darkColorSchemeFor(style: ThemeColorStyle) = when (style) {
    ThemeColorStyle.DEFAULT -> DarkColorScheme
    ThemeColorStyle.PINK -> darkColorScheme(
        primary = PinkDarkPrimary,
        onPrimary = PinkDarkOnPrimary,
        primaryContainer = PinkDarkPrimaryContainer,
        onPrimaryContainer = PinkDarkOnPrimaryContainer,
        secondaryContainer = PinkDarkSecondaryContainer,
        surface = PinkDarkSurface,
        surfaceContainer = PinkDarkSurfaceContainer,
        surfaceContainerLow = PinkDarkSurfaceContainerLow
    )
    ThemeColorStyle.FOREST -> darkColorScheme(
        primary = ForestDarkPrimary,
        onPrimary = ForestDarkOnPrimary,
        primaryContainer = ForestDarkPrimaryContainer,
        onPrimaryContainer = ForestDarkOnPrimaryContainer,
        secondaryContainer = ForestDarkSecondaryContainer,
        surface = ForestDarkSurface,
        surfaceContainer = ForestDarkSurfaceContainer,
        surfaceContainerLow = ForestDarkSurfaceContainerLow
    )
    ThemeColorStyle.MIDNIGHT -> darkColorScheme(
        primary = MidnightDarkPrimary,
        onPrimary = MidnightDarkOnPrimary,
        primaryContainer = MidnightDarkPrimaryContainer,
        onPrimaryContainer = MidnightDarkOnPrimaryContainer,
        secondaryContainer = MidnightDarkSecondaryContainer,
        surface = MidnightDarkSurface,
        surfaceContainer = MidnightDarkSurfaceContainer,
        surfaceContainerLow = MidnightDarkSurfaceContainerLow
    )
}

/**
 * Hanako 全局主题。
 *
 * @param themeMode 亮暗模式（跟随系统 / 白天 / 黑夜）。
 * @param colorStyle 配色风格（默认 / 粉黛 / 森林 / 星空）。
 * @param dynamicColor 默认风格下是否使用系统动态取色（Android 12+）。
 */
@Composable
fun HanakoTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    colorStyle: ThemeColorStyle = ThemeColorStyle.DEFAULT,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        // 仅默认风格支持系统动态取色；自定义配色风格强制使用预设色板。
        dynamicColor && colorStyle == ThemeColorStyle.DEFAULT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> darkColorSchemeFor(colorStyle)
        else -> lightColorSchemeFor(colorStyle)
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                window.statusBarColor = colorScheme.surface.toArgb()
                window.navigationBarColor = colorScheme.surface.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
