package com.nesdeck.ui

import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesdeck.data.Game
import com.nesdeck.data.Prefs
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun clock(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

private fun batteryLevel(ctx: Context): Int =
    (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

private fun importRom(ctx: Context, prefs: Prefs, uri: Uri) {
    val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && i >= 0) c.getString(i) else null
    } ?: "Juego.nes"
    val bytes = try {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }
    val ok = bytes != null && prefs.addGame(name.substringBeforeLast('.'), bytes)
    if (!ok) Toast.makeText(ctx, "Archivo no válido: se espera una ROM .nes", Toast.LENGTH_LONG).show()
}

@Composable
fun HomeScreen(prefs: Prefs, onPlay: (Game) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    var time by remember { mutableStateOf(clock()) }
    var battery by remember { mutableIntStateOf(batteryLevel(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            time = clock()
            battery = batteryLevel(ctx)
        }
    }
    var toDelete by remember { mutableStateOf<Game?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importRom(ctx, prefs, uri)
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(prefs.games.size) {
        if (prefs.games.isNotEmpty()) runCatching { firstFocus.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Sw.BgTop, Sw.Bg)))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Sw.Red), contentAlignment = Alignment.Center) {
                Text("N", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("$battery %", color = Sw.Dim, fontSize = 14.sp)
            Spacer(Modifier.width(16.dp))
            Text(time, color = Sw.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.weight(1f))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Top
        ) {
            item {
                TileFrame(
                    title = "Añadir juego",
                    onClick = { picker.launch(arrayOf("*/*")) },
                    onLong = {},
                    fill = Brush.linearGradient(listOf(Color(0xFF505050), Color(0xFF383838)))
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Sw.Text, modifier = Modifier.size(48.dp))
                }
            }
            itemsIndexed(prefs.games, key = { _, g -> g.id }) { index, g ->
                val hue = ((g.title.hashCode() and 0xFFFF) % 360).toFloat()
                TileFrame(
                    title = g.title,
                    onClick = { onPlay(g) },
                    onLong = { toDelete = g },
                    fill = Brush.linearGradient(
                        listOf(Color.hsv(hue, 0.55f, 0.85f), Color.hsv((hue + 40f) % 360f, 0.7f, 0.4f))
                    ),
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier
                ) {
                    Text(initials(g.title), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (prefs.games.isEmpty()) {
            Text(
                "Aún no hay juegos. Pulsa «Añadir juego» y elige una ROM .nes que tengas.",
                color = Sw.Dim,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, start = 32.dp, end = 32.dp)
            )
        }

        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth().padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            RoundButton(Icons.Filled.Settings, "Ajustes", onSettings)
        }
    }

    toDelete?.let { g ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Quitar «${g.title}»") },
            text = { Text("Se borrarán la ROM copiada y sus partidas guardadas de esta app.") },
            confirmButton = {
                TextButton(onClick = { prefs.removeGame(g); toDelete = null }) { Text("Quitar") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } }
        )
    }
}

private fun initials(title: String): String =
    title.split(' ', '_', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileFrame(
    title: String,
    onClick: () -> Unit,
    onLong: () -> Unit,
    fill: Brush,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, label = "tileScale")
    val glow by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "glowAlpha"
    )
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.width(150.dp).scale(scale)) {
        Box(
            modifier
                .size(150.dp)
                .clip(shape)
                .background(fill)
                .then(if (focused) Modifier.border(4.dp, Sw.Cyan.copy(alpha = glow), shape) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .combinedClickable(onClick = onClick, onLongClick = onLong),
            contentAlignment = Alignment.Center
        ) { content() }
        Text(
            title,
            color = if (focused) Sw.Text else Sw.Dim,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, label = "btnScale")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.scale(scale)) {
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Sw.Panel)
                .then(if (focused) Modifier.border(3.dp, Sw.Cyan, CircleShape) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .combinedClickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = Sw.Text, modifier = Modifier.size(28.dp))
        }
        Text(label, color = if (focused) Sw.Text else Sw.Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
