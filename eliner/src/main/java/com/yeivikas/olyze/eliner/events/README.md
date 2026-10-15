# EliNer — Events — Event Bus IMPLEMENTADO (Fase 2)

**Responsabilidad:** bus de eventos interno — el mecanismo que evitará que
módulos futuros (ej. Audio y MIDI) se llamen directamente entre sí.

**Archivos:**
- [`EliNerEvent`](../api/event/EliNerEvent.kt) — contrato base, sin conocer
  ningún dominio específico. **Vive en `eliner.api.event`** (ADR 0027): es
  vocabulario público que implementan eventos de varios paquetes (incluido
  `api.midi.MidiDeviceEvent`), y el bus depende de él, no al revés.
- [`EventBus.kt`](EventBus.kt) — `publish()` + `subscribe<T>()` genérico,
  tipado con reified generics.
- [`EngineStateChangedEvent.kt`](EngineStateChangedEvent.kt) — el único
  evento concreto de esta fase.

**Por qué solo existe un evento concreto:** la taxonomía completa
documentada en la Fase 3 (`SystemEvent`, `AudioEvent`, `MidiEvent`,
`ProjectEvent`, `DiagnosticEvent`, `HardwareEvent`) sigue siendo un mapa
conceptual, no tipos Kotlin — crear esas 6 interfaces vacías ahora habría
sido exactamente el "código de relleno" prohibido en esta fase. Cada
dominio define sus propios eventos cuando se implemente ese módulo;
`EventBus` no necesita conocerlos de antemano — esa es la garantía real que
da un bus genérico tipado con `EliNerEvent` como única raíz.

**`EngineStateChangedEvent` no está conectado automáticamente:** publicar
este evento cada vez que cambia `EliNerCore.state` requiere que algo
recolecte ese `StateFlow` en una corrutina — esa raíz de composición no
existe todavía en esta fase (ver `eliner.services.ThreadManager`). El tipo
del evento existe y es correcto; el cableado automático es trabajo futuro.

**Dependencias:** `EventBus.kt` — `eliner.api.event.EliNerEvent` y
`kotlinx.coroutines.flow`. `EngineStateChangedEvent.kt` —
`eliner.core.EngineState` y `eliner.api.event.EliNerEvent`. Matriz de capas:
`docs/eliner/ARCHITECTURE.md`.

**Estado actual:** mecanismo real y funcional. Taxonomía de dominios
documentada arriba, no implementada — se materializa módulo por módulo.
