#!/usr/bin/env bash
# Ejecuta la suite C++ standalone de EliNer (sin Android/NDK/Oboe).
#
# Uso (desde cualquier directorio):   bash eliner/src/test/cpp/run_native_tests.sh
#
# Se invoca con "bash" explícito a propósito, no como ./run_native_tests.sh:
# el bit de ejecución de un script no siempre sobrevive zip -> extracción ->
# git add -> GitHub (depende del SO/cliente usado en cada paso) — invocarlo
# vía "bash" elimina esa dependencia por completo, tanto en CI (ver
# .github/workflows/build.yml) como en local.
# Requiere: g++ >= 11 con soporte C++20 y sanitizers (ASan/UBSan/TSan).
#
# Cada test se compila con -Wall -Wextra -Wpedantic y se ejecuta con
# ASan+UBSan; los que ejercitan hilos reales se ejecutan ADEMÁS con TSan
# (ASan y TSan son incompatibles en una misma compilación).
#
# Códigos esperados: 0 = PASS. `test_command_queue_interleaving_proof` es una
# DEMOSTRACIÓN deliberada del defecto de la cola SPSC con dos productores
# (motivo del AudioCommandDispatcher, ADR 0014): sale con 1 por diseño.
#
# NO incluye `test_audio_engine_request_start_failure_cleanup.cpp`: requiere
# los headers reales de Oboe (se compila con el NDK/CMake del proyecto).
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ELINER="$(cd "$HERE/../../.." && pwd)"
CORE="$HERE/core"
INC=(-I"$ELINER/include" -I"$ELINER/include/eliner/core" -I"$ELINER/include/eliner/dsp" -I"$ELINER/include/eliner/fx" -I"$ELINER/include/eliner/transport" -I"$ELINER/src/main/cpp" -I"$ELINER/src/main/cpp/core")
BASE=(-std=c++20 -Wall -Wextra -Wpedantic -g -O1 -pthread)
OUT="$(mktemp -d)"
# ADR 0028: los tests de transporte enlazan el código de PRODUCCIÓN (no un modelo).
TRN="$ELINER/src/main/cpp/transport"
# Fuentes del AudioEngine REAL (cuatro unidades por responsabilidad + colaboradores) contra el
# backend de audio FALSO. Una sola lista para todos los tests que enlazan el motor: así añadir
# o mover un archivo del motor se toca en UN sitio.
CORE_SRC="$ELINER/src/main/cpp/core"
ENGINE_SRCS="$HERE/platform_stubs/oboe_fake.cpp $CORE_SRC/AudioEngine.cpp $CORE_SRC/AudioEngineCommands.cpp $CORE_SRC/AudioEngineControl.cpp $CORE_SRC/AudioEngineFxChain.cpp $ELINER/src/main/cpp/dsp/SynthVoice.cpp $ELINER/src/main/cpp/dsp/VoicePool.cpp $ELINER/src/main/cpp/dsp/DspModuleFactory.cpp $ELINER/src/main/cpp/fx/Reverb.cpp $ELINER/src/main/cpp/fx/Delay.cpp $TRN/TempoClock.cpp $TRN/Metronome.cpp $TRN/TransportEngine.cpp"
declare -A EXTRA=(
  # TransportEngine aislado (sin AudioEngine ni Oboe): reloj + metrónomo + pulso + estado del sync.
  [test_transport_engine]="$TRN/TempoClock.cpp $TRN/Metronome.cpp $TRN/TransportEngine.cpp"
  [test_voice_pool]="$ELINER/src/main/cpp/dsp/SynthVoice.cpp $ELINER/src/main/cpp/dsp/VoicePool.cpp"
  [test_tempo_clock]="$TRN/TempoClock.cpp"
  [test_metronome]="$TRN/TempoClock.cpp $TRN/Metronome.cpp"
  # ADR 0031: sonidos, acento y subdivisión del click.
  [test_metronome_click]="$TRN/TempoClock.cpp $TRN/Metronome.cpp"
  [test_delay_tempo_glide]="$ELINER/src/main/cpp/fx/Delay.cpp"
  [test_transport_properties]="$TRN/TempoClock.cpp $TRN/Metronome.cpp $ELINER/src/main/cpp/fx/Delay.cpp"
  # Integración del AudioEngine REAL contra un backend de audio FALSO (no Oboe): ver oboe_fake.cpp.
  [test_engine_transport_concurrency]="$ENGINE_SRCS"
  [test_engine_transport_integration]="$ENGINE_SRCS"
  # ADR 0029: publicación de la posición de pulso (indicador visual de la UI).
  [test_engine_beat_pulse]="$ENGINE_SRCS"
  [test_engine_click_settings]="$ENGINE_SRCS"
  # ADR 0031: ajustes del click de extremo a extremo.
  [test_engine_click_settings]="$ENGINE_SRCS"
)
# Stubs de plataforma (Oboe/Android/JNI) SOLO para los tests que enlazan AudioEngine.
declare -A EXTRAINC=( [test_engine_transport_integration]="-I$HERE/platform_stubs" [test_engine_transport_concurrency]="-I$HERE/platform_stubs" [test_engine_beat_pulse]="-I$HERE/platform_stubs" [test_engine_click_settings]="-I$HERE/platform_stubs" [test_engine_click_settings]="-I$HERE/platform_stubs" )
declare -A EXPECT=( [test_command_queue_interleaving_proof]=1 )
THREADED=(test_engine_init_state_machine_model test_thread_manager_shutdown_protocol_model test_dispatcher_pattern_multi_producer test_dispatcher_shutdown_race
          test_command_queue_race_evidence test_command_queue_spsc_baseline test_retire_queue
          test_engine_transport_concurrency test_engine_beat_pulse test_engine_click_settings)
fail=0; total=0
run() { # nombre  sanitizer
  local n="$1" san="$2" exp="${EXPECT[$1]:-0}"
  local exe="$OUT/${n}_${san//,/_}"
  if ! g++ "${BASE[@]}" -fsanitize="$san" "${INC[@]}" ${EXTRAINC[$n]:-} "$CORE/$n.cpp" ${EXTRA[$n]:-} -o "$exe" 2>"$exe.err"; then
    echo "COMPILE-FAIL  $n [$san]"; head -5 "$exe.err"; fail=$((fail+1)); return; fi
  if grep -q "warning:" "$exe.err"; then echo "WARNINGS      $n [$san]"; fail=$((fail+1)); fi
  timeout 300 "$exe" >"$exe.out" 2>&1; local rc=$?
  total=$((total+1))
  # Excepción: test_command_queue_race_evidence bajo TSan es una DEMOSTRACIÓN de una data race
  # en la cola SPSC mal usada; TSan puede reportarla legítimamente, así que ahí solo se exige que termine.
  if [ "$rc" -eq "$exp" ] && ! grep -q "ThreadSanitizer: data race\|AddressSanitizer\|runtime error:" "$exe.out" || { [ "$n" = test_command_queue_race_evidence ] && [ "$san" = thread ]; }; then
    echo "OK            $n [$san] rc=$rc"
  else
    echo "FAIL          $n [$san] rc=$rc (esperado $exp)"; tail -3 "$exe.out"; fail=$((fail+1))
  fi
}
for f in "$CORE"/*.cpp; do
  n="$(basename "$f" .cpp)"
  [ "$n" = test_audio_engine_request_start_failure_cleanup ] && { echo "OMITIDO       $n (requiere Oboe/NDK)"; continue; }
  run "$n" address,undefined
  for t in "${THREADED[@]}"; do [ "$t" = "$n" ] && run "$n" thread; done
done
# ADR 0028: compilación (sin enlazar) del motor y de los puentes JNI contra stubs de
# plataforma — cubre AudioEngine.cpp, que ningún test standalone puede incluir porque
# depende de Oboe. Ver check_engine_compile.sh para el alcance exacto.
echo "-- compilación del motor nativo (stubs de plataforma) --"
if ! bash "$HERE/check_engine_compile.sh"; then fail=$((fail+1)); fi
echo "== $total ejecuciones, $fail problema(s) =="
[ "$fail" -eq 0 ]
