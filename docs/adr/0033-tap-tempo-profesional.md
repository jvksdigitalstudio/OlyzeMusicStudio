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
| Cada escenario de `TapTempoTest` (21 casos) | simulado a mano contra el algoritmo antes de entregar (pausa, hueco, flam, rebote, atípico, estabilidad, cambio de tempo…) |
| `verify_kotlin_references.py` (R1–R4), balance de llaves, nombres de test, símbolos sin importar | OK |

## Límites de la verificación (declarados)

- **Kotlin NO se compiló aquí** y los tests JVM nuevos no se ejecutaron.
- **El tacto no se puede medir sin dedo:** tamaño del pad, intensidad de la vibración (`TextHandleMove`),
  duración del destello (160 ms) y los umbrales (1,8×, 0,4×, 3 %) son valores de partida razonables, no
  verdades: hay que probarlos y ajustarlos. La vibración depende del dispositivo.
- La mejora del estimador es **moderada y solo con 5+ toques**; con 2–3 toques la estimación sigue siendo
  ruidosa por naturaleza humana.

## Fuera de alcance (decisiones para el siguiente ciclo)

1. **«Sync» a los toques** (alinear la fase del metrónomo con el último toque, como la opción *Sync* del tapper
   de FL): exige compensar la latencia de salida de audio (decenas de ms) o el click sonaría desplazado respecto
   al toque; sin esa medición sería peor que no tenerlo.
2. **Tap-in** (los toques marcan también la cuenta atrás de una grabación): depende del secuenciador.
3. **Bloquear el tempo** tras un TAP estable para evitar cambios accidentales.
