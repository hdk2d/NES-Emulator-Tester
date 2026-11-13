package com.nesdeck.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nesdeck.core.Emulator
import com.nesdeck.core.InputHub
import com.nesdeck.data.Btn
import com.nesdeck.data.Game
import com.nesdeck.data.Prefs
import kotlin.math.abs

private class PadState {
    var keys = 0
    var stick = 0
    var dpad = 0
    var ab = 0
    var ss = 0
    fun pad() = keys or stick
    fun touch() = dpad or ab or ss
    fun reset() { keys = 0; stick = 0; dpad = 0; ab = 0; ss = 0 }
}

private fun Int.withBit(bit: Int, on: Boolean) = if (on) this or (1 shl bit) else this and (1 shl bit).inv()

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("No hay actividad")
}

@Composable
fun GameScreen(game: Game, prefs: Prefs, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val emu = remember {
        Emulator(ctx.applicationContext, prefs) { msg ->
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            onExit()
        }
    }
    var menu by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val uiOpen = menu || settings
    val uiOpenNow by rememberUpdatedState(uiOpen)
    val ps = remember { PadState() }

    DisposableEffect(Unit) {
        emu.start(game)
        onDispose { emu.stop() }
    }

    // Pantalla completa y pantalla encendida mientras se juega
    DisposableEffect(Unit) {
        val window = ctx.findActivity().window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }

    // Pausa cuando hay un menú abierto y suelta los botones pulsados
    LaunchedEffect(uiOpen) {
        emu.paused = uiOpen
        if (uiOpen) {
            ps.reset()
            emu.pad = 0
            emu.touch = 0
        }
    }

    // Mando físico: teclas, cruceta y palanca izquierda
    DisposableEffect(Unit) {
        InputHub.onKey = { e ->
            val btn = prefs.bindings[e.keyCode]
            when {
                e.keyCode == KeyEvent.KEYCODE_BUTTON_MODE -> {
                    if (e.action == KeyEvent.ACTION_UP) menu = !menu
                    true
                }
                uiOpenNow -> false
                btn != null -> {
                    if (e.action == KeyEvent.ACTION_DOWN) ps.keys = ps.keys.withBit(btn, true)
                    else if (e.action == KeyEvent.ACTION_UP) ps.keys = ps.keys.withBit(btn, false)
                    emu.pad = ps.pad()
                    true
                }
                else -> false
            }
        }
        InputHub.onMotion = { m ->
            if (!uiOpenNow &&
                (m.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
                m.action == MotionEvent.ACTION_MOVE
            ) {
                val hx = m.getAxisValue(MotionEvent.AXIS_HAT_X)
                val hy = m.getAxisValue(MotionEvent.AXIS_HAT_Y)
                val x = if (abs(hx) > 0.5f) hx else m.getAxisValue(MotionEvent.AXIS_X)
                val y = if (abs(hy) > 0.5f) hy else m.getAxisValue(MotionEvent.AXIS_Y)
                ps.stick = 0
                    .withBit(Btn.RIGHT, x > 0.5f)
                    .withBit(Btn.LEFT, x < -0.5f)
                    .withBit(Btn.DOWN, y > 0.5f)
                    .withBit(Btn.UP, y < -0.5f)
                emu.pad = ps.pad()
                true
            } else {
                false
            }
        }
        onDispose {
            InputHub.onKey = null
            InputHub.onMotion = null
        }
    }

    BackHandler(enabled = !settings) { menu = !menu }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                SurfaceView(c).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { emu.surface = h }
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) { emu.surface = h }
                        override fun surfaceDestroyed(h: SurfaceHolder) { emu.surface = null }
                    })
                }
            }
        )

        if (prefs.touchEnabled.value && !uiOpen) {
            TouchControls(
                prefs = prefs,
                onDpad = { ps.dpad = it; emu.touch = ps.touch() },
                onA = { ps.ab = ps.ab.withBit(Btn.A, it); emu.touch = ps.touch() },
                onB = { ps.ab = ps.ab.withBit(Btn.B, it); emu.touch = ps.touch() },
                onStart = { ps.ss = ps.ss.withBit(Btn.START, it); emu.touch = ps.touch() },
                onSelect = { ps.ss = ps.ss.withBit(Btn.SELECT, it); emu.touch = ps.touch() },
                onMenu = { menu = true }
            )
        }

        if (menu && !settings) {
            PauseMenu(
                onResume = { menu = false },
                onSave = { emu.request(Emulator.ACT_SAVE); menu = false },
                onLoad = { emu.request(Emulator.ACT_LOAD); menu = false },
                onReset = { emu.request(Emulator.ACT_RESET); menu = false },
                onSettings = { settings = true },
                onExit = onExit
            )
        }

        if (settings) SettingsScreen(prefs) { settings = false }
    }
}

@Composable
private fun PauseMenu(
    onResume: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onReset: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.width(300.dp).verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
            Text("En pausa", color = Sw.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            MenuItem("Continuar", Icons.Filled.PlayArrow, onResume, Modifier.focusRequester(first))
            MenuItem("Guardar partida rápida", Icons.Filled.Save, onSave)
            MenuItem("Cargar partida rápida", Icons.Filled.FolderOpen, onLoad)
            MenuItem("Reiniciar", Icons.Filled.Refresh, onReset)
            MenuItem("Ajustes", Icons.Filled.Settings, onSettings)
            MenuItem("Salir al inicio", Icons.Filled.Close, onExit)
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(if (focused) Color(0xFF555555) else Sw.Panel)
            .then(if (focused) Modifier.border(3.dp, Sw.Cyan, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Sw.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = Sw.Text, fontSize = 16.sp)
    }
}
