# ADR 0037 — Latencia de salida de audio y sincronía del indicador de pulso

**Estado:** Aceptado (alcance reducido, ver «NO hecho») — **motor nativo verificado por tests (49 ejecuciones, 0
problemas, ASan/UBSan y TSan); capa Kotlin pendiente de compilar y de probar en dispositivo.**
**Relacionado:** ADR 0029 (pulso publicado), 0036 (posición y MIDI).
**Fase:** 3a de la hoja de ruta (la fase 3 completa se parte: ver «NO hecho»).

## Contexto
El motor publica un pulso en el instante en que RENDERIZA el bloque, pero el altavoz lo reproduce
`latencia de salida` milisegundos después (10–40 ms en AAudio exclusivo; cientos por Bluetooth). El indicador de
pulso se encendía en el momento del render, es decir, **adelantado** justo esa cantidad respecto de lo que se oye;
con un metrónomo se nota. Ableton Link exige además sumar esa latencia para alinear dispositivos, y la
compensación de latencia es la base de cualquier captura posterior.

## Decisiones

### D1 — Estimador por MEDIANA (`OutputLatency.h`)
La latencia que informa el sistema sale de marcas de tiempo del hardware y tiene valores atípicos esporádicos. Una
media los arrastra y la última muestra los hereda enteros; la mediana de las últimas 9 los ignora y converge en
pocas muestras. Descarta (no recorta) las muestras no finitas, ≤ 0 o > 1000 ms. Header-only, sin Oboe ni hilos.

### D2 — Se mide con Oboe, desde un hilo de CONTROL, sin esperar
`AudioEngine::sampleOutputLatency()` usa `AudioStream::calculateLatencyMillis()` (Oboe 1.9.3, el que fija el
proyecto), que se apoya en `getTimestamp()`. Toma el candado del ciclo de vida con **try-lock**: si el motor está
arrancando, parando o recuperándose devuelve la última medida publicada. El JNI también usa try-lock sobre
`gEngineMutex`, como `nativeReadPulse`: `nativeCreate` lo mantiene mientras Oboe abre el stream y quien sondea corre
en el mismo bucle que el indicador. Un resultado no válido del dispositivo (aún sin marcas de tiempo) **no destruye**
la medida anterior; stream parado o reiniciado vuelve a «sin medida» (−1 / `null`): nunca se inventa un valor.

### D3 — El indicador se retrasa lo que tarda en oírse (`PulseDelayLine`)
Lógica pura con reloj inyectable: cada pulso se retiene `latencia` ms desde que se observó. Orden preservado, sin
pérdidas, latencia desconocida o corrupta = sin retardo (**comportamiento anterior, sin regresión**), tope de 1000 ms
para que una medida absurda no congele el indicador, y vaciado inmediato si se acumulan > 64 pulsos.

### D4 — El muestreo vive en el bucle del pulso
`TransportController.pulse` muestrea cada 64 sondeos (≈ 1 s) y publica `outputLatencyMillis`. Es lo único que la
consume hoy y así no hay un hilo más. Consecuencia aceptada: **la latencia solo se mantiene al día mientras alguien
recoge `pulse`** (la UI visible). Un futuro consumidor en segundo plano (compensación MIDI, captura) necesitará su
propio muestreador.

## Verificación realizada
- Nativo: 49 ejecuciones, 0 problemas. `test_output_latency` cubre el estimador (descartes, límites, atípicos,
  ventana circular, reset) y el motor real contra un stream Oboe falso con latencia programable (sin medida,
  medida válida, resultado inválido que no destruye la anterior, atípico aislado, parado, reinicio).
- Verificadores estáticos del repo: sin violaciones (47 funciones JNI enlazadas).

## Límites de la verificación (importante)
- **Kotlin no se compiló ni ejecutó.** Tests nuevos sin ejecutar: `PulseDelayLineTest` y los de latencia en
  `TransportControllerTest` (estos usan temporización real del sondeo con un reloj inyectado; si resultan frágiles en
  CI, se pasarán a un dispatcher de pruebas).
- **La latencia real no se midió**: el stream es falso. Hay que comprobar en un dispositivo que
  `calculateLatencyMillis()` devuelve valores razonables (decenas de ms en AAudio exclusivo) y que el indicador se
  enciende a la vez que se oye el click. En algunos dispositivos el sistema tarda unos instantes en dar marcas de
  tiempo: durante ese rato el indicador funciona como antes.

## NO hecho en esta fase (y por qué)
- **Compensar el MIDI de salida** (START y reloj retrasados la latencia de salida): sin un equipo externo y un
  dispositivo reales no se puede validar la dirección ni la magnitud, y la latencia propia del transporte MIDI (USB,
  BLE) es otra incógnita. Los DAW lo resuelven con un desfase MANUAL por dispositivo («Sync delay» en Logic, «MIDI
  Clock Offset» en Cubase). Propuesta: añadir ese desfase manual como ajuste y, opcionalmente, sumarle la latencia de
  salida medida. Requiere prueba con hardware.
- **Reloj MIDI derivado del reloj nativo** (precisión de muestra): exige un callback nativo→JVM o un envío MIDI
  nativo (AMidi) por pulso; es la pieza de mayor riesgo y no se puede validar sin dispositivo.
- **Captura de audio/MIDI y pre-roll:** la captura es un módulo grande por sí mismo (entrada de audio, permisos,
  almacenamiento, latencia de ida y vuelta) y merece su propia fase y su propio ADR.
- **Latencia de entrada / ida y vuelta:** hace falta un stream de entrada; llega con la captura.
- **Indicadores en pantalla** de posición (compás|pulso|tick) y de conteo («CONTEO 2/4»): siguen pendientes; los datos
  ya llegan a la UI.

## Hoja de ruta actualizada
3b. **Captura y latencia de ida y vuelta**, con pre-roll. 3c. **MIDI:** desfase manual y reloj derivado del nativo
(con hardware). 4. **Mapa de tempo y compás.** 5. **Sincronización de entrada** (MIDI clock/SPP, Link). 6. **Click
avanzado.**
