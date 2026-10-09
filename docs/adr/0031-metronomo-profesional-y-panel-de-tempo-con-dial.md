# ADR 0031 — Metrónomo profesional y panel de tempo con dial

**Estado:** Aceptado — **pendiente de confirmación por compilación Kotlin/NDK y prueba en dispositivo**
(ver «Límites de la verificación»).
**Relacionado:** ADR 0028 (reloj y metrónomo), 0029 (indicador de pulso y primer panel), 0030
(desacople por responsabilidad).

## Contexto

El panel de tempo del ADR 0029 era una hoja inferior con un metrónomo mínimo (ON y volumen), sin
tap tempo, sin ajuste fino y sin elección de sonido. La referencia pedida fue la de los DAW
profesionales; se tomaron dos de FL Studio:

- **FL Studio Mobile:** dial circular de BPM con decimales, pasos −10 −1 +1 +10, compás, botón de
  metrónomo y TAP.
- **FL Studio de escritorio («Tempo tapper»):** pad de TAP grande y nudge −/+.

Las dos referencias abren el panel **desde el control de BPM**, no como hoja inferior.

## Decisión

### 1. Motor: click profesional (verificable con tests nativos)

- **5 sonidos** (`transport/ClickSound.h`): Clásico, Madera, Beep, Cowbell, Hi-hat. Un preset es una
  tabla (frecuencias de acento/pulso, caída, parcial inarmónico, ruido); el motor no tiene muestras.
  **«Clásico» es el sonido histórico, bit a bit** (el `test_metronome` previo pasa sin tocarlo).
  Todos se normalizan para que el pico no crezca con parciales ni ruido (techo 0,8). El ruido del
  Hi-hat usa un xorshift32 propio: determinista, sin asignación, seguro en tiempo real.
- **Subdivisión** (1–4 clicks por pulso: pulso, corcheas, tresillos, semicorcheas): el `Metronome`
  cuenta frames desde cada pulso con la duración de pulso al tempo del momento (`framesPerBeat`, que le
  pasa `TransportEngine`). **Cada pulso resincroniza la cuenta**: un cambio de tempo corrige la
  subdivisión en el siguiente pulso y nunca acumula error. `TempoClock` **no cambia**: sigue siendo la
  única autoridad de los pulsos (y de su publicación para la UI, ADR 0029). La salida es idéntica con
  cualquier tamaño de bloque (test con 480 y 97 frames).
- **Acento** del primer tiempo: activable/desactivable.
- **Parar** (`resetSubdivision`) no deja clicks sueltos.
- Parámetros nuevos (`DspParameterId`, append-only): `MetronomeSound`, `MetronomeAccent`,
  `MetronomeSubdivision`. Se validan en el hilo de control **y** otra vez en el de audio; un valor
  fuera de rango **se ignora, nunca se recorta a otro** (lo fijan tests).
- 3 funciones JNI nuevas; V8 confirma las 38 firmas.

### 2. App: lógica pura y testeable

- **`TapTempo`** (`:app/transport`): estadística con reloj inyectado. Promedia las últimas 7
  diferencias, **descarta atípicos** (> 25 % de la mediana, con ≥ 4 intervalos), reinicia tras una
  pausa de 3,5 s (más que el intervalo de 20 BPM), ignora rebotes < 100 ms y relojes que retroceden,
  redondea a décimas y limita al rango del motor.
- **`TempoDialMath`** (`ui/components/tempo`): ángulos con la convención del lienzo, giro por el
  **camino corto** (sin salto al cruzar 360°), mapa BPM ↔ arco de 300°, giro tipo jog (una vuelta =
  90 BPM) y pasos a décimas sin acumular error de coma flotante.
- `MainViewModel.nudgeTempo(delta)` suma sobre el tempo **actual del motor**, no sobre el que la UI
  tenga pintado: mantener pulsado un botón repite el paso cada ~90 ms y sumar sobre un valor aún sin
  recomponer perdería pasos.

### 3. Panel (referencias de FL)

Un **popover que nace bajo el control de BPM** (antes, hoja inferior), en dos columnas en pantallas
anchas y una sola en estrechas, con desplazamiento si no cabe:

| Pieza | Archivo |
|---|---|
| Dial circular (arco, bolita, giro, anillo de pulso), BPM con decimales y compás | `TempoDial.kt` |
| −10 −1 · +1 +10, pad de **TAP** (dispara al tocar, no al soltar) y nudge ±0,1 | `TempoDialSection.kt` |
| ON/OFF, volumen, sonido, subdivisión y acento | `MetronomeSection.kt` |
| Puntos de pulso y compás (x/4) | `MeterSection.kt`, `BeatDots.kt` |
| Delay al tempo, **plegado** por defecto con la división activa visible | `DelaySection.kt`, `DelayDivisionGrid.kt` |
| Botones con repetición al mantener, tarjetas, chips | `TempoWidgets.kt` |
| Estado que muestra / acciones que pide (sin 14 lambdas sueltas) | `TempoPanelModel.kt` |
| Composición del popover | `TempoPanel.kt` |
| Unión con el ViewModel (recoge los flujos aquí, no en `MainScreen`) | `ui/screens/TempoPanelHost.kt` |

El destello de pulso se extrajo a `ui/components/pulse/BeatFlash.kt` y lo comparten el botón ♪ del
header y el anillo del dial. Accesibilidad: `contentDescription`/`Role.Button` y acciones
semánticas en todos los controles.

## Verificación realizada

| Comprobación | Resultado |
|---|---|
| Suite nativa (ASan+UBSan; TSan en los tests con hilos) | **36 ejecuciones, 0 problemas** (antes 33) |
| `test_metronome_click` (Metronome REAL) | sonidos distintos y acotados, sin NaN, Hi-hat determinista; acento; n clicks por pulso en el frame exacto (±1); independiente del bloque; resincroniza tras cambio de tempo; inválidos ignorados; parar sin clicks sueltos |
| `test_engine_click_settings` (AudioEngine REAL) | los ajustes llegan al sonido por la **cola de comandos** (8 clicks en 2 s con corcheas, 16 con semicorcheas) |
| `check_engine_compile.sh` (`-Wall -Wextra`) | sin warnings |
| `verify_architecture.py` (V1–V8) | OK; **38** funciones JNI |
| `verify_kotlin_references.py` (R1–R4) | OK |
| Cada caso de `TapTempoTest` y `TempoDialMathTest` | simulado a mano contra la lógica antes de entregar |

## Mejoras al verificador (lecciones de CI)

Cada fallo de compilación del CI pasa a ser una regla que lo habría detectado:
- **R3** (ADR 0030): delegado de Compose sin `getValue`/`setValue`.
- **R4** (nuevo): nombre de test con acentos graves que contiene un carácter prohibido en la JVM
  (`.` `;` `[` `]` `/` `<` `>` `:` `\`). Detectado antes del CI en `TempoDialMathTest`; se comprobó que la
  regla reproduce el fallo y que no quedan otros.

## Límites de la verificación (declarados)

- **Kotlin NO se compiló** aquí. Los 5 archivos nuevos de lógica y los 11 de UI pasaron las reglas
  estáticas, una revisión de imports símbolo a símbolo y de balance de paréntesis, pero eso no
  sustituye al compilador. **No se ejecutaron** los tests JVM nuevos (`TapTempoTest`,
  `TempoDialMathTest`, `TransportControllerTest` ampliado, contrato de enums).
- **El aspecto visual y el tacto del dial no se pueden verificar sin dispositivo**: tamaño, zonas
  táctiles, fluidez del giro, comportamiento en pantallas pequeñas y en landscape. Hay que probarlo y
  ajustar.
- **El sonido de los presets lo valida el oído**: los tests prueban propiedades (nivel, duración,
  determinismo, temporización), no que «suene bien». Los valores del cencerro y la madera son un punto
  de partida técnico, no una decisión de diseño sonoro.

## Fuera de alcance (decisiones para el siguiente ciclo)

1. **Entrada numérica del BPM** (tocar el número y teclear). Requiere un campo de texto; no se
   hizo para no añadir superficie sin poder compilar.
2. **Pre-roll / cuenta atrás** antes de grabar: pertenece al secuenciador de grabación (aún no existe).
3. **Compases x/8** (ADR 0029): unidad de pulso configurable en el reloj.
4. **Posición compás.tiempo** (1.1.1): requiere publicar el contador de compases desde el motor.
5. **Sonidos con muestras** (WAV) en vez de síntesis: requiere carga de recursos y decisión de licencias.
