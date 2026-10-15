# EliNer — Hoja de ruta de módulos previstos

> **Estado: NO IMPLEMENTADOS.** Este documento consolida los README que
> antes ocupaban directorios vacíos dentro del source set de `:eliner`
> (ADR 0027). Un directorio con solo un README no es un módulo: sugería
> estructura que no existe y se confundía con paquetes reales
> (`interfaces` vs `api`, `modules/dsp` vs `dspfoundation`, `tests/` dentro
> del código de producción). El contenido se conserva íntegro, sin
> reinterpretar; cuando un módulo se implemente, nace como paquete real con
> su propio README de paquete.

Orden: tal como estaban en el árbol. Cada sección indica su ruta original.

---

<!-- origen: eliner/src/main/java/com/yeivikas/olyze/eliner/hardware/README.md -->
## EliNer — Hardware (Hardware Layer)

**Responsabilidad:** integración con hardware externo, aislando el código
específico de detección/permisos del resto del motor.

**Categorías previstas:**

| Categoría | Detalle |
|---|---|
| **USB Audio** | Interfaces de audio profesionales conectadas por USB |
| **USB MIDI** | Controladores/teclados MIDI por USB |
| **Bluetooth MIDI** | Controladores MIDI inalámbricos |
| **Interfaces de audio** | Abstracción común para USB Audio + audio interno del dispositivo |
| **Micrófonos externos** | Selección de fuente de entrada distinta al mic interno |
| **Controladores MIDI** | Mapeo de controles físicos (knobs, pads, faders) |

**Objetivo:** que `modules/audio` y `modules/midi` solo vean "un
dispositivo disponible", sin importar cómo se conectó (USB, Bluetooth,
interno).

**Futuro uso:** soporte para interfaces de audio profesionales, teclados
MIDI USB/Bluetooth, control surfaces.

**Dependencias:** entrega dispositivos detectados a `modules/audio` y
`modules/midi`. Usa `eliner.diagnostics` para reportar desconexiones/errores.

**Estado actual:** no existe implementación todavía. Carpeta vacía a
propósito — únicamente reserva el espacio en la arquitectura.

---

<!-- origen: eliner/src/main/java/com/yeivikas/olyze/eliner/interfaces/README.md -->
> **Actualización (ADR 0027):** la necesidad que describe esta sección —
> contratos Kotlin compartidos entre módulos sin acoplarlos— ya tiene un
> paquete real: `eliner.contracts` (hoy con los contratos DSP). Lo que sigue
> es el texto original, conservado como histórico.
## EliNer — Interfaces (contratos Kotlin, comunicación interna)

**Responsabilidad:** contratos (interfaces Kotlin) que definen cómo se
comunican entre sí los distintos módulos *internos* de EliNer — a
diferencia de `eliner.api`, que define el contrato hacia la UI (`:app`).

**No confundir con** `eliner/interfaces/` (sección «Interfaces» C++ al final de este documento)
(carpeta nativa, en la raíz del módulo) — esa define contratos C++ entre
componentes nativos; esta define contratos Kotlin entre módulos Kotlin.

**Objetivo:** que la comunicación interna entre módulos (por ejemplo, un
futuro Mixer Engine pidiéndole un buffer procesado al DSP Engine, del lado
Kotlin) también pase por interfaces, no por referencias directas a clases
concretas de otro módulo.

**Futuro uso:** contratos como `AudioModuleContract`, `MidiModuleContract`,
`ResourceProviderContract`, a medida que cada módulo de `eliner.modules` se
implemente del lado Kotlin.

**Dependencias:** ninguna — es, junto con `eliner.api` y `eliner.events`,
una de las carpetas más independientes de todo EliNer.

**Estado actual:** el primer contrato del proyecto ya existe y sirve como
plantilla de referencia: [`eliner.api.audio.EliNerAudioApi`](../../eliner/src/main/java/com/yeivikas/olyze/eliner/api/audio/EliNerAudioApi.kt)
(contrato hacia la UI, no interno — pero mismo patrón a replicar). Esta
carpeta permanece vacía hasta que exista un segundo módulo Kotlin real que
necesite comunicarse con otro.

---

<!-- origen: eliner/src/main/java/com/yeivikas/olyze/eliner/recovery/README.md -->
## EliNer — Recovery (Recovery System)

**Responsabilidad:** recuperación de proyectos ante fallos — backups
automáticos periódicos, snapshots de estado, recuperación tras un crash de
la app o del proceso de audio.

**Objetivo:** que un crash o cierre inesperado nunca implique perder el
trabajo del usuario en un proyecto `.oms`.

**Futuro uso:** autoguardado en segundo plano, historial de snapshots
recuperables, detección de cierre anómalo en el siguiente arranque.

**Dependencias:** trabaja junto a `modules/project` (qué guardar) y
`resources` (dónde guardarlo). Escucha `events` para detectar condiciones
de fallo.

**Estado actual:** no existe implementación todavía. Carpeta vacía a
propósito — únicamente reserva el espacio en la arquitectura.

---

<!-- origen: eliner/src/main/java/com/yeivikas/olyze/eliner/tests/README.md -->
## EliNer — Tests (estrategia, no código)

Esta carpeta **no contiene tests** — Gradle exige que vivan en los source
sets estándar del módulo `:eliner`:

- Unitarios (JVM): `eliner/src/test/java/com/yeivikas/olyze/eliner/...`
- Instrumentados (dispositivo/emulador): `eliner/src/androidTest/java/com/yeivikas/olyze/eliner/...`

**Responsabilidad de esta carpeta:** documentar la estrategia de testing
por categoría, para que quede decidida de antemano.

**Categorías previstas:**

| Categoría | Tipo de test | Ubicación futura |
|---|---|---|
| **Unit Tests** | Lógica pura Kotlin, contratos de `eliner.api`/`eliner.interfaces` con fakes | `eliner/src/test` |
| **Integration Tests** | Kotlin ↔ JNI ↔ nativo end-to-end (requiere `.so` cargado) | `eliner/src/androidTest` |
| **Performance Tests** | Latencia, uso de CPU/RAM del motor de audio | `eliner/src/androidTest` (Macrobenchmark, a evaluar) |
| **Stress Tests** | Polifonía máxima sostenida, buffers al límite | `eliner/src/androidTest` |
| **Regression Tests** | Snapshot de comportamiento conocido antes de cada release | Por definir (podría vivir en `:app` si involucra UI) |

**Estado actual:** no hay tests de EliNer todavía, ni carpetas creadas en
`test/`/`androidTest/` dentro de `:eliner` — se documenta la estrategia,
no se implementa.

---

<!-- origen: eliner/src/main/java/com/yeivikas/olyze/eliner/modules/dsp/README.md -->
## EliNer — Modules / DSP Engine

**Responsabilidad:** procesamiento digital de señal — síntesis (osciladores,
envolventes, filtros), efectos (reverb, delay) y, a futuro, procesamiento
avanzado (EQ paramétrico, compresión, saturación, convolución).

**Objetivo:** aislar todo el procesamiento matemático de señal en un solo
lugar, independiente de cómo entra o sale el audio del dispositivo.

**Futuro uso:** el Mixer Engine y el Audio Engine invocan este módulo para
procesar buffers; el Plugin System (a futuro) se apoyará en las mismas
interfaces de procesamiento.

**Dependencias:** ninguna hacia otros módulos de más alto nivel. Puede ser
usado por `mixer`, `render` y, más adelante, por el Plugin System.

**Estado actual:** ya existe una implementación real del lado nativo, dentro
del propio módulo `:eliner`:
- Síntesis — headers: `eliner/include/eliner/dsp/` (Oscillator, Envelope,
  Filter, SynthVoice); implementación: `eliner/src/main/cpp/dsp/`
- Efectos — headers: `eliner/include/eliner/fx/` (Reverb, Delay);
  implementación: `eliner/src/main/cpp/fx/`

Esta carpeta Kotlin es el punto de extensión reservado para lógica DSP
futura del lado Kotlin/JVM (por ejemplo, generación/edición de presets). No
se implementa DSP nuevo en esta fase.

**No confundir con `eliner.dspfoundation`** (Fase 5 de EliNer Engine —
"DSP Foundation"): esa es la infraestructura administrativa alrededor del
DSP (contrato de procesador, grafo, cadena, buses, parámetros, scheduler)
— no implementa ningún algoritmo. Esta carpeta (`modules/dsp`) sigue
siendo el futuro "DSP Engine" real, todavía sin implementar del lado
Kotlin.

---

<!-- origen: eliner/src/main/java/com/yeivikas/olyze/eliner/modules/mixer/README.md -->
## EliNer — Modules / Mixer Engine

**Responsabilidad:** mezcla multicanal — niveles, pan, sends de efectos,
buses, salida master.

**Objetivo:** ser el punto único donde las señales de múltiples voces/pistas
se combinan antes de llegar al Audio Engine.

**Futuro uso:** base para la futura UI de mezclador multicanal (ver Roadmap
en el README raíz del proyecto).

**Dependencias:** consume `dsp` (efectos por canal) y entrega su salida al
`audio` (Audio Engine).

**Estado actual:** ya existe una implementación real y mínima del lado
nativo en `eliner/include/eliner/mixer/Mixer.h` (mezcla estéreo simple usada
por el motor actual — header-only, sin `.cpp` propio). Esta carpeta Kotlin
queda reservada para la lógica de UI/estado del futuro mezclador
multicanal. No se implementa lógica nueva en esta fase.

---

<!-- origen: eliner/src/main/java/com/yeivikas/olyze/eliner/modules/plugin/README.md -->
## EliNer — Modules / Adaptive Plugin System

**Responsabilidad:** carga, gestión y ejecución de plugins/instrumentos
adicionales dentro de EliNer.

**Objetivo:** permitir extender el motor con nuevos generadores/efectos sin
modificar el core — cumpliendo el principio arquitectónico obligatorio de
módulos reemplazables e independientes.

**Futuro uso:** instrumentos virtuales adicionales (ver Roadmap del
proyecto); eventualmente, un posible host de formatos externos.

**Dependencias:** se comunicará con `dsp`, `audio` y `events` a través de
interfaces — nunca con acceso directo a memoria/estado interno del motor.

**Estado actual:** no existe implementación todavía. Esta carpeta es
intencionalmente la más vacía de todas: es el punto de extensión a más
largo plazo del proyecto. Explícitamente fuera de alcance en esta fase
(ver regla 17: no implementar VST Host, Plugin Host ni plugins).

---

<!-- origen: eliner/src/main/java/com/yeivikas/olyze/eliner/modules/project/README.md -->
## EliNer — Modules / Project System

**Responsabilidad:** ciclo de vida del proyecto del usuario — crear, abrir,
guardar, cerrar; serialización a disco.

**Objetivo:** ser el único módulo que lee/escribe el futuro formato de
proyecto `.oms` (ver especificación preliminar en
`docs/eliner/PROJECT_FORMAT_OMS.md`).

**Futuro uso:** guardar/cargar todo el estado de una sesión — timeline,
mezcla, presets, automatización, metadata.

**Dependencias:** coordina con `timeline`, `mixer`, `resources` (para
samples/presets referenciados) y `recovery` (para snapshots/backups).

**Estado actual:** no existe implementación todavía. Carpeta vacía a
propósito — el formato `.oms` en sí tampoco está implementado, solo
documentado como referencia futura.

---

<!-- origen: eliner/src/main/java/com/yeivikas/olyze/eliner/modules/render/README.md -->
## EliNer — Modules / Render Engine

**Responsabilidad:** renderizado offline (bounce) del proyecto o de pistas
individuales a archivos de audio (WAV/otros formatos, a definir).

**Objetivo:** separar la reproducción en tiempo real (Audio Engine) del
procesamiento no-realtime necesario para exportar un proyecto completo.

**Futuro uso:** exportación de proyectos, bounce de pistas, mezcla final.

**Dependencias:** consume `dsp`, `mixer` y `timeline` para reconstruir el
proyecto completo fuera del hilo de audio en tiempo real.

**Estado actual:** no existe implementación todavía. Carpeta vacía a
propósito — únicamente reserva el espacio en la arquitectura. Ver también
Fase 9 / regla explícita de no implementar render profesional todavía.

---

<!-- origen: eliner/src/main/java/com/yeivikas/olyze/eliner/modules/timeline/README.md -->
## EliNer — Modules / Timeline Engine

**Responsabilidad:** representación temporal del proyecto — patrones,
secuenciador, playlist, posición de reproducción/grabación, quantización.

**Objetivo:** desacoplar "qué suena y cuándo" (Timeline) de "cómo suena"
(DSP/Mixer) y de "cómo se guarda" (Project System).

**Futuro uso:** soporte para el Piano Roll, la Playlist/Secuenciador y la
grabación MIDI/audio listados en el Roadmap del proyecto.

**Dependencias:** orquesta llamadas hacia `midi`, `audio` y `mixer` según la
posición temporal; es consumido por el Project System al guardar/cargar.

**Estado actual:** no existe implementación todavía, ni siquiera parcial.
Carpeta vacía a propósito — únicamente reserva el espacio en la arquitectura.

---

<!-- origen: eliner/interfaces/README.md (raíz del módulo Gradle, fuera del source set) -->
## EliNer — interfaces/ (contratos C++, nativo)

**Responsabilidad:** clases base abstractas (interfaces C++, típicamente
`class IFoo { public: virtual ~IFoo() = default; virtual ... = 0; };`) que
definirán los contratos entre módulos nativos del motor — por ejemplo, un
futuro `IDspProcessor` que tanto `Reverb` como `Delay` implementarían, para
que `AudioEngine` los procese de forma polimórfica sin conocer el tipo
concreto.

**No confundir con** `eliner.interfaces` (sección «Interfaces» Kotlin de este documento)
(paquete Kotlin) — esa carpeta define contratos del lado Kotlin/JVM entre
módulos Kotlin; esta define contratos del lado C++ entre módulos nativos.
Son capas de interfaz paralelas, una por lenguaje, ambas cumpliendo el
mismo principio arquitectónico obligatorio (módulos independientes,
comunicación vía interfaces).

**Objetivo:** hoy `AudioEngine.h` conoce directamente los tipos concretos
`Reverb`, `Delay`, `Mixer`, `SynthVoice` (ver
`include/eliner/core/AudioEngine.h`). Eso es aceptable para el motor actual
(pequeño, estable), pero si el DSP Engine crece con más efectos/plugins,
esta carpeta es donde se definirán las interfaces que permitan
desacoplarlos sin modificar `AudioEngine`.

**Futuro uso:** `IDspProcessor`, `IAudioSource`, `IMidiSink`, u otros
contratos nativos, según lo que necesite cada módulo cuando se implemente.

**Estado actual:** no existe ninguna interfaz nativa todavía — el motor
actual es pequeño y su acoplamiento directo (`AudioEngine` → `Reverb`/
`Delay`/`Mixer` concretos) es aceptable en este tamaño. No se introduce
abstracción especulativa sin necesidad real (evitar over-engineering).
Carpeta vacía a propósito.
