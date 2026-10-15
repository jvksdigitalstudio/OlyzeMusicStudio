# ADR 0014 — Fase 1: Estabilización profesional del núcleo (concurrencia, lifecycle, boundary)

**Estado:** Parcialmente ejecutado. Ver "Estado real por objetivo" — nada
aquí se marca IMPLEMENTADO salvo que tenga código real + verificación
(ejecutada o, cuando el entorno no lo permitió, análisis riguroso
explícitamente señalado como no ejecutado).

## Contexto

Esta fase NO partió de cero: partió de una auditoría obligatoria del
código real (`eliner/include/eliner/core/`, `eliner/src/main/cpp/`,
`MainViewModel.kt`, `MidiRouter.kt`, `ThreadManager.kt`) antes de tocar
nada, según exige el prompt de esta fase. Esa auditoría encontró 3
hallazgos críticos confirmados con evidencia (no solo análisis) y 1
hallazgo adicional descubierto durante la implementación misma (Objetivo
E), no listado en el prompt original pero claramente dentro de su
alcance.

## CRÍTICO 1 — `SpscCommandQueue` con dos productores reales concurrentes

`eliner/include/eliner/core/CommandQueue.h` documenta *"Producer: Control
thread ONLY"*. En la práctica, dos hilos Android reales llamaban a
`pushCommand()` sin ninguna serialización: el hilo **UI** (`MainViewModel.
noteOn()`, toques en el teclado en pantalla) y el hilo **`eliner-dsp`**
(`MidiRouter`, en su propio `ExecutionLane.DSP` — `ThreadManager.kt` —
despachando `MidiToSynthBridge.consumer`).

**Evidencia:** no solo análisis — una prueba determinista de
intercalación (`eliner/src/test/cpp/core/
test_command_queue_interleaving_proof.cpp`), compilada y **ejecutada
realmente** con g++ en este entorno, reproduce paso a paso el algoritmo
real de `push()` y demuestra matemáticamente la pérdida silenciosa de un
comando sin que `droppedCommands` lo refleje. Complementada por
`test_command_queue_spsc_baseline.cpp` (1 productor-1 consumidor, 200 000
comandos, **PASS limpio bajo ThreadSanitizer** — confirma que el ring
buffer en sí es correcto para su contrato documentado) y
`test_command_queue_race_evidence.cpp` (2 productores reales sin barrera
artificial — conteos anómalos observados, consistente con el hallazgo).

**Decisión (Opción A, no Opción B):** en vez de convertir el ring buffer
en MPSC en C++ (más costo/superficie de bugs en código realtime ya
correcto), se serializa en Kotlin, ANTES de JNI —
`eliner.bridge.AudioCommandDispatcher`, un único `ExecutorService` de un
solo hilo (`eliner-audio-commands`) por el que pasa TODA mutación del
motor (lifecycle + comandos). `SpscCommandQueue` sigue siendo SPSC, y
ahora eso es literalmente cierto.

**Verificación:** test JUnit real escrito
(`AudioCommandDispatcherTest.kt`, 4 casos: orden FIFO bajo 2 hilos
productores reales, `start()` síncrono, orden relativo con métodos
bloqueantes, `close()` rechaza comandos tardíos) — **EJECUTADO: NO**, sin
`kotlinc`/Gradle en este entorno.

## CRÍTICO 2 — `gEngine` sin sincronización (JNI)

`static std::unique_ptr<eliner::AudioEngine> gEngine;` en
`EliNerAudioBridge.cpp`, protegido solo por `if (gEngine)` en cada
función — `unique_ptr::reset()` no es atómico, así que una lectura racy
mientras otro hilo lo resetea es UB real (no hipotético, dado el Crítico
1: hay dos hilos de control reales).

**Fix:** `std::mutex gEngineMutex` protegiendo las 27 funciones JNI que
tocan `gEngine` (26 de 27 — la restante, `nativeGetMaxChainSlots`, no
toca `gEngine` en absoluto, es una constante de clase). Realtime-safe por
construcción: el callback de audio (`onAudioReady`) nunca pasa por
`gEngine`, solo hilos de control lo hacen.

**Verificación:** el patrón (no el archivo real — depende de `jni.h`, no
disponible sin NDK) fue validado en `test_gengine_mutex_fix.cpp`,
compilado y **ejecutado con ThreadSanitizer**: 5 hilos concurrentes + 500
ciclos destroy/create, **cero reportes de data race**.

## CRÍTICO 3 — orden de shutdown roto en `onCleared()`

`audio.stop()` (destruye `gEngine`) se llamaba ANTES de `midi.stop()` —
ventana real donde el hilo `eliner-dsp` podía seguir despachando hacia un
motor ya destruido.

**Fix:** orden corregido — toda fuente MIDI se detiene primero
(`midiManager.close()` → `midi.stop()` → `unregisterConsumer` →
`midi.shutdown()`), después `audio.allNotesOff()` → `audio.stop()` →
`audioDispatcher.close()`. Ahora vive en `AppServices.shutdown()` (ver
Objetivo H).

## Objetivo F — `requestStart()` failure sin cleanup

Confirmado por inspección: si `requestStart() != OK`, el código dejaba
`mStream` **abierto** (nunca `close()`/`reset()`) y el DSP graph que
`buildDspGraph()` ya había construido, sin liberar.

**Fix:** reutiliza `stopLocked()` (ver Objetivo D/E) en el path de
fallo — mismo cierre completo y ya verificado del path normal de parada,
sin duplicar lógica.

**Test:** `test_audio_engine_request_start_failure_cleanup.cpp` — código
real, pensado para el build NDK real. **EJECUTADO: NO** — requiere
Oboe/NDK y, para ejercer específicamente el path de `requestStart()`
fallando DESPUÉS de un `openStream()` exitoso, un dispositivo/emulador
real (Oboe no expone un hook para inyectar ese fallo en un test headless).
Documentado como limitación de testabilidad, no como test que "pasó".

## Objetivo D/E — Oboe error recovery (hallazgo adicional, no en la lista original)

Descubierto durante la implementación: el fix del Crítico 2 protege el
**puntero** `gEngine`, pero `onErrorAfterClose()` es invocado por un hilo
**interno de Oboe** que llama `stop()`/`start()` directamente sobre el
objeto — sin pasar por `gEngineMutex` en absoluto. Dos hilos de control
reales (JNI vs. hilo de error de Oboe) podían mutar `mStream`/`mFxChain`
del mismo objeto concurrentemente.

**Fix:** `std::mutex mLifecycleMutex` interno a `AudioEngine`. Riesgo de
deadlock real detectado durante el diseño: `start()` ya llama
internamente a `stop()` en su path de fallo (Objetivo F), y
`reopenStream()` llama a ambos — un mutex simple habría causado
relock-deadlock. Resuelto con el patrón `startLocked()`/`stopLocked()`
(privados, sin tomar el lock — el llamador ya lo tiene) + `start()`/
`stop()` públicos como wrappers finos que toman el lock una sola vez.
Ninguno de los tres métodos se llama jamás desde el callback de audio
realtime — el mutex nunca compite con ese hilo.

## Objetivo C — Lifecycle FSM formal

Antes: estado disperso en dos booleans independientes (`mIsRunning`,
`mDspReady`), sin política única de transiciones. Añadido, de forma
**aditiva** (no reemplaza esos booleans — menor riesgo sin poder
recompilar y verificar en este entorno): `enum class LifecycleState {
Stopped, Starting, Running, Stopping, Recovering, Failed }`, actualizado
en cada punto real de `startLocked()`/`stopLocked()`/`reopenStream()`,
bajo el mismo `mLifecycleMutex`. `DESTROYED` no es un valor del enum: lo
representa `gEngine == nullptr`, fuera de este objeto. Expuesto vía
`AudioEngine::getLifecycleState()` — lectura atómica pública, no
consumida todavía desde Kotlin/JNI (trabajo de seguimiento).

## Objetivo H — `MainViewModel` boundary

Antes: `MainViewModel` construía directamente `AudioCommandDispatcher`,
`ThreadManager`, `EventBus`, `LoggerService`, `TimeService`,
`DeviceCapabilityManager`, `PerformanceProfileManager`,
`MidiFoundationModule`, `MidiOutputBridge`, `MidiToSynthBridge` — junto
con todo su UI state y comandos de transporte.

**Fix:** nueva clase `com.yeivikas.olyze.AppServices` (Application
Services) — construye y posee TODA la infraestructura de audio/MIDI, con
`start()`/`shutdown()` propios. `MainViewModel` ya no construye ninguna
pieza de infraestructura — solo consume `services.audio`/`services.midi`/
`services.midiManager`/`services.midiToSynth` y mantiene exclusivamente
UI state + comandos de transporte/teclado/FX. Verificado que ninguna
pantalla (`MainScreen.kt`) accedía a `audio`/`midi`/`midiManager`
directamente — solo a `bpm`/`isPlaying`/`isRecording`/`keyboardVisible`/
`externalActiveNotes` + los métodos de comando, todos preservados 1:1.

**Límite honesto, no oculto:** el ideal completo de "Application
Composition Root" viviría en una `android.app.Application` custom
(sobrevive a recreación de Activity/ViewModel, compartible entre futuros
ViewModels — Mixer, Sequencer). Esta fase NO llega hasta ahí: `MainViewModel`
sigue siendo quien construye `AppServices`. Tocar `AndroidManifest.xml`
sin poder compilar/verificar excede el riesgo aceptable para el alcance
de esta fase. Documentado como trabajo de seguimiento explícito en el
propio `AppServices.kt`.

## Objetivo J — Parameter Architecture (estrategia, NO implementada)

`EliNerAudioApi` sigue creciendo por métodos específicos
(`setReverbMix`, `setDelayMix`, `setDelayTime`, `setDelayFeedback`,
`setModuleParameter(slot, paramId, value)` — este último ya es un primer
paso hacia parámetros genéricos por slot+id). No se justifica eliminar
los métodos específicos en esta fase (están en uso). Estrategia futura
documentada aquí para cuando Automation/Plugin System lo requieran:

- **Parameter ID:** un `UInt`/enum estable por parámetro, namespaced por
  slot de DSP chain (`slot:paramId`, ya el formato que
  `setModuleParameter` usa parcialmente) — nunca un string (costo en el
  hot path, y `CommandQueue.h` es de tamaño fijo, no puede llevar
  strings sin asignación dinámica, prohibida en el audio thread).
- **Parameter metadata:** rango (min/max), curva (lineal/logarítmica),
  unidad, nombre para mostrar — vive en Kotlin (no en el hot path C++),
  como una tabla estática por `DspModuleType`.
- **Parameter value:** siempre `Float` normalizado o en unidades nativas
  — decisión pendiente de qué automation/UI real lo consuma primero.
- **Parameter ownership:** el motor C++ es la fuente de verdad del valor
  actual (vía `mSlotTypesShadow`-like shadow arrays, ya el patrón que
  usa `getModuleType`); Kotlin nunca cachea un valor "optimista" sin
  confirmación.
- **Automation:** un futuro `AutomationEngine` escribiría al mismo
  `CommandQueue` que MIDI/UI ya usan — mismo `AudioCommandDispatcher`,
  ningún productor nuevo sin pasar por él (el Crítico 1 de esta fase no
  debe repetirse).
- **MIDI mapping:** `MidiParameterBinding` (`eliner.api`) ya existe y ya
  evalúa CC→valor — el gap no es el contrato, es que no hay destino
  conectado (`DspParameterManager` vive en el stack Kotlin desconectado,
  ADR 0010 — conectar ahí sonaría "aplicado" sin serlo).
- **Plugin mapping:** fuera de alcance — depende del Plugin System, no
  implementado (prohibido explícitamente en esta fase).

## Testing — estado real, sin inflar

| Test | Tipo | Ejecutado | Resultado |
|---|---|---|---|
| `test_command_queue_spsc_baseline.cpp` | C++, g++ real | **SÍ** (con y sin TSAN) | PASS — 200k comandos, 0 pérdida, 0 reportes TSAN |
| `test_command_queue_interleaving_proof.cpp` | C++, g++ real | **SÍ** | Confirma la pérdida — comportamiento esperado del test (documenta el problema) |
| `test_command_queue_race_evidence.cpp` | C++, g++ real | **SÍ** | Conteos anómalos observados bajo contención real |
| `test_gengine_mutex_fix.cpp` (patrón, no el archivo real) | C++, g++ real, TSAN | **SÍ** | PASS — 0 reportes de race tras el fix |
| `test_audio_engine_request_start_failure_cleanup.cpp` | C++, requiere NDK+Oboe+dispositivo | **NO** | No ejecutable en este entorno |
| `AudioCommandDispatcherTest.kt` (4 casos) | Kotlin JUnit | **NO** | Sin `kotlinc`/Gradle en este entorno |
| Retire queue (`mRetireQueue` en `AudioEngine.h`) | — | **NO auditado a fondo en esta fase** | Diseño ya correcto por inspección (ownership claro, SPSC real); sin test de `queue full` dedicado |
| DSP (`DspChain` insert/remove/move/replace) | — | **NO** | Fuera del alcance cubierto en esta fase — pendiente |
| JNI lifecycle (create/start/stop/destroy repetido) | — | **NO** | Requiere NDK/dispositivo |

## Build

Sin cambios de configuración de build en esta fase (§16 — "no cambies
versiones porque sí"). `./gradlew` sigue sin poder ejecutarse en este
entorno (sin red, sin NDK instalado) — mismo estado que fases previas del
proyecto. Lo verificable sin ese toolchain SÍ se verificó realmente: los
3 archivos de `CommandQueue.h` no dependen de Android/NDK/Oboe, así que
se compilaron y ejecutaron con `g++`/ThreadSanitizer reales, no
simulados.

## Riesgos restantes

**CRÍTICOS:** ninguno identificado que siga sin mitigar dentro del
alcance de esta fase.

**IMPORTANTES:**
- Retire queue (`mRetireQueue`, capacidad 32) sigue con la política de
  "leak silencioso si se llena", ya documentada en el código, no
  revisitada en esta fase.
- `test_audio_engine_request_start_failure_cleanup.cpp` no puede
  ejecutarse sin dispositivo real — el fix del Objetivo F está verificado
  solo por inspección de código, no por ejecución.
- Ninguno de los cambios de este turno se compiló contra el toolchain
  real (NDK+Gradle) — verificación manual rigurosa (balance de llaves/
  paréntesis, contraste de firmas, y para `CommandQueue.h` ejecución real
  con g++/TSAN), pero no sustituye una compilación real.

**MENORES:**
- `AppServices` sigue siendo construido por `MainViewModel`, no por una
  `Application` custom — ver límite documentado en Objetivo H.
- `LifecycleState` no se consume todavía desde Kotlin (solo expuesto en
  C++).

**FUTUROS:**
- Parameter Architecture (Objetivo J) — estrategia documentada, cero
  código.
- Mixer, Transport completo, OlySf2, Synth completo, Project System,
  Recording, Render, Plugin Host, VST2/VST3, Winlator/Wine/Box64,
  IPC/SHM, Floating Window System — deliberadamente NO implementados en
  esta fase (prohibición explícita del prompt de esta fase).

## Archivos

**Creados:**
- `eliner/src/main/java/com/yeivikas/olyze/eliner/bridge/AudioCommandDispatcher.kt`
- `eliner/src/test/java/com/yeivikas/olyze/eliner/bridge/AudioCommandDispatcherTest.kt`
- `eliner/src/test/cpp/core/test_command_queue_spsc_baseline.cpp`
- `eliner/src/test/cpp/core/test_command_queue_race_evidence.cpp`
- `eliner/src/test/cpp/core/test_command_queue_interleaving_proof.cpp`
- `eliner/src/test/cpp/core/test_audio_engine_request_start_failure_cleanup.cpp`
- `app/src/main/java/com/yeivikas/olyze/AppServices.kt`
- `docs/adr/0014-fase1-estabilizacion-nucleo.md` (este archivo)

**Modificados:**
- `eliner/src/main/cpp/core/EliNerAudioBridge.cpp` (mutex `gEngineMutex`)
- `eliner/include/eliner/core/AudioEngine.h` (`mLifecycleMutex`,
  `LifecycleState`, declaraciones `startLocked`/`stopLocked`)
- `eliner/src/main/cpp/core/AudioEngine.cpp` (cleanup de `requestStart()`,
  patrón `Locked`, transiciones de `LifecycleState`)
- `app/src/main/java/com/yeivikas/olyze/MainViewModel.kt` (usa
  `AppServices`, ya no construye infraestructura)

**Eliminados:** ninguno en esta fase.
