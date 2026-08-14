# ADR 0012 — MIDI Foundation: infraestructura MIDI real (input), sin Synth

**Estado:** Implementado en este entorno (sin red, sin NDK, sin Gradle
real — ver "Build" abajo), pendiente de confirmación real en GitHub
Actions, igual que toda fase anterior.

## Contexto

El proyecto ya tenía salida MIDI funcional
(`com.yeivikas.olyze.midi.OlyzeMidiManager`) pero cero infraestructura de
entrada. La carpeta `eliner.modules.midi/README.md` ya anticipaba esto:
"cuando el MIDI Engine se implemente de verdad" — este es ese momento.

## Investigación previa (§4 del prompt)

Confirmado, código en mano, antes de escribir nada:
- `android.media.midi.MidiManager` existe desde API 23; minSdk de este
  proyecto es 24 — sin gap de compatibilidad.
- `android.media.midi.MidiDeviceInfo.getType()` distingue `TYPE_USB`/
  `TYPE_VIRTUAL`/`TYPE_BLUETOOTH` de forma confiable desde API 23 (mi
  primer borrador de `MidiTransport` decía lo contrario — corregido antes
  de cerrar esta fase, ver el comentario de esa clase).
- Recepción de datos: Android invierte la nomenclatura — para RECIBIR de
  un dispositivo hay que abrir su **output port**
  (`MidiDevice.openOutputPort`) y conectarle un `MidiReceiver`; para
  ENVIAR se abre su **input port**. Documentado explícitamente en
  `AndroidMidiBackend.kt` porque es fácil de confundir.
- SysEx: Android entrega el payload ya ensamblado dentro de los límites
  del buffer del framework — no hay reensamblado multi-paquete nativo que
  aprovechar ni que reimplementar salvo que el hardware fragmente SysEx
  manualmente (caso no común, no implementado — ver §19).
- `BLUETOOTH_CONNECT` (permiso runtime desde API 31) está declarado en el
  manifest pero **nunca solicitado en runtime** — ni por el código nuevo
  de esta fase ni por el `OlyzeMidiManager` preexistente. Esto es un gap
  real y heredado, no introducido aquí: sin esa solicitud, dispositivos
  MIDI Bluetooth podrían no ser descubribles en Android 12+. Documentado,
  no corregido en esta fase (corregirlo implica añadir un flujo de
  permisos en `:app`, fuera del alcance de "infraestructura MIDI").

## Decisión — arquitectura

```
Android MIDI (Binder callbacks)
    ↓
AndroidMidiBackend (implements MidiPlatformBackend — único archivo con
                    imports android.media.midi.*, §26)
    ↓
MidiStreamParser (running status + realtime intercalado — por conexión)
    ↓
MidiEventQueue (bounded, MPSC-safe — ver Decisión de cola abajo)
    ↓
MidiRouter (drena en ExecutionLane.DSP, reusa ThreadManager — §27)
    ├── MidiClockEngine (Clock/Start/Stop/Continue, tempo real desde timing real)
    ├── consumidores registrados (MidiConsumer — ninguno implementado, §3)
    └── bindings (MidiParameterBinding.evaluate() real, sin destino conectado)
```

Todo detrás de `EliNerMidiApi` (`eliner.api`) — la UI/app nunca ve un tipo
`android.media.midi.*` (§25).

## Decisión — la cola (§12, exige justificación explícita)

**No** se usó un ring buffer lock-free hecho a mano (el patrón que sí usa
correctamente el motor nativo en `CommandQueue.h`) porque ese caso es
SPSC (un hilo de audio, un hilo de control) y este es genuinamente
**MPSC**: §37 exige múltiples dispositivos simultáneos, y Android entrega
cada uno en su propio hilo de callback. Un MPSC lock-free correcto (CAS
para reclamar slots entre productores) es sustancialmente más difícil de
acertar que el caso SPSC, y este entorno no tiene forma de compilarlo ni
de someterlo a stress-test. §12 es explícito: "NO utilizar 'lock-free'
simplemente como palabra de marketing" — enviar un MPSC lock-free sin
verificar es exactamente eso.

Se usó `java.util.concurrent.ArrayBlockingQueue` — acotada, thread-safe
para múltiples productores de fábrica, con un candado interno cuyo tiempo
de posesión es mínimo (unas pocas operaciones de array), y ese candado se
sostiene en un hilo Binder de Android, **no** en el callback de render de
audio nativo (la ruta realtime que este proyecto protege con más cuidado
— ver ADR 0011). Política de overflow: se descarta el evento más nuevo,
no el más viejo — mismo criterio que `CommandQueue.h` ya documenta, para
que un NOTE_OFF ya encolado nunca sea desalojado por algo más nuevo.

## Decisión — MIDI Learn: contrato real, sin destino conectado

`MidiParameterBinding.evaluate()` hace la matemática real (CC 0-127 →
valor escalado, con curva/inversión) y `MidiRouter` la ejecuta de verdad
contra bindings registrados para eventos CC reales — pero el resultado
solo se publica en un `SharedFlow` (`bindingUpdates`), sin aplicarlo a
ningún parámetro concreto. Conectarlo a `DspParameterManager` habría sido
más fácil de escribir, pero `DspParameterManager` es parte del stack
Kotlin desconectado (ADR 0010) — un binding que "funciona" contra un
parámetro que no suena sería peor que uno honestamente sin conectar:
parecería implementado sin estarlo.

## Qué se dejó fuera, a propósito (§3, §31, §42)

Synth/Sampler/Instrument Engine, Piano Roll, Sequencer, Automation UI,
Mixer UI, Plugin UI — nada de esto se tocó. Reensamblado de SysEx
multi-paquete — solo un payload ya ensamblado (tope 4 KiB). Migración de
`OlyzeMidiManager` (salida) a `EliNerMidiApi` — sigue en `:app`, sin
tocar, documentado como trabajo de seguimiento. MPE real (per-note
pitch/pressure/timbre) — el modelo de eventos no lo impide
arquitectónicamente (cada `MidiEvent` ya lleva canal individual, base
necesaria para MPE zone-per-channel) pero no hay lógica de zonas MPE
implementada.

## Build

Mismo entorno sin red/NDK/Gradle real que todas las fases anteriores.
Verificación manual: balance de llaves/paréntesis en los 13 archivos
nuevos + los 4 tocados (`MainViewModel.kt`, 2 READMEs, `ARCHITECTURE.md`).
No se pudo correr `./gradlew` — pendiente de GitHub Actions, como
siempre. Ver el informe de esta fase para el desglose
IMPLEMENTADO/PARCIAL/PREPARADO campo por campo, sin maquillar resultados
(§45).
