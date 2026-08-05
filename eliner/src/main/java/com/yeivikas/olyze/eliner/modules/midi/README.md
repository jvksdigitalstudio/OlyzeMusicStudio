# EliNer — Modules / MIDI Engine

**Responsabilidad:** entrada/salida MIDI — dispositivos conectados, mensajes
Note On/Off, CC, Pitch Bend, MIDI Clock.

**Objetivo:** centralizar toda la lógica MIDI bajo una interfaz estable,
igual que se hizo con el audio en `eliner.api.EliNerAudioApi`.

**Futuro uso:** cuando el proyecto lo requiera, el `MidiManager` actual
(hoy independiente en `com.yeivikas.olyze.midi`) se formalizará como
implementación de un contrato `EliNerMidiApi` que vivirá aquí, siguiendo
el mismo patrón ya aplicado al audio.

**Dependencias:** ninguna hacia otros módulos EliNer. Es consumido por
`MainViewModel` y, a futuro, por el Timeline Engine (grabación MIDI) y el
Project System (guardar/cargar rutas MIDI).

**Estado actual:** implementación funcional real ya existe en
`com.yeivikas.olyze.midi.OlyzeMidiManager` (Android MIDI API). Se mantiene
fuera de `eliner` intencionalmente por ahora — ver nota de migración en
`eliner/documentation/ARCHITECTURE.md`. Esta carpeta queda reservada para
cuando se decida formalizar esa migración. No se modifica el `MidiManager`
actual en esta fase.
