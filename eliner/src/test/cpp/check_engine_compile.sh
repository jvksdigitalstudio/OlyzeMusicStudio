#!/usr/bin/env bash
# Verificación de COMPILACIÓN del motor nativo sin NDK ni Oboe (ADR 0028).
#
# Compila (g++ -fsyntax-only) las cuatro unidades de AudioEngine, EngineHandle.cpp y los dos
# puentes JNI contra los stubs de plataforma de platform_stubs/ (Oboe, Android
# log y jni.h mínimos: no hace falta NDK ni JDK). Usa los MISMOS flags de warnings que
# CMakeLists.txt (-Wall -Wextra) más -fno-exceptions y -ffast-math.
#
# Qué demuestra: sintaxis, tipos, firmas, includes y declaraciones del motor y
# de los puentes JNI. Qué NO demuestra: enlace, comportamiento en dispositivo
# ni compatibilidad con la versión concreta de Oboe — eso lo cubre el CI (NDK).
#
# Uso: bash check_engine_compile.sh        (desde cualquier directorio)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ELINER="$(cd "$HERE/../../.." && pwd)"
CPP="$ELINER/src/main/cpp"
STUBS="$HERE/platform_stubs"

INC="-I$STUBS -I$ELINER/src/main/cpp/core -I$ELINER/include/eliner -I$ELINER/include/eliner/core -I$ELINER/include/eliner/dsp \
     -I$ELINER/include/eliner/fx -I$ELINER/include/eliner/transport"
FLAGS="-std=c++20 -fsyntax-only -O3 -ffast-math -fno-exceptions -DNDEBUG -Wall -Wextra"

fail=0
for f in core/AudioEngine core/AudioEngineCommands core/AudioEngineControl core/AudioEngineFxChain \
         core/EngineHandle core/EliNerAudioBridge core/EliNerTransportBridge \
         transport/TempoClock transport/Metronome transport/TransportEngine dsp/VoicePool; do
  if out=$(g++ $FLAGS $INC "$CPP/$f.cpp" 2>&1); then
    printf 'OK   %s.cpp\n' "$f"
    [ -n "$out" ] && { printf '%s\n' "$out" | sed 's/^/     /'; }
  else
    printf 'FAIL %s.cpp\n%s\n' "$f" "$out"; fail=1
  fi
done
exit $fail
