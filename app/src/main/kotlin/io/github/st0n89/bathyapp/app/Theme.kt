package io.github.st0n89.bathyapp.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Navy = Color(0xFF0A2A5E)
private val Aqua = Color(0xFF3FA9D9)

@Composable
fun BathyAppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = Aqua,
            onPrimary = Color.Black,
            surface = Color(0xFF10203A),
            background = Color(0xFF0A1526),
        )
    } else {
        lightColorScheme(
            primary = Navy,
            surface = Color(0xFFEFF4FB),
            background = Color.White,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
