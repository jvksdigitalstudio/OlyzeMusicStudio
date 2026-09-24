# EliNer — Modules / MIDI Foundation

**Responsabilidad:** infraestructura MIDI real — descubrimiento de
dispositivos (hot-plug), apertura de puertos, parseo de bytes MIDI
crudos a eventos tipados, una cola realtime-adjacent para transportarlos
del callback de Android hasta el resto del motor, routing hacia
consumidores registrados (Synth/Sampler/Automation, ninguno existe
todavía), MIDI Clock/Transport, y el contrato base de MIDI Learn.

**Estado actual: IMPLEMENTADO Y VERIFICADO (parcial) / PREPARADO, NO
IMPLEMENTADO (el resto).** Ver el informe de esta fase
(`Informe_MIDI_Foundation_OlyzeMusicStudio.md`, entregado junto al
proyecto) para el desglose exacto de qué es cada cosa — no lo repito aquí
para no arriesgar que este README y ese informe se desincronicen.

**Dependencias:** `eliner.api` (contratos: `MidiEvent`, `MidiDeviceInfo`,
`EliNerMidiApi`, etc.), `eliner.core` (`EliNerModule`, `StateMachine`,
`EngineError`), `eliner.diagnostics` (`Logger`), `eliner.events`
(`EventBus`), `eliner.services` (`ThreadManager`/`TaskExecutor`,
`TimeService`/`TimeProvider`) — todas capas ya existentes, ninguna nueva.
Cero dependencia hacia Synth/Sampler/DSP/UI (§30 — MIDI produce eventos,
no llama directamente a nada que los consuma).

**`android.media.midi` está confinado a `AndroidMidiBackend.kt`** — es el
único archivo de todo `eliner.modules.midi` que lo importa (§26); todo lo
demás depende solo de `MidiPlatformBackend` (la interfaz).

**Sobre `com.yeivikas.olyze.midi.OlyzeMidiManager` (en `:app`) — MIGRADO
(ver ADR 0013):** ya no existe. Reimplementaba desde cero conexión a
dispositivo, `MidiManager.DeviceCallback` y envío de bytes
`android.media.midi.*` — la misma responsabilidad que `AndroidMidiBackend`
+ `MidiDeviceManager` ya resolvían, duplicada en paralelo. Reemplazado por
[`MidiOutputBridge`](../../bridge/MidiOutputBridge.kt)
(`eliner.bridge`), que traduce las mismas llamadas de alto nivel
(nota/CC/pitch-bend/transporte) a `MidiEvent` + `EliNerMidiApi.send(...)`
sobre la MISMA instancia de `MidiFoundationModule` que ya maneja la
entrada — una sola implementación de "hablar con dispositivos MIDI", no
dos. El trabajo de seguimiento que este README dejaba pendiente
("migrar `OlyzeMidiManager` para que use `EliNerMidiApi`") queda cerrado.

**Conectado a `:app` de verdad** — no es infraestructura huérfana como el
resto del stack Kotlin desconectado (ver ADR 0010): `MainViewModel`
construye `MidiFoundationModule` directamente (mismo patrón que
`DeviceCapabilityManager`/`PerformanceProfileManager`, sin pasar por
`EliNerRuntime`/`EliNerCore` — evita a propósito el bug de lifecycle A-1
ya documentado) y lo arranca/detiene en su propio ciclo de vida.
