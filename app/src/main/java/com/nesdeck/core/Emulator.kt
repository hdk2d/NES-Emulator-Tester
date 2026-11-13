package com.nesdeck.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceHolder
import android.widget.Toast
import com.nesdeck.data.Game
import com.nesdeck.data.Prefs
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * Hilo de emulación: ejecuta un fotograma, envía el audio (la escritura bloqueante
 * marca el ritmo) y dibuja en la superficie según los ajustes de gráficos.
 */
class Emulator(
    private val ctx: Context,
    private val prefs: Prefs,
    private val onError: (String) -> Unit
) {
    companion object {
        @Volatile var current: Emulator? = null
        const val MAX_W = 512
        const val MAX_H = 480
        const val MAX_AUDIO = 8192
        const val ACT_SAVE = 1
        const val ACT_LOAD = 2
        const val ACT_RESET = 3
    }

    @Volatile var touch = 0
    @Volatile var pad = 0
    @Volatile var paused = false
    @Volatile var background = false
    @Volatile var surface: SurfaceHolder? = null
    @Volatile private var running = false
    @Volatile private var action = 0
    private var thread: Thread? = null
    private val ui = Handler(Looper.getMainLooper())

    private val paint = Paint()
    private val scanPaint = Paint().apply { color = Color.BLACK }
    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 36f; isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val srcRect = Rect()
    private val dstRect = RectF()

    fun start(game: Game) {
        current = this
        running = true
        thread = Thread({ loop(game) }, "nes-emu").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(2500)
        thread = null
        if (current === this) current = null
    }

    fun request(a: Int) { action = a }

    private fun toast(msg: String) { ui.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } }
    private fun fail(msg: String) { ui.post { onError(msg) } }

    private fun loop(game: Game) {
        val saves = File(ctx.filesDir, "saves").apply { mkdirs() }
        val system = File(ctx.filesDir, "system").apply { mkdirs() }
        val srm = File(saves, "${game.id}.srm")
        val state = File(saves, "${game.id}.state")
        val video = ByteBuffer.allocateDirect(MAX_W * MAX_H * 4).order(ByteOrder.nativeOrder())
        val audio = ByteBuffer.allocateDirect(MAX_AUDIO * 4).order(ByteOrder.nativeOrder())
        val shorts = audio.asShortBuffer()
        var track: AudioTrack? = null
        var loaded = false

        fun flushSram() { Native.getSram()?.let { if (it.isNotEmpty()) srm.writeBytes(it) } }

        try {
            if (!Native.ok) { fail("Falta el núcleo de emulación (libfceumm_core.so). Lee el README."); return }
            val core = File(ctx.applicationInfo.nativeLibraryDir, "libfceumm_core.so").absolutePath
            val romFile = prefs.romFile(game)
            if (!romFile.isFile || romFile.length() <= 16L) {
                fail("El archivo ROM no existe o está incompleto.")
                return
            }
            loaded = Native.load(core, system.absolutePath, saves.absolutePath, romFile.absolutePath, romFile.readBytes(), video, audio)
            if (!loaded) { fail("El núcleo no pudo cargar esta ROM."); return }
            if (srm.exists()) Native.setSram(srm.readBytes())

            val timing = Native.timing()
            val rate = if (timing[1] >= 8000.0) timing[1].toInt() else 48000
            val t = buildTrack(rate).also { track = it }
            t.play()

            val out = IntArray(3)
            var bmp: Bitmap? = null
            var lastSave = SystemClock.uptimeMillis()
            var fpsStart = lastSave
            var fpsCount = 0
            var fps = 0

            while (running) {
                val a = action
                if (a != 0) {
                    action = 0
                    when (a) {
                        ACT_SAVE -> {
                            val data = Native.saveState()
                            if (data != null) { state.writeBytes(data); toast("Partida guardada") }
                            else toast("No se pudo guardar")
                        }
                        ACT_LOAD -> {
                            if (state.exists() && Native.loadState(state.readBytes())) toast("Partida cargada")
                            else toast("No hay partida guardada")
                        }
                        ACT_RESET -> { Native.reset(); toast("Juego reiniciado") }
                    }
                }
                if (paused || background) {
                    if (t.playState == AudioTrack.PLAYSTATE_PLAYING) { t.pause(); flushSram() }
                    Thread.sleep(40)
                    continue
                }
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()

                Native.runFrame(touch or pad, out)

                val frames = out[0]
                if (frames > 0) {
                    val vol = if (prefs.mute.value) 0f else prefs.volume.value
                    if (vol != 1f) for (i in 0 until frames * 2) shorts.put(i, (shorts.get(i) * vol).toInt().toShort())
                    audio.position(0)
                    t.write(audio, frames * 4, AudioTrack.WRITE_BLOCKING)
                } else {
                    Thread.sleep(16)
                }

                val w = out[1]
                val h = out[2]
                if (w > 0 && h > 0) {
                    var b = bmp
                    if (b == null || b.width != w || b.height != h) {
                        b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp = b
                    }
                    video.rewind()
                    b.copyPixelsFromBuffer(video)
                    draw(b, if (prefs.showFps.value) fps else -1)
                }

                fpsCount++
                val now = SystemClock.uptimeMillis()
                if (now - fpsStart >= 1000) { fps = fpsCount; fpsCount = 0; fpsStart = now }
                if (now - lastSave >= 15_000) { flushSram(); lastSave = now }
            }
        } catch (e: Exception) {
            fail("Error del emulador: ${e.message}")
        } finally {
            if (loaded) {
                try { flushSram(); Native.unload() } catch (_: Throwable) {}
            }
            track?.release()
        }
    }

    private fun buildTrack(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val mult = when (prefs.latency.value) { 0 -> 1; 1 -> 2; else -> 4 }
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min * mult, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (prefs.latency.value == 0) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        return builder.build()
    }

    private fun draw(bmp: Bitmap, fps: Int) {
        val holder = surface ?: return
        val c = try { holder.lockHardwareCanvas() } catch (e: Exception) { null } ?: return
        try {
            c.drawColor(Color.BLACK)
            val crop = if (prefs.cropOverscan.value && bmp.height > 32) 8 else 0
            srcRect.set(0, crop, bmp.width, bmp.height - crop)
            val sw = c.width.toFloat()
            val sh = c.height.toFloat()
            val srcW = srcRect.width().toFloat()
            val srcH = srcRect.height().toFloat()

            if (prefs.aspect.value == 2) {
                dstRect.set(0f, 0f, sw, sh)
            } else {
                val par = if (prefs.aspect.value == 0) 1.25f else 1f   // 4:3 de TV o píxeles cuadrados
                var scale = minOf(sw / (srcW * par), sh / srcH)
                if (prefs.integerScale.value && scale >= 1f) scale = floor(scale)
                val dw = srcW * par * scale
                val dh = srcH * scale
                val left = (sw - dw) / 2f
                val top = if (sh > sw) 0f else (sh - dh) / 2f          // en vertical, el juego va arriba
                dstRect.set(left, top, left + dw, top + dh)
            }

            paint.isFilterBitmap = prefs.smooth.value
            c.drawBitmap(bmp, srcRect, dstRect, paint)

            if (prefs.scanlines.value) {
                scanPaint.alpha = (prefs.scanIntensity.value * 255).toInt().coerceIn(0, 255)
                val lh = dstRect.height() / srcH
                var y = dstRect.top
                for (i in 0 until srcH.toInt()) {
                    c.drawRect(dstRect.left, y + lh * 0.55f, dstRect.right, y + lh, scanPaint)
                    y += lh
                }
            }
            if (fps >= 0) c.drawText("$fps FPS", 24f, 64f, textPaint)
        } finally {
            holder.unlockCanvasAndPost(c)
        }
    }
}
