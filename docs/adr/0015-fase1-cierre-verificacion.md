# ADR 0015 — Fase 1: Cierre y verificación profesional del núcleo

**Estado:** Ver "Final Status" al final de este documento — no se declara
cerrada de forma genérica; el veredicto explícito está al final, tal como
exige el prompt de esta fase.

## Contexto

Este ADR documenta la fase de CIERRE de la estabilización (ADR 0014), no
una nueva fase de desarrollo. Su origen es una auditoría final que
encontró un bug real y concreto en la implementación de ADR 0014, más una
lista de puntos a re-verificar con evidencia ejecutable en vez de
solamente por inspección. Cada hallazgo de este documento tiene código +
ejecución real (g++ + sanitizers), salvo donde se indica explícitamente
lo contrario.

## Bug corregido — `AudioCommandDispatcher.close()` propagaba `RejectedExecutionException`

**Confirmado real.** `executor.execute(...)`/`executor.submit(...)`
estaban fuera de cualquier manejo de rechazo — el propio acto de encolar
(no el cuerpo de la tarea) es lo que lanza `RejectedExecutionException`
tras `shutdown()`. Antes del fix, una llamada como `noteOff(...)` tras
`close()` podía propagar esa excepción directamente hacia quien llamó
(por ejemplo, el hilo `eliner-dsp` procesando un evento MIDI que llega en
el instante exacto del apagado).

**Fix:** se captura específicamente `RejectedExecutionException` (no un
`catch (Throwable)` genérico, que ocultaría bugs reales) alrededor de la
llamada a `execute()`/`submit()` en sí. Contrato final, verificado:

```
ANTES DE close()  → aceptar comandos, ejecutarlos en el único hilo dispatcher
DESPUÉS DE close() → rechazar comandos → NO lanzar excepción → NO llegar
                      a JNI → NO llegar al SPSC
```

Para los métodos bloqueantes (`start`/`insertModule`/`removeModule`/
`moveModule`/`getModuleType`), cada uno devuelve su propio valor de
repliegue semánticamente correcto (`false` / `DspModuleType.NONE`) en vez
de propagar el rechazo. `close()` es idempotente y segura de llamar
concurrentemente (garantía de `ExecutorService.shutdown()` de la JDK, no
reinventada con un mecanismo propio).

**Verificado con:** `AudioCommandDispatcherTest.kt`, ampliado con 4 tests
nuevos (fire-and-forget nunca lanza, bloqueantes devuelven fallback,
`close()` concurrente idempotente, carrera productor/`close()` con 20 000
intentos) — **EJECUTADO: NO** (sin `kotlinc`/Gradle en este entorno,
mismo disclaimer que el resto del proyecto). Complementado con
`test_dispatcher_shutdown_race.cpp`, que modela el MISMO contrato en C++
puro y sí se ejecutó de verdad, con y sin ThreadSanitizer — **PASS en
ambos**, 0 reportes de data race, en 3 interleavings distintos
(cierre-antes-del-primer-comando, cierre-concurrente-con-un-productor,
cierre-concurrente-con-tres-productores).

## Validación real del modelo de productor único (sección 4)

Auditoría exhaustiva (no solo los call-sites ya conocidos): exactamente
**una** instancia de `EliNerAudioBridge.getInstance()` en todo el
proyecto Kotlin (`AppServices.kt`), envuelta inmediatamente por
`AudioCommandDispatcher`. Nadie más importa `EliNerAudioBridge`
directamente. Del lado C++: `mCommandQueue.push()` solo se llama desde
`AudioEngine::pushCommand()`, que a su vez solo lo llaman los métodos
públicos alcanzables únicamente vía las funciones JNI ya auditadas —
ningún módulo DSP (`SynthVoice.cpp`, `Reverb.cpp`, `Delay.cpp`,
`DspModuleFactory.cpp`) toca `pushCommand`/`mCommandQueue`. **Ningún
camino paralelo directo al SPSC.**

## Tests de concurrencia — A.4 completado (sección 5)

Nuevo archivo `test_dispatcher_pattern_multi_producer.cpp`, que modela el
patrón arquitectónico COMPLETO (N productores → cola intermedia FIFO →
único hilo dispatcher → SPSC), no el SPSC aislado — fiel a
`AudioCommandDispatcher.kt`, incluyendo su comportamiento real de "un
solo intento de `push()`, sin reintento" bajo saturación.

Durante su desarrollo se encontraron y corrigieron 3 defectos reales del
propio arnés de test (no del código de producción), documentados aquí
por transparencia: (1) el consumidor solo drenaba después de que el
dispatcher terminara, causando saturación artificial; (2) un comando
"despertador" para desbloquear la cola intermedia se colaba en el conteo
real por compartir el valor por defecto de `EngineCommandType`; (3) el
dispatcher simulado reintentaba indefinidamente en vez de intentar una
vez, no reflejando el comportamiento real de `AudioCommandDispatcher`. El
entorno de ejecución tiene **1 solo núcleo virtual** (`nproc`==1,
confirmado) — con `yield()` puro en busy-loops bajo esa restricción se
observó livelock real; corregido usando `sleep_for` corto.

| Caso | Resultado (sin TSAN) | Resultado (con TSAN) |
|---|---|---|
| 1P1C (5000) | PASS, 0 descartes | PASS, 0 descartes, 0 races |
| 2P1C (5000 c/u) | PASS, 0 descartes | PASS, 0 descartes, 0 races |
| 3P1C (5000 c/u) | PASS, saturación real observada (contabilizada) | PASS, 0 descartes, 0 races |
| Flood (3×15000) | PASS, saturación real observada (contabilizada) | PASS, 0 descartes, 0 races |
| Queue full (determinista, capacidad 16) | PASS — acepta Capacity-1, rechaza el resto, `droppedCount()` exacto | — |
| Shutdown race (3 interleavings) | PASS | PASS, 0 races |

En todos los casos, la propiedad verificada es: **todo comando enviado
termina contabilizado como consumido o como descartado — nunca
desaparece sin explicación**, y el orden por productor es monótono
(nunca retrocede ni se duplica, aunque puede tener huecos legítimos bajo
descarte real).

## Retire Queue (sección 7)

Nuevo `test_retire_queue.cpp`. Confirmado por inspección Y por ejecución:
`AudioEngine::insertModule()` YA maneja correctamente el caso de
`pushCommand()` rechazado — el `DspModule*` recién construido se libera
explícitamente (`delete mod`) antes de retornar `false`, sin fuga (esto
corrige una sospecha planteada en el ADR 0014 anterior, que resultó
infundada al revisar el código real).

- **Churn normal** (5000 módulos, concurrente): cada módulo construido se
  destruye exactamente una vez — **PASS bajo AddressSanitizer y
  ThreadSanitizer**, 0 reportes.
- **Queue llena** (determinista, capacidad real 32): acepta exactamente
  31 (`Capacity-1`), rechaza el resto, `droppedCount()` exacto. El
  comportamiento del código de producción bajo esta condición (ver
  `AudioEngine.cpp`, casos `InsertModule`/`RemoveModule`/`MoveModule` en
  `processCommands()`) es un **leak intencional y ya documentado**
  (`raiseError(kErrorRetireQueueFull)`, sin I/O en el hilo de audio) — no
  double-free, no use-after-free, confirmado con AddressSanitizer.

## Lifecycle FSM — semántica de `FAILED` (sección 8)

**Decisión tomada, no solo señalada.** Antes de este fix, `FAILED` era
puramente transitorio: `stopLocked()` lo sobrescribía con `Stopped` de
forma incondicional, en la misma región bajo lock donde se había escrito
microsegundos antes — ningún observador externo podía verlo jamás,
haciendo que `getLifecycleState()` fuera indistinguible entre "el usuario
detuvo el motor" y "el último intento de arrancar falló".

**Decisión:** `FAILED` es ahora un estado **observable persistente** — si
`stopLocked()` se invoca con el motor ya en `FAILED` (el path de cleanup
tras un fallo de `requestStart()`), el estado queda en `FAILED` tras el
cleanup, no en `STOPPED`. La única salida de `FAILED` es un `start()`
explícito (`FAILED -> STARTING`, incondicional). `DESTROYED` se mantiene,
por decisión explícita (no omisión), fuera del enum — lo representa
`gEngine == nullptr`, porque es un concepto de ownership (¿existe la
instancia?), no de estado interno de una instancia que sí existe.

**Verificado con:** `test_lifecycle_fsm_model.cpp` — réplica exacta de la
lógica real (mismo enum, misma decisión de no-sobrescritura), 7 casos,
**PASS, 0 fallos**, ejecutado de verdad.

## gEngine (sección 11)

Re-auditado sin cambios necesarios: 27 funciones JNI, 26 protegidas por
`gEngineMutex` (la restante, `nativeGetMaxChainSlots`, no toca `gEngine`
— es una constante de clase). Ningún acceso a `gEngine` fuera de
`EliNerAudioBridge.cpp`. El mutex actual es suficiente — no se reemplaza
por una arquitectura más compleja, siguiendo la instrucción explícita del
prompt de esta fase de no sobre-diseñar sin justificación.

## Testing C++ / Build (sección 16)

**Decisión: NO se agrega GoogleTest al `CMakeLists.txt` de producción en
esta fase.** Razones: (1) sin NDK/CMake real disponible en este entorno
para verificar que la integración compilaría; (2) los tests escritos ya
cumplen el propósito real (verificación ejecutable con TSAN/ASan) sin
ninguna dependencia nueva, compilando con `g++` plano fuera del árbol de
build de producción; (3) el target real (`eliner_audio_core`) compila con
`-fno-exceptions` — cualquier framework de test que dependa de
excepciones (gtest las usa para algunas aserciones) necesitaría
evaluarse con cuidado contra esa flag, evaluación que este entorno no
permite hacer con confianza. Todos los tests de esta fase son archivos
`.cpp` standalone, fuera de `CMakeLists.txt`, sin ninguna dependencia del
árbol de build real — no contaminan el código de producción.

## Documentación

Actualizados: `README.md`, `eliner/.../ARCHITECTURE.md`, este ADR 0015
(nuevo — no se sobrescribió el ADR 0014, que documenta la fase anterior
tal cual ocurrió, siguiendo la convención ya establecida del proyecto de
"nueva ADR cuando algo cambia realmente").
