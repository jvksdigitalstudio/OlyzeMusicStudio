# ADR 0034 — Auditoría de BPM, metrónomo y tap: reloj MIDI exacto, TAP por figura, presets y parada/arranque

**Estado:** Aceptado — **pendiente de confirmación por compilación Kotlin y prueba en dispositivo**
(ver «Límites de la verificación»).
**Relacionado:** ADR 0028 (reloj y metrónomo), 0029 (pulso), 0031/0032 (panel y metrónomo), 0033 (tap tempo).

## Contexto

Se pidió auditar TODO el código de BPM y metrónomo (motor nativo, capa `:eliner`, ViewModel, UI, presets, tests y
documentación) y corregir lo que se encontrara de forma quirúrgica, sin parches, y dejar la documentación coherente
con el código. Se leyó cada archivo del recorrido completo (listado al final) y **cada hallazgo se midió o se
reprodujo antes de tocar nada**; lo que no resultó ser un defecto no se modificó.

## 1. Hallazgos que cambian el comportamiento (corregidos)

### H1 — El reloj MIDI de salida corría más rápido que el tempo (alto)

`MidiClockGenerator` esperaba `delay(pulseIntervalMs(bpm))` entre pulsos, y `pulseIntervalMs` **truncaba** el
intervalo a milisegundos enteros. Además cada espera se sumaba a la anterior, así que ni el retraso de `delay()` ni el
del planificador se compensaban nunca.

| BPM del transporte | Intervalo exacto | Intervalo usado | Reloj MIDI efectivo |
|---|---|---|---|
| 60 | 41,667 ms | 41 ms | 60,98 BPM |
| 120 | 20,833 ms | 20 ms | **125,00 BPM** (+4,2 %) |
| 140 | 17,857 ms | 17 ms | **147,06 BPM** (+5,0 %) |
| 200 | 12,500 ms | 12 ms | 208,33 BPM |
| 300 | 8,333 ms | 8 ms | 312,50 BPM |

(Solo 20 y 100 BPM caían en un número entero de milisegundos.) Un dispositivo externo sincronizado por este reloj
iba, como mínimo, un 3–5 % más rápido que el metrónomo de la propia app, y la diferencia se notaba como deriva de
fase. Los tests existentes **fijaban el defecto** (`120 bpm gives 20 ms pulses`).

**Corrección.** Cada pulso tiene una hora objetivo absoluta, calculada con el intervalo exacto en nanosegundos
(`Double`, con su fracción) y medida con un reloj monótono (`System.nanoTime`, inyectable) desde el arranque. La lógica
vive en `PulseSchedule`, una clase pura que recibe la hora como argumento, para poder probarla sin esperar:
- un pulso tardío no desplaza a los siguientes (la espera de cada pulso corrige la de los anteriores);
- el redondeo de `delay()` (1 ms) se hace **por exceso**, de modo que nunca se despierta antes de la hora objetivo y
  no se acumula;
- un cambio de BPM rige desde el pulso siguiente (el intervalo se calcula con el tempo del momento);
- un atraso pequeño se recupera (los pulsos retrasados salen seguidos, ninguno se pierde); un atraso mayor de 100 ms
  (app suspendida, hilo bloqueado) **reancla**: el siguiente pulso cae un intervalo después de ahora, en lugar de
  emitir una ráfaga que un receptor leería como un tempo disparado.

`pulseIntervalMs` se eliminó (no tenía más usos que su propio test) y se sustituyó por `pulseIntervalNanos`.

### H2 — Cargar un preset no cerraba la medición de tap ni el nudge (medio)

`MainViewModel.loadPreset` llamaba directamente a `applyPreset`, saltándose `abandonTapSession()` y `nudgeBase = null`
que sí ejecutan `setTempoBpm` y `stepTempo` (ADR 0033: «un ajuste manual del tempo da por terminada la medición»). Con
la opción MUTE activa y una medición en curso, cargar un preset dejaba el click **en silencio hasta que caducaba la
medición** (3,5 s), y los toques anteriores se mezclaban con los siguientes.

**Corrección.** `loadPreset` hace lo mismo que cualquier otro ajuste manual de tempo: cierra la medición, restablece
el Mute y anula un nudge pendiente.

### H3 — El TAP ignoraba la figura del pulso (medio)

El tempo del motor es siempre en NEGRAS por minuto (ADR 0032), pero el tapper medía «toques por minuto» y los aplicaba
tal cual. Con la figura en 1/8 (6/8) quien marca el pulso que oye (corcheas, 240/min) obtenía un tempo de 240 negras/min:
el metrónomo sonaba al DOBLE de lo marcado (cuádruple en 1/16, mitad en 1/2).

**Corrección.** `quarterNoteBpmFor(pulsosPorMinuto, figura)` convierte con `quartersPerPulse`, redondea a una décima y limita
al rango del motor; `MainViewModel.tapTempo` la aplica con la figura vigente. `TapTempo` sigue midiendo PULSOS por minuto
(también lo que muestra el pad, coherente con «1/8 = 240.0» del dial) y no conoce la figura: la conversión es del
llamador. En 4/4 y demás compases de negra el resultado es idéntico al anterior. Límite asumido: el estimador mantiene
su rango de 20–300 pulsos/min (un pulso de semicorchea por encima de 300/min, más de 5 toques por segundo, no es
marcable a mano).

### H4 — El reloj MIDI corría en el hilo principal (medio)

Un fotograma largo de la UI retrasaba los pulsos hacia el dispositivo externo. Se comprobó que moverlo es seguro:
`MidiOutputBridge.emit` solo lee un `@Volatile` y `AndroidMidiBackend.send` toma su propio candado (`handlesLock`, ya
usado por el teclado y la entrada MIDI desde otros hilos); `MidiMetrics` es atómico.

**Corrección.** El reloj tiene su propio hilo (`olyze-midi-clock`, demonio, `THREAD_PRIORITY_AUDIO`), creado y cerrado
por `MainViewModel`. Para que el cambio de hilo no abra una carrera con `TransportCoordinator` («ningún pulso de reloj
tras STOP»), `MidiClockGenerator.stop()` es ahora **determinista**: la comprobación y el envío de cada pulso comparten
un candado con `stop()`, y un contador de puesta en marcha impide que un bucle viejo emita tras un `stop(); start()`.
Cuando `stop()` devuelve, no sale ningún pulso más.

### H5 — `stop()` + `start()` fusionados no reiniciaban el compás (bajo)

Límite que el propio `TransportController` documentaba: si ambos caían en el mismo vaciado, el motor solo recibía «en
marcha», ignoraba el start repetido y seguía en su compás, mientras el MIDI externo sí recibía STOP y START (se
desfasaba). **Corrección.** El controlador recuerda que hubo una parada desde el último vaciado (`stopPending`) y, si el
vaciado termina «en marcha», envía «parado» y luego «en marcha». Un vaciado que termina parado envía una sola parada, y
un `start()` posterior a una parada ya vaciada no añade ninguna.

### H6 — El bucle del reloj MIDI no tenía test (cobertura)

`MidiClockGeneratorLoopTest` (6 casos) ejecuta el bucle real con tiempo virtual (`kotlinx-coroutines-test`, añadido como
`testImplementation`, misma versión que las corrutinas): 49 pulsos en 1010 ms a 120 BPM y 561 en 10 010 ms a 140 BPM
(el esquema anterior daba 51 y 589), cambio de tempo en marcha, ningún pulso tras `stop()`, doble `start()` y reinicio
tras una parada.

## 2. Comentarios y documentación que contradecían el código (corregidos)

| Dónde | Decía | Realidad |
|---|---|---|
| `Delay.cpp` (constructor) | 0,375 s = «1/4 de nota a 120 BPM» | A 120 BPM una negra son 0,5 s; 0,375 s es la **corchea con puntillo** (la división por defecto del sync, ADR 0028) |
| `MetronomePresetLibrary` (KDoc) | «aplicar un preset es [applyTo]» | La función se llama `applyPreset` |
| `MainViewModel.beatsPerBar` (KDoc) | «Pulsos por compás (negras)» | Desde ADR 0032 el pulso puede ser blanca, negra, corchea o semicorchea |
| `EliNerTransportApi.setMetronomeDuck` (KDoc) | «Se restablece sola tras reiniciar el motor» | El controlador **reaplica** el último valor al recrear el motor (`syncToNative`); no se restablece a 1 |
| ADR 0028 y `ARCHITECTURE.md` | El reloj MIDI usa `delay()` entre pulsos | Usa horas objetivo absolutas (H1); `delay()` solo espera hasta cada una |
| ADR 0033 | `TapTempoTest` «21 casos» | 21 al escribirlo; 25 al auditar y 31 tras H3 |
| `TransportController` (KDoc de clase) | «Límite conocido»: `stop(); start()` fusionados no reinician | Resuelto (H5) |
| ADR 0032 (§2.2) | «Un usuario … debe introducir 120 negras/min» | Sigue valiendo para el dial; el TAP convierte desde H3 |

El texto original de los ADR 0028 y 0033 se conserva como registro histórico salvo esas afirmaciones, que se
corrigieron en su sitio con una nota.

## 3. Revisado y SIN defecto (no se tocó)

Anotado para que no se vuelva a investigar:
- **Reinicio brusco del click** (`Metronome::trigger` con el click anterior aún vivo, p. ej. subdivisión de
  semicorcheas a 300 BPM): se midió el salto máximo entre muestras en los cinco sonidos y queda dentro de la pendiente
  normal de un click aislado (0,15–0,26; el hi-hat, 1,17, es su ruido diferenciado, igual que en un click suelto). No hay
  chasquido adicional.
- **Cola SPSC con varios productores:** los dos productores (hilo del transporte y `AudioCommandDispatcher`) entran
  por JNI, y toda función JNI que toca el motor toma `gEngineMutex` (la única que no lo toma, `nativeGetMaxChainSlots`, devuelve una constante): quedan serializados, así que no hay carrera. (La prueba
  `test_command_queue_interleaving_proof` demuestra el defecto de una cola sin ese candado y sale con 1 por diseño.)
- **`TempoClock`:** fase continua al cambiar tempo, unidad de pulso, frecuencia de muestreo y compás; el desbordamiento
  de `kMaxBeatsPerBlock` mantiene el contador de compás correcto.
- **`TapTempo`, `TapMute`, `TempoNudge`, `TempoMarking`, `TempoDialMath`:** los escenarios de sus tests se ejecutaron
  sobre un port fiel (ver «Verificación»); coinciden con lo esperado, incluida la mejora del 23,8 % de la regresión.
- **Nudge con el panel cerrado a mitad de gesto:** `onEnd` va en un `finally`, así que el tempo no se queda desviado.
- **Delay sincronizado:** el plegado por octavas deja siempre el tiempo en [0,01 s; 1,99 s] a 20–300 BPM.

## 4. Decisiones que siguen abiertas

Ninguna de las tres anteriores (TAP por figura, hilo del reloj MIDI, parada/arranque fusionados) queda abierta: se
resolvieron en H3, H4 y H5. Quedan, ya registradas en ADR anteriores y sin cambios:
1. **Derivar el reloj MIDI del `TempoClock` nativo** (callback nativo→JVM por pulso, ADR 0028 «Trabajo futuro»): daría
   precisión de muestra; hoy la media es exacta y el jitter es el de `delay()` (1 ms) más el planificador del hilo.
2. **BPM expresado en la figura elegida** (ADR 0032 «Fuera de alcance» 1): cambia la semántica del tempo en todo el
   sistema; el TAP ya convierte correctamente sin necesitarla.
3. **Compensar la latencia de salida de audio en SYNC** (ADR 0033 «Fuera de alcance» 1).

## 5. Verificación realizada

| Comprobación | Resultado |
|---|---|
| Suite nativa (ASan+UBSan; TSan en los tests con hilos), antes y después de los cambios | **39 ejecuciones, 0 problemas** (idéntica) |
| `verify_architecture.py` | OK; 41 funciones JNI (V8) |
| `verify_kotlin_references.py` (R1–R4) | OK; 258 tipos indexados (+3: `PulseSchedule`, `MidiClockGeneratorLoopTest`, su doble de MIDI) |
| Intervalo del reloj MIDI | Tabla de H1 calculada de forma independiente |
| `PulseSchedule` | Los escenarios de planificación de `MidiClockGeneratorTest` (8 de sus 14 casos; los 6 de intervalo se calcularon aparte) ejecutados sobre un port exacto: rejilla ideal (192 pulsos = 4,000000 s a 120 BPM), sin deriva (60 negras con espera redondeada por exceso = 30,000000 s; el esquema anterior daba 28,8 s), cambio de tempo, atraso pequeño, reanclaje |
| `TapTempo` (`TapTempoTest`, 31 casos) | Los 23 escenarios del estimador ejecutados sobre un port fiel: todos coinciden; regresión −23,8 % de error frente al promedio. Los 6 de conversión por figura, verificados en aritmética `float32` |
| Bucle del reloj (`MidiClockGeneratorLoopTest`, 6 casos) | Conteos esperados (49 / 561 / 25 + 45–49) obtenidos con una simulación del bucle en tiempo virtual |
| `TransportControllerTest` (40 casos, 4 nuevos) | Trazas de los 4 casos de parada/arranque fusionados comprobadas a mano contra `flush()` |
| Balance de llaves/paréntesis y nombres de test (R4) en lo modificado | OK |

## 6. Límites de la verificación (declarados)

- **Kotlin NO se compiló aquí** (no hay `kotlinc` ni Gradle en este entorno) y los tests JVM no se ejecutaron: la
  lógica de los tests nuevos se ejecutó sobre un port, no sobre el código real. La confirmación es el CI
  (`./gradlew testDebugUnitTest`).
- `kotlinx-coroutines-test` es una dependencia NUEVA: su resolución y los tests de tiempo virtual los confirma el CI.
- El hilo propio del reloj (`olyze-midi-clock`, prioridad de audio) y el corte determinista de `stop()` no son
  observables sin dispositivo; la lógica del corte se cubre con los tests del bucle, no la carrera real entre hilos.
- **No se ha medido en dispositivo** el jitter real del reloj MIDI ni su sincronía con un receptor externo; se
  recomienda comprobarlo con un analizador de tempo MIDI a 120 y 140 BPM.

## Archivos revisados

Nativo: `TempoClock`, `Metronome`, `TransportEngine`, `TempoSync`, `BeatUnit`, `BeatPulse`, `ClickSound`, `Delay`,
`AudioEngine*` (integración del transporte), `CommandQueue`, `EliNerTransportBridge.cpp`.
`:eliner`: `EliNerTransportApi`, `TransportController`, `TransportNative`, `EliNerTransportBridge`, `BeatPattern`,
`BeatPulse`, `BeatUnit`, `NoteDivision`, `MetronomeClick`.
`:app`: `MainViewModel`, `TransportCoordinator`, `TapTempo`, `TapStatus`, `TapMute`, `TempoNudge`, `TempoMarking`,
`MidiClock`, `MidiClockGenerator`, presets (`MetronomePreset`, `…Codec`, `…Library`, `…Applier`, `PresetStore`) y toda
la UI de `ui/components/tempo`, `ui/components/transport`, `ui/components/pulse` y `TempoPanelHost`.
