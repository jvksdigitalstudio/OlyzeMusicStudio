# ADR 0030 — Desacople por responsabilidad: motor nativo y UI

**Estado:** Aceptado — **pendiente de confirmación por compilación Kotlin/NDK y prueba en
dispositivo** (ver «Límites de la verificación»).
**Relacionado:** ADR 0027 (capas y fachada), ADR 0028 (transporte), ADR 0029 (pulso y panel de
tempo). Cierra el pendiente «partir `AudioEngine.cpp`» de los ADR 0027 y 0029.

## Contexto

Tras los ADR 0028–0029 varias piezas acumulaban más de una responsabilidad:

| Pieza | Líneas | Mezclaba |
|---|---|---|
| `AudioEngine.cpp` | 873 | ciclo de vida del stream, callback, aplicación de comandos, API de control, cadena de FX, **polifonía**, **reloj/metrónomo/pulso/estado del sync** |
| `AudioEngine.h` | ~430 | la clase + constantes + enum de ciclo de vida + flags de error |
| `Header.kt` | 393 | contenedor de la barra, grupo de transporte, 4 botones, metrónomo con animación, control de BPM, un botón huérfano |
| `TempoControls.kt` | 389 | hoja, puntos de pulso, rejilla de divisiones, chips, estilos |
| `PianoKeyboard.kt` | 549 | gestos, layout y **contabilidad pura** de pulsaciones |
| `MainViewModel.kt` | — | estado de UI **y la secuencia** play/stop (motor + MIDI + reloj + notas) |
| `AudioCommandDispatcher.kt` | 489 | mecanismo (hilo único, colas) **y** vocabulario de parámetros coalescibles |

Además, `test_midi_channel_voice_routing` probaba una **réplica** del `switch` del motor (porque
el motor incluye Oboe): una réplica puede divergir del código real sin que nada lo detecte.

**Regla aplicada:** una pieza = una razón para cambiar. Se extrae cuando hay un estado propio y
coherente que se puede probar aislado; no se parte por partir. El **comportamiento no cambia**:
es una reorganización, no una reescritura.

## Decisión

### 1. Motor nativo: dos colaboradores nuevos y cuatro unidades de compilación

Dos clases con estado propio, sin Oboe, probables aisladas:

- **`dsp/VoicePool`** — polifonía: reparto de voces, robo de la más antigua, pitch bend por
  canal, contrato de canal MIDI 0–15 (ADR 0020). Sustituye a `mVoices` + `mChannelPitchBend` y
  a la lógica que vivía dentro de `applyCommand`.
- **`transport/TransportEngine`** — posee el tiempo musical: reloj, metrónomo, publicación de
  pulso (ADR 0029) y el **estado** del delay sincronizado. No conoce colas, Oboe, voces ni la
  cadena de FX: solo **calcula** cuánto debe durar el eco (`delaySyncSeconds`); aplicárselo al
  Delay lo hace `AudioEngine`, que es quien conoce la cadena.

`AudioEngine` pasa a ser un **orquestador**: dueño del stream, la cola de comandos, la cadena de
FX y los dos colaboradores. Su `.cpp` se reparte **por responsabilidad** (una clase, su contrato
público intacto en un solo header):

| Unidad | Hilo | Responsabilidad |
|---|---|---|
| `AudioEngine.cpp` | control / Oboe / audio | ciclo de vida (start/stop/reopen), callback, render del bloque, métricas |
| `AudioEngineCommands.cpp` | **audio** | vaciar la cola y aplicar cada comando (decodifica y **delega**) |
| `AudioEngineControl.cpp` | control | API pública que valida y **encola** (MIDI, master, transporte, FX legacy) |
| `AudioEngineFxChain.cpp` | control | alta/baja/movimiento de módulos, espejo de control, recogida de retirados |

Los vocabularios salen de `AudioEngine.h`: `EngineConstants.h`, `LifecycleState.h`,
`EngineErrorFlags.h` (headers hoja; `AudioEngine.h` los incluye, así que ningún consumidor cambia).
Los macros de log pasan a `src/main/cpp/core/EngineLog.h`, **privado de `src/`**: incluirlo desde
un header público arrastraría `<android/log.h>` a todos los consumidores.

**API pública de `AudioEngine`: sin cambios**; los puentes JNI no se tocaron.

### 2. `test_voice_pool` sustituye a la réplica

`test_midi_channel_voice_routing` se reemplaza por `test_voice_pool`, que ejercita el
`VoicePool` **real**: los mismos escenarios (bug de NoteOff entre canales, bend por canal y su no
filtrado a voces recicladas, canales inválidos, pánico global, 16 canales) más robo de voz y el
conteo que devuelve `render()`. Nuevo `test_transport_engine`: avance, pulso, parada, cambio de
tempo en marcha, cálculo del delay (incluido el plegado por octavas) y click en su bus.
`VoicePool::countActive` es la única superficie de introspección añadida (solo lectura).

`run_native_tests.sh` define **una sola lista** de fuentes del motor (`ENGINE_SRCS`): añadir o
mover un archivo del motor se toca en un sitio, no en cada test.

### 3. UI (`:app`): paquetes por responsabilidad

```
ui/components/
  transport/  AppHeader (solo contenedor) · TransportGroup (compone) · TransportButtons
              (Rec, Rebobinar, Play) · MetronomeButton (pulso) · BpmControl
  tempo/      TempoControlsSheet (layout) · BeatDots · DelayDivisionGrid · TempoWidgets
  piano/      PianoKeyboard · PianoKey · PianoKeyModel · PianoKeyStyle · PianoRowIcon ·
              NoteHoldTracker (contabilidad pura, nueva)
  channels/   AddChannelButton · AddChannelSheet
```

- `NoteHoldTracker` extrae del composable la contabilidad 0→1 / 1→0 de pulsaciones (arreglo del
  solapamiento de doble fila). Es Kotlin puro: ahora tiene test JVM (`NoteHoldTrackerTest`).
- **Eliminado código muerto del header:** `KeyboardToggleBtn` (nunca se colocó en pantalla) y los
  parámetros `keyboardVisible`/`onKeyboardToggle` de `AppHeader` (se recibían y no se usaban).
  `MainViewModel.toggleKeyboard()` queda sin llamadores (ver «Pendientes»).

### 4. `TransportCoordinator` (`:app/transport`)

La **secuencia** de reproducir/parar salió de `MainViewModel`: arrancar = motor → MIDI START →
reloj; parar = reloj → motor → MIDI STOP → silenciar notas. El orden importa (ningún pulso de
reloj tras STOP; START antes del primer pulso) y ahora está fijado por `TransportCoordinatorTest`
con dobles que escriben en un registro común. Depende de la capacidad mínima `MidiClock`
(implementada por `MidiClockGenerator`), no de corrutinas.

### 5. Motor Kotlin: `ParamTarget` aparte

`AudioCommandDispatcher` mezclaba **mecanismo** (hilo único, colas, vaciado) con **vocabulario**
(qué parámetros coalescen y a qué método de `EliNerAudioApi` corresponde cada uno). El
vocabulario vive ahora en `bridge/ParamTarget.kt` (`internal`): añadir un parámetro coalescible
toca un archivo.

### 6. Herramienta: `tools/report_unreachable.py`

Informe de solo lectura de los 55 archivos Kotlin de producción no alcanzables desde la app (el
stack paralelo kernel/runtime/foundation, ADR 0010). **No se eliminaron ni se movieron**: es una
decisión de producto con 9 ADR detrás, no una limpieza mecánica (ver «Pendientes»).

## Verificación realizada

| Comprobación | Resultado |
|---|---|
| Suite nativa (ASan+UBSan; TSan en los tests con hilos) | **33 ejecuciones, 0 problemas** (antes 32) |
| `check_engine_compile.sh` (g++ `-Wall -Wextra`, 11 unidades incl. las 4 de `AudioEngine`) | todas OK, sin warnings |
| Tests de integración del motor REAL tras el reparto (transporte, pulso, concurrencia, test que cuenta asignaciones en el hilo de audio) | pasan sin tocar sus aserciones |
| `verify_architecture.py` (V1–V8) y `verify_kotlin_references.py` | OK |
| `NoteHoldTrackerTest`, `TransportCoordinatorTest` | escritos, **no ejecutados** |

## Corrección posterior (primer CI tras el cambio)

El primer CI falló en `:app:compileDebugKotlin` con dos errores en `TempoControlsSheet.kt`
(`State<…> has no method getValue` y `Unresolved reference 'currentOnDismiss'`, el segundo
consecuencia del primero). Causa: al repartir `TempoControls.kt` mi podador de imports descartó
`androidx.compose.runtime.getValue`, que nunca aparece escrito (lo usa el `by`). Corregido, y se
añadió la regla **R3** a `verify_kotlin_references.py` (delegado de Compose sin
`getValue`/`setValue`); se comprobó que la regla reproduce exactamente ese fallo (línea 215) y que
no hay otros casos en el repositorio. Ese fue el único error de compilación del CI.

## Límites de la verificación (declarados)

- **Kotlin NO se compiló** aquí (sin Gradle/Android SDK/`kotlinc`). Los verificadores estáticos
  no detectan errores de tipos ni de Compose. Los movimientos de la UI son mecánicos (mismo
  cuerpo, paquete nuevo, imports recalculados y comprobados contra el uso real), pero **debe
  confirmarlos la compilación**. Si falla, lo esperable son imports o visibilidad `internal`.
- **`CMakeLists.txt` no se ejecutó** (sin NDK/CMake): se actualizó la lista de fuentes y se
  comprobó contra el compilador de sintaxis, no contra un enlace real. Un archivo olvidado se
  manifestaría como símbolo sin resolver al enlazar.
- Sin prueba en dispositivo.

## Pendientes y decisiones que requieren tu aprobación

1. **Los 55 archivos del stack paralelo:** conectarlos o retirarlos. Datos en
   `python3 tools/report_unreachable.py`. No se tocaron.
2. **`MainViewModel` sigue siendo un único ViewModel** (estado de UI + órdenes). Partirlo en
   varios exige un propietario del motor a nivel de `Application` (hoy el ViewModel crea el
   motor); eso cambia el manifest y el ciclo de vida y no se puede validar sin compilar.
3. **`MainViewModel.toggleKeyboard()`** quedó sin llamadores al retirar el botón huérfano:
   quitarlo o volver a cablearlo (requiere autorizar tocar el header).
4. **`PianoKeyboard` (≈ 520 líneas)** sigue siendo un composable grande (gestos de altura/zoom,
   cabecera, tiras). Los gestos comparten mucho estado local; partirlo bien es un rediseño
   para una iteración con build de CI.
5. **Compases x/8** (ADR 0029): necesitan una unidad de pulso configurable en el reloj.
