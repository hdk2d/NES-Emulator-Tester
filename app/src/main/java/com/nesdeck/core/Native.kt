package com.nesdeck.core

import java.nio.ByteBuffer

/** Puente JNI hacia el núcleo libretro (ver app/src/main/cpp/bridge.cpp). */
object Native {
    /** false si falta libfceumm_core.so (ejecuta scripts/build_core.sh). */
    val ok: Boolean = try {
        System.loadLibrary("fceumm_core")
        System.loadLibrary("nesbridge")
        true
    } catch (t: Throwable) {
        false
    }

    external fun load(lib: String, sysDir: String, saveDir: String, romPath: String, rom: ByteArray, video: ByteBuffer, audio: ByteBuffer): Boolean
    external fun timing(): DoubleArray                 // [fps, frecuencia de audio]
    external fun runFrame(buttons: Int, out: IntArray) // out = [frames de audio, ancho, alto]
    external fun getSram(): ByteArray?
    external fun setSram(data: ByteArray)
    external fun saveState(): ByteArray?
    external fun loadState(data: ByteArray): Boolean
    external fun reset()
    external fun unload()
}
