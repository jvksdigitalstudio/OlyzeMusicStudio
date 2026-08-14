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
│   └── src/main/
│       ├── java/com/yeivikas/olyze/
│       │   ├── MainActivity.kt
│       │   ├── MainViewModel.kt
│       │   ├── midi/                    → OlyzeMidiManager (independiente del motor)
│       │   └── ui/                      → theme/, components/, screens/
│       ├── res/values/
│       └── AndroidManifest.xml
│
├── eliner/                              ← módulo :eliner (el motor, independiente de :app)
│   ├── build.gradle.kts                 → Android library, SIN Compose, SIN dependencia de :app
│   ├── CMakeLists.txt
│   ├── cmake/                           → helpers .cmake (reservado)
│   ├── include/eliner/                  → headers públicos C++: core, dsp, fx, mixer
│   ├── interfaces/                      → contratos C++ nativos entre módulos (reservado)
│   └── src/main/
│       ├── AndroidManifest.xml          → manifest mínimo, sin <application>
│       ├── java/com/yeivikas/olyze/eliner/
│       │   ├── api/                     → ✅ EliNerAudioApi, EliNerRuntimeApi + vocabulario público (RuntimeState, RuntimeContext, ServiceRegistry, ModuleLoader)
│       │   ├── bridge/                  → EliNerAudioBridge.kt (implementación JNI real)
│       │   ├── core/                    → ✅ IMPLEMENTADO — EliNerCore, EngineState, ModuleRegistry, etc. (Core Foundation)
│       │   ├── runtime/                 → ✅ IMPLEMENTADO — EliNerRuntime, LifecycleManager (Runtime Foundation)
│       │   ├── audiofoundation/         → ✅ IMPLEMENTADO — sesión, dispositivos, backend, formato, sample rate, buffer, reloj, latencia, ruteo, canales (Audio Foundation)
│       │   ├── dspfoundation/           → ✅ IMPLEMENTADO — DspProcessor, DspFrame, DspGraph, DspScheduler, DspParameterManager, etc. (DSP Foundation)
│       │   ├── modules/
│       │   │   └── audio/               → ✅ IMPLEMENTADO — AudioEngine, primer EliNerModule real (Audio Engine)
│       │   ├── interfaces/              → contratos Kotlin internos (preparado)
│       │   ├── events/                  → ✅ IMPLEMENTADO — EventBus (Foundation Services)
│       │   ├── resources/               → ✅ IMPLEMENTADO (solo contratos) — ResourceManager (Foundation Services)
│       │   ├── configuration/           → ✅ IMPLEMENTADO — ConfigurationService (Foundation Services)
│       │   ├── diagnostics/             → ✅ IMPLEMENTADO — LoggerService (Foundation Services)
│       │   ├── services/                → ✅ IMPLEMENTADO — ThreadManager, TaskScheduler, TimeService, DeviceCapabilityManager, PerformanceProfileManager
│       │   ├── hardware/                → Hardware Layer (preparado)
│       │   ├── recovery/                → Recovery System (preparado)
│       │   ├── tests/                   → estrategia de testing (preparado, sin tests)
│       │   └── documentation/           → ARCHITECTURE.md, PROJECT_FORMAT_OMS.md
│       └── cpp/                         → implementación C++: core, dsp, fx
│
├── docs/                                ← documentación de nivel de proyecto
│   ├── README.md
│   └── adr/                             → registro de decisiones arquitectónicas (ADR)
├── tools/                                ← reservado para scripts futuros (vacío a propósito)
├── .github/workflows/build.yml
├── build.gradle.kts
├── settings.gradle.kts                   → include(":app", ":eliner")
└── gradle/libs.versions.toml
```

## Arquitectura

```
UI (:app, Jetpack Compose)
    ↓  (solo conoce la interfaz)
EliNer API      →  eliner.api      (EliNerAudioApi — contrato)
    ↓  (implementado por)
EliNer Bridge     →  eliner.bridge   (EliNerAudioBridge — adaptador JNI)
    ↓
EliNer Engine Core  →  eliner/include/eliner/core + eliner/src/main/cpp/core (C++)
    ↓
Módulos independientes → eliner/include/eliner/{dsp,fx,mixer} + eliner/src/main/cpp/{dsp,fx}
```

`:app` depende de `:eliner` (`implementation(project(":eliner"))`).
`:eliner` **no depende de `:app` en absoluto** — ni Gradle, ni Kotlin, ni
C++. Esto no es solo una convención: Gradle lo hace cumplir en tiempo de
compilación. Detalle completo en
[`eliner/documentation/ARCHITECTURE.md`](eliner/src/main/java/com/yeivikas/olyze/eliner/documentation/ARCHITECTURE.md).

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
[`eliner/documentation/ARCHITECTURE.md`](eliner/src/main/java/com/yeivikas/olyze/eliner/documentation/ARCHITECTURE.md)
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
  proyecto tenía salida MIDI (`OlyzeMidiManager`, app → hardware externo)
  pero cero infraestructura de entrada. Nuevo: descubrimiento de
  dispositivos con hot-plug, parseo correcto de bytes MIDI crudos
  (running status + mensajes realtime intercalados), una cola acotada
  MPSC-safe, router + MIDI Clock/Transport, y el contrato (evaluado de
  verdad, sin destino conectado todavía) de MIDI Learn — todo detrás de
  `EliNerMidiApi`, conectado de verdad a `MainViewModel` (no otro rincón
  del stack Kotlin desconectado) — ver `docs/adr/0012-...md`.

Detalle completo en
[`eliner/documentation/ARCHITECTURE.md`](eliner/src/main/java/com/yeivikas/olyze/eliner/documentation/ARCHITECTURE.md)
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
- [ ] Mixer multicanal (UI — el core ya existe en `eliner/include/eliner/mixer`)
- [ ] Piano Roll
- [ ] Grabación MIDI
- [ ] Instrumentos virtuales adicionales
- [ ] EliNer: DSP Engine, Plugin System, Render Engine, Resource Manager
      (estructura preparada en `eliner/.../modules`, sin implementar)
- [ ] Renombrar el paquete de `:eliner` fuera del namespace de "olyze"
      (ver `docs/adr/0003-...md`, evaluado y pospuesto por riesgo/beneficio)
- [ ] Firma APK para publicación en Play Store
