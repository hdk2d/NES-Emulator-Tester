# NesDeck

App Android (Kotlin + Jetpack Compose) para jugar ROM de NES que tú ya tengas, con menú inspirado en la Switch.

- **Gráficos:** proporción (TV 4:3, píxeles cuadrados, pantalla completa), escala entera, suavizado, líneas de escaneo, recorte de bordes, FPS.
- **Sonido:** volumen, silenciar, latencia del búfer.
- **Controles táctiles:** tamaño, opacidad, vibración, modo zurdo.
- **Mando físico:** asignación de botones, cruceta y palanca izquierda; el botón Mode/Home abre el menú.
- **Partidas:** guardado de batería (SRAM) automático y guardado rápido desde el menú de pausa.

## Cómo funciona
El NES usa un procesador 6502 y chips de vídeo y audio propios, que Android (ARM) no ejecuta de forma directa:
siempre hace falta emulación. NesDeck usa el núcleo libretro **FCEUmm** (GPL-2.0), que se compila aparte y se
carga con `dlopen` desde `app/src/main/cpp/bridge.cpp`.

La app **no incluye ningún juego**: se añaden desde Inicio → «Añadir juego» y se copian a la memoria privada de la app.

## Compilar
**En GitHub (lo más simple):** sube esta carpeta a un repositorio. El workflow `.github/workflows/build.yml`
compila el núcleo y el APK; descárgalo en Actions → Artifacts → `NesDeck-debug`.

**En local:**
1. Instala Android Studio, el NDK 26.1.10909125 y define `ANDROID_NDK_HOME`.
2. Ejecuta `./scripts/build_core.sh` (copia `libfceumm_core.so` a `app/src/main/jniLibs/`).
3. Abre la carpeta en Android Studio y ejecuta la app.

## Estado
Primera versión sin probar en un dispositivo: el puente C++ pasa una comprobación de sintaxis, pero el resto
no se ha compilado todavía. Si el workflow falla, copia el error y lo corregimos.

## Licencia
Al enlazar con FCEUmm (GPL-2.0), el proyecto resultante debe distribuirse bajo GPL-2.0.
