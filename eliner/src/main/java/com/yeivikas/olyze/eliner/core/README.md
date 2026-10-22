# EliNer — Core (Core Foundation) — IMPLEMENTADO (Fase 1 de EliNer)

**Responsabilidad:** administración central del motor — ciclo de vida,
registro de módulos, versión, información e informe de errores. Ningún
procesamiento de audio, DSP, MIDI ni render vive aquí.

**Estado actual: código real, no documentación.** A diferencia de todas las
carpetas `eliner/*` anteriores (que decían "preparado, vacío"), esta es la
primera con implementación funcional.

## Archivos y por qué existe cada uno

| Archivo | Responsabilidad | Por qué es un archivo propio |
|---|---|---|
| [`EngineState.kt`](EngineState.kt) | Enum de 8 estados + tabla de transiciones válidas | Fuente única de verdad del ciclo de vida — no duplicar la lógica de "qué transición es legal" en varios lugares |
| [`EngineVersion.kt`](EngineVersion.kt) | Identidad de esta build del motor (nombre, versión, build, fecha) | Dato reutilizable — lo consumirá `EngineInfo`, y a futuro cualquier pantalla "Acerca de" |
| [`EngineError.kt`](EngineError.kt) | Representación de un fallo interno + severidad | Contrato hacia el futuro Diagnostic System — sin implementarlo |
| [`EliNerModule.kt`](EliNerModule.kt) | Interfaz que deberá implementar todo módulo futuro | Contrato puro, sin implementación falsa — ver nota abajo sobre por qué esto reemplaza crear una clase stub por cada motor futuro |
| [`ModuleRegistry.kt`](ModuleRegistry.kt) | Registro/descubrimiento thread-safe de módulos | Mecanismo genérico — funciona igual para Audio, DSP, MIDI, etc. sin conocerlos |
| [`EngineInfo.kt`](EngineInfo.kt) | Snapshot de solo lectura del estado global | Agregación derivada en vivo, nunca datos inventados |
| [`EliNerCore.kt`](EliNerCore.kt) | Fachada — conecta lifecycle + registry + versión + errores | El punto de entrada real de "Core Foundation" en el diagrama de arquitectura |

**Por qué no hay una clase por cada futuro módulo (Audio Engine, DSP
Engine, MIDI Engine, etc.):** `ModuleRegistry` solo necesita que algo
implemente `EliNerModule` — no necesita conocer Audio, DSP o MIDI para
"soportarlos". Crear 12 clases stub vacías (`AudioEngineStub`,
`DspEngineStub`...) solo para "dejar preparado el registro" habría sido
exactamente el anti-patrón que se pidió evitar: archivos sin
responsabilidad real, existentes solo por si "algún día se usan". El
mecanismo genérico ya cumple el objetivo sin necesitarlos.

## Flujo real de EliNerCore

```
initialize()  → UNINITIALIZED → INITIALIZING → READY
start()       → READY/PAUSED  → RUNNING          (llama onStart() de cada módulo registrado)
pause()       → RUNNING       → PAUSED
resume()      → PAUSED        → RUNNING
stop()        → cualquiera    → STOPPING → STOPPED (llama onStop() de cada módulo registrado)
reportError() → si es FATAL, fuerza transición a ERROR
```

Cada transición pasa por `EngineState.isValidTransition` — una transición
inválida no lanza excepción, devuelve `false` sin tocar el estado. Esto es
intencional: en un motor de audio, una operación de lifecycle mal llamada
no debería poder dejar el estado a medio camino.

## Reglas de dependencia (verificadas, no solo documentadas)

Ningún archivo de este paquete importa nada de `eliner.api`,
`eliner.bridge`, `eliner.modules`, ni de `:app`. Los únicos imports son
Kotlin estándar y `kotlinx.coroutines.flow` (ya declarado en
`eliner/build.gradle.kts` desde la Fase 3 — no se agregó ninguna
dependencia nueva para este Core Foundation).

## Qué NO hace este Core a propósito

- No llama a `eliner.bridge.EliNerAudioBridge`. Integrar el motor de audio
  existente como un `EliNerModule` registrado es trabajo de la fase que
  implemente el Audio Engine — tocar el bridge de audio aquí habría sido
  el mayor riesgo de esta fase, y esta fase explícitamente no debía tocar
  audio.
- No implementa Diagnostics, Configuration, Recovery, ni ningún otro
  sistema — solo expone lo mínimo que esos sistemas futuros necesitarán
  (`errors`, `snapshot()`).
- No tiene multithreading complejo. `ModuleRegistry` usa `synchronized`
  (bloqueante, pero simple y correcto — el registro de módulos no ocurre
  en el hilo de audio en tiempo real). `EliNerCore.state` usa
  `MutableStateFlow.update` (sin bloqueo, seguro entre hilos) porque
  `reportError` sí podría llamarse desde el hilo de un módulo en el
  futuro.

## Próximos pasos (fuera de alcance de esta fase)

Cuando exista el primer módulo real (probablemente Audio Engine, ya que
tiene base nativa), esta es la secuencia esperada: el módulo implementa
`EliNerModule`, se registra con `EliNerCore.registerModule(...)`, y
`EliNerCore.start()`/`stop()` ya sabe llamar sus hooks — sin modificar
`EliNerCore` en absoluto. Esa es la prueba de que la Fase 1 cumplió su
objetivo: los módulos futuros dependen del Core, nunca al revés.
