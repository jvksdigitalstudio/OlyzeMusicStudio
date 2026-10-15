# ADR 0027 — Reorganización por capas y fachada `EliNerEngine`

**Estado:** Aceptado — pendiente de confirmación por compilación en CI
(ver «Límites de la verificación»).
**Enmienda parcialmente:** ADR 0006 (`RuntimeContext` en `eliner.api`) y
ADR 0022 («`api/` solo importa contratos»).
**No reabre:** ADR 0010 (stack Kotlin paralelo, «opción C»).

## Contexto

Auditoría estructural pedida por el propietario: módulos desacoplados, sin
dependencias combinadas ni responsabilidades mezcladas, con la API de EliNer
claramente definida. Medido con `tools/verify_architecture.py` sobre el
árbol original (120 archivos Kotlin), contra reglas de capas derivadas de lo
que el propio código ya declaraba (p. ej. el KDoc de `MidiParameterBinding`:
«`eliner.api` sits below `eliner.dspfoundation` … a binding contract in the
API layer must not import a Foundation layer type»):

**24 violaciones** (13 de capas + 11 de acceso a internos desde `:app`).

1. **`eliner.api` dependía de implementación** (13): `RuntimeContext`,
   `ModuleLoader`, `EliNerAudioApi` (vía `services.PerformanceProfile`) y
   `MidiDeviceEvent` (vía `events.EliNerEvent`) arrastraban `services`,
   `events`, `diagnostics`, `configuration`, `resources` y `core` dentro del
   paquete que debía ser el contrato público.
2. **`:app` importaba 11 clases internas de EliNer** (`bridge`, `services`,
   `diagnostics`, `events`, `modules.midi`) desde `AppServices`, contra la
   regla documentada de que `:app` solo depende de la API pública.
3. **Un mismo paquete `api` mezclaba tres cosas distintas**: contrato de app
   (audio, MIDI), contrato del kernel (`Runtime*`, `ModuleLoader`,
   `ServiceRegistry`) y contratos de módulos del kernel (`AudioEngineApi`,
   `DspApi` y sus estados/métricas).
4. **Archivos cuyo nombre no reflejaba su contenido**: el contrato público
   `EliNerMidiApi` + `MidiConsumer` vivía en `MidiParameterBinding.kt`;
   `MidiDeviceEvent` y `MidiMetricsSnapshot` en `MidiEvents.kt`;
   `PerformanceProfileProvider` en `PerformanceProfile.kt`.
5. **Responsabilidad de temporización dentro del `ViewModel`**: el bucle del
   reloj MIDI vivía en `MainViewModel`, junto al estado de UI.
6. **Ruido estructural**: 11 directorios con solo un README de un módulo
   *previsto* dentro del source set de producción (`hardware`, `recovery`,
   `interfaces`, `tests`, `modules/{dsp,mixer,plugin,project,render,timeline}`),
   documentación de 50 KB dentro de `src/main/java`, y un stub C++
   (`Mixer`) instanciado en `AudioEngine` y nunca leído.

No hay ciclos de paquetes en el original (verificado); el problema era de
dirección de dependencias y de responsabilidades, no de ciclos.

## Decisión

### 1. `eliner.api` pasa a ser solo contrato de aplicación, sin dependencias internas

```
api/                 EliNerEngine (fachada), EngineInitState
api/audio/           EliNerAudioApi, DspModuleType, ParameterCatalog,
                     EngineErrorFlags, PerformanceProfile
api/midi/            EliNerMidiApi, MidiConsumer, MidiOutputApi, MidiEvent,
                     MidiDevice*, MidiParameterBinding, MidiDeviceEvent,
                     MidiMetricsSnapshot
api/event/           EliNerEvent

contracts/           DspApi, DspState, DspMetricsSnapshot (compartidos entre módulos)
```

- `PerformanceProfile` (enum de vocabulario) sube de `services` a `api.audio`;
  `PerformanceProfileProvider` (contrato interno) queda en `services`, en su
  propio archivo.
- `EliNerEvent` (marcador de una línea) pasa de `events` a `api.event`: el
  bus y los módulos dependen del contrato, no al revés. Va en un subpaquete
  hoja propio para que la fachada (`api`) quede arriba y los dominios
  (`api.audio`, `api.midi`) abajo, **sin ciclo `api ↔ api.midi`** (el
  verificador detectó ese ciclo en un primer intento).

### 2. Los contratos del kernel salen de `api` y viven junto a su implementación

- `RuntimeContext`, `ModuleLoader`, `ServiceRegistry`, `EliNerRuntimeApi`,
  `RuntimeState` → `runtime`.
- `AudioEngineApi`, `AudioEngineState`, `AudioMetricsSnapshot` → `modules.audio`.
- `DspApi`, `DspState`, `DspMetricsSnapshot` → **`contracts`**, un paquete
  hoja nuevo. No a `dspfoundation`: `AudioEngine` (`modules.audio`) acepta un
  `DspApi` y `DspFoundation` lo implementa; ponerlo en `dspfoundation`
  habría acoplado `modules.audio → dspfoundation`, justo lo que los README
  de ambos paquetes dicen que no debe ocurrir (los dos módulos solo se
  conocen por la interfaz). Criterio de admisión de `contracts`: contratos
  usados por dos o más módulos hermanos; uno usado por un solo módulo vive
  con él (`AudioEngineApi` → `modules.audio`). Realiza la necesidad que el
  antiguo README de `eliner/interfaces` describía.

**Enmienda a ADR 0006/0022.** Aquella decisión movió el «vocabulario
público» a `api` para romper un ciclo `api ↔ runtime`. Tenía sentido con la
estructura de entonces, pero trataba el síntoma: el ciclo existía porque el
contrato de la *app* y el contrato del *kernel* compartían paquete. Al
separar ambos, la causa desaparece sin esconder el kernel en la API pública.
Resultado verificable: 0 ciclos y `api` sin ninguna dependencia fuera de
`api.*` (antes: 13 imports hacia `services`, `events`, `diagnostics`,
`configuration`, `resources` y `core`).

### 3. Fachada única `EliNerEngine` y raíz de composición dentro de EliNer

- `api.EliNerEngine`: `audio`, `midi`, `midiOutput`, `externalActiveNotes`,
  `state`, `start()`, `shutdown()`.
- `api.midi.MidiOutputApi`: contrato de salida MIDI; `MidiOutputBridge` pasa
  a implementarlo (solo se añadieron `override`).
- `composition.EliNerEngineFactory` (público) + `DefaultEliNerEngine`
  (`internal`). `DefaultEliNerEngine` **es `AppServices` portado por
  transformación**, no reescrito: mismo orden de arranque/apagado, mismo
  timeout de 5 s, misma semántica de cancelación y la documentación de
  decisiones íntegra. `EngineInitState` se mueve de `:app` a `api`.
- `:app` borra `AppServices.kt` y `EngineInitState.kt`; `MainViewModel` solo
  conoce `eliner.api.*` y `eliner.composition`.

Razón: *cómo se ensambla el motor* es conocimiento del motor. Cualquier otra
app del ecosistema (p. ej. Olyze Movie Creator) lo obtiene sin copiarlo.

### 4. El reloj MIDI sale del `ViewModel`

`transport.MidiClockGenerator` (en `:app`): solo temporización de 24 PPQN.
**Cambio de comportamiento declarado:** `setBpm` ya no reinicia el reloj; el
generador lee el BPM en cada pulso. Antes, cambiar el tempo durante la
reproducción emitía un pulso extra inmediato. Test:
`MidiClockGeneratorTest` (valores calculados a mano).

### 5. Limpieza del árbol

- `documentation/*.md` → `docs/eliner/` (la documentación no va en el source set).
- Los 10 README de módulos previstos (+ `eliner/interfaces`) se conservan
  **íntegros** en `docs/eliner/ROADMAP_MODULES.md`; los directorios vacíos
  se eliminan.
- C++: se elimina `Mixer.h` (stub vacío) y el miembro `mMixer`, que se
  asignaba una vez y no se leía en ningún sitio (`grep -rnw mMixer`: 0 lecturas).

### 5b. `PianoKeyboard.kt` (706 líneas) dividido por responsabilidad

Mismo paquete (`ui.components`), sin cambios de imports externos:
`PianoKeyModel.kt` (`KeyInfo`, nombres de nota, `ALL_KEYS`),
`PianoKeyStyle.kt` (formas, degradados y colores precalculados),
`PianoKey.kt` (`WhiteKey`, `BlackKey`) y `PianoKeyboard.kt` (composable
principal + `PianoKeysStrip`, 549 líneas). Los `private` de primer nivel que
ahora cruzan archivos pasan a `internal` (solo los que se usan entre
archivos; `BLACK_SEMITONES` sigue `private`); un script comprobó que no
queda ningún `private` usado fuera de su archivo. Los imports se podaron por
uso real; los comodines se conservan donde el archivo usa Compose. Sin
cambio de lógica ni de firmas públicas. **Sin compilar** (ver límites).
`PianoKeyboard` sigue siendo grande (composable con gestos y estado de
scroll/zoom); partirlo más exige rediseño de estado, no un corte mecánico.

### 6. Barrera automática

`tools/verify_architecture.py` (Python estándar, sin Android) comprueba:
paquete = directorio, imports resueltos, tipos usados sin import, FQN
duplicados, matriz de capas (documentada en `ARCHITECTURE.md`), ausencia de
ciclos y que `:app` solo importe `eliner.api` + `eliner.composition`. Se
ejecuta como primer paso de `.github/workflows/build.yml`.

## Qué NO se hizo, y por qué

- **No se eliminó el stack Kotlin paralelo** (`audiofoundation`,
  `dspfoundation`, `modules.audio`, `runtime` y los servicios que solo ellos
  usan: 55 de los 106 archivos de `src/main` no son alcanzables desde `:app`
  y ninguno tiene tests; análisis por nombre de símbolo, conservador). Es la
  «opción C» de ADR 0010, decisión explícita del propietario; solo se
  reubicaron sus contratos. Si se decide retirarlo, el grafo de dependencias
  (`verify_architecture.py --graph`) muestra que es un corte limpio. (Un
  archivo más es inalcanzable desde `:app` pero no pertenece al stack:
  `ParameterCatalog`, API pública con su propio test.)
- **No se partió `AudioEngine.cpp` (35 KB) / `AudioEngine.h` (23 KB) ni
  `AudioCommandDispatcher.kt`** (una sola clase con la cola y el coalescing:
  partirla es rediseño, no un corte mecánico). Son candidatos reales,
  pero partir código nativo que depende de Oboe/NDK o Compose sin poder
  compilar aquí es un riesgo desproporcionado frente al beneficio. Se
  recomienda hacerlo en una iteración con el build de CI como red.
  **Actualización (ADR 0030):** `AudioEngine` se repartió en cuatro unidades por
  responsabilidad con dos colaboradores extraídos (`VoicePool`, `TransportEngine`), verificado
  con la suite nativa; `ParamTarget` salió de `AudioCommandDispatcher`.
- **No se creó la subclase `Application`** (ADR 0014 la dejó como trabajo
  de seguimiento): requiere tocar el manifiesto. Con la fábrica actual es un
  cambio de una línea en `:app`.
- `RuntimeContext.events` sigue tipado contra `EventBus` (clase concreta);
  heredado de ADR 0022, no tocado.

## Verificación realizada

| Comprobación | Resultado |
|---|---|
| `verify_architecture.py` sobre el original | 24 violaciones |
| `verify_architecture.py` sobre el resultado | 0 violaciones, 127 archivos |
| Revisión manual de diffs de `package`/`import` en archivos representativos | Solo los cambios esperados |
| Suite nativa `run_native_tests.sh` (ASan/UBSan/TSan) | 22 ejecuciones, 0 problemas (antes y después de tocar C++) |

Dos errores míos durante el trabajo fueron **detectados por la propia
verificación**, no por casualidad:

1. Una regex del script de refactor tomó `fun interface` por una función
   llamada `interface` y generó imports basura → corregido repitiendo todo
   desde una copia limpia, no parcheando a mano.
2. Mi primera ubicación de `DspApi` (en `dspfoundation`) introducía la
   arista `modules.audio → dspfoundation`, que la matriz de capas de esa
   versión del verificador *permitía* y por tanto no marcó; la detecté al
   comparar el grafo antes/después. Se corrigió con `contracts`, se endureció
   la matriz para prohibir esa arista y se comprobó con una violación
   inyectada a propósito (el verificador falla con exit=1; restaurado, pasa).

## Límites de la verificación (declarados, no ocultos)

El entorno de trabajo no tiene `kotlinc`, Gradle, Android SDK ni NDK. **Nada
de esto se ha compilado ni ejecutado como Kotlin/Android.** El verificador
estático cubre las clases de error típicas de una reorganización de paquetes
pero no sustituye al compilador (no comprueba tipos, firmas ni
sobrescrituras). En particular quedan sin confirmar por compilación:

- los `override` añadidos a `MidiOutputBridge` y su conformidad con `MidiOutputApi`;
- `DefaultEliNerEngine` (`internal`) usado desde `EliNerEngineFactory`;
- la eliminación de `mMixer`/`Mixer.h` en C++ (la suite nativa no compila
  `AudioEngine.cpp`, que requiere Oboe);
- el nuevo `testImplementation(libs.junit)` de `:app`.

La confirmación es `./gradlew testDebugUnitTest assembleDebug` (el workflow
de CI ya lo ejecuta). Si algo falla, el fallo será de compilación local
(firma/visibilidad), no estructural.

## Consecuencias

- `:app` ya no puede acoplarse a internos sin romper CI.
- Añadir otro consumidor de EliNer (otro `ViewModel`, otra app) requiere solo
  `EliNerEngineFactory.create(...)`.
- Rutas antiguas citadas en ADR 0001–0026 quedan históricas a propósito
  (los ADR no se reescriben); este ADR y `ARCHITECTURE.md` («Qué cambió de
  sitio») dan la correspondencia.
- Cualquier import nuevo que viole la matriz de capas falla en el primer
  paso del CI, en segundos.
