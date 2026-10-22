# ADR 0038 — Núcleo de captura de audio y ventana de grabación

**Estado:** Aceptado — **fase 3b-1 (núcleo nativo). Verificado por tests (56 ejecuciones, 0 problemas, ASan/UBSan y
TSan, con hilos y archivos reales). NO está conectado todavía a la entrada de audio, ni a JNI, ni a Kotlin, ni a la
UI: eso es la fase 3b-2 y exige un dispositivo (ver «NO hecho»).**
**Relacionado:** ADR 0035 (conteo y grabar), 0036 (posición), 0037 (latencia de salida).

## Contexto
`recordArmed` ya gobernaba el conteo y el click, pero no existía captura: grabar no producía ningún archivo. La captura
es el módulo más sensible del motor, porque comparte el hilo de audio, que **no puede** escribir en disco, bloquear ni
reservar memoria. Esta fase construye y prueba, aislado de Android, todo lo que sí se puede verificar sin dispositivo:
el camino de las muestras hasta el disco y la decisión de QUÉ frames se graban.

## Decisiones

### D1 — Una pieza, una responsabilidad
| Pieza | Responsabilidad | NO sabe de |
|---|---|---|
| `CaptureRing` (header) | llevar muestras del hilo de audio al escritor sin bloqueos | disco, tiempo, transporte |
| `WavWriter` | escribir un WAV float32 en streaming | hilos, audio, transporte |
| `TakeAlignment` (header) | latencia → frames a recortar | todo lo demás |
| `TakeRecorder` | mover una toma de audio a un archivo, recortando el comienzo | cuándo empezar/parar, Oboe, JNI |
| `TransportEngine::recordingBlock()` | decidir qué frames de cada bloque se graban | audio, disco |

### D2 — El hilo de audio nunca espera
`onInput()` solo copia al anillo (O(n), sin llamadas al sistema). Si el disco se atrasa y el anillo se llena, las
muestras que no caben se **descartan y se cuentan** (`droppedFrames`): un hueco en la toma es recuperable; un xrun del
callback, no. El anillo es SPSC con contadores de 64 bits monótonos (sin ABA y sin posición desperdiciada).

### D3 — `end()` es determinista
Cuando `end()` devuelve, todo lo que `onInput()` aceptó antes está en el archivo y el archivo está cerrado, o se informa
del fallo (`Result::ok`). Una llamada a `onInput()` en vuelo se espera (`mPushing`, microsegundos): ni se pierde ni se
escribe tras el cierre. El hilo escritor lee la señal de parada **antes** de retirar del anillo: si ve «parando», todo
lo aceptado ya está dentro y un `pop()` vacío significa vacío de verdad. (Una versión inicial leía la señal después y
necesitaba una segunda comprobación con una ventana de carrera de nanosegundos que ningún test podía forzar; se
sustituyó por este patrón, correcto por construcción.)

### D4 — WAV float32 sin conversión
La toma original se guarda tal cual entra (IEEE float, formato 3, con el bloque `fact` que ese formato exige). Los
tamaños del encabezado se reescriben al cerrar; un archivo cuyo cierre no llegó a ejecutarse queda con tamaños 0 y es
recuperable leyéndolo hasta el final. Se verifica en compilación que la ABI es little-endian (todas las de Android).

### D5 — Compensación de latencia: recortar el comienzo
El intérprete toca lo que OYE. Lo que oye salió del motor `latenciaSalida` antes de sonar y lo que toca tarda
`latenciaEntrada` en llegar: el frame k de la captura contiene lo que sonó en `k − (salida + entrada)`. Descartar los
primeros `salida + entrada` frames alinea la toma con la posición de grabación. Una latencia sin medir aporta 0 (no se
inventa un valor) y el total se limita para que una medida absurda no se coma la toma. Es una **estimación**; el ajuste
fino residual será un desfase manual por toma.

### D6 — Ventana de grabación con precisión de muestra
`TransportEngine::recordingBlock()` informa, por bloque: `active`, `started` (con `firstFrame` y `startPosition`) y
`stopped`. Hay grabación con la grabación armada, el transporte en marcha y **el conteo ya pasado**: el conteo no se
graba y la grabación empieza en el frame exacto del primer pulso tras él (el test usa tamaños de bloque que no dividen
ese frame para forzar un inicio a mitad de bloque). Cada inicio es una toma **nueva**: pausar y reanudar con la
grabación armada cierra una y abre otra; `locate()` durante la grabación NO parte la toma. Armar a mitad de
reproducción empieza en el siguiente bloque. El resultado no depende del tamaño de bloque.

## Verificación realizada
- **55 → 56 ejecuciones nativas, 0 problemas**: `test_capture_ring` (incluye concurrencia real productor/consumidor,
  también con el productor sin esperar: aceptado + descartado = intentado), `test_wav_writer` (se lee de vuelta el
  archivo real: encabezado, bit a bit, estéreo, vacío, uso incorrecto, disco lleno con `/dev/full`),
  `test_take_alignment`, `test_take_recorder` (hilos y archivos reales; `end()` con el audio aún enviando; 20 tomas
  seguidas con el audio sin parar) y `test_transport_recording_window`. Los de hilos corren también bajo TSan.
- **Mutaciones:** recorte con off-by-one, `end()` sin esperar a los envíos en vuelo (lo detecta TSan), envoltura del
  anillo, `fact` sin reescribir, el conteo grabándose, pausa que no cierra la toma, inicio en el frame equivocado y
  posición de inicio errónea: **todos detectados**. Un mutante de la ventana sobrevivió al principio por un hueco de mi
  propio test (todos los tamaños de bloque dividían el frame de inicio); se añadieron tamaños que no, y ahora muere.

## Límites de la verificación (importante)
- **No se compiló con el NDK ni con CMake**: se añadieron `WavWriter.cpp`, `TakeRecorder.cpp` y el directorio
  `include/eliner/capture` a `CMakeLists.txt`, y solo se probaron con `g++` de escritorio. Compilar en Android Studio.
- **No hay captura real**: nada de esto está enlazado todavía a un stream de entrada de Oboe.
- `std::thread` y `fopen` en el NDK funcionan, pero las rutas de archivo y los permisos de Android (almacenamiento de la
  app) no se han ejercitado.

## NO hecho — fase 3b-2 (necesita dispositivo) y por qué
1. **Stream de ENTRADA de Oboe** y su llamada en el callback (`read()` sin espera, o dúplex): el comportamiento real
   (xruns, deriva entre relojes de entrada y salida, mono/estéreo del micrófono, ruta de audio) solo se valida en
   hardware.
2. **Permiso `RECORD_AUDIO`** y su solicitud en la UI, y la **carpeta de tomas** (almacenamiento específico de la app).
3. **Latencia de entrada** (`calculateLatencyMillis()` del stream de entrada) y su suma a la de salida para el recorte.
4. **JNI + API Kotlin + UI**: estado de la toma, lista de tomas, indicador de grabación y de descartes.
5. **Pre-roll:** sigue pendiente; ahora que hay posición, conteo y ventana, es el mismo mecanismo con contenido que
   reproducir, y no tiene sentido hasta que existan tomas que reproducir.
6. **Reproducción** de lo grabado.

Puntos de integración ya definidos: en `AudioEngine`, tras `mTransport.advance()` se lee `recordingBlock()`;
`started` → `TakeRecorder::begin` (desde el hilo de control, vía comando, nunca en el callback), `active` → `onInput`
con `input + firstFrame * canales`, `stopped` → `TakeRecorder::end`. Cuidado conocido: `begin()`/`end()` abren/cierran
archivos y esperan a un hilo, así que **no pueden llamarse desde el callback de audio**: deben ir por el hilo de
control con una cola de eventos, y la toma empezaría uno o dos bloques tarde si no se pide `begin()` antes del
inicio (se resuelve preparando el grabador al armar, antes de que acabe el conteo).

## Hoja de ruta
3b-2 (con dispositivo): entrada de Oboe, permiso, JNI, Kotlin, UI, latencia de entrada. 3c: MIDI (desfase manual, reloj
derivado del nativo). 4: mapa de tempo y compás. 5: sincronización de entrada. 6: click avanzado.
