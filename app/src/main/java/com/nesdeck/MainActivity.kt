package com.nesdeck

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nesdeck.core.Emulator
import com.nesdeck.core.InputHub
import com.nesdeck.data.Game
import com.nesdeck.data.Prefs
import com.nesdeck.ui.GameScreen
import com.nesdeck.ui.HomeScreen
import com.nesdeck.ui.NesDeckTheme
import com.nesdeck.ui.SettingsScreen

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class Play(val game: Game) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(applicationContext)
        setContent { NesDeckTheme { App(prefs) } }
    }

    // Las teclas y palancas del mando pasan primero por InputHub (juego o asignación de botones)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        InputHub.onKey?.invoke(event) == true || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        InputHub.onMotion?.invoke(ev) == true || super.dispatchGenericMotionEvent(ev)

    override fun onPause() {
        super.onPause()
        Emulator.current?.background = true
    }

    override fun onResume() {
        super.onResume()
        Emulator.current?.background = false
    }
}

@Composable
fun App(prefs: Prefs) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    when (val s = screen) {
        Screen.Home -> HomeScreen(prefs, onPlay = { screen = Screen.Play(it) }, onSettings = { screen = Screen.Settings })
        Screen.Settings -> SettingsScreen(prefs, onBack = { screen = Screen.Home })
        is Screen.Play -> GameScreen(s.game, prefs, onExit = { screen = Screen.Home })
    }
}
