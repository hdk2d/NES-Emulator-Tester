#!/usr/bin/env bash
# Compila el núcleo libretro FCEUmm (licencia GPL-2.0) para Android y lo copia a app/src/main/jniLibs.
# Requiere git y el NDK (r26 o superior) con ANDROID_NDK_HOME definido.
set -euo pipefail
: "${ANDROID_NDK_HOME:?Define ANDROID_NDK_HOME apuntando al NDK}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
git clone --depth 1 https://github.com/libretro/libretro-fceumm.git "$WORK/fceumm"
"$ANDROID_NDK_HOME/ndk-build" -C "$WORK/fceumm/jni" \
  APP_ABI="arm64-v8a armeabi-v7a" APP_PLATFORM=android-21 -j"$(nproc)"
for abi in arm64-v8a armeabi-v7a; do
  so="$(find "$WORK/fceumm" -path "*/$abi/*" -name 'libretro.so' | head -n1)"
  [ -n "$so" ] || { echo "No se encontró libretro.so para $abi"; exit 1; }
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  cp "$so" "$ROOT/app/src/main/jniLibs/$abi/libfceumm_core.so"
done
echo "Núcleo copiado a app/src/main/jniLibs"
