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

**Sobre `com.yeivikas.olyze.midi.OlyzeMidiManager` (en `:app`):**
sigue sin tocarse — es MIDI de salida únicamente (app → hardware externo)
y ya funciona. Esta fase construyó la mitad que faltaba (entrada: recibir
eventos DE un controlador externo), que no existía en absoluto antes.
Migrar `OlyzeMidiManager` para que implemente `EliNerMidiApi` en vez de
mantenerse aparte es trabajo de seguimiento explícitamente fuera de esta
fase (§42 — no tocar código estable existente sin necesidad estricta).

**Conectado a `:app` de verdad** — no es infraestructura huérfana como el
resto del stack Kotlin desconectado (ver ADR 0010): `MainViewModel`
construye `MidiFoundationModule` directamente (mismo patrón que
`DeviceCapabilityManager`/`PerformanceProfileManager`, sin pasar por
`EliNerRuntime`/`EliNerCore` — evita a propósito el bug de lifecycle A-1
ya documentado) y lo arranca/detiene en su propio ciclo de vida.
