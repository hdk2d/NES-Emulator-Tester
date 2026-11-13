package com.nesdeck.ui

import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesdeck.data.Btn
import com.nesdeck.data.Prefs

@Composable
fun TouchControls(
    prefs: Prefs,
    onDpad: (Int) -> Unit,
    onA: (Boolean) -> Unit,
    onB: (Boolean) -> Unit,
    onStart: (Boolean) -> Unit,
    onSelect: (Boolean) -> Unit,
    onMenu: () -> Unit
) {
    val view = LocalView.current
    val s = prefs.touchScale.value
    val alpha = prefs.touchOpacity.value
    val lefty = prefs.leftHanded.value
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val buzz: () -> Unit = {
        if (prefs.haptics.value) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onMenu)
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Text("Menú", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
        }

        DPad(
            Modifier
                .align(if (lefty) Alignment.BottomEnd else Alignment.BottomStart)
                .padding(bottom = 24.dp)
                .size((150 * s).dp),
            alpha, buzz, onDpad
        )

        Box(
            Modifier
                .align(if (lefty) Alignment.BottomStart else Alignment.BottomEnd)
                .padding(bottom = 24.dp)
                .size((170 * s).dp, (120 * s).dp)
        ) {
            ActionButton("B", Modifier.align(Alignment.BottomStart), (64 * s).dp, alpha, buzz, onB)
            ActionButton("A", Modifier.align(Alignment.TopEnd), (64 * s).dp, alpha, buzz, onA)
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (portrait) ((170 * s) + 40).dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Pill("Select", alpha, buzz, onSelect)
            Pill("Start", alpha, buzz, onStart)
        }
    }
}

private fun dirMask(pos: Offset, size: IntSize): Int {
    val dx = pos.x - size.width / 2f
    val dy = pos.y - size.height / 2f
    val t = size.width * 0.14f
    var m = 0
    if (dx > t) m = m or (1 shl Btn.RIGHT)
    if (dx < -t) m = m or (1 shl Btn.LEFT)
    if (dy > t) m = m or (1 shl Btn.DOWN)
    if (dy < -t) m = m or (1 shl Btn.UP)
    return m
}

@Composable
private fun DPad(modifier: Modifier, alpha: Float, buzz: () -> Unit, onChange: (Int) -> Unit) {
    var mask by remember { mutableIntStateOf(0) }
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var current = dirMask(down.position, size)
                mask = current
                onChange(current)
                buzz()
                down.consume()
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    val m = dirMask(ch.position, size)
                    if (m != current) {
                        current = m
                        mask = m
                        onChange(m)
                        if (m != 0) buzz()
                    }
                    ch.consume()
                }
                mask = 0
                onChange(0)
            }
        }
    ) {
        val w = this.size.width
        val t = w / 3f
        val r = CornerRadius(t * 0.28f)
        val base = Color.White.copy(alpha = alpha * 0.8f)
        val hi = Color.White.copy(alpha = minOf(1f, alpha + 0.4f))
        drawRoundRect(base, Offset(0f, t), Size(w, t), r)
        drawRoundRect(base, Offset(t, 0f), Size(t, w), r)
        if (mask and (1 shl Btn.UP) != 0) drawRoundRect(hi, Offset(t, 0f), Size(t, t), r)
        if (mask and (1 shl Btn.DOWN) != 0) drawRoundRect(hi, Offset(t, 2 * t), Size(t, t), r)
        if (mask and (1 shl Btn.LEFT) != 0) drawRoundRect(hi, Offset(0f, t), Size(t, t), r)
        if (mask and (1 shl Btn.RIGHT) != 0) drawRoundRect(hi, Offset(2 * t, t), Size(t, t), r)
    }
}

@Composable
private fun ActionButton(
    label: String,
    modifier: Modifier,
    size: Dp,
    alpha: Float,
    buzz: () -> Unit,
    onChange: (Boolean) -> Unit
) {
    var down by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (down) alpha * 0.6f else alpha * 0.25f))
            .border(2.dp, Color.White.copy(alpha = alpha), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false)
                    d.consume()
                    down = true
                    onChange(true)
                    buzz()
                    waitForUpOrCancellation()
                    down = false
                    onChange(false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = Color.White.copy(alpha = minOf(1f, alpha + 0.3f)),
            fontSize = 22.sp, fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun Pill(label: String, alpha: Float, buzz: () -> Unit, onChange: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = if (down) alpha * 0.6f else alpha * 0.25f))
            .border(2.dp, Color.White.copy(alpha = alpha), shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false)
                    d.consume()
                    down = true
                    onChange(true)
                    buzz()
                    waitForUpOrCancellation()
                    down = false
                    onChange(false)
                }
            }
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color.White.copy(alpha = minOf(1f, alpha + 0.3f)), fontSize = 13.sp)
    }
}
