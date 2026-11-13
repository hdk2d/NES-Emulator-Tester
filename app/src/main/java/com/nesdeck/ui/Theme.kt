package com.nesdeck.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Paleta inspirada en el menú de la Switch: fondo grafito, selección cian, acento rojo neón. */
object Sw {
    val Bg = Color(0xFF2B2B2B)
    val BgTop = Color(0xFF3A3A3A)
    val Panel = Color(0xFF444444)
    val Cyan = Color(0xFF00C3E3)
    val Red = Color(0xFFFF4554)
    val Text = Color(0xFFEDEDED)
    val Dim = Color(0xFF9A9A9A)
}

@Composable
fun NesDeckTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Sw.Cyan,
            secondary = Sw.Red,
            background = Sw.Bg,
            surface = Sw.Panel,
            onSurface = Sw.Text,
            onBackground = Sw.Text
        ),
        content = content
    )
}
