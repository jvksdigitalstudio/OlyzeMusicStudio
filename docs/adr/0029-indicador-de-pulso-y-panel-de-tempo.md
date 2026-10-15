# ADR 0029 — Indicador de pulso real y panel de tempo

**Estado:** Aceptado — **pendiente de confirmación por compilación Kotlin/NDK y prueba en
dispositivo** (ver «Límites de la verificación»).
**Relacionado:** ADR 0028 (reloj de transporte, metrónomo, delay sincronizado), ADR 0027 (capas y
fachada `EliNerEngine`).

## Contexto

Tras el ADR 0028 el motor tiene un reloj de transporte con precisión de muestra, un metrónomo y un
delay sincronizado, pero la UI solo exponía play/stop, BPM y el interruptor del metrónomo. Quedaban
cuatro mejoras pequeñas que cierran esa funcionalidad:

1. Indicador visual del pulso.
2. Control de volumen del metrónomo.
3. Selector de compás.
4. Selector de división del delay sincronizado.

Las tres últimas ya existían en la API (`setMetronomeVolume`, `setBeatsPerBar`,
`setDelayTempoSync`) y en el motor; faltaba la UI. La primera **no se podía hacer con la API
existente**: ningún dato salía del hilo de audio hacia la UI. Un indicador que se calcule en Kotlin
con un temporizador volvería a ser exactamente el problema que el ADR 0028 resolvió (tiempo
musical fuera del reloj de muestra), así que el indicador debe leer el reloj real.

## Decisión

### 1. El hilo de audio publica la posición de pulso en un único `std::atomic<uint64_t>`

`eliner/include/eliner/transport/BeatPulse.h` define el formato (64 bits):

| Bits | Campo |
|---|---|
| 0–7 | `beatInBar` (0 = primer tiempo) |
| 8 | `running` |
| 16–62 | `sequence`: contador monótono de pulsos (cambia en cada pulso) |

`sequence` es imprescindible: con un compás de 1 tiempo `beatInBar` no cambia nunca, y sin ella la
UI no podría distinguir «pulso nuevo» de «mismo pulso». Capacidad: 2⁴⁷−1 pulsos (≈ 9·10⁵ años a
300 BPM), con el bit 63 siempre 0 para que el valor sea positivo como `jlong`
(`static_assert` en el test).

`AudioEngine::renderAudio` hace **un** `store(release)` por bloque en el que cae algún pulso, y
`TransportRunning=false` publica `running=0` conservando la secuencia (para que la UI vea la
parada aunque no llegue otro pulso). Sin locks, sin asignación: seguro en tiempo real.
`AudioEngine::pulseSnapshot()` lo lee desde cualquier hilo (`load(acquire)`).

### 2. JNI `nativeReadPulse` con `try_lock`, nunca bloqueante

Se sondea ~60 veces por segundo. `nativeCreate` mantiene `gEngineMutex` mientras Oboe abre el
stream (hasta 5 s en un dispositivo lento); un `lock_guard` congelaría al sondeador. Con
`try_lock`: ocupado → `-1` («sin dato», se conserva el valor previo); sin motor → `0`.
Es la única función JNI del proyecto que NO usa `lock_guard`, a propósito y documentada.

### 3. API: `EliNerTransportApi.pulse: Flow<BeatPulse>`

Flujo **frío**: sondea (en `Dispatchers.Default`, nunca en el hilo del motor de comandos ni en el
de la UI) solo mientras alguien lo recolecta, y emite solo cuando el valor cambia. La app en
segundo plano no cuesta nada (`collectAsStateWithLifecycle`). `BeatPulse` es un `data class` en
`api.transport`; el decodificador es `internal`. Se añade `readPulse()` a `TransportNative`
(interfaz `internal`, único implementador real + el doble de test).

Es el único cambio en una interfaz pública: añadir un miembro rompe a quien implemente
`EliNerTransportApi`, pero el único implementador es `TransportController` (comprobado con
búsqueda en todo el repositorio).

### 4. UI

> Nota (ADR 0030): `Header.kt` y `TempoControls.kt` se repartieron después en los paquetes
> `ui/components/transport/` y `ui/components/tempo/`.
> **Nota (ADR 0031):** el panel descrito aquí (hoja inferior) fue **reemplazado** por un popover con
> dial circular, tap tempo y metrónomo profesional; ver ADR 0031. El indicador de pulso del header
> sigue como se describe.

- **Indicador:** el botón de metrónomo (♪) del header **destella** en cada pulso (más fuerte en el
  primer tiempo), con o sin click audible. No añade ningún elemento al header (la restricción del
  comentario en `AppHeader` se respeta). El estado `pulse` llega como `State` y solo se lee en la
  fase de dibujo: un pulso no recompone el header ni la pantalla.
- **Panel de tempo** (`TempoControlsSheet`, hoja inferior con los tokens `Fl*` del header): puntos
  de pulso por tiempo (el primero, verde y mayor), ON/OFF + volumen del metrónomo, selector de
  compás y rejilla de divisiones del delay (1/4, 1/8, 1/16 × recta/puntillo/tresillo, más 1/2 y
  1/1) con interruptor de sync. Cierra con scrim, botón ✕ y botón Atrás. Zonas táctiles ≥ 40 dp
  y `contentDescription`/`Role.Button` en todos los controles.
- **Cómo se abre:** tocando el número de BPM del header (decisión que requiere tu aprobación, ver
  abajo).

## Corrección de una afirmación previa: compases

Se dijo que el motor «ya soporta» selector de compás «3/4, 6/8…». **Es inexacto**: el reloj cuenta
**pulsos de negra** (`beatsPerBar` = negras por compás). Mapear «6/8» a 6 negras sonaría como 6/4.
Por eso el panel ofrece solo compases **x/4 (2/4 a 7/4)**. Un 6/8 o 12/8 correcto necesita una
unidad de pulso configurable en `TempoClock` (p. ej. pulso de corchea o de negra con puntillo) y
un cambio de contrato; queda como decisión abierta.

## Verificación realizada

| Comprobación | Resultado |
|---|---|
| Suite nativa (ASan + UBSan; TSan en los tests con hilos) | **32 ejecuciones, 0 problemas** (30 previas + `test_engine_beat_pulse` ×2) |
| `test_engine_beat_pulse` (AudioEngine REAL, backend falso) | Reposo = 0; 3 s a 120 BPM → exactamente 6 pulsos, el 6.º en tiempo 2; compás de 1 tiempo: `sequence` avanza aunque `beatInBar` no; parar → `running=0` con secuencia conservada y valor estable; rearrancar → tiempo 0 y secuencia continúa; lector concurrente (300 BPM, 20 s): secuencia nunca retrocede (TSan limpio) |
| Formato empaquetado | `static_assert` en C++ (campos independientes, bit 63 = 0 hasta 2⁴⁷−1) + test JVM que **lee `BeatPulse.h`** y compara máscara/desplazamientos con el decodificador Kotlin |
| `verify_architecture.py` (V1–V8) | OK; 35 funciones JNI (V8 confirma firma de `nativeReadPulse`) |
| `verify_kotlin_references.py` | OK, 141 archivos |
| Flaky detectado y corregido | La comprobación de lector concurrente fallaba sin sanitizers (el render terminaba antes de arrancar el hilo); ahora espera a la primera lectura. Repetido 5× con -O0/-O1/-O2 |

## Límites de la verificación (declarados)

- **Kotlin NO se compiló** en este entorno (sin Gradle/Android SDK/`kotlinc`). Solo pasaron los
  verificadores estáticos del repositorio, que NO detectan errores de tipos ni de Compose. Los
  tests JVM nuevos (`TransportControllerTest` ×5, `TransportConstantsMatchNativeTest` ×2) están
  escritos pero **no se ejecutaron**. Debe confirmarlo el CI/compilación local.
- La UI **no se probó en dispositivo**. Pendiente de validar a mano: destello sincronizado con el
  click, panel en landscape (se desplaza si no cabe), arrastre fluido del volumen, y que la app no
  se congela al abrir.
- Sincronía con el oído: el destello marca cuándo el hilo de audio **renderizó** el pulso; el
  sonido sale con la latencia de salida del dispositivo, así que el destello se adelanta ese
  tiempo (decenas de ms con Oboe de baja latencia). No es un error del reloj. Una compensación
  exacta requeriría la latencia de salida medida de Oboe; no se hizo.

## Decisiones que requieren tu aprobación

1. **Abrir el panel tocando el número de BPM.** No añade controles al header, pero es poco
   descubrible. Alternativas: pulsación larga en ♪, o un botón nuevo (requiere autorizar tocar el
   header).
2. **Destello en ♪** como único indicador en el header (los puntos de pulso viven en el panel).
3. **Compases solo x/4** (ver arriba).
4. Pendientes heredados, sin tocar: 55 archivos Kotlin del stack paralelo sin uso (conectar o
   retirar) y partir `AudioEngine.cpp` (≈ 41 KB tras este cambio) antes de que siga creciendo
   (**el segundo, resuelto en el ADR 0030**).
