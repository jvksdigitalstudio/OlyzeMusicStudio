# ADR 0033 — Tap tempo profesional

**Estado:** Aceptado — **pendiente de confirmación por compilación Kotlin y prueba en dispositivo**
(el «tacto» de un pad solo se valida con el dedo; ver «Límites de la verificación»).
**Relacionado:** ADR 0031 (primer TAP), ADR 0032 (auditoría del metrónomo).

## Contexto

El TAP de la entrega anterior «se sentía raro». Antes de cambiar nada se auditó el recorrido completo
(pad → ViewModel → `TapTempo` → motor) y se midió con simulación qué importaba de verdad.

### Qué se encontró

| # | Hallazgo | Importancia medida |
|---|---|---|
| 1 | La hora del toque se tomaba al **procesar** el gesto, no al **tocar** (Android agrupa los toques por fotograma: hasta ~16 ms de retraso aleatorio) | **Pequeña**: con el temblor humano (σ ≈ 20 ms) el error medio pasa de 0,60 a 0,61 BPM. Es gratis corregirla, pero **no es la causa principal** (una afirmación previa lo exageraba) |
| 2 | El promedio de intervalos equivale a `(último − primero)/(n − 1)`: usa **solo los dos toques de los extremos** | **Real**: la regresión con todos los toques baja el error un **15–32 %** con 6–8 toques (idéntico con 2–3: matemáticamente es lo mismo) |
| 3 | Solo se atendía el primer dedo: tocar rápido alternando dos dedos **perdía pulsaciones** | Funcional |
| 4 | Pad de 112×52 dp, sin feedback ni estado: no se veía cuántos toques contaban ni qué BPM se estimaba, ni si ya era fiable | Probable causa del «raro» |
| 5 | Sin defensa ante errores humanos: un compás saltado, una pausa corta o un doble toque corrompían la medición | Funcional |
| 6 | Un ajuste manual del tempo (dial, ±1) no terminaba la medición: el siguiente TAP se mezclaba con toques viejos | Incoherencia |
| 7 | Con **2–3 toques el error es grande por naturaleza** (≈ ±5 BPM con 2 toques a 120 BPM) | Inevitable: no hay algoritmo que lo arregle; hay que **decirlo** al usuario (estimación «provisional») |

## Fuentes oficiales (consultadas en una segunda pasada)

**Reconocimiento:** la primera versión de este ADR se hizo SIN consultar la documentación oficial, solo con las
capturas de referencia y conocimiento general; fue un error. Esta ampliación la corrige con las fuentes. Lo que
dicen, parafraseado:

| Fuente | Qué documenta |
|---|---|
| Manual de FL Studio, sección *Tap tempo* (`image-line.com/fl-studio-learning/fl-studio-online-manual/html/toolbar_panels.htm`) | El tempo empieza a ajustarse tras el **2.º toque**, con una **barra** que muestra el número ideal de toques. El **nudge** (± mantenido) cambia el tempo **temporalmente** y al soltar vuelve al fijado, para sincronizar con una fuente en vivo. **Sync:** cada toque reinicia el tiempo 1, y se indica que apagado es el uso más común. **Mute:** silencia al empezar y vuelve a entrar gradualmente en los siguientes toques. Además: *Now* (silencio de unos compases), *half speed* y presets de tempo |
| Manual de FL Studio Mobile, módulo Playlist/TMP (`…/fl-studio-mobile-online-manual/html/plugins/FL Studio Mobile_Playlist.htm`) | El tempo se fija arrastrando el dial, con los botones +1/−1 o con el tap tempo |
| Guía de control de FL Studio de Novation (Launchkey Mini) | Existen pasos **permanentes** de tempo (0,1 · 1 · 5) y, aparte, nudge ± y tap tempo |
| Soporte de Soundbrenner (manual del Core) | El tap tempo pide **3 o más toques** a ritmo estable y fija el promedio. Las notas de versión de la app (reproducidas por una web de terceros, no oficial) dicen que un algoritmo nuevo mejora con cada toque |

Fuentes de terceros o foros (ProRec, foro de Image-Line) se usaron solo para contrastar, no como base: p. ej. un hilo
menciona una opción «Apply tempo» en versiones recientes que **no** aparece en la sección del manual consultada.

### Alcance REAL de la lectura (para no sobrestimarla)

- **FL Studio (escritorio):** se leyó **una sección** del manual, *Tap tempo*, dentro de la página de la barra de
  herramientas. No se leyó el manual completo (p. ej. las secciones de tempo del proyecto o de automatización).
- **FL Studio Mobile:** se leyó **una página** (Playlist, apartado TMP). Todo lo que documenta sobre fijar el tempo son dos
  frases: arrastrar el dial / botones ±1 / tap tempo, y «toca al tempo, real o imaginado». **No documenta** ni el número
  de toques, ni opciones (nudge, Sync, Mute), ni el algoritmo. En esa misma página aparecen dos ajustes que importan para
  el futuro secuenciador: **cuenta atrás al grabar** y que el **volumen del metrónomo** vive en Ajustes, no en el transporte.
- Los botones **−10/+10**, «Time: 4/4» y el icono de metrónomo de la referencia visual de FL Mobile salen de la **captura**
  aportada, no del manual.
- **Ningún manual describe cómo se estima el tempo a partir de los toques.** El estimador de este ADR (regresión, rechazo
  de atípicos, reglas de pausa/hueco/rebote, estabilidad) es **diseño propio**, validado con simulación, **no una
  reproducción del de FL ni del de Soundbrenner**. Tampoco se ha podido usar FL Studio ni FL Mobile para comparar el
  *tacto*: la paridad de sensación no está garantizada y solo se puede afinar comparando en un dispositivo.

### Lo que la primera versión incumplía

| Documentado | Antes | Ahora |
|---|---|---|
| Barra del número ideal de toques | solo texto | barra de progreso en el pad (`TapStatus.progress`, ideal = 8) |
| Nudge **temporal** (mantener → cambia, soltar → vuelve) | pasos ±0,1 **permanentes** con ese nombre | **Nudge real** (`TempoNudge`, ±4 %), y los pasos permanentes pasan a llamarse **pasos** (`stepTempo`) |
| Sync (cada toque reinicia el tiempo 1) | no existía | opción SYNC, apagada por defecto, con reinicio de compás en el motor |
| Mute con retorno gradual | no existía | opción MUTE, apagada por defecto (`TapMute`, duck suavizado en el motor) |
| Pasos permanentes 0,1/1/10 | ±1 y ±10 | se añade ±0,1 al dial |

### Decisiones y límites de la ampliación
- **El ±4 % del nudge es decisión de diseño de esta app**: el manual no da la cantidad.
- **Se aplica el tempo desde el 2.º toque** (como FL), aunque Soundbrenner pida 3; la barra y «provisional/estable» dicen
  cuánto fiarse. Soundbrenner exige más toques porque su hardware vibra sin pantalla donde mirar.
- **Sync no compensa la latencia de salida de audio** (el manual no dice que FL lo haga): el click sale unas decenas de ms
  después del toque. Por eso está apagado por defecto.
- **Mute** se aplica con un «duck» del click (ganancia 0–1 multiplicada por el volumen del usuario), no tocando el volumen: el
  deslizador del usuario no cambia y vuelve entero al acabar o al abandonar la medición.
- **Un ajuste permanente del tempo anula un nudge en curso sin restaurar el tempo viejo**, y el final del nudge se
  ejecuta en un `finally`: si el panel se cierra con el dedo puesto, el tempo no se queda desviado.
- **No implementado, por decisión:** *Now* (silencio de unos compases), *half speed* (exige separar «tempo del proyecto» de
  «tempo efectivo» en todo el motor) y los presets de tempo 80–160 (los presets guardables de la entrega 0032 cubren la
  necesidad). El contenido de los menús «♩» y «…» de Soundbrenner no está en lo consultado.

## Decisión

### 1. Estimador: regresión lineal sobre todos los toques
Se ajusta `tiempo = a + intervalo · nº_de_toque` por mínimos cuadrados con los últimos 8 toques. Con 5+ toques
se descartan los que se alejan de la recta más de `max(30 ms, 12 % del intervalo)` y se reajusta: un dedo
flojo no arrastra el tempo. Resultado redondeado a décimas y limitado a 20–300 BPM.

### 2. Reglas ante errores humanos (con ≥ 3 toques hay un intervalo con el que comparar)
| Situación | Regla |
|---|---|
| Pausa > 3,5 s | Medición nueva (debe superar los 3000 ms de 20 BPM) |
| Toque a > 1,8× el intervalo | **Reinicio** con ese toque como primero: un hueco o un cambio de tempo no corrompen la medición y el motor conserva su tempo hasta tener dos toques nuevos |
| Toque a < 0,4× el intervalo (flam) o < 100 ms (rebote) | Se ignora |
| Ajuste manual del tempo | **Termina** la medición |
| 3,5 s sin tocar | La medición **caduca** y la UI vuelve a «toca al ritmo» |

No se infieren compases saltados (índices que saltan): sería ambiguo con un cambio de tempo a la mitad
(tocar a mitad de velocidad se leería como «saltarse un tiempo» y el tempo nunca cambiaría).

### 3. Fiabilidad observable
`TapStatus(count, bpm, isStable)`. `isStable` = ≥ 4 toques válidos cuyo error respecto a la recta no supera
el 3 % del intervalo: la señal de «ya puedes dejar de tocar». Con menos toques la estimación se muestra como
provisional.

### 4. Pad
- **Hora real del toque** (`PointerInputChange.uptimeMillis`) y **todos los dedos** (`changedToDown` en cada
  evento): dispara al **tocar**, no al soltar; alternar dos dedos no pierde pulsaciones.
- **72 dp de alto y todo el ancho disponible** (antes 112×52), como el pad grande del «Tempo tapper» de FL.
- **Respuesta inmediata:** destello en cada toque (se dibuja sin recomponer) y vibración corta.
- **Estado en vivo:** «toca al ritmo» → «1 toque · sigue» → «128.4 · 5 toques» → «128.4 · 8 toques · estable»
  (el pad pasa a verde cuando es estable).

## Verificación realizada

| Comprobación | Resultado |
|---|---|
| Simulación del estimador (4000 mediciones por celda, σ = 20 ms) | regresión −15…−32 % de error con 6–8 toques; 0 % con 3 (esperado) |
| Test determinista de precisión (`TapTempoTest`, 300 mediciones con generador fijo) | regresión −23,8 % frente al promedio de intervalos (verificado en Python con el mismo generador y el mismo redondeo) |
| Suite nativa (ASan+UBSan; TSan en los tests con hilos) | **39 ejecuciones, 0 problemas**; `test_tap_sync_mute` (13 comprobaciones, AudioEngine real) **validado por mutación**: con `restartBar` roto falla 4 y con él pasa; fuzzing ampliado con duck y resync |
| Controlador y JNI | tests de duck (límites, no finitos, reenvío al recrear el motor) y de resync (orden tras un cambio de tempo, no se coalesce, no se reenvía); V8: **41** funciones JNI |
| Cada escenario de `TapTempoTest` (21 casos al escribir este ADR; 25 en el árbol actual, ver ADR 0034) | simulado a mano contra el algoritmo antes de entregar (pausa, hueco, flam, rebote, atípico, estabilidad, cambio de tempo…) |
| `verify_kotlin_references.py` (R1–R4), balance de llaves, nombres de test, símbolos sin importar | OK |

## Límites de la verificación (declarados)

- **Kotlin NO se compiló aquí** y los tests JVM nuevos no se ejecutaron (TapTempo, TapMute, TempoNudge, controlador).
- **El tacto no se puede medir sin dedo:** tamaño del pad, intensidad de la vibración (`TextHandleMove`),
  duración del destello (160 ms) y los umbrales (1,8×, 0,4×, 3 %) son valores de partida razonables, no
  verdades: hay que probarlos y ajustarlos. La vibración depende del dispositivo.
- La mejora del estimador es **moderada y solo con 5+ toques**; con 2–3 toques la estimación sigue siendo
  ruidosa por naturaleza humana.

## Fuera de alcance (decisiones para el siguiente ciclo)

1. **Compensar la latencia de salida de audio en Sync** (medirla con Oboe y adelantar el reinicio de compás): sin ella el
   click sale desplazado unas decenas de ms respecto al toque. Requiere medir la latencia real por dispositivo.
2. ***Now***, ***half speed*** y presets de tempo del manual de FL (ver «Decisiones y límites de la ampliación»).
3. **Tap-in** (los toques marcan también la cuenta atrás de una grabación): depende del secuenciador.
4. **Bloquear el tempo** tras un TAP estable para evitar cambios accidentales.
5. **Persistir** las opciones SYNC y MUTE entre sesiones (hoy vuelven a «apagado» al reiniciar la app).
