# EliNer — Arquitectura (estado actual y visión)

**Producto:** Olyze Music Studio (y futuros productos: Olyze Movie Creator, etc.)
**Empresa:** YeiViKas Digital Studio
**Motor/API:** EliNer

## Fase 3 — el cambio más importante: EliNer ahora es un módulo Gradle independiente

Hasta la Fase 2, `eliner` era solo un **paquete Kotlin** dentro del módulo
`:app` (`app/src/main/java/com/yeivikas/olyze/eliner/...`) y el motor nativo
vivía en `app/src/main/cpp/`. Eso significaba que, en los hechos, EliNer
*era* parte del binario de la app — no podía reutilizarse en otro proyecto
sin copiar y pegar código.

Desde la Fase 3, **EliNer es su propio módulo Gradle** (`:eliner`), física y
lógicamente separado de `:app`:

```
OlyzeMusicStudio/                  (proyecto raíz — workspace multi-módulo)
├── app/                           (módulo :app — la aplicación Android Olyze Music Studio)
│   └── src/main/java/com/yeivikas/olyze/
│       ├── MainActivity.kt
│       ├── MainViewModel.kt
│       ├── ui/                    (Compose)
│       └── midi/                  (MIDI — ver nota más abajo)
│
├── eliner/                        (módulo :eliner — el motor, independiente)
│   ├── build.gradle.kts           (Android library — SIN Compose, SIN dependencia de :app)
│   ├── CMakeLists.txt
│   ├── cmake/                     (helpers .cmake, reservado — ver README)
│   ├── include/eliner/            (headers públicos C++: core, dsp, fx, mixer)
│   ├── interfaces/                (contratos C++ nativos entre módulos, reservado)
│   └── src/main/
│       ├── AndroidManifest.xml    (manifest mínimo — sin <application>, sin permisos)
│       ├── java/com/yeivikas/olyze/eliner/   (api, bridge, core, modules, interfaces, events, resources, configuration, diagnostics, hardware, recovery, tests, documentation)
│       └── cpp/                   (implementación C++: core, dsp, fx)
│
├── docs/                          (documentación de nivel de proyecto — decisiones arquitectónicas)
├── tools/                         (reservado para scripts futuros — ver README, vacío a propósito)
├── settings.gradle.kts            (include(":app", ":eliner"))
└── ...
```

**Regla de dependencia (verificada, no solo documentada):**
`app/build.gradle.kts` tiene `implementation(project(":eliner"))`.
`eliner/build.gradle.kts` **no tiene ninguna referencia a `:app`** — ni
como dependencia Gradle, ni como import Kotlin, ni como símbolo C++. Esto
no es una promesa en un README: es una restricción que Gradle hace cumplir
en tiempo de compilación. Si alguien intentara hacer que `:eliner`
dependiera de `:app`, el build fallaría por ciclo de dependencias.

**Por qué `:eliner` no depende de Compose:** su `build.gradle.kts` no
aplica el plugin `kotlin.compose` ni activa `buildFeatures.compose`. Los
únicos dos archivos Kotlin con lógica real hoy (`EliNerAudioApi.kt`,
`EliNerAudioBridge.kt`) solo usan `kotlinx.coroutines.flow.StateFlow` y
`android.util.Log` — cero acoplamiento a UI. Esto es lo que hace posible
que, en el futuro, Olyze Movie Creator (u otra app) dependa de `:eliner`
sin arrastrar Jetpack Compose si no lo necesita.

## Capas

```
UI (:app, Jetpack Compose)
        ↓  (solo conoce la interfaz — nunca la implementación)
EliNer API           → eliner.api           (contrato — EliNerAudioApi)
        ↓  (implementado por)
EliNer Bridge          → eliner.bridge        (adaptador JNI — EliNerAudioBridge)
        ↓
EliNer Engine Core      → eliner/include/eliner/core + eliner/src/main/cpp/core (C++)
        ↓
Módulos independientes    → eliner/include/eliner/{dsp,fx,mixer} + eliner/src/main/cpp/{dsp,fx}
```

Sistemas transversales (consumidos por varios niveles a la vez, sin
formar parte de la pila anterior):

```
eliner.events          → comunicación entre módulos sin dependencias directas
eliner.resources        → memoria, streaming, cache, preload
eliner.configuration     → valores centralizados por dominio (Audio, Graphics, Performance, Plugins, Project, UI)
eliner.diagnostics       → Logger, Crash, Performance, Reports, Future Debug
eliner.hardware          → USB Audio/MIDI, Bluetooth MIDI, controladores
eliner.recovery          → backups, snapshots, recuperación de proyectos
eliner.interfaces        → contratos Kotlin de comunicación interna entre módulos
eliner/interfaces/       → contratos C++ nativos entre módulos (carpeta hermana, nivel del módulo — no confundir con la anterior)
eliner.tests             → estrategia de testing (no contiene tests en sí)
```

## Separación include/ vs src/ (código nativo)

Nueva en Fase 3: los headers públicos (`.h`) viven en `eliner/include/eliner/`
y la implementación (`.cpp`) en `eliner/src/main/cpp/`, en vez de estar
mezclados en la misma carpeta como hasta la Fase 2. Esto es una convención
estándar de bibliotecas C++ profesionales (separar "qué expone" de "cómo lo
hace") y facilita que, si algún día se quisiera exponer el motor a
consumidores nativos externos (vía Android Prefab, por ejemplo — no
implementado todavía), quede claro qué es superficie pública y qué es
detalle interno.

`CMakeLists.txt` permanece en la raíz de `eliner/` (convención estándar de
CMake), no dentro de `cmake/` — esa carpeta queda reservada para scripts
`.cmake` auxiliares que hoy no existen.

## Nota sobre MIDI: por qué sigue fuera de `:eliner`

`com.yeivikas.olyze.midi.OlyzeMidiManager` permanece dentro de `:app`, no
de `:eliner`, a propósito. Usa `android.media.midi`, una API que requiere
un `Context` de Android — moverlo a `:eliner` hoy forzaría a `:eliner` a
depender de `android.content.Context` de forma más profunda, sin beneficio
real todavía (nada más en `:eliner` lo necesita). Está documentado como
candidato a migrar en `eliner.modules.midi/README.md` cuando el MIDI Engine
se implemente de verdad y necesite vivir junto al resto del motor.

## Principio arquitectónico obligatorio

> Todos los componentes de EliNer deben diseñarse como módulos
> independientes, reemplazables y extensibles. Ninguna función crítica debe
> depender directamente de otro módulo. Las comunicaciones entre sistemas
> deberán realizarse mediante APIs, interfaces internas y protocolos
> definidos.

Aplicado hoy, verificablemente:
- `:app` → `:eliner` es una dependencia de compilación real (Gradle), no
  solo una convención de código.
- La UI y `MainViewModel` (en `:app`) nunca importan `EliNerAudioBridge`
  directamente — solo `EliNerAudioApi`.
- `:eliner` no importa nada de `:app` — ni una sola línea.

## EliNer Engine — Fase 1: Core Foundation (implementado)

A partir de aquí, "Fase 1/2/3" del proyecto (identidad → arquitectura
ampliada → módulo independiente) y las fases del **motor EliNer en sí**
son numeraciones distintas. Esta sección cubre la primera fase de
construcción real del motor: **Core Foundation**.

`eliner.core` dejó de ser una carpeta "preparada, vacía" — contiene el
núcleo administrativo real del motor: ciclo de vida (`EngineState`,
`EliNerCore`), registro de módulos (`ModuleRegistry`, `EliNerModule`),
versión (`EngineVersion`) e informe de errores (`EngineError`). Ningún
módulo funcional (Audio/DSP/MIDI/etc.) fue implementado — solo el
mecanismo que los administrará cuando existan. Detalle completo, incluyendo
por qué cada archivo existe, en [`eliner.core/README.md`](../core/README.md).

## EliNer Engine — Fase 2: Foundation Services (implementado)

Construida sobre el Core Foundation de la Fase 1. Consolidación de la
arquitectura, ahora con una capa explícita entre la API y los motores:

```
UI (:app)
    ↓
EliNer API           → eliner.api
    ↓
Foundation Services    → eliner.diagnostics, eliner.events, eliner.configuration,
                          eliner.resources, eliner.services
    ↓
Core Foundation           → eliner.core
    ↓
Módulos futuros              → eliner.modules.* (sin implementar)
```

10 servicios implementados, repartidos en 5 paquetes (4 ya existían desde
la Fase 3, 1 es nuevo — ver [`eliner.services/README.md`](../services/README.md)
para el porqué):

| Servicio | Paquete | Archivo principal |
|---|---|---|
| Logger Service | `eliner.diagnostics` | `LoggerService.kt` |
| Event Bus | `eliner.events` | `EventBus.kt` |
| Configuration Service | `eliner.configuration` | `ConfigurationService.kt` |
| Resource Manager | `eliner.resources` | `ResourceManager.kt` |
| Thread Manager | `eliner.services` | `ThreadManager.kt` |
| Task Scheduler | `eliner.services` | `TaskScheduler.kt` |
| Time Service | `eliner.services` | `TimeService.kt` |
| Device Capability Manager | `eliner.services` | `DeviceCapabilityManager.kt` |
| Performance Profile Manager | `eliner.services` | `PerformanceProfileManager.kt` |

Regla de dependencia verificada (no solo documentada, confirmada por
grep antes de empaquetar): ningún archivo de `eliner.core` importa nada de
estos 5 paquetes. Los Foundation Services pueden depender de Core (una
capa inferior); Core nunca depende de ellos.

Detalle completo de decisiones y por qué cada archivo existe en los README
de cada paquete y en `docs/adr/0005-foundation-services.md`.



- ✅ **Fase 1:** identidad migrada (Jvk's Studio Mobile → Olyze Music
  Studio) sin restos del nombre anterior.
- ✅ **Fase 2:** `minSdk` 24, C++20, estructura Kotlin de `eliner/` ampliada
  a 12+ carpetas documentadas.
- ✅ **Fase 3:** `eliner` promovido de paquete Kotlin dentro de `:app` a
  **módulo Gradle independiente** (`:eliner`), con separación include/src
  en el código nativo, cero dependencia de Compose, cero dependencia de
  `:app`, y documentación de nivel de proyecto en `docs/`.
- ✅ Ningún módulo de `eliner.modules.*` tiene lógica implementada más allá
  de lo que ya existía (Audio Engine, DSP Engine, Mixer Engine — motor
  nativo real, sin cambios de comportamiento). Timeline, Render, Project,
  Plugin System siguen sin implementación, tal como se pidió.

## EliNer Engine — Fase 2.5: Runtime Foundation (implementado)

Construida sobre Core Foundation (Fase 1) y Foundation Services (Fase 2).
Consolida la arquitectura definitiva:

```
UI (:app)
    ↓
EliNer API           → eliner.api      (EliNerRuntimeApi, RuntimeState, RuntimeContext, ServiceRegistry, ModuleLoader — el "vocabulario público")
    ↓
Runtime                → eliner.runtime  (EliNerRuntime, LifecycleManager — implementación pura)
    ↓
Foundation Services       → eliner.diagnostics, eliner.events, eliner.configuration, eliner.resources, eliner.services
    ↓
Core Foundation              → eliner.core
    ↓
Módulos futuros                 → eliner.modules.* (sin implementar)
```

`EliNerRuntime` es el composition root real: construye/recibe un
`EliNerCore` y un `RuntimeContext` (7 servicios, todos por interfaz salvo
`EventBus` — excepción documentada, ver ADR 0006), y orquesta
`initialize()`/`pause()`/`resume()`/`shutdown()` publicando
`RuntimeStateChangedEvent` de forma síncrona en cada transición exitosa —
sin necesitar un `CoroutineScope` de suscripción, a diferencia de lo que
Fase 2 dejó pendiente.

**Hallazgo importante de esta fase:** la auditoría interna obligatoria
detectó un ciclo real de paquetes (`eliner.api` ↔ `eliner.runtime`) que sí
compilaba pero violaba la regla de dependencias circulares. Se corrigió
moviendo el "vocabulario público" (`RuntimeState`, `RuntimeContext`,
`ServiceRegistry`, `ModuleLoader`) a `eliner.api`, dejando `eliner.runtime`
como implementación pura de dependencia única. Detalle completo en
`docs/adr/0006-runtime-foundation.md`.

## EliNer Engine — Fase 3: Audio Foundation (implementado)

Construida sobre Runtime Foundation (Fase 2.5). Infraestructura
administrativa de audio — 11 componentes, ninguno reproduce ni procesa
audio real: `AudioSessionManager` (ciclo de vida), `AudioDeviceManager`
(enumeración real de dispositivos vía `AudioManager`),
`AudioBackendManager` (Oboe/AAudio/OpenSL ES), `AudioFormatManager`
(PCM16/24/32, Float32/64), `SampleRateManager`, `BufferManager` (derivado
del perfil de rendimiento), `AudioClock` (basado en frames, no wall-clock),
`LatencyManager`, `AudioRoutingGraph` (arquitectura, sin procesamiento),
`AudioChannelConfiguration` (Mono/Stereo), y `AudioFoundationContext` (el
agregador, construido reutilizando un `RuntimeContext` existente).

Nuevo paquete: `eliner.audiofoundation` — deliberadamente distinto de
`eliner.modules.audio` (que sigue siendo el futuro Audio Engine real). Ver
[`eliner.audiofoundation/README.md`](../audiofoundation/README.md).

También en esta fase: `com.yeivikas.olyze.eliner.core.StateMachine<S>`, un
utilitario genérico extraído para no triplicar el patrón de transiciones
de estado (`EngineState`, `RuntimeState`, y ahora `AudioSessionState`).
`EliNerCore`/`LifecycleManager` (Fases 1/2.5) se dejaron intactos a
propósito — ver `docs/adr/0007-audio-foundation.md`.



## EliNer Engine — Fase 4: Audio Engine (implementado)

El primer motor funcional real, construido sobre Audio Foundation (Fase
3). `AudioEngine` (en `eliner.modules.audio`) es la primera clase que
implementa `EliNerModule` (Core, Fase 1) con contenido real, validando por
primera vez que `ModuleRegistry`/`ModuleLoader` funcionan sin cambios.
También implementa `AudioEngineApi` (`eliner.api`) — la única puerta que
la UI podrá usar para controlar el motor.

```
UI (:app)
    ↓
EliNer API      → eliner.api.AudioEngineApi (+ AudioEngineState, AudioMetricsSnapshot — vocabulario público)
    ↓
Runtime          → (podrá registrar AudioEngine vía ModuleLoader)
    ↓
Audio Engine       → eliner.modules.audio.AudioEngine (implementación)
```

Componentes: `AudioStreamController` (máquina de estados vía
`StateMachine<S>`), `AudioCallback`/`AudioCallbackRegistry`
(infraestructura, sin invocación real), `AudioPipeline` (Input→Engine→
DSP→Mixer→Master→Output, sin procesamiento), `AudioBufferPool` (pool real
sin bloqueos, `ConcurrentLinkedQueue`), `AudioErrorManager` (reutiliza
`EngineError`/`Logger`), `AudioMetrics` (contadores atómicos reales),
`AudioPerformanceMonitor` (lee, nunca modifica).

**Tres decisiones de no-duplicación explícitas:** se reutilizó
`ExecutionLane.AUDIO` (Fase 2) en vez de crear un "Audio Thread" nuevo; se
reutilizó `EngineError`/`Logger` en vez de un `AudioError` paralelo; se
aplicó la lección de la Fase 2.5 (ciclo `api`↔`runtime`) de forma
preventiva, colocando `AudioEngineState`/`AudioMetricsSnapshot`
directamente en `eliner.api` desde el diseño — el grafo de dependencias
verificó sin ciclos en el primer intento, no después de una corrección.
Detalle completo en `docs/adr/0008-audio-engine.md`.



## EliNer Engine — Fase 5: DSP Foundation (implementado)

Construida sobre Audio Engine (Fase 4). Infraestructura DSP pura — ningún
algoritmo real (EQ, compresor, reverb, etc.) implementado. Nuevo paquete
`eliner.dspfoundation`: `DspProcessor` (contrato), `DspFrame` (bloque
planar, preparado para SIMD/NEON futuro), `DspParameter`/
`DspParameterManager` (preparado para automatización/presets/MIDI Learn),
`DspGraph` (sobre `ConnectionGraph<N>`, nuevo utilitario genérico),
`DspChain`/`DspBus`, `DspBufferPool` (sobre `FloatBufferPool`, nuevo
utilitario genérico), `DspScheduler` (ordenamiento topológico real —
Kahn — que solo calcula orden, nunca ejecuta), `DspErrorManager`
(reutiliza `EngineError`/`Logger`), `DspMetrics`, `DspContext`
(agregador), `DspFoundation` (implementa `DspApi`, dueño de `DspState`,
distinto de `EngineState`/`RuntimeState`/`AudioEngineState`/
`AudioSessionState`).

`ConnectionGraph<N>` y `FloatBufferPool` (ambos nuevos en `eliner.core`)
generalizan mecánica ya usada por `AudioRoutingGraph` (Fase 3) y
`AudioBufferPool` (Fase 4) — mismo patrón de no-duplicación que
`StateMachine<S>` estableció en la Fase 3, sin retocar código ya
verificado de fases anteriores.

Con autorización explícita del usuario para esta fase, se agregó un único
campo aditivo `AudioEngine.dsp: DspApi?` (Fase 4) — nullable, sin cambiar
ninguna firma existente, verificado explícitamente que los 8 métodos
públicos que `AudioEngine` ya implementaba siguen exactamente iguales.
Detalle completo en `docs/adr/0009-dsp-foundation.md`.

`dspfoundation` y `modules.audio` quedaron mutuamente desacoplados en
ambas direcciones — ninguno importa al otro directamente; el enganche pasa
enteramente por `DspApi` (`eliner.api`).

## Riesgo declarado de esta fase (para verificar en CI)

El riesgo estructural de la Fase 3 (módulo Gradle independiente) ya fue
verificado en CI y compiló correctamente — ver `docs/adr/0003-...md`.

El riesgo de la Fase 2 de EliNer Engine (Foundation Services) es distinto:
es la primera vez que código de `:eliner` usa APIs reales de Android más
allá de `android.util.Log` — `DeviceCapabilityManager` usa `Context`,
`ActivityManager`, `PackageManager`, `AudioManager`, `Build`. Cada
constante (`FEATURE_VULKAN_HARDWARE_VERSION`, `FEATURE_AUDIO_LOW_LATENCY`,
`FEATURE_AUDIO_PRO`, `FEATURE_USB_HOST`, `FEATURE_BLUETOOTH_LE`,
`PROPERTY_OUTPUT_SAMPLE_RATE`, `PROPERTY_OUTPUT_FRAMES_PER_BUFFER`) se
verificó manualmente contra el nivel de API en que se introdujo — todas
por debajo de `minSdk 24` — pero no se pudo compilar en este entorno
(sin SDK/red) para confirmarlo con el compilador real. Es el punto más
probable de un error de compilación si algo falla en el próximo build.

Todo el resto del código de la Fase 2 (Logger, Event Bus, Configuration,
Resource Manager, Thread Manager, Task Scheduler, Time Service,
Performance Profile Manager) es Kotlin puro sin ninguna API de plataforma
— mismo nivel de confianza que el Core Foundation de la Fase 1.

El riesgo de la Fase 2.5 (Runtime Foundation) es distinto otra vez: es la
primera vez que hay código real de composición entre >10 clases a la vez
(`EliNerRuntime` construye/usa `EliNerCore`, los 8 servicios de
`RuntimeContext`, `LifecycleManager`, `ServiceRegistry`, `ModuleLoader`).
Se verificó exhaustivamente con grep: cada símbolo importado existe donde
se espera, el grafo de dependencias entre paquetes no tiene ciclos (se
encontró y corrigió uno real durante esta misma verificación — ver ADR
0006), balance de llaves/paréntesis en los 17 archivos nuevos o
modificados. Lo que no se pudo verificar por no poder compilar: que el uso
de `kotlinx.coroutines.launch` dentro de `EliNerRuntime.initialize()`
(para reenviar `EliNerCore.errors` al `Logger`) esté sintácticamente
perfecto — es la única línea de esta fase con inferencia de tipos algo más
compleja (`CoroutineScope.launch { core.errors.collect { ... } }`).

El riesgo de la Fase 3 (Audio Foundation) se concentra en
`AudioDeviceManager.kt` — el único archivo nuevo que usa
`android.media.AudioManager`/`AudioDeviceInfo` para enumerar dispositivos
reales (mismo patrón y mismo nivel de confianza que
`DeviceCapabilityManager` en Fase 2, ya verificado exhaustivamente
entonces). El resto de los 11 componentes de Audio Foundation es Kotlin
puro sin ninguna API de plataforma.

La Fase 4 (Audio Engine) es, en teoría, la de menor riesgo hasta ahora:
100% Kotlin puro, cero imports de `android.*` en todo `eliner.modules.audio`
(todo lo que necesita de Android ya llega resuelto vía
`AudioFoundationContext`/`RuntimeContext`). El riesgo real de esta fase no
es de plataforma sino de composición: `AudioEngine` es el archivo con más
dependencias inyectadas de todo el proyecto hasta ahora (8, entre
`AudioFoundationContext` y `RuntimeContext`) — se verificó cada símbolo
por grep, pero es el punto donde más fácil sería un error de tipeo en un
nombre de propiedad que el compilador real detectaría al instante.

La Fase 5 (DSP Foundation) es, otra vez, 100% Kotlin puro — cero imports
de `android.*` en todo `eliner.dspfoundation`. El único algoritmo no
trivial de esta fase es `DspScheduler.computeExecutionOrder()`
(ordenamiento topológico, Kahn) — se verificó su corrección lógica
manualmente (grado de entrada, cola de nodos listos, detección de ciclo
por conteo de nodos ordenados), pero como todo lo demás en este proyecto,
no se pudo ejecutar contra un caso de prueba real sin compilador.

## Lo que NO se implementó en esta fase (a propósito)

EQ, Compressor, Limiter, Gate, Reverb, Delay, Chorus, Flanger, Phaser,
Distortion, Filters, Synth Engine, Instrument Engine, Sampler Engine,
Mixer Engine, Automation Engine, Plugin Rack, Adaptive Plugin System, VST
Host Module, Piano Roll, Playlist, Editor, interfaz gráfica. Ni un solo
`DspProcessor` real existe — `DspChain.process()` y `DspGraph`/
`DspScheduler` son infraestructura real y funcional, pero no tienen ningún
procesador que ejecutar todavía.

## Lo que NO se implementó — Fase 4 (referencia histórica)

DSP Engine, Plugin Rack, Adaptive Plugin System, VST Host Module,
Instrument Engine, Synth Engine, Sampler Engine, Mixer Engine, Playlist,
Piano Roll, Automation Engine, Recording Engine, Export Engine, interfaz
gráfica. Ni un solo callback real se dispara — `AudioCallbackRegistry.dispatch()`
existe pero nada lo llama. El motor nativo (Oboe, `eliner.bridge.EliNerAudioBridge`)
sigue exactamente igual desde antes de EliNer Engine — `AudioEngine` no lo
conoce todavía; conectarlos es trabajo de una fase futura de integración
de backend real.


## EliNer Engine — Fase 6: EliNer Real-Time DSP Integration & Native DSP Runtime (implementado — solo el motor nativo)

Esta fase NO tocó `eliner.modules.audio`/`eliner.dspfoundation` (el stack
Kotlin de las Fases 4-5). Eso fue una decisión, no un olvido — ver
`docs/adr/0010-fase6-frontera-native-dsp.md`. Confirma, con código real,
lo que la Fase 4 ya había dejado escrito arriba: "conectarlos es trabajo
de una fase futura de integración de backend real". Esta fase se enfocó
en el motor que sí produce audio hoy: `eliner.bridge.EliNerAudioBridge`
(JNI) → `AudioEngine.cpp`/`.h` (C++, Oboe).

**Antes de esta fase**, `AudioEngine.cpp` tenía un `std::mutex` dentro del
audio callback (`renderAudio()`), compartido con `noteOn`/`noteOff`/
`setMasterVolume`/etc. llamados desde el control thread — el problema más
grave que puede tener un motor de audio realtime: el audio thread podía
bloquearse esperando el control thread, lo que en un dispositivo real se
manifiesta como clicks, glitches o silencio.

**Después de esta fase:**

```
Control thread (Kotlin/JNI)
    ↓  push (nunca bloquea)
EngineCommandQueue           → CommandQueue.h, SPSC lock-free, capacidad 256
    ↓  pop (audio thread, al inicio de cada callback)
AudioEngine::processCommands() / applyCommand() / applyParameter()
    ↓
AudioEngine::renderAudio()   → voces + Reverb + Delay + master vol/pan
    ↓
Oboe → dispositivo
```

Cambios concretos: cero `std::mutex` en el audio callback; sistema de
parámetros genérico (`DspParameterId` + un solo `EngineCommandType::SetParameter`,
en vez de un tipo de comando por parámetro); sample rate real del stream
(antes hardcodeado a 48kHz, ahora los objetos DSP se construyen después de
abrir el stream, con el valor que el dispositivo realmente negoció);
fallback de `SharingMode::Exclusive` a `Shared` si el dispositivo no
soporta exclusivo; buffer muerto (`mRenderBuffer`, nunca usado) eliminado
en vez de dejarlo como ambigüedad de ownership.

## Riesgo declarado — Fase 6

Mismo patrón de honestidad que las fases anteriores: `CommandQueue.h` se
compiló de forma aislada (`g++ -std=c++20 -fsyntax-only`, limpio) y se
probó funcionalmente — un test con 2 threads de sistema operativo reales
moviendo 2,000,000 de comandos con verificación de orden e integridad, sin
pérdida ni corrupción. Confirmado en dispositivo real vía GitHub Actions
por el usuario: compiló y abrió, incluyendo la integración Kotlin de
device capability → buffer size (ver más abajo).

## Auditoría final (Sección 32 del prompt) — resultado

Ejecutada tras confirmar build real (GitHub Actions) y apertura en dispositivo:

| # | Verificación | Resultado |
|---|---|---|
| 1 | Mutex en el audio callback | Ninguno |
| 2 | Allocations en el audio callback | Ninguna (voces/FX preasignados en `buildDspGraph()`, control thread) |
| 3 | JNI dentro del audio callback | Ninguna |
| 4 | Logging pesado en el audio callback | Ninguno (LOGI/LOGE solo en start/stop/error callbacks) |
| 5 | Filesystem/red en el audio callback | Ninguno |
| 6 | Locks indirectos (SynthVoice/Reverb/Delay/Mixer) | Ninguno |
| 7 | Data races | `mMasterVolume`/`mMasterPan`/parámetros FX se escriben únicamente desde `applyCommand()`/`applyParameter()` (audio thread) |
| 8 | Ciclos de dependencia | `eliner.core` no importa de `api`/`bridge`/`modules` (por diseño, ver EliNerCore.kt) |
| 9 | Código duplicado | Comandos por parámetro unificados en uno genérico (Sección 6) |
| 10 | API pública exponiendo implementación interna | `AudioEngine` es la única clase que toca Oboe directamente; JNI bridge y `EliNerAudioApi` la encapsulan |
| 11-16 | Lifecycle/ownership/graph validation/parameter sync/buffer ownership/native-Kotlin boundary | Resuelto para el motor nativo real. DspGraph Kotlin sigue fuera del camino de audio — decisión documentada, no pendiente accidental |
| 17 | ABI | arm64-v8a, armeabi-v7a, x86_64 — se mantienen los tres (ver decisión abajo) |
| 18 | CMake | FetchContent de Oboe por git (requiere red en cada configuración — riesgo conocido, documentado) |
| 19 | Gradle Wrapper | Resuelto por `.github/workflows/build.yml` (regenera `gradle-wrapper.jar`) |
| 20 | Build real ejecutado | Sí — confirmado por el usuario vía GitHub Actions: compiló y abrió en dispositivo |

## Decisión — ABI (Sección 26)

Se mantienen `arm64-v8a`, `armeabi-v7a`, `x86_64`. No se elimina
`armeabi-v7a` en esta fase: hacerlo es una decisión de producto (¿qué
porcentaje de la base de usuarios objetivo sigue en hardware de 32-bit?),
no una decisión técnica que esta auditoría deba forzar. Queda documentada
como pendiente de decisión de producto, no de código.

## Decisión — C++ Standard (Sección 27)

Se mantiene C++20. No se sube a C++23: el NDK/toolchain actual ya soporta
C++20 de forma estable, y "más nuevo" no es por sí solo una razón técnica
válida (el propio prompt de esta fase lo dice explícitamente). Sin una
necesidad concreta (un feature de C++23 que el proyecto realmente use),
cambiarlo sería exactamente el tipo de cambio decorativo que esta fase
debe evitar.

## Realtime Error Flag y métricas — Secciones 23-24 (cierre)

El audio thread nunca lanza excepciones. En vez de eso, `AudioEngine`
tiene un `std::atomic<uint32_t> mErrorFlags` (bitmask `EngineErrorFlag`)
que solo el audio thread (o el error-callback thread de Oboe) puede
encender vía `raiseError()` — un `fetch_or` relajado, sin bloqueo, sin
excepción. El control thread lo lee (`getLastError()`) y lo limpia
(`clearError()`) cuando quiere, sin ninguna carrera: el audio thread
nunca borra bits, solo el control thread lo hace.

Flags actuales: `kErrorDspNotReady` (el callback se disparó antes de que
`buildDspGraph()` terminara — no debería pasar, pero produce silencio en
vez de un crash si pasa), `kErrorCommandQueueFull` (se detectó un comando
nuevo perdido — comparado contra el último conteo visto, no contra ">0",
para que `clearError()` no sea inútil), `kErrorStream` (Oboe reportó un
error de stream), `kErrorStreamRecoveryFailed` (el intento de reabrir el
stream también falló).

Métricas nuevas expuestas end-to-end (nativo → JNI → `EliNerAudioApi`):
`droppedCommands`, `xrunCount`, `lastError` (como `EngineErrorFlags`,
value class en Kotlin que espeja el bitmask nativo sin duplicar la
definición de los flags). `getLastCallbackDurationMs()` queda disponible
en el motor nativo pero no se expuso todavía por JNI — no había un
consumidor real pidiéndolo; agregar el getter cuando exista uno es una
línea, no una decisión de arquitectura.

Prueba real ejecutada (no solo lectura de código): bitmask OR/store/clear
verificado con un test funcional aislado (g++), igual método que el
command queue.

## Lo que NO se implementó — Fase 6 (a propósito)

DSP Graph con nodos dinámicos y validación de ciclos en el camino real de
audio (sigue siendo Kotlin desconectado — `DspGraph`/`DspScheduler` de la
Fase 5 no tienen ruta a producción de audio; el propio ARCHITECTURE.md
documenta esa fusión, desde la Fase 4, como trabajo de una fase futura de
integración de backend, no algo a forzar aquí). Abstracción Float32/Float64
explícita (el propio prompt de esta fase pide no crear la abstracción
hasta tener un segundo consumidor real). SIMD/NEON (pospuesto
explícitamente). EQ, Compressor, Limiter, Reverb/Delay comerciales,
Sampler, Mixer UI — ninguno de estos efectos, exactamente como pedía el
alcance de la fase.

## Fase 7 — DSP Graph real (vertical slice, un canal)

Retoma exactamente el punto que la Fase 6 dejó abierto: "DSP Graph con
nodos dinámicos ... en el camino real de audio". La Fase 6 decidió no
forzarlo conectando el stack Kotlin desconectado (ADR 0010, opción C); la
Fase 7 lo construye del otro lado — **dentro del motor nativo**, que ya
es la única autoridad de audio real — sin tocar `modules.audio`/
`dspfoundation` en absoluto. Detalle completo en
`docs/adr/0011-fase7-dsp-graph-real.md`.

**Alcance deliberadamente acotado a un canal.** El motor no tiene
enrutamiento multicanal (`Mixer` sigue siendo el stub de Fase 3) — esto
NO es el Mixer del roadmap, es el vertical slice que valida la
arquitectura antes de construir Mixer UI + Channel Rack encima.

**Qué cambió en el camino real de audio:**

```
Antes (Fase 4-6):
  renderAudio() → voces → mReverb->process() → mDelay->process() → master

Ahora (Fase 7):
  renderAudio() → voces → mFxChain.process() [8 slots ordenados,
                            reconfigurables en runtime] → master
```

- `DspModule` (`eliner/include/eliner/dsp/DspModule.h`) — interfaz común
  que `Reverb`/`Delay` ahora implementan, sin cambiar su API pública
  previa (mismo principio aditivo que ADR 0009 Decisión 3).
- `DspChain` (`eliner/include/eliner/dsp/DspChain.h`) — 8 slots ordenados,
  propiedad exclusiva del hilo de audio. `insert`/`remove`/`move` se
  disparan vía 4 comandos nuevos en el mismo `CommandQueue` SPSC
  lock-free ya verificado en Fase 6 (§3-6) — no un mecanismo nuevo.
- **Nunca se hace `delete` en el hilo de audio.** Un módulo retirado
  (removido o reemplazado) se empuja a una segunda cola SPSC
  (`mRetireQueue`, audio→control) y se libera solo desde el hilo de
  control (`collectGarbage()`). `AudioEngine::stop()` la drena
  explícitamente antes de `mFxChain.clear()` para no fugar memoria al
  reiniciar el motor (antes esto era automático vía `unique_ptr`; con
  punteros crudos en la cadena dinámica hay que hacerlo a mano).
- API legacy (`setReverbMix`/`setDelayMix`/etc., Fase 4-6) sigue
  funcionando sin cambio de firma — ahora enruta por tipo de módulo
  dentro de `mFxChain`; no-op seguro si ese módulo fue removido.
- Kotlin: `EliNerAudioApi`/`EliNerAudioBridge` ganan
  `insertModule`/`removeModule`/`moveModule`/`setModuleParameter`/
  `getModuleType` + `DspModuleType.kt` — deliberadamente en el mismo
  paquete `eliner.api` que ya usa la ruta real de audio, no en
  `modules.audio`.

**Qué NO se implementó en esta fase, a propósito:** más de un canal
(Mixer multicanal — roadmap futuro); persistencia de qué hay en cada slot
(no existe aún formato de proyecto real); tipos de módulo nuevos (EQ,
Compressor, Sampler — `DspModuleFactory` devuelve `nullptr` para
cualquiera sin fábrica, sin stub que finja ser real); Mixer UI / Channel
Rack (la decisión de orden acordada: motor real primero, UI después de
validar que escala). Build: mismo entorno sin red que Fase 6 — revisado
archivo por archivo, no compilado aquí; pendiente de confirmación real
vía GitHub Actions.

## MIDI Foundation — infraestructura MIDI real (input), no un DAW MIDI

Cierra un vacío real: el proyecto tenía salida MIDI funcional
(`com.yeivikas.olyze.midi.OlyzeMidiManager`, app → hardware externo) pero
**cero** infraestructura de entrada — nada recibía eventos DE un
controlador MIDI conectado. Esta fase construye esa mitad, como
infraestructura reutilizable en `eliner.modules.midi` + contratos en
`eliner.api`, no como una feature de UI.

**Camino real:**
```
Controlador MIDI (USB/BT) → Android MIDI framework
  → AndroidMidiBackend (único archivo con `android.media.midi`, §26)
  → MidiStreamParser (running status + tiempo real intercalado, por conexión)
  → MidiEventQueue (ArrayBlockingQueue acotada — ver su propio doc para
     por qué NO es un ring buffer lock-free hecho a mano: correcto y
     verificable le gana a "lock-free" no probado en un entorno sin
     compilador — §12)
  → MidiRouter (drena en ExecutionLane.DSP — reusa ThreadManager, sin
     MidiThreadManager nuevo, §27) → MidiClockEngine + consumidores
     registrados (ninguno existe todavía — Synth fuera de alcance, §3)
```

**Conectado de verdad a `:app`** — `MainViewModel` construye
`MidiFoundationModule` directamente (mismo patrón que
`DeviceCapabilityManager`, sin pasar por `EliNerRuntime`/`EliNerCore`,
evitando a propósito el bug de lifecycle A-1 de la fase de hardening) y
lo arranca/detiene en su propio ciclo de vida — no es otro rincón del
stack Kotlin desconectado (ADR 0010).

**Qué NO se implementó, a propósito:** Synth/Sampler/Instrument/Piano
Roll/Sequencer/Mixer UI/Plugin Rack (§3, explícitamente fuera de alcance);
reensamblado de SysEx multi-paquete (solo un payload ya ensamblado, con
tope de 4 KiB — §19); ejecución real de MIDI Learn (el contrato
`MidiParameterBinding` evalúa CC→valor de verdad, pero no hay destino
conectado — `DspParameterManager` es parte del stack Kotlin desconectado,
conectar ahí sonaría "aplicado" sin serlo, §20/§21); migración de
`OlyzeMidiManager` a `EliNerMidiApi` (§42, no tocar lo estable sin
necesidad estricta). Detalle completo, incluyendo qué quedó
"IMPLEMENTADO Y VERIFICADO" vs "PREPARADO, NO IMPLEMENTADO" campo por
campo, en `docs/adr/0012-midi-foundation.md` y en el informe de esta
fase.



