package com.example.ui.theme

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

private val DarkColorScheme =
  darkColorScheme(
    primary = MinimForestPrimaryDark,
    secondary = MinimForestPrimaryDark.copy(alpha = 0.5f),
    tertiary = MinimSecondaryGrass,
    background = MinimBgDark,
    surface = MinimSurfaceDark,
    onPrimary = Color(0xFF112905),
    onSecondary = MinimTextDark,
    onBackground = MinimTextDark,
    onSurface = MinimTextDark
  )

private val LightColorScheme =
  lightColorScheme(
    primary = MinimForestPrimary,
    secondary = MinimForestPrimaryLight,
    tertiary = MinimSecondaryGrass,
    background = MinimBgLight,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = MinimTextColor,
    onBackground = MinimTextColor,
    onSurface = MinimTextColor,
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
