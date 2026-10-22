# ADR 0013 — Desacoplamiento: fusión de las dos implementaciones MIDI y extracción de `handleExternalMidiEvent` del ViewModel

**Estado:** Aceptado. Ejecutado.

## Contexto

Una auditoría externa del proyecto (revisión de responsabilidades/
acoplamiento, independiente de las fases numeradas de EliNer) identificó
cuatro problemas concretos de responsabilidad mezclada. Esta ADR cierra
dos de los cuatro — los dos que no dependían de una decisión de producto
pendiente. Los otros dos quedan fuera, a propósito (ver "Qué se dejó
fuera" al final).

### Problema 1 — dos implementaciones paralelas de "hablar con dispositivos MIDI"

`com.yeivikas.olyze.midi.OlyzeMidiManager` (en `:app`) importaba
`android.media.midi.*` directamente y reimplementaba desde cero: apertura
de dispositivo, `MidiManager.DeviceCallback`, envío de bytes NOTE_ON/
NOTE_OFF/CC/PITCH_BEND/CLOCK/START/STOP/CONTINUE. Esto era exactamente lo
mismo que `AndroidMidiBackend` + `MidiDeviceManager` + `MidiRouter` +
`MidiClockEngine` (en `:eliner`, `eliner.modules.midi`) ya resolvían,
detrás de `EliNerMidiApi` — pero de forma ad hoc, sin pasar por esa
abstracción. Un bug de conexión (p. ej. Bluetooth) habría requerido
arreglarse en dos sitios con lógica distinta.

Esto era deuda ya documentada, no una novedad de esta ADR: el propio
`eliner.modules.midi/README.md` (sección "Sobre OlyzeMidiManager") y
`docs/adr/0012-midi-foundation.md` (sección "Qué se dejó fuera") decían
explícitamente que migrar `OlyzeMidiManager` a `EliNerMidiApi` era
"trabajo de seguimiento explícitamente fuera de esta fase" — nunca una
decisión de "no tocar", a diferencia del stack `modules.audio`/
`dspfoundation` (ver ADR 0010, y "Qué se dejó fuera" más abajo).

### Problema 2 — `MainViewModel` traduciendo protocolo MIDI, no solo orquestando

`MainViewModel.handleExternalMidiEvent` (privado) traducía `MidiEvent`
crudos (NOTE_ON/OFF, CONTROL_CHANGE, PITCH_BEND) a llamadas de
`EliNerAudioApi`. El propio comentario en el código ya señalaba: "debería
vivir en su propia clase... se metió en el ViewModel por rapidez, pero
para mantenimiento futuro serio no es el lugar correcto." El ViewModel
además construía directamente `ThreadManager`/`EventBus`/`LoggerService`/
`TimeService` y controlaba el reloj MIDI — acumulando responsabilidades
de infraestructura, traducción de protocolo y orquestación de UI en una
sola clase.

## Decisión

### 1. `OlyzeMidiManager` → `eliner.bridge.MidiOutputBridge`

Nueva clase en `eliner.bridge` (no en `:app`): recibe la misma
`EliNerMidiApi` que `MainViewModel` ya construye para entrada, observa
`EliNerMidiApi.devices` para elegir automáticamente el primer dispositivo
con puerto de salida disponible (mismo comportamiento de auto-conexión
que tenía `OlyzeMidiManager.refreshDevices()`), y traduce cada llamada de
alto nivel (`sendNoteOn`, `sendNoteOff`, `sendCC`, `sendPitchBend`,
`sendClock`, `sendStart`, `sendStop`, `sendContinue`) a un `MidiEvent` +
`EliNerMidiApi.send(portId, event)`.

No importa `android.media.midi` en ningún punto — `AndroidMidiBackend`
sigue siendo el único archivo de todo el proyecto que lo hace (§26, sin
excepción nueva). No posee el ciclo de vida de `EliNerMidiApi` (no llama
`start()`/`stop()`/`shutdown()` sobre ella) — solo observa su
`StateFlow<List<MidiDeviceInfo>>`, exactamente el mismo patrón de
no-apropiación que ya usaba `MidiRouter` frente a `taskExecutor`.

`com.yeivikas.olyze.midi.OlyzeMidiManager` fue **eliminado** (no dejado
en desuso) — la superficie pública que la UI/ViewModel consumía
(`sendNoteOn`/`sendNoteOff`/`sendCC`/`sendPitchBend`/`sendClock`/
`sendStart`/`sendStop`/`sendContinue`/`close`) se preservó 1:1 en
`MidiOutputBridge`, así que `MainViewModel` solo cambió de dónde
construye `midiManager`, no cómo lo usa.

### 2. `handleExternalMidiEvent` → `eliner.bridge.MidiToSynthBridge`

Nueva clase en `eliner.bridge`: recibe `EliNerAudioApi`, expone
`externalActiveNotes: StateFlow<Set<Int>>` (el mismo estado que antes
vivía en el ViewModel) y un `consumer: MidiConsumer` para que el dueño
(`MainViewModel`) lo registre/desregistre contra
`EliNerMidiApi.registerConsumer`/`unregisterConsumer`. La lógica de
traducción NOTE_ON/OFF/CC/PITCH_BEND es exactamente la que tenía
`MainViewModel` — se movió, no se reescribió, para no introducir
comportamiento nuevo sin poder compilar y probar en dispositivo (mismo
criterio de riesgo que ya aplicó ADR 0010 para no tocar código sin
verificación real).

`MainViewModel` conserva la orquestación (transporte, BPM, reloj MIDI,
estado de UI) — que sí es su responsabilidad — y pierde la traducción de
protocolo, que no lo era.

## Qué NO cambió (a propósito)

- **ADR 0010 permanece cerrado.** `eliner.modules.audio`,
  `eliner.audiofoundation`, `eliner.dspfoundation` — el stack Kotlin
  desconectado del motor nativo — no se tocaron. Esa ADR documenta una
  decisión final ya confirmada por el usuario (opción C: dejarlo
  documentado, sin conectar ni retirar). Revertir esa decisión sin una
  confirmación explícita nueva habría violado la misma regla que el
  propio proyecto se impone (§42 — no tocar código estable sin necesidad
  estricta), aplicada aquí en sentido inverso: tampoco se deshace una
  decisión ya cerrada sin que quien la tomó la reabra.
- **`DspChain.h` (C++, el motor real) vs. `DspChain.kt`
  (`dspfoundation`, capa desconectada) siguen ambos existiendo con el
  mismo nombre.** Es una consecuencia directa de dejar `dspfoundation`
  intacto (punto anterior) — no un descuido de esta ADR. Si en el futuro
  se retira o renombra `dspfoundation.DspChain`, la colisión de nombres
  se resuelve como efecto secundario de esa decisión, no de esta.
- El motor nativo, `EliNerAudioBridge`, `MidiFoundationModule`,
  `AndroidMidiBackend`, `MidiRouter`, `MidiClockEngine`,
  `MidiDeviceManager` — sin cambios funcionales. Esta ADR solo mueve
  código existente a su responsabilidad correcta; no reescribe lógica de
  audio/MIDI ya verificada.

## Build

Mismo entorno sin red/NDK/Gradle real que todas las fases anteriores del
proyecto — no se pudo ejecutar `./gradlew`. Verificación manual
realizada: balance de llaves/paréntesis en los 3 archivos tocados/creados
(`MainViewModel.kt`, `MidiOutputBridge.kt`, `MidiToSynthBridge.kt`);
contraste línea por línea de cada firma usada (`EliNerMidiApi.send`,
`EliNerMidiApi.devices`, `MidiFoundationModule.create`,
`EliNerAudioApi.noteOn/noteOff/sendCC/setPitchBend`, `TimeProvider`)
contra su declaración real en `eliner.api`/`eliner.services`; rastreo de
imports (ningún import roto, ninguna referencia a
`com.yeivikas.olyze.midi.*` sobreviviente en código, solo en comentarios
históricos). Pendiente de confirmación real vía compilación en
dispositivo/GitHub Actions, como toda fase anterior de este proyecto.

## Archivos

**Creados:**
- `eliner/src/main/java/com/yeivikas/olyze/eliner/bridge/MidiOutputBridge.kt`
- `eliner/src/main/java/com/yeivikas/olyze/eliner/bridge/MidiToSynthBridge.kt`
- `docs/adr/0013-desacoplamiento-midi-output-y-viewmodel.md` (este archivo)

**Eliminados:**
- `app/src/main/java/com/yeivikas/olyze/midi/MidiManager.kt` (contenía `OlyzeMidiManager`)

**Modificados:**
- `app/src/main/java/com/yeivikas/olyze/MainViewModel.kt`
- `README.md`
- `docs/adr/0012-midi-foundation.md`
- `eliner/src/main/java/com/yeivikas/olyze/eliner/modules/midi/README.md`
- `eliner/src/main/java/com/yeivikas/olyze/eliner/documentation/ARCHITECTURE.md`
