# EliNer — API pública (`eliner.api`)

**Responsabilidad:** el contrato que una aplicación usa para hablar con el
motor. Es lo único de `:eliner` (junto con `EliNerEngineFactory`) que `:app`
puede importar.

**Regla de dependencia:** `eliner.api` no depende de ningún otro paquete de
EliNer — solo de `eliner.api.*` y de `kotlinx.coroutines`. Se comprueba en
CI con `tools/verify_architecture.py`. Un contrato público que importa una
implementación deja de ser un contrato.

| Paquete | Contenido |
|---|---|
| `api` | `EliNerEngine` — **punto de entrada único** (audio, MIDI, estado de arranque, `start()`/`shutdown()`); `EngineInitState` |
| `api.audio` | `EliNerAudioApi` (notas, FX, parámetros, métricas), `DspModuleType`, `ParameterCatalog`, `EngineErrorFlags`, `PerformanceProfile` |
| `api.midi` | `EliNerMidiApi` (dispositivos, eventos, bindings), `MidiOutputApi` (salida al dispositivo activo), `MidiEvent`, `MidiDevice*`, `MidiParameterBinding`, `MidiDeviceEvent`, `MidiMetricsSnapshot`, `MidiConsumer` |
| `api.event` | `EliNerEvent` — marcador de lo publicable en el bus de eventos |

**Uso típico (app):**

```kotlin
val engine: EliNerEngine = EliNerEngineFactory.create(application)
engine.start()                       // no bloquea; observar engine.state
engine.audio.noteOn(0, 60, 100)
engine.midiOutput.sendStart()
// …
engine.shutdown()                    // exactamente una vez
```

**Qué NO va aquí:** contratos del kernel (`RuntimeContext`, `ModuleLoader`,
`ServiceRegistry` → `eliner.runtime`) ni contratos de módulos internos
(`AudioEngineApi` → `eliner.modules.audio`, `DspApi` → `eliner.dspfoundation`).
Historia de esta separación: ADR 0027.
