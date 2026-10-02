# Olyze Music Studio

App de producción musical nativa Android — construida con **Kotlin + Jetpack
Compose**, con motor de audio de baja latencia en C++ (Oboe), impulsado por
el motor independiente **EliNer**.

**Empresa / marca:** YeiViKas Digital Studio
**Motor / API:** EliNer *(módulo Gradle independiente — ver [Arquitectura](#arquitectura))*

> Este proyecto era anteriormente conocido como **"Jvk's Studio Mobile"**.
> Fue migrado y reorganizado en 3 fases (identidad → arquitectura ampliada
> → EliNer como módulo independiente). Resumen de cada fase más abajo;
> historial completo de decisiones en [`docs/adr/`](docs/adr/).

## Stack técnico

| Componente | Tecnología |
|-----------|------------|
| Lenguaje | Kotlin 2.1 |
| UI | Jetpack Compose (Material 3) — solo en `:app` |
| MIDI | Android MIDI API (`android.media.midi`) — en `:app` |
| Audio | Motor nativo C++ (Oboe / AAudio), vía JNI — en `:eliner` |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 35 (Android 15) |
| Build | Gradle 8.9 + AGP 8.7, **2 módulos** (`:app`, `:eliner`) |
| Nativo | NDK + CMake, C++20, `arm64-v8a` / `armeabi-v7a` (+ `x86_64` en CI) |

## Estructura del proyecto

```
OlyzeMusicStudio/                        (workspace multi-módulo)
├── app/                                 ← módulo :app (la aplicación)
│   └── src/
│       ├── main/java/com/yeivikas/olyze/
│       │   ├── MainActivity.kt
│       │   ├── MainViewModel.kt          → solo estado de UI + comandos; usa EliNerEngine (ADR 0014, 0027)
│       │   ├── transport/                → MidiClockGenerator (reloj MIDI 24 PPQN — ADR 0027)
│       │   └── ui/                       → theme/, components/, screens/
│       └── test/                         → tests JVM de :app
│
├── eliner/                              ← módulo :eliner (el motor, independiente de :app)
│   ├── build.gradle.kts                 → Android library, SIN Compose, SIN dependencia de :app
│   ├── CMakeLists.txt
│   ├── include/eliner/                  → headers públicos C++: core, dsp, fx
│   └── src/main/
│       ├── java/com/yeivikas/olyze/eliner/
│       │   ├── api/                     → CONTRATO PÚBLICO — sin dependencias fuera de api.*
│       │   │   ├── (raíz)               → EliNerEngine (fachada), EngineInitState
│       │   │   ├── audio/               → EliNerAudioApi, DspModuleType, ParameterCatalog, EngineErrorFlags, PerformanceProfile
│       │   │   ├── midi/                → EliNerMidiApi, MidiOutputApi, MidiEvent, MidiDevice*, MidiParameterBinding, …
│       │   │   └── event/               → EliNerEvent
│       │   ├── composition/             → RAÍZ DE COMPOSICIÓN — EliNerEngineFactory (público) + DefaultEliNerEngine (internal)
│       │   ├── bridge/                  → EliNerAudioBridge (JNI), AudioCommandDispatcher, MidiOutputBridge, MidiToSynthBridge
│       │   ├── modules/{audio,midi}/    → AudioEngine (kernel) + AudioEngineApi · MIDI Foundation (backend, router, reloj, cola)
│       │   ├── contracts/               → contratos compartidos entre módulos hermanos (DspApi, DspState, DspMetricsSnapshot) — paquete hoja
│       │   ├── runtime/                 → EliNerRuntime, LifecycleManager + contratos del kernel (RuntimeContext, ModuleLoader, …)
│       │   ├── audiofoundation/         → Audio Foundation (ADR 0007)
│       │   ├── dspfoundation/           → DSP Foundation (ADR 0009)
│       │   └── core/ events/ services/ diagnostics/ configuration/ resources/   → Core + Foundation Services
│       ├── cpp/                         → implementación C++: core, dsp, fx
│       └── AndroidManifest.xml          → manifest mínimo, sin <application>
│
├── docs/                                ← documentación de nivel de proyecto
│   ├── README.md
│   ├── adr/                             → registro de decisiones arquitectónicas (ADR 0001–0027)
│   └── eliner/                          → ARCHITECTURE.md, ROADMAP_MODULES.md, PROJECT_FORMAT_OMS.md
├── tools/verify_architecture.py         ← barrera de arquitectura (capas, ciclos, imports) — corre en CI
├── .github/workflows/build.yml
├── build.gradle.kts
├── settings.gradle.kts                   → include(":app", ":eliner")
└── gradle/libs.versions.toml
```

Los módulos previstos y aún no implementados (Mixer, Timeline, Render,
Project, Plugin, Hardware, Recovery…) **no** ocupan directorios del código:
están en [`docs/eliner/ROADMAP_MODULES.md`](docs/eliner/ROADMAP_MODULES.md).

## Arquitectura

```
UI (:app, Jetpack Compose)
    ↓  solo conoce eliner.api.* + EliNerEngineFactory
EliNer API          →  eliner.api          (EliNerEngine · EliNerAudioApi · EliNerMidiApi · MidiOutputApi)
    ↓  ensamblado por
EliNer Composition  →  eliner.composition  (EliNerEngineFactory → DefaultEliNerEngine)
    ↓  implementado por
EliNer Bridge       →  eliner.bridge       (JNI, despachador de comandos, puentes MIDI)
    ↓
EliNer Engine Core  →  eliner/include/eliner/core + eliner/src/main/cpp/core (C++)
    ↓
Módulos nativos     →  eliner/include/eliner/{dsp,fx} + eliner/src/main/cpp/{dsp,fx}
```

Las dependencias entre paquetes siguen una matriz de capas fija (tabla en
[`ARCHITECTURE.md`](docs/eliner/ARCHITECTURE.md#estructura-vigente-adr-0027))
que **hace cumplir `tools/verify_architecture.py` en CI**: `eliner.api` no
depende de nada fuera de `api.*`, y `:app` solo puede importar `eliner.api`
y `eliner.composition`.

`:app` depende de `:eliner` (`implementation(project(":eliner"))`).
`:eliner` **no depende de `:app` en absoluto** — ni Gradle, ni Kotlin, ni
C++. Esto no es solo una convención: Gradle lo hace cumplir en tiempo de
compilación. Detalle completo en
[`docs/eliner/ARCHITECTURE.md`](docs/eliner/ARCHITECTURE.md).

## Resumen de las 3 fases de migración

### Fase 1 — Identidad (Jvk's Studio Mobile → Olyze Music Studio)

`applicationId`/`namespace` (`com.jvk.studio` → `com.yeivikas.olyze`),
tema, biblioteca nativa, namespace C++, funciones JNI, artefactos de CI,
Proguard — sin referencias residuales al nombre anterior. Se introdujo
`EliNerAudioApi` como primera interfaz que desacopla la UI del motor
nativo concreto.

### Fase 2 — Arquitectura profesional ampliada

Puramente arquitectónica, sin motor nuevo:
- `minSdk` 26 → **24**, verificado contra `@RequiresApi(M)` (API 23) real
  en el código. `ANDROID_PLATFORM` sincronizado a `android-24`.
- C++17 → **C++20** en preparación para EliNer.
- `eliner/` ampliado a la estructura `API / Bridge / Core / Modules /
  Interfaces / Events / Resources / Configuration / Diagnostics / Hardware
  / Documentation / Tests`, cada carpeta documentada individualmente.

### Fase 3 — EliNer como módulo Gradle independiente

La reorganización más importante hasta ahora:
- **EliNer dejó de ser un paquete dentro de `:app`** y pasó a ser su
  propio módulo Gradle (`:eliner`), sin dependencia de Compose ni de
  `:app` — condición necesaria para poder reutilizarlo en futuros
  proyectos (ej. Olyze Movie Creator).
- El motor nativo se reorganizó separando headers públicos
  (`eliner/include/eliner/`) de su implementación
  (`eliner/src/main/cpp/`).
- Se agregó `docs/adr/` con el registro de decisiones arquitectónicas.
- Ningún módulo de `eliner.modules.*` ganó lógica nueva — Timeline,
  Render, Project System y Plugin System siguen sin implementación.

Detalle técnico completo, incluyendo el riesgo declarado de esta fase, en
[`docs/eliner/ARCHITECTURE.md`](docs/eliner/ARCHITECTURE.md)
y en [`docs/adr/0003-eliner-modulo-independiente.md`](docs/adr/0003-eliner-modulo-independiente.md).

## EliNer Engine — construcción real (distinto de las Fases 1-3 del proyecto)

Las "Fases 1-3" de arriba son del **proyecto** (identidad → arquitectura →
módulo independiente). A partir de ahí empezó la construcción real del
**motor EliNer en sí**, con su propia numeración de fases:

- **Fase 1 — Core Foundation:** ciclo de vida del motor (`EngineState`,
  `EliNerCore`), registro genérico de módulos (`ModuleRegistry`,
  `EliNerModule`), versión (`EngineVersion`), informe de errores
  (`EngineError`). 7 archivos, cero stubs, cero dependencia de Android/UI.
- **Fase 2 — Foundation Services:** 9 servicios reales — Logger, Event Bus,
  Configuration, Resource Manager, Thread Manager, Task Scheduler, Time
  Service, Device Capability Manager, Performance Profile Manager. 13
  archivos, todos con responsabilidad propia y real (no relleno).
- **Fase 2.5 — Runtime Foundation:** `EliNerRuntime`, el composition root
  real — orquesta Core + los 8 servicios de Fase 2 con su propio ciclo de
  vida (`RuntimeState`, independiente de `EngineState`), registro de
  servicios por contrato (`ServiceRegistry`, explícitamente no un Service
  Locator global), y una API pública (`EliNerRuntimeApi`) que es la única
  puerta que la UI podrá usar. La auditoría interna obligatoria de esta
  fase encontró y corrigió un ciclo real de paquetes (`eliner.api` ↔
  `eliner.runtime`) — ver `docs/adr/0006-...md`.
- **Fase 3 — Audio Foundation:** 11 componentes de infraestructura de
  audio — sesión, dispositivos (enumeración real vía `AudioManager`),
  backend (Oboe/AAudio/OpenSL ES), formato, sample rate, buffer (derivado
  del perfil de rendimiento), reloj (basado en frames, no wall-clock),
  latencia, ruteo (arquitectura, sin procesamiento), canales. Ninguno
  reproduce ni procesa audio real. Nuevo paquete `eliner.audiofoundation`
  (distinto de `eliner.modules.audio`, el futuro Audio Engine real).
  También se extrajo `StateMachine<S>` genérico en `eliner.core` para no
  triplicar el patrón de máquina de estados — ver `docs/adr/0007-...md`.
- **Fase 4 — Audio Engine:** el primer motor funcional real —
  `AudioEngine` (en `eliner.modules.audio`) es la primera clase que
  implementa `EliNerModule` (Core, Fase 1) con contenido real, y también
  `AudioEngineApi` (`eliner.api`) — la única puerta que la UI podrá usar.
  Cero DSP. Tres reutilizaciones deliberadas para evitar duplicación: el
  hilo de audio reutiliza `ExecutionLane.AUDIO` (Fase 2) en vez de crear
  uno nuevo, los errores reutilizan `EngineError`/`Logger` en vez de un
  tipo paralelo, y la lección del ciclo `api`↔`runtime` (Fase 2.5) se
  aplicó preventivamente desde el diseño — ver `docs/adr/0008-...md`.
- **Fase 5 — DSP Foundation:** infraestructura DSP pura — ningún
  algoritmo real (EQ, compresor, reverb) implementado. `DspProcessor`
  (contrato), `DspFrame` (planar, preparado para SIMD/NEON),
  `DspParameter`/`DspParameterManager`, `DspGraph`/`DspScheduler`
  (ordenamiento topológico real), `DspChain`/`DspBus`, `DspBufferPool`,
  `DspFoundation` (implementa `DspApi`, `DspState` propio). Dos
  utilitarios genéricos nuevos en `eliner.core` (`ConnectionGraph<N>`,
  `FloatBufferPool`) para no duplicar la mecánica de `AudioRoutingGraph`/
  `AudioBufferPool`. Con autorización explícita, se agregó un campo
  aditivo `AudioEngine.dsp: DspApi?` — nullable, sin cambiar ninguna
  firma existente — ver `docs/adr/0009-...md`.
- **Fase 6 — Frontera Native DSP (auditoría realtime):** confirmó que el
  camino real de audio no es el stack Kotlin de Fase 5 (`DspGraph`/
  `DspFoundation` siguen sin ruta a producción, decisión explícita, no
  pendiente accidental), sino el motor C++ (`AudioEngine.cpp`) — voces →
  Reverb → Delay → master, hardcodeado, verificado sin locks, sin
  allocations y sin JNI en el audio callback. Se agregó un sistema de
  error flags realtime-safe (`std::atomic<uint32_t>`, sin excepciones) y
  métricas (`droppedCommands`, `xrunCount`, `lastError`) expuestas
  end-to-end hasta `EliNerAudioApi` — ver `docs/adr/0010-...md`.
- **Fase 7 — DSP Graph real (vertical slice, un canal):** reemplaza la
  cadena fija Reverb→Delay de la Fase 6 por un `DspChain` de 8 slots
  reconfigurable en runtime — insertar, quitar y reordenar módulos de
  efecto realmente cambia lo que el hilo de audio renderiza, sin romper
  ninguna garantía realtime de la Fase 6 (el hilo de audio nunca asigna
  ni libera memoria; los módulos retirados se liberan en una cola
  separada, solo desde el hilo de control). Acotado a un canal a
  propósito — es la base que valida la arquitectura antes de construir
  Mixer UI / Channel Rack encima — ver `docs/adr/0011-...md`.
- **MIDI Foundation (entrada MIDI real):** cierra un vacío real — el
  proyecto tenía salida MIDI (`OlyzeMidiManager`, app → hardware externo;
  migrado desde entonces a `eliner.bridge.MidiOutputBridge` sobre la
  misma `EliNerMidiApi` — ver ADR 0013) pero cero infraestructura de
  entrada. Nuevo: descubrimiento de
  dispositivos con hot-plug, parseo correcto de bytes MIDI crudos
  (running status + mensajes realtime intercalados), una cola acotada
  MPSC-safe, router + MIDI Clock/Transport, y el contrato (evaluado de
  verdad, sin destino conectado todavía) de MIDI Learn — todo detrás de
  `EliNerMidiApi`, conectado de verdad a `MainViewModel` (no otro rincón
  del stack Kotlin desconectado) — ver `docs/adr/0012-...md`.
- **Estabilización del núcleo (concurrencia/lifecycle, transversal — no
  añade features):** auditoría de concurrencia real encontró que
  `SpscCommandQueue` (contrato "un solo productor") recibía comandos de
  DOS hilos reales concurrentes (UI + `eliner-dsp`/MidiRouter) sin
  serializar — confirmado con una prueba determinista de intercalación,
  no solo análisis. Corregido en Kotlin (`AudioCommandDispatcher`, Opción
  A: serializar antes de JNI, no convertir el ring buffer en MPSC).
  También: `gEngine` (JNI) sin sincronización — mutex agregado, validado
  con ThreadSanitizer; el hilo interno de error de Oboe podía mutar
  `AudioEngine` concurrentemente con el hilo de control — mutex de
  lifecycle interno agregado (patrón `...Locked()` para evitar deadlock
  por re-lock); `requestStart()` fallido dejaba el stream huérfano —
  corregido; `MainViewModel` construía toda la infraestructura de
  audio/MIDI directamente — extraída a `AppServices` (Application
  Services) — ver `docs/adr/0014-...md`. Verificación final de esa fase
  (contrato de cierre del dispatcher, `gEngine` re-auditado, decisión de
  no integrar GoogleTest) en `docs/adr/0015-fase1-cierre-verificacion.md`.

### Fase 1.1 — Core Hardening (continuación de la estabilización — sigue sin añadir features de DAW)

Auditoría independiente sobre el código real de Fase 1/1 ya cerrada,
enfocada en cuatro debilidades concretas que esa fase no había cubierto
todavía. Cada una con bug real identificado por lectura del código
(no hipotético), fix estructural, y verificación por ejecución real —
detalle completo en cada ADR:

- **`AudioEngine::moveModule()` desincronizaba el shadow del grafo DSP
  real** (`mSlotTypesShadow` se actualizaba en cuanto el comando quedaba
  ENCOLADO, no cuando se EJECUTABA — `DspChain::move()` hace no-op
  silencioso en same-slot y empty-source, y el shadow no lo reflejaba).
  Corregido replicando las mismas precondiciones en el hilo de control
  antes de encolar — ver `docs/adr/0016-...md`.
- **`AndroidMidiBackend` podía resucitar un handle MIDI tras
  `shutdown()`** — el callback asíncrono de `MidiManager.openDevice()`
  podía llegar después de `shutdown()`/`closeDevice()` y escribir igual
  en los mapas internos. Corregido con generation/token — ver
  `docs/adr/0017-...md`.
- **`AudioCommandDispatcher` sin cola acotada ni backpressure** —
  `Executors.newSingleThreadExecutor()` acumulaba `Runnable`s sin límite;
  riesgo real de cara a automation/MIDI de alta frecuencia. Rediseñado
  con cola acotada (`ThreadPoolExecutor` + `ArrayBlockingQueue`,
  capacidad 256, igual que `EngineCommandQueue` nativa) para eventos
  discretos, y coalescing "latest-value" con batching para parámetros
  continuos (volumen, mix, feedback) — ver `docs/adr/0018-...md`.
- **`AppServices.start()` bloqueaba el hilo principal en cada arranque
  de la app** — `MainViewModel.init{}` llamaba a `audio.start()`
  síncronamente hasta que Oboe terminaba de abrir el stream. Rediseñado
  como arranque asíncrono con estado observable (`EngineInitState`),
  timeout y cancelación lifecycle-safe; de paso se corrigió un segundo
  bug real (MIDI arrancaba sin mirar si el audio lo había hecho) — ver
  `docs/adr/0019-...md`.

- **El canal MIDI se descartaba en la frontera nativa** —
  `AudioEngine::noteOn/noteOff/setPitchBend` ignoraban `channel` y
  `EngineCommand` no tenía campo para llevarlo: un NoteOff de un canal
  silenciaba la nota de otro y el pitch bend afectaba a todas las voces.
  Contrato de canal definido (0-15 cero-basado, ownership por voz, bend
  por canal, `AllNotesOff` global) e implementado de extremo a extremo —
  ver `docs/adr/0020-...md`.

Los tests C++ standalone del núcleo se ejecutan con
`eliner/src/test/cpp/run_native_tests.sh` (ASan/UBSan/TSan; no requiere
Android/NDK). Correcciones de entrega y verificación de esta fase (incluidos
errores propios detectados en revisión): `docs/adr/0021-...md`.

- **`ThreadManager.shutDown` sin protocolo de acceso definido** — escritura
  bajo lock parcial, lectura sin lock ni `@Volatile`: sin happens-before, y
  un segundo `shutdown()` concurrente podía retornar antes de que el primero
  terminara de cancelar. Corregido con lock que cubre la secuencia completa
  + `@Volatile` en la lectura — ver `docs/adr/0022-...md`.
- **Frontera de la API pública (`eliner.api`) dependía de clases concretas**
  — `RuntimeContext` (7 campos) y `ModuleLoader` (`EliNerCore`) tipaban
  contra implementaciones, no contratos. Separados en archivos de
  contrato/implementación co-ubicados (mismo paquete, 0 FQN cambiados,
  verificado símbolo por símbolo) — ver `docs/adr/0022-...md`.
- **La duplicación de arquitecturas de audio (§21) ya estaba cerrada** por
  decisión explícita del usuario (ADR 0010, opción C) — verificado que
  sigue vigente (cero llamadores reales), sin reabrir la decisión.
- **Catálogo de parámetros** (`ParameterCatalog.kt`) — encontró y corrigió
  dos parámetros nativos (`ReverbRoom`/`ReverbDamp`) con manejo completo en
  el motor pero sin ningún setter público que los alcanzara — ver
  `docs/adr/0023-...md`.

El CI (`.github/workflows/build.yml`) ahora ejecuta la suite C++ y todos los
tests Kotlin antes de compilar el APK — antes solo compilaba, sin correr
ningún test.

**Fase 1.1: READY.** Build real compilado y ejecutado con el toolchain del
usuario (Gradle/AGP/NDK/Oboe) — confirmado, no asumido: la app arranca sin
bloquear la UI (§15) y el motor de audio produce sonido real de extremo a
extremo (JNI → `AudioCommandDispatcher` → `AudioEngine` nativo → síntesis →
Oboe) al tocar notas en el teclado en pantalla, en un dispositivo real. Los
91 tests Kotlin y la suite C++ pasan en CI real (`docs/adr/0026-...md`).
Verificación honesta de alcance: lo confirmado por el usuario es
reproducción de notas básica desde el teclado en pantalla — no cubre
todavía un controlador MIDI externo real, carga sostenida de automation, ni
las pantallas de UI que construirán sobre este núcleo (Mixer, canales,
Project System — explícitamente fuera del alcance de esta fase, §28 del
prompt de origen; la pantalla "Añadir canal" que ya existe en la UI está
deliberadamente vacía desde antes de esta fase, no es una regresión de
ningún cambio aquí). Detalle completo del cierre en
`docs/adr/0023-...md`, "Veredicto de cierre de Fase 1.1".

Detalle completo en
[`docs/eliner/ARCHITECTURE.md`](docs/eliner/ARCHITECTURE.md)
y en `docs/adr/0004-...md` / `docs/adr/0005-...md`.

## Compilar con GitHub Actions

1. Sube este proyecto a un repositorio GitHub (se entrega sin historial de
   Git — inicializa uno nuevo con `git init`).
2. GitHub Actions compilará automáticamente en cada `push` a `main`
   (ahora compila 2 módulos: `:eliner` primero, luego `:app`, que depende
   de él — Gradle resuelve el orden automáticamente).
3. Descarga el APK desde **Actions → artifacts**
   (`OlyzeMusicStudio-debug-<sha>` / `OlyzeMusicStudio-release-unsigned-<sha>`).

## Compilar local

```bash
# Requiere JDK 17 y Android SDK/NDK instalados
chmod +x gradlew
./gradlew assembleDebug
# APK generado en: app/build/outputs/apk/debug/app-debug.apk
```

## Features actuales v1.0

- ✅ Teclado MIDI completo (C-1 a B8, 120 notas)
- ✅ Multi-touch en el teclado
- ✅ Transport: Play/Stop, REC, Rewind, BPM con clock MIDI
- ✅ MIDI clock sync enviado a DAW externa
- ✅ Motor de audio nativo (Oboe) con síntesis, reverb y delay
- ✅ Diseño dark premium (estilo FL Studio Mobile)
- ✅ Toggle para ocultar/mostrar teclado
- ✅ Orientación landscape forzada

## Roadmap

- [ ] Playlist / Secuenciador de patrones
- [ ] Drum Pads
- [ ] Mixer multicanal (core + UI — no implementado; ver `docs/eliner/ROADMAP_MODULES.md`)
- [ ] Piano Roll
- [ ] Grabación MIDI
- [ ] Instrumentos virtuales adicionales
- [ ] EliNer: DSP Engine, Plugin System, Render Engine, Resource Manager
      (estructura preparada en `eliner/.../modules`, sin implementar)
- [ ] Renombrar el paquete de `:eliner` fuera del namespace de "olyze"
      (ver `docs/adr/0003-...md`, evaluado y pospuesto por riesgo/beneficio)
- [ ] Firma APK para publicación en Play Store
