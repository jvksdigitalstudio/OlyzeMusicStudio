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
INC=(-I"$ELINER/include" -I"$ELINER/include/eliner/core" -I"$ELINER/include/eliner/dsp" -I"$ELINER/src/main/cpp")
BASE=(-std=c++20 -Wall -Wextra -Wpedantic -g -O1 -pthread)
OUT="$(mktemp -d)"
declare -A EXTRA=( [test_midi_channel_voice_routing]="$ELINER/src/main/cpp/dsp/SynthVoice.cpp" )
declare -A EXPECT=( [test_command_queue_interleaving_proof]=1 )
THREADED=(test_engine_init_state_machine_model test_thread_manager_shutdown_protocol_model test_dispatcher_pattern_multi_producer test_dispatcher_shutdown_race
          test_command_queue_race_evidence test_command_queue_spsc_baseline test_retire_queue)
fail=0; total=0
run() { # nombre  sanitizer
  local n="$1" san="$2" exp="${EXPECT[$1]:-0}"
  local exe="$OUT/${n}_${san//,/_}"
  if ! g++ "${BASE[@]}" -fsanitize="$san" "${INC[@]}" "$CORE/$n.cpp" ${EXTRA[$n]:-} -o "$exe" 2>"$exe.err"; then
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
echo "== $total ejecuciones, $fail problema(s) =="
[ "$fail" -eq 0 ]
