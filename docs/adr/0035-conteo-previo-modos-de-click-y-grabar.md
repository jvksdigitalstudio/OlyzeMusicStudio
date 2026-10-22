# ADR 0035 — Conteo previo (count-in), modos de click y el botón de grabar conectado al transporte

**Estado:** Aceptado — **motor nativo verificado por tests (43 ejecuciones, 0 problemas, ASan/UBSan y TSan);
capa Kotlin y UI pendientes de confirmación por compilación Gradle y prueba en dispositivo** (ver «Límites de la
verificación»).
**Relacionado:** ADR 0028 (reloj y metrónomo), 0029 (pulso publicado), 0032 (metrónomo), 0033/0034 (tap y auditoría).
**Fase:** 1 de la hoja de ruta «metrónomo a nivel DAW». Las fases siguientes están al final.

> **Actualización (ADR 0036):** parar ahora PAUSA (conserva la posición) en lugar de rebobinar, y el conteo
> corre alineado al compás con la posición de la canción retenida. Las decisiones D1–D6 de este ADR siguen
> vigentes; solo cambia qué significa «parar» y «volver al principio».
> **Corrección de la fase 1:** el test `TransportCoordinatorTest` no se actualizó cuando «parar desarma la
> grabación» añadió `recordArmed=false` al registro de la secuencia. Quedó desactualizado y se corrigió en la
> fase 2 (los verificadores estáticos no ejecutan tests; solo Gradle lo habría detectado).

## Contexto

La auditoría comparó el metrónomo con la documentación de Ableton Live, Logic Pro, Cubase y Pro Tools. La carencia
de mayor impacto era que **grabar no hacía nada**: `MainViewModel.toggleRecord()` solo invertía un booleano de la UI,
sin relación con el motor. Faltaban, en consecuencia, las tres funciones que esos manuales describen alrededor de
grabar con click:

| Función | Referencia | Estado antes |
|---|---|---|
| Conteo previo de N compases | Ableton «Count-In» (1/2/4 compases), Cubase «Precount», Pro Tools «Countoff» | no existía |
| Cuándo suena el click (reproducir / grabar / solo conteo) | Pro Tools «Click During Play and Record / Only During Record / Only During Countoff», Logic «Click while recording / playing / count-in only» | solo ON/OFF |
| Grabar como modo del transporte | todos | botón decorativo |

## Decisiones

### D1 — El conteo se cuenta en COMPASES y termina siempre en una barra
`CountIn` (header-only, sin estado global ni E/S) recibe cada pulso del reloj y decide si es de conteo. Un compás se
da por completado al pasar su último pulso con el compás VIGENTE en ese momento. Contar pulsos (N × pulsos por
compás al armar) fallaría si el usuario cambia el compás durante el conteo: la grabación empezaría a mitad de
compás. Está cubierto por `test_count_in` y `test_transport_count_in` (caso «cambio de compás durante el conteo»).

### D2 — El reloj NO se reinicia al acabar el conteo
El pulso que sigue al último del conteo es el tiempo 1 de la grabación, en su frame exacto: sin hueco ni solape.
`test_transport_count_in` mide que el último click del conteo suena y el primero de la grabación no, con precisión de
muestra, y que la señal es **idéntica bit a bit** con bloques de 64, 480 y 4800 frames.

### D3 — La política del click vive en un solo sitio y el metrónomo sigue siendo tonto
`ClickPolicy.h` (`gateFor(modo, fase, grabaciónArmada)`) devuelve por pulso un `ClickGate`: `Follow` (manda el botón
del usuario), `Silence` o `Force`. `TransportEngine` lo evalúa por pulso y lo entrega al `Metronome` junto con la
lista de pulsos; el metrónomo no sabe qué es un conteo ni una grabación. El parámetro es opcional (`nullptr` = todos
`Follow`), de modo que **el contrato histórico de `Metronome::render` y todos sus tests no cambian**.

| Fase | `Always` | `RecordOnly` | `CountInOnly` |
|---|---|---|---|
| Conteo | Force | Force | Force |
| En marcha, grabación armada | Follow | Follow | Silence |
| En marcha, sin grabar | Follow | Silence | Silence |

«Follow» significa que el botón del metrónomo sigue siendo el interruptor general (como en Pro Tools: el click debe
estar habilitado para oírse). **El conteo suena aunque el botón esté apagado** (es una función aparte, como el botón
Count Off de Pro Tools): existe precisamente para oírse.

### D4 — Grabar es un modo del transporte
- `TransportCoordinator.toggleRecord()`: parado → arma y arranca (con conteo); en marcha sin grabar → arma, **sin
  conteo** (no hay nada que contar si ya suena); grabando → desarma y el transporte sigue.
- **Parar siempre desarma** (la grabación termina con el transporte).
- El motor decide si hay conteo **en el instante de arrancar**, por eso `TransportController` envía armado, duración
  y modo **antes** que el arranque (cubierto por un test de orden).
- Desarmar durante el conteo lo cancela en el motor y el transporte sigue como reproducción normal; así la capa
  Kotlin no necesita conocer la fase.
- `MainViewModel.isRecording` deja de ser un booleano de UI y pasa a derivarse del motor (`recordArmed`).

### D5 — El pulso publicado lleva la fase (bits 9–11, formato compatible)
`BeatPulse.h` usa ahora el bit 9 (`countIn`) y los bits 10–11 (compás del conteo, 0..3). `pack()` con los campos
nuevos por defecto produce un valor **idéntico** al formato anterior (`static_assert` en `test_count_in`). La capa
Kotlin lo decodifica en `BeatPulse.isCountIn` / `countInBar`. La transición `isCountIn: true → false` es el instante
en que «empieza a grabar» (el gancho para la captura de la fase 3).

### D6 — Valores por defecto
Conteo de **1 compás** (el «Count-in: 1 Bar» de Ableton) y modo `Always`. Reproducir sin grabar es idéntico a antes
porque el conteo solo existe con grabación armada. `kCountInBarsDefault` (C++) y
`EliNerTransportApi.DEFAULT_COUNT_IN_LENGTH` (Kotlin) se comprueban contra el header en
`TransportConstantsMatchNativeTest`.

## Cambios por capa

| Capa | Archivos |
|---|---|
| Vocabulario C++ (hoja) | `ClickSound.h` (+`ClickGate`), `CountIn.h` (nuevo), `ClickPolicy.h` (nuevo), `BeatPulse.h` |
| Motor | `Metronome.{h,cpp}` (gate por pulso), `TransportEngine.{h,cpp}` (fase, conteo, gates), `CommandQueue.h` (`RecordArmed`, `CountInBars`, `ClickMode`, append-only), `AudioEngine.h`, `AudioEngineControl.cpp` (validan), `AudioEngineCommands.cpp`, `EliNerTransportBridge.cpp` (3 funciones JNI) |
| `:eliner` Kotlin | `CountInLength.kt`, `ClickMode.kt` (nuevos), `BeatPulse.kt`, `EliNerTransportApi.kt`, `TransportNative.kt`, `EliNerTransportBridge.kt`, `TransportController.kt` |
| `:app` | `TransportCoordinator.kt`, `MainViewModel.kt`, `TempoPanelModel.kt`, `TempoPanelHost.kt`, `TempoPanel.kt`, `TempoLabels.kt`, `RecordingSection.kt` (nuevo) |
| Tests | `test_count_in`, `test_transport_count_in`, `test_engine_count_in` (nuevos), `TransportControllerTest`, `TransportConstantsMatchNativeTest`, `FakeTransport` |

## Verificación realizada

- Suite nativa completa: **43 ejecuciones, 0 problemas**, con ASan+UBSan y, para los tests con hilos, TSan; incluye
  la compilación del motor y de los puentes JNI contra stubs de plataforma.
- **Mutaciones**: se rompió a propósito la regla del conteo (gate Force→Follow, CountInOnly→Follow, contar pulsos en
  lugar de compases, play repetido que reinicia el conteo) y los tests fallaron en todos los casos.
- `tools/verify_kotlin_references.py` (192 archivos) y `tools/verify_architecture.py` (44 funciones JNI): sin
  violaciones nuevas.

## Límites de la verificación (importante)

- **Kotlin y Compose NO se compilaron** (no hay Gradle ni Kotlin en el entorno de trabajo): solo se ejecutaron los
  verificadores estáticos del repositorio. Hay que ejecutar `./gradlew :eliner:testDebugUnitTest :app:testDebugUnitTest`.
- No se probó en dispositivo. Lo que hay que comprobar al oído y a la vista: el conteo con el metrónomo apagado, el
  paso conteo→grabación sin hueco, y que el botón de grabar y el panel se comportan como se describe aquí.
- **No hay captura de audio ni MIDI todavía.** «Grabar» es hoy un estado del transporte que gobierna el conteo y el
  modo del click; la captura es de una fase posterior y colgará de `recordArmed` y de la transición `isCountIn`.

## Consecuencias aceptadas

- Si el motor se recrea (cambio de dispositivo de audio) con el transporte en marcha y la grabación armada, el
  arranque reaplicado vuelve a empezar por el conteo, igual que ya vuelve al primer tiempo.
- El conteo y el modo del click **no se guardan en los presets del metrónomo** ni persisten entre sesiones: son
  ajustes de grabación, y ampliar el formato versionado de presets queda para decidirlo con la persistencia de
  proyecto (fase 2).
- El START y el reloj MIDI hacia fuera salen al empezar el conteo, no al empezar la grabación.

## Pendiente dentro de esta fase

- ~~Indicador visual del conteo~~ — cerrado en la adenda del ADR 0036 (`TransportStatus`, en el área de trabajo y no
  en la barra superior, por su restricción de producto).

## Hoja de ruta (fases siguientes)

1. **Fase 2 — Posición y reloj externo:** línea de tiempo con PPQ, Song Position Pointer y Continue MIDI, pre-roll.
2. **Fase 3 — Latencia:** compensación con `getTimestamp()` de Oboe y reloj MIDI derivado del reloj nativo.
3. **Fase 4 — Mapa de tempo y compás:** cambios de tempo y de compás, rampas.
4. **Fase 5 — Sincronización de entrada:** reloj MIDI entrante y Ableton Link.
5. **Fase 6 — Click avanzado:** sonidos personalizados, swing, agrupación por compases compuestos, salida separada.
