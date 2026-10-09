# ADR 0028 — Reloj de transporte, metrónomo y delay sincronizado al tempo

**Estado:** Aceptado — pendiente de confirmación por compilación Kotlin/NDK en CI
(ver «Límites de la verificación»).
**Relacionado:** ADR 0027 (capas y fachada `EliNerEngine`), Fase 6 §6 (convención
`SetParameter`), Fase 6 §16 (API pública estable).

## Contexto

El control de BPM de la UI «no hacía nada». La investigación lo confirmó con el
código, no con suposiciones:

- `AudioEngine::setTempo(float)` era un método **vacío** (su comentario decía
  «no tempo-dependent DSP exists yet»), y ni la API de Kotlin ni el puente JNI
  lo exponían.
- El único efecto del BPM era un reloj MIDI hacia un dispositivo externo,
  generado en Kotlin con `delay()` (planificación sin precisión de muestra).
- No existía ningún consumidor interno del tempo: ni metrónomo, ni secuenciador,
  ni efecto sincronizado.

Se pidió una solución de nivel comercial, ordenada y desacoplada, sin tocar la
API de EliNer de forma que la rompa.

## Decisión

### 1. Reloj de transporte nativo con precisión de muestra (`TempoClock`)

`eliner/include/eliner/transport/TempoClock.h`. Dado el tempo y el tamaño de
cada bloque de audio, devuelve el **frame exacto** de cada pulso dentro del
bloque. Es la única autoridad del tiempo musical del motor: el metrónomo y el
delay sincronizado lo consumen, y un secuenciador futuro también.

- Estado: frames que faltan para el próximo pulso, en `double`, arrastrando la
  fracción de muestra de pulso en pulso: no acumula error de redondeo.
- **Fase continua** al cambiar el tempo: el tiempo restante del pulso en curso se
  reescala por `bpmAnterior/bpmNuevo`. Ni pulsos repetidos ni omitidos.
- `start()` idempotente (un «play» repetido no reinicia el compás); `stop()` +
  `start()` vuelve al primer tiempo.
- Cambiar la frecuencia de muestreo en marcha reescala la fase (relevante al
  reabrir el stream).

### 2. Metrónomo (`Metronome`) como consumidor del reloj, en un bus propio

Seno con ataque de 0,5 ms y caída exponencial (τ = 12 ms); el primer tiempo del
compás suena a 1500 Hz y más fuerte, el resto a 1000 Hz. Se mezcla **después de la
cadena de FX y del volumen master**: no pasa por reverb/delay y no lo silencia el
master (como el bus de click de una DAW). Volumen con curva cuadrática y ganancia
suavizada (τ = 5 ms) contra *zipper noise*. Un click que nace desde el silencio
nace ya a la ganancia objetivo (ver «Defectos encontrados», 2).

### 3. Delay sincronizado al tempo, sin clicks

- `segundos = 60 / BPM × pulsos`, **plegado por octavas** al rango del delay
  (`foldIntoRange`): a 20 BPM una corchea con puntillo son 2,25 s, que no caben
  en el buffer; se usa la mitad (1,125 s), que conserva la relación rítmica.
  Recortarla la rompería.
- `Delay::setTime` ya no mueve el puntero de lectura de golpe (eso es un click, y
  con sync ocurre en cada cambio de BPM, p. ej. al mantener pulsado `+`). El
  tiempo se alcanza **deslizándose**: filtro de un polo (τ = 40 ms) con techo de
  velocidad de 0,2 muestras/muestra (≤ ±20 % de afinación durante un salto
  grande), con lectura interpolada solo mientras se desliza. En régimen estático
  la lectura es directa y la salida es **idéntica bit a bit** a la del delay
  anterior (probado contra una réplica literal del original).
- **Activo por defecto** con corchea con puntillo (0,75 pulsos): a 120 BPM son
  0,375 s, el tiempo histórico del delay, así que a ese tempo el sonido no cambia.
  Fijar `DelayTime` a mano (`setDelayTime` o el parámetro del módulo) lo desactiva
  («el último que escribe gana»); `setDelayTempoSync(true, …)` lo reactiva.

### 4. API: contrato nuevo, `EliNerAudioApi` intacta

`api.transport.EliNerTransportApi` (+ `NoteDivision`, `DelayTempoSync`), expuesta
como `EliNerEngine.transport`. **No** se añadieron métodos a `EliNerAudioApi`:
ampliar una interfaz pública rompe a cualquier implementador (Fase 6 §16). Su
fichero es idéntico al anterior salvo un comentario de ruta.

La capa Kotlin separa tres responsabilidades:

- `TransportController` (puro, sin dependencias de Android, testeable en JVM): estado
  **deseado**, validación y límites. Necesario porque `nativeCreate` construye un
  `AudioEngine` nuevo en cada arranque y el arranque es asíncrono: un cambio hecho
  antes de que el motor exista se perdería.
  - **Nunca llama a JNI en el hilo del llamador.** `nativeCreate` mantiene
    `gEngineMutex` mientras Oboe abre el stream (hasta el timeout de 5 s); una llamada
    directa desde la UI la congelaría. Los setters actualizan el estado al instante y
    marcan qué cambió; un hilo propio (`eliner-transport-commands`) lo vacía hacia JNI.
  - **Coalescencia.** Cada vaciado lee el estado más reciente y envía solo los campos
    marcados, en orden fijo (configuración primero, transporte en marcha al final).
    Mantener pulsado `+` mientras el hilo del motor está bloqueado produce UN envío del
    último tempo, no una cola creciente, y un valor viejo nunca llega después de uno nuevo.
  - **Reaplicación ante cualquier recreación del motor.** `EliNerAudioBridge.start()`
    notifica tras cada `nativeCreate` exitoso (listener) y el controlador marca todo
    como pendiente. Cubre también un `engine.audio.stop()` + `start()` hecho por un
    consumidor de la API pública, no solo el primer arranque.
- `EliNerTransportBridge` (JNI, sin estado) detrás de la interfaz mínima `TransportNative`.
- `DefaultEliNerEngine` crea el hilo, conecta el listener y los apaga en `shutdown()`.

### 5. Convenciones del proyecto respetadas

- Parámetros de un valor → `SetParameter` + `DspParameterId` (Fase 6 §6): siete
  valores nuevos (`Tempo`, `TransportRunning`, `BeatsPerBar`, `MetronomeEnabled`,
  `MetronomeVolume`, `DelaySyncEnabled`, `DelaySyncBeats`), **sin** tipos de
  comando nuevos. (Mi primer diseño los contradecía; lo detecté al releer la
  cabecera de `CommandQueue.h`.)
- Hilo de audio: sin asignación, sin bloqueo, sin E/S.
- Validación de entradas externas **por bits** (`tempo::isFinite`): el build usa
  `-ffast-math`, bajo el cual `std::isfinite(NaN)` puede devolver `true`
  (comprobado empíricamente: con `-ffast-math` devuelve 1; la versión por bits, 0).

### 6. Cambios de estructura

- `EngineHandle.{h,cpp}`: `gEngine`/`gEngineMutex` salen de `EliNerAudioBridge.cpp`
  (donde eran `static`) para que cada dominio tenga su JNI propio
  (`EliNerTransportBridge.cpp`) sin duplicar estado. Semántica sin cambios.
- Nuevo módulo nativo `transport/` (`include/eliner/transport`, `src/main/cpp/transport`).
- **UI:** botón de metrónomo (♪) en el grupo de transporte del header. Ver «Decisiones
  que requieren tu aprobación».
- `MainViewModel`: el tempo, el estado de reproducción y el metrónomo dejan de ser
  estado local y pasan a leerse del transporte del motor (una sola fuente de
  verdad). `MidiClockGenerator` toma el tempo `Float` del mismo transporte.

### 7. Barrera de verificación nueva

- **V8** en `tools/verify_architecture.py`: cada `external fun` de Kotlin debe
  tener su `Java_…` en C++ con igual nombre, número y tipo de parámetros y tipo de
  retorno. Un desajuste *compila* y revienta en ejecución (`UnsatisfiedLinkError`);
  ni el compilador ni el CI lo ven. Valida 34 funciones JNI.
- `check_engine_compile.sh` + `platform_stubs/`: compila `AudioEngine.cpp` y los
  dos puentes JNI con los flags reales del build sin NDK (stubs de Oboe, Android
  log y `jni.h`). El motor original compila con los mismos stubs (los stubs son
  representativos).
- `oboe_fake.cpp`: backend de audio **falso** que permite instanciar el
  `AudioEngine` real e invocar `onAudioReady()` a mano. No es Oboe.

## Verificación realizada

| Comprobación | Resultado |
|---|---|
| Suite nativa (ASan + UBSan, TSan donde aplica) | **30 ejecuciones, 0 problemas** (22 previas + 8 nuevas de 7 tests; el de concurrencia corre bajo dos sanitizers); 71 s en total |
| Propiedades con oráculo independiente (fase musical integrada por tramos en `long double`) | 240 escenarios, 32 607 pulsos, desviación máxima **0 frames**, con tempo/bloque (1–9000 frames)/frecuencia (8–192 kHz) aleatorios |
| Concurrencia (ThreadSanitizer, `AudioEngine` real + hilo de audio + 1, 2 y 3 hilos de control) | 0 data races, salida siempre finita |
| Coste en CPU (build de producción, ráfaga de 192 frames) | +2 a +5,5 µs por bloque de 4 ms (≈ 0,05–0,14 % del presupuesto) |
| Integración del `AudioEngine` real contra backend falso | 35 aserciones: clicks en el frame exacto, cambio de tempo en caliente, bus independiente de FX y master, tiempos de delay **exactos al sample** (18000/36000/9000/54000/12000/16000/9600/24000), 44 100 y 96 000 Hz |
| Asignaciones en el hilo de audio (600 bloques, todo activo) | **0** (medido reemplazando `operator new/delete`, todas las variantes) |
| Compilación del motor y JNI (`-O3 -ffast-math -fno-exceptions -Wall -Wextra`) | 6/6 archivos, 0 warnings |
| `verify_architecture.py` | 0 violaciones (138 archivos Kotlin, 34 funciones JNI) |
| Pruebas de mutación | **32 roturas inyectadas, 32 detectadas** (reloj 3, metrónomo 1, delay 3, integración 7, asignaciones 4, compilación 2, JNI 4, propiedades y delay 7, concurrencia 1) |

### Defectos encontrados por esa verificación en mi propio trabajo

1. **Delay atascado (real).** Con la longitud en `float`, el paso del filtro
   (`diff × 5e-4`) para diff pequeño caía por debajo de media unidad de resolución
   (≈ 0,0078 a ~70 000 muestras), se redondeaba a 0 y el delay quedaba ~7 muestras
   antes del objetivo **para siempre**. Corregido pasando posición y objetivo a
   `double`; el test de convergencia lo detectó.
2. **Primer click atenuado (real).** Activar el metrónomo en el mismo instante en que
   arranca el transporte atenuaba justo el primer tiempo (el acentuado) por la rampa
   de ganancia. Corregido: un click que nace del silencio nace a la ganancia objetivo.
3. **`AppHeader` incompleto.** Añadí un parámetro sin declararlo en la firma ni
   pasarlo desde `MainScreen` (error de compilación seguro). Lo detectó una
   comprobación cruzada de parámetros.
4. **Medición ciega a `new[]`.** Mi contador de asignaciones no veía `new float[n]`
   porque ASan intercepta `operator new[]` por su cuenta; lo descubrió la mutación
   (la primera inyección «sobrevivió»). Ahora cubre todas las variantes.
5. **Tests míos con aritmética o supuestos erróneos** (p. ej. `24000×3` frames son
   3 pulsos, no 6; un seno de 440 Hz hace invisible el salto del delay antiguo).
   El motor era correcto; se corrigieron los tests y se documentó el porqué.

#### Segunda pasada (auditoría completa)

6. **`BpmControl`: el control solo oscilaba entre 119 y 121 (bug preexistente, probable
   causa de «solo es visual»).** `pointerInput(Unit)` ejecuta su bloque una sola vez y
   capturaba `bpm = 120`; «+» calculaba siempre 121 y «−» siempre 119, y mantener
   pulsado repetía ese valor. Corregido de raíz: el control emite un **paso** (+1/−1) y
   `MainViewModel.stepBpm` lo aplica sobre el tempo actual del motor, de modo que ningún
   valor capturado puede quedar obsoleto. El resto de `pointerInput(Unit)` de la app solo
   captura callbacks sin estado mutable (revisados).
7. **La UI podía congelarse durante el arranque (mi diseño).** `TransportController`
   llamaba a JNI en el hilo del llamador; `nativeCreate` mantiene `gEngineMutex` mientras
   Oboe abre el stream. Rediseñado con hilo propio y coalescencia (ver sección 4).
8. **El estado del transporte se perdía si el consumidor reiniciaba el audio.** Solo el
   primer `start()` lo reaplicaba; `engine.audio` es público y expone `start()`/`stop()`.
   Ahora lo hace el puente de audio tras cada creación del motor.
9. **La suite del CI habría fallado al compilar** mi test de propiedades: la suite
   descubre los `.cpp` automáticamente y las fuentes del test no estaban registradas.
11. **Error de compilación que llegó al CI** (`Unresolved reference 'setEngineCreatedListener'`).
    `EliNerAudioBridge.getInstance()` está declarado como `fun getInstance(): EliNerAudioApi`
    (devuelve la interfaz a propósito: «callers never depend on this concrete
    implementation»), pero `DefaultEliNerEngine` llamaba a un método que solo existe en la
    clase concreta. Asumí el tipo en vez de leer la firma. Corregido con un acceso
    `internal` solo para la raíz de composición (`getInstanceForComposition()`), sin tocar
    el contrato público del singleton. Mi verificador solo comprobaba que el *nombre*
    existiera, no el *tipo* del receptor, así que no podía verlo. Se añadió
    `tools/verify_kotlin_references.py` (R1: miembro inexistente en el tipo declarado, con
    resolución de cadenas `a.b.c`; R2: tipo usado sin import), calibrado con el proyecto
    original (120 archivos que compilan: 0 hallazgos), que reproduce este error exacto y
    detecta errores inyectados; corre como segundo paso del CI.
10. **Huecos en mis propios tests**, descubiertos por mutación: el test «delay en el
    máximo» nunca alcanzaba el extremo (el glissando tarda ~8 s y procesaba 3 s; su mensaje
    afirmaba lo contrario), nada probaba el límite de feedback, y una de mis mutaciones era
    un no-op. Reforzados con casos que alcanzan los extremos (ASan detecta ahora la lectura
    fuera del buffer si se relaja el límite).

### Hallazgo preexistente (no tocado)

`EliNerAudioBridge.cpp` exporta `nativeGetLastCallbackDurationMs`, que ninguna clase
Kotlin declara (la métrica de duración del callback de audio nunca se cableó). Es
inofensiva en ejecución; V8 la reporta como AVISO, no como error.

## Límites de la verificación (declarados)

- **El Kotlin de esta fase no se ha compilado ni ejecutado** (tampoco los tests de `TransportController`, `NoteDivision` y de constantes). La verificación de referencias (R1/R2) reduce el riesgo pero NO es un compilador: no valida tipos de argumentos, nulabilidad ni genéricos. Un error de compilación ya llegó al CI una vez (defecto 11). No hay `kotlinc`/Gradle aquí. Lo
  cubren `verify_architecture.py`, el balance de llaves y la revisión manual, que no
  sustituyen al compilador. La confirmación es el CI.
- **No se ha probado en dispositivo**: carga real de la librería, JNI en ejecución,
  y el comportamiento de Oboe/AAudio (latencia, xruns, reconexión) no son
  observables aquí. El backend falso valida la lógica del motor, no a Oboe.
- La suite nativa **no** compila contra el Oboe real; eso lo hace el CI con NDK.
- El reloj MIDI de salida sigue en Kotlin (`delay()`); ver «Trabajo futuro».

## Decisiones que requieren tu aprobación

1. **Botón de metrónomo en el header.** El código del header dice explícitamente
   «no agregar nada acá sin que se pida». Lo añadí porque sin un control el
   metrónomo sería inalcanzable desde la app. Es un único botón circular de 28 dp,
   con el estilo de los existentes. Si no lo quieres, se quita borrando
   `MetronomeButton` y su uso (el resto de la arquitectura no depende de él).
2. **Delay sincronizado activo por defecto.** A 120 BPM suena igual que antes;
   a otros tempos el eco sigue al BPM. Se desactiva con
   `transport.setDelayTempoSync(false)`.
3. **Un paso de 1 BPM = glissando breve del eco** (≈ 8 % de afinación al inicio, que
   decae con τ = 40 ms y se asienta en < 0,4 s) en lugar de un click. Es el compromiso estándar de un delay con
   tiempo modulado; el techo de velocidad lo acota.

## Trabajo futuro

- Derivar el reloj MIDI de salida del `TempoClock` nativo (callback nativo→JVM por
  pulso), eliminando el `delay()` de Kotlin.
- Exponer posición de compás/pulso a la UI para un indicador visual.
- Cablear (o eliminar) `nativeGetLastCallbackDurationMs`.
- Control de volumen del metrónomo en la UI (la API y el motor ya lo soportan).
