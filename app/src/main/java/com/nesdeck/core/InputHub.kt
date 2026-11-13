package com.nesdeck.core

import android.view.KeyEvent
import android.view.MotionEvent

/** Punto único por donde MainActivity reenvía teclas y palancas del mando físico. */
object InputHub {
    @Volatile var onKey: ((KeyEvent) -> Boolean)? = null
    @Volatile var onMotion: ((MotionEvent) -> Boolean)? = null
}
