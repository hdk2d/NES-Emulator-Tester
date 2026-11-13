package com.nesdeck.data

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Identificadores de botón = ids del joypad de libretro (bit n de la máscara). */
object Btn {
    const val B = 0
    const val SELECT = 2
    const val START = 3
    const val UP = 4
    const val DOWN = 5
    const val LEFT = 6
    const val RIGHT = 7
    const val A = 8
    val all = listOf(UP, DOWN, LEFT, RIGHT, A, B, START, SELECT)
    fun label(id: Int) = when (id) {
        A -> "A"; B -> "B"; START -> "Start"; SELECT -> "Select"
        UP -> "Arriba"; DOWN -> "Abajo"; LEFT -> "Izquierda"; RIGHT -> "Derecha"
        else -> "?"
    }
}

data class Game(val id: String, val title: String)

class BoolPref(private val sp: SharedPreferences, private val key: String, def: Boolean) {
    private val s = mutableStateOf(sp.getBoolean(key, def))
    var value: Boolean
        get() = s.value
        set(v) { s.value = v; sp.edit().putBoolean(key, v).apply() }
}

class IntPref(private val sp: SharedPreferences, private val key: String, def: Int) {
    private val s = mutableStateOf(sp.getInt(key, def))
    var value: Int
        get() = s.value
        set(v) { s.value = v; sp.edit().putInt(key, v).apply() }
}

class FloatPref(private val sp: SharedPreferences, private val key: String, def: Float) {
    private val s = mutableStateOf(sp.getFloat(key, def))
    var value: Float
        get() = s.value
        set(v) { s.value = v; sp.edit().putFloat(key, v).apply() }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("nesdeck", Context.MODE_PRIVATE)
    private val romDir = File(context.filesDir, "roms")
    private val savesDir = File(context.filesDir, "saves")

    // Gráficos
    val aspect = IntPref(sp, "aspect", 0)            // 0 = TV 4:3, 1 = píxeles cuadrados, 2 = estirar
    val integerScale = BoolPref(sp, "integer", false)
    val smooth = BoolPref(sp, "smooth", false)
    val scanlines = BoolPref(sp, "scanlines", false)
    val scanIntensity = FloatPref(sp, "scanInt", 0.35f)
    val cropOverscan = BoolPref(sp, "crop", false)
    val showFps = BoolPref(sp, "fps", false)

    // Sonido
    val volume = FloatPref(sp, "volume", 1f)
    val mute = BoolPref(sp, "mute", false)
    val latency = IntPref(sp, "latency", 1)          // 0 = baja, 1 = normal, 2 = alta

    // Controles táctiles
    val touchEnabled = BoolPref(sp, "touch", true)
    val touchScale = FloatPref(sp, "tscale", 1f)
    val touchOpacity = FloatPref(sp, "talpha", 0.55f)
    val haptics = BoolPref(sp, "haptics", true)
    val leftHanded = BoolPref(sp, "lefty", false)

    // Mando físico: código de tecla de Android -> botón
    val bindings = mutableStateMapOf<Int, Int>()
    val games = mutableStateListOf<Game>()

    init {
        loadBindings()
        loadGames()
    }

    private fun defaultBindings() = mapOf(
        KeyEvent.KEYCODE_DPAD_UP to Btn.UP,
        KeyEvent.KEYCODE_DPAD_DOWN to Btn.DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to Btn.LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to Btn.RIGHT,
        KeyEvent.KEYCODE_BUTTON_B to Btn.A,      // el botón "este" del mando hace de A, como en Nintendo
        KeyEvent.KEYCODE_BUTTON_A to Btn.B,
        KeyEvent.KEYCODE_BUTTON_START to Btn.START,
        KeyEvent.KEYCODE_BUTTON_SELECT to Btn.SELECT
    )

    private fun loadBindings() {
        bindings.clear()
        val raw = sp.getString("bindings", null)
        if (raw == null) { bindings.putAll(defaultBindings()); return }
        raw.split(",").forEach { pair ->
            val p = pair.split(":")
            if (p.size == 2) {
                val k = p[0].toIntOrNull()
                val b = p[1].toIntOrNull()
                if (k != null && b != null) bindings[k] = b
            }
        }
    }

    private fun saveBindings() {
        sp.edit().putString("bindings", bindings.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
    }

    fun keyFor(btn: Int): Int? = bindings.entries.firstOrNull { it.value == btn }?.key

    /** Asigna una tecla a un botón; una tecla solo puede tener un botón y viceversa. */
    fun bind(btn: Int, keyCode: Int) {
        bindings.keys.filter { bindings[it] == btn || it == keyCode }.forEach { bindings.remove(it) }
        bindings[keyCode] = btn
        saveBindings()
    }

    fun resetBindings() {
        bindings.clear()
        bindings.putAll(defaultBindings())
        saveBindings()
    }

    // Biblioteca de juegos (las ROM se copian a la memoria privada de la app)
    fun romFile(g: Game) = File(romDir, "${g.id}.nes")

    fun addGame(title: String, bytes: ByteArray): Boolean {
        val valid = bytes.size > 16 && bytes[0] == 'N'.code.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'S'.code.toByte() && bytes[3] == 0x1A.toByte()
        if (!valid) return false
        romDir.mkdirs()
        val game = Game(UUID.randomUUID().toString(), title)
        romFile(game).writeBytes(bytes)
        games.add(game)
        saveGames()
        return true
    }

    fun removeGame(g: Game) {
        romFile(g).delete()
        File(savesDir, "${g.id}.srm").delete()
        File(savesDir, "${g.id}.state").delete()
        games.remove(g)
        saveGames()
    }

    private fun saveGames() {
        val arr = JSONArray()
        games.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title)) }
        sp.edit().putString("games", arr.toString()).apply()
    }

    private fun loadGames() {
        val raw = sp.getString("games", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                games.add(Game(o.getString("id"), o.getString("title")))
            }
        } catch (_: Exception) {
        }
    }
}
