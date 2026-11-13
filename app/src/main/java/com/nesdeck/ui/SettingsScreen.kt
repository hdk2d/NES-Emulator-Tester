package com.nesdeck.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesdeck.core.InputHub
import com.nesdeck.data.BoolPref
import com.nesdeck.data.Btn
import com.nesdeck.data.FloatPref
import com.nesdeck.data.IntPref
import com.nesdeck.data.Prefs

@Composable
fun SettingsScreen(prefs: Prefs, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val tabs = listOf("Gráficos", "Sonido", "Táctil", "Mando", "Acerca de")
    var tab by remember { mutableIntStateOf(0) }

    Row(
        Modifier
            .fillMaxSize()
            .background(Sw.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(Modifier.width(128.dp).fillMaxHeight().background(Sw.BgTop).padding(vertical = 16.dp)) {
            Text(
                "Ajustes", color = Sw.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            tabs.forEachIndexed { i, t -> NavItem(t, tab == i) { tab = i } }
            Spacer(Modifier.weight(1f))
            NavItem("‹ Volver", false, onBack)
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            when (tab) {
                0 -> GraphicsTab(prefs)
                1 -> SoundTab(prefs)
                2 -> TouchTab(prefs)
                3 -> ControllerTab(prefs)
                else -> AboutTab()
            }
        }
    }
}

@Composable
private fun GraphicsTab(prefs: Prefs) {
    Title("Gráficos")
    ChoiceRow("Proporción", listOf("TV 4:3", "Píxeles cuadrados", "Pantalla completa"), prefs.aspect)
    ToggleRow("Escala entera", prefs.integerScale, "Mantiene todos los píxeles del mismo tamaño")
    ToggleRow("Suavizado", prefs.smooth, "Filtra la imagen al ampliarla")
    ToggleRow("Líneas de escaneo", prefs.scanlines)
    if (prefs.scanlines.value) {
        SliderRow("Intensidad", prefs.scanIntensity, 0.1f..0.8f) { "${(it * 100).toInt()} %" }
    }
    ToggleRow("Recortar bordes", prefs.cropOverscan, "Oculta 8 líneas arriba y abajo, como en una tele antigua")
    ToggleRow("Mostrar FPS", prefs.showFps)
}

@Composable
private fun SoundTab(prefs: Prefs) {
    Title("Sonido")
    ToggleRow("Silenciar", prefs.mute)
    SliderRow("Volumen", prefs.volume, 0f..1f) { "${(it * 100).toInt()} %" }
    ChoiceRow(
        "Latencia de audio", listOf("Baja", "Normal", "Alta"), prefs.latency,
        "Si oyes cortes, sube la latencia. Se aplica al abrir un juego."
    )
}

@Composable
private fun TouchTab(prefs: Prefs) {
    Title("Controles táctiles")
    ToggleRow("Mostrar controles", prefs.touchEnabled)
    SliderRow("Tamaño", prefs.touchScale, 0.7f..1.5f) { "${(it * 100).toInt()} %" }
    SliderRow("Opacidad", prefs.touchOpacity, 0.2f..1f) { "${(it * 100).toInt()} %" }
    ToggleRow("Vibración", prefs.haptics)
    ToggleRow("Modo zurdo", prefs.leftHanded, "Cruceta a la derecha; A y B a la izquierda")
}

@Composable
private fun ControllerTab(prefs: Prefs) {
    var capturing by remember { mutableStateOf<Int?>(null) }
    Title("Mando físico")
    Text(
        "Toca un botón de la lista y pulsa la tecla del mando que quieras asignarle.",
        color = Sw.Dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)
    )
    Btn.all.forEach { b ->
        val key = prefs.keyFor(b)
        BindRow(Btn.label(b), key?.let { keyName(it) } ?: "Sin asignar") { capturing = b }
    }
    TextButton(onClick = { prefs.resetBindings() }) { Text("Restablecer valores") }

    val cap = capturing
    if (cap != null) {
        DisposableEffect(cap) {
            val previous = InputHub.onKey
            InputHub.onKey = { e ->
                if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                    false
                } else {
                    if (e.action == KeyEvent.ACTION_DOWN) {
                        prefs.bind(cap, e.keyCode)
                        capturing = null
                    }
                    true
                }
            }
            onDispose { InputHub.onKey = previous }
        }
        AlertDialog(
            onDismissRequest = { capturing = null },
            title = { Text("Asignar «${Btn.label(cap)}»") },
            text = { Text("Pulsa ahora un botón del mando.") },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { capturing = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun AboutTab() {
    Title("Acerca de")
    Text("NesDeck 0.1", color = Sw.Text, fontSize = 15.sp)
    Text(
        "Núcleo de emulación: FCEUmm (libretro), licencia GPL-2.0.",
        color = Sw.Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)
    )
    Text(
        "La app no incluye juegos. Carga solo ROM que tengas derecho a usar.",
        color = Sw.Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)
    )
}

private fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

@Composable
private fun Title(text: String) {
    Text(text, color = Sw.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Sw.Panel else Color.Transparent)
            .then(if (focused) Modifier.border(2.dp, Sw.Cyan) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(label, color = if (selected) Sw.Text else Sw.Dim, fontSize = 15.sp)
    }
}

@Composable
private fun ToggleRow(title: String, pref: BoolPref, hint: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Sw.Text, fontSize = 15.sp)
            if (hint != null) Text(hint, color = Sw.Dim, fontSize = 12.sp)
        }
        Switch(checked = pref.value, onCheckedChange = { pref.value = it })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(title: String, options: List<String>, pref: IntPref, hint: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, color = Sw.Text, fontSize = 15.sp)
        if (hint != null) Text(hint, color = Sw.Dim, fontSize = 12.sp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            options.forEachIndexed { i, o -> Chip(o, pref.value == i) { pref.value = i } }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) Sw.Cyan else Sw.Panel)
            .then(if (focused) Modifier.border(2.dp, Sw.Text, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (selected) Color.Black else Sw.Text, fontSize = 13.sp)
    }
}

@Composable
private fun SliderRow(title: String, pref: FloatPref, range: ClosedFloatingPointRange<Float>, format: (Float) -> String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row {
            Text(title, color = Sw.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(format(pref.value), color = Sw.Dim, fontSize = 13.sp)
        }
        Slider(value = pref.value, onValueChange = { pref.value = it }, valueRange = range)
    }
}

@Composable
private fun BindRow(label: String, key: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape)
            .background(Sw.Panel)
            .then(if (focused) Modifier.border(2.dp, Sw.Cyan, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Sw.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(key, color = Sw.Dim, fontSize = 13.sp)
    }
}
