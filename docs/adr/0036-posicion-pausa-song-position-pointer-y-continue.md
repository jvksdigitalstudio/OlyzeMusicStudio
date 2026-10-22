# ADR 0036 — Posición del transporte, pausa y reanudación, Song Position Pointer y MIDI Continue

**Estado:** Aceptado — **motor nativo verificado por tests (47 ejecuciones, 0 problemas, ASan/UBSan y TSan);
capa Kotlin y UI pendientes de confirmación por compilación Gradle y prueba en dispositivo.**
**Relacionado:** ADR 0028 (reloj), 0029 (pulso), 0034 (auditoría), 0035 (conteo previo y grabar).
**Fase:** 2 de la hoja de ruta «metrónomo a nivel DAW».

## Contexto
El transporte no tenía posición: «parar» y «rebobinar» eran lo mismo y el reloj siempre arrancaba en el tiempo
1. Eso impedía tres cosas que los DAW hacen con naturalidad: **pausar y continuar**, **grabar desde un punto
que no sea el origen**, y decirle a un equipo externo **dónde** está la canción (Song Position Pointer + Continue,
MIDI 1.0: SPP en semicorcheas, 14 bits; START empieza desde 0, CONTINUE reanuda desde el SPP recibido).

## Decisiones

### D1 — La posición es del reloj y se mide en TICKS a 960 por negra
`Position.h` (vocabulario puro, header-only) define la unidad. 960 divide exactamente la negra en 2, 3, 4, 5, 6,
8, 10, 12, 15 y 16 partes, así que corcheas, tresillos, semicorcheas y quintillos caen en ticks enteros (las
cuatro unidades de pulso del metrónomo duran 1920, 960, 480 y 240). `TempoClock` es el único dueño: cada pulso
trae su posición (`Beat::positionTicks`, con valor por defecto para no romper inicializaciones anteriores) y
`positionTicks()` da la del instante actual con resolución de muestra. **La posición no depende del tamaño de
bloque** (test: 64, 480, 960 y 4800 frames dan la misma).

### D2 — Pausar conserva, parar-y-rebobinar es `locate(0)`
`TempoClock::pause()` detiene conservando posición y fase dentro del pulso; `start()` continúa justo ahí;
`stop()` (rebobina a 0) se mantiene y los tests antiguos del reloj siguen pasando. Para que una pausa a mitad de
pulso sea fiel, cambiar el tempo o la frecuencia de muestreo con el reloj **pausado** reescala igualmente el
restante del pulso (antes solo con el reloj en marcha). A nivel de motor `setRunning(false)` es ahora PAUSA.
Consecuencia: dos tests nativos que codificaban «parar y arrancar vuelve al primer tiempo» pasan a decir «parar,
rebobinar y arrancar»; es un cambio deliberado de significado, no un parche.

### D3 — `locate` coloca el reloj en cualquier posición, también en negativo
Con división de SUELO (`floorDiv/floorMod`), así que −1 tick es el último del compás anterior. Soporta límite de
pulso (el pulso cae en el frame 0), mitad de pulso (el siguiente llega cuando toca) y posiciones negativas. La
posición viaja al motor como parámetro `float` (`DspParameterId::TransportPosition`), exacto hasta 2^24 ticks
(≈ 2,4 h a 120 BPM); por encima se recorta.

### D4 — El conteo previo corre alineado al compás y la canción queda RETENIDA
Corrige un defecto de diseño detectado por los propios tests: el conteo de la fase 1 debía contar «1-2-3-4»
aunque la grabación empiece en el tiempo 3. El conteo corre en el reloj colocado N compases antes del origen (en
negativo); la posición publicada durante él es la de grabación (retenida). Al primer pulso de la grabación la
canción pasa a ser una **proyección** del reloj (`mPositionOffset` y `mBeatShift`), de modo que ese pulso vale
justo la posición de grabación **y su tiempo del compás** (el tiempo 3 si se graba desde el tiempo 3). Si el
compás cambia durante el conteo, esa proyección absorbe la diferencia. Una posición a mitad de pulso se redondea
hacia abajo a la rejilla de pulsos. Pausar durante el conteo no mueve la canción; localizar lo cancela.

### D5 — MIDI: START desde 0; SPP + CONTINUE al reanudar
`TransportCoordinator` decide con la posición del motor:
- desde 0 → `START`; desde otra posición → `SPP` y después `CONTINUE` (un receptor aplica el SPP en el siguiente
  START/CONTINUE); SPP = posición / 240, hacia abajo, recortado a 14 bits.
- rebobinar sonando → `START`; rebobinar parado → `SPP 0`.
`MidiEventType.SONG_POSITION` se modela **solo para la salida** (`0xF2 LSB MSB`). El parser de ENTRADA sigue
informando 0xF2 como `SYSTEM_OTHER` (con sus tests): interpretar SPP entrante es sincronización externa, fase 5.

### D6 — `locate` es una orden de un solo uso que viaja ANTES del arranque
Si fuera una orden suelta (como `resyncBar`), un `locate(0)` podía llegar al motor *después* de un `start` ya
fundido en el vaciado y cancelar el conteo. En `TransportController` es estado pendiente (último valor gana) con
su bit propio, se envía tras el armado y **antes** del arranque, y **no** forma parte de `ALL`: recrear el motor no
vuelve a saltar a una posición vieja.

### D7 — Ventana de lectura tras rebobinar
El motor aplica `locate` en su siguiente bloque (≈ 10 ms). El coordinador recuerda solo eso
(`rewoundWhileStopped`) para que un play inmediato tras rebobinar no lea la posición vieja y mande un SPP erróneo.
Es la única memoria del coordinador y está documentada y probada.

## Verificación realizada
- **Nativo:** 47 ejecuciones, 0 problemas (ASan+UBSan; TSan en los tests con hilos), incluyendo `test_position`
  (conversiones puras y reloj), `test_transport_position` (pausa, locate, conteo con posición retenida, cambio de
  compás durante el conteo) y `test_engine_position` (cableado por la cola de comandos).
- **Mutaciones** sobre la lógica nueva: pausa que rebobina, `locate` a mitad de pulso con el pulso equivocado,
  conteo que no retiene la posición, falta del desplazamiento de tiempo, tempo que no reescala en pausa y
  `floorDiv` truncando. **Los 6 mutantes fueron detectados.**
- Verificadores estáticos del repo: sin violaciones nuevas (46 funciones JNI enlazadas).

## Límites de la verificación (importante)
- **Kotlin y Compose NO se compilaron ni ejecutaron** (no hay Gradle ni Kotlin aquí). Ejecutar
  `./gradlew :eliner:testDebugUnitTest :app:testDebugUnitTest`. Los tests Kotlin nuevos
  (`TransportCoordinatorTest`, `TransportControllerTest`, `TransportConstantsMatchNativeTest`) están escritos pero
  sin ejecutar.
- No se probó en dispositivo ni con un equipo MIDI externo real. Habría que comprobar con uno que reanuda en el
  punto correcto tras SPP + CONTINUE.

## Consecuencias aceptadas / deuda conocida
- **Nombre:** la API Kotlin conserva `start()/stop()`, pero `stop()` ahora PAUSA. Renombrar a `pause()` toca
  muchos puntos y se deja para cuando haya un botón de parada real (que rebobine).
- El botón de rebobinar cambia de comportamiento: antes detenía; ahora vuelve al principio y, si sonaba, sigue.
- Con el conteo, el `START`/`CONTINUE` y el reloj MIDI salen al empezar el conteo, no al empezar la grabación
  (igual que en el ADR 0035).
- Si el motor se recrea, la posición vuelve a 0 (el transporte reaplicado arranca desde el principio).
- El compás se supone CONSTANTE desde el origen al calcular compás|pulso|tick: el mapa de compases llega con el
  mapa de tempo (fase 4).
- La posición se publica con resolución de bloque de audio; la UI la leerá sondeando, como el pulso.

## NO hecho en esta fase (y por qué)
- **Pre-roll:** con el conteo ya ligado a la posición, el pre-roll es el mismo mecanismo (arrancar N compases
  antes del punto de grabación y reproducir lo que haya). Hoy no hay contenido grabado que reproducir, así que
  añadirlo ahora sería un ajuste sin efecto audible. Se hará en la fase 3 junto con la captura.
- **Indicador de posición en pantalla** (compás|pulso|tick) y el indicador «CONTEO 2/4» de la fase 1: los datos
  ya llegan a la UI (`positionTicks()`, `BeatPulse.isCountIn`); falta el componente.
- **Relojes y sincronización de entrada** (SPP entrante, Link): fase 5.

## Adenda — indicadores en pantalla (cerrados)
Se añadió `ui/components/status/TransportStatus`, que muestra sobre el ÁREA DE TRABAJO:
- el **conteo previo** mientras suena («CONTEO», el pulso dentro del compás en grande y «compás n/N»), a partir de
  `BeatPulse.isCountIn`/`countInBar` (ADR 0035). Como la canción está retenida durante el conteo, se muestra esto en
  lugar de la posición;
- la **posición** en `compás.pulso.tick` (base 1 para compás y pulso; el primer tiempo es `1.1.000`), mientras el
  transporte suena o está lejos del origen; parado en el origen no se pinta nada.
La posición llega por `EliNerTransportApi.positionUpdates` (flujo frío ≈ 30 Hz que emite solo al cambiar, como `pulse`)
y la conversión es `TransportPosition.barBeatTickFor`, réplica de `barBeatTickFor` de `Position.h` con los mismos casos
en sus tests. **Decisión de producto:** el comentario de `AppHeader` prohíbe añadir elementos a la barra superior sin
que se pida; por eso los indicadores NO van ahí. Para quitarlos basta borrar la llamada a `TransportStatus` en
`MainScreen`. Sin compilar ni ver en pantalla: revisar tamaños y posición en el móvil.

## Hoja de ruta
3. **Fase 3 — Latencia y captura:** compensación con `getTimestamp()` de Oboe, reloj MIDI derivado del reloj
   nativo, captura de audio/MIDI sobre `recordArmed` y pre-roll.
4. **Fase 4 — Mapa de tempo y compás:** cambios de tempo y de compás, rampas, compás|pulso|tick con mapa.
5. **Fase 5 — Sincronización de entrada:** reloj MIDI entrante, SPP entrante y Ableton Link.
6. **Fase 6 — Click avanzado:** sonidos personalizados, swing, compases compuestos, salida separada.
