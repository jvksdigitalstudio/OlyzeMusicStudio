# ADR 0039 — Swing de la subdivisión del click

**Estado:** Aceptado — **motor nativo verificado por tests (medidos sobre el audio renderizado); capa Kotlin y UI
pendientes de compilar y de probar en dispositivo.**
**Relacionado:** ADR 0032 (metrónomo), 0034 (auditoría; carencia «sin swing»).
**Fase:** 6a de la hoja de ruta (click avanzado).

## Contexto
La auditoría señaló que la subdivisión del click era siempre recta. Logic, Cubase y Pro Tools ofrecen swing en su
metrónomo: el click intermedio de cada par se retrasa para marcar un groove (shuffle, hip-hop, jazz).

## Decisiones

### D1 — El eje es el porcentaje del PAR que ocupa la primera subdivisión (50–75)
50 = recto (sin swing), 67 ≈ tresillo (el shuffle clásico), 75 = punto y semi. Es el mismo eje que el «Swing» de Logic
y Cubase. `kMinClickSwingPercent/kMaxClickSwingPercent/kDefaultClickSwingPercent` viven en `ClickSound.h`
(única definición); `ClickSwing` (Kotlin) ofrece cinco valores y `TransportConstantsMatchNativeTest` comprueba que
estén dentro del rango y que el recto sea el defecto del motor. Un valor fuera de rango se IGNORA, no se recorta.

### D2 — Solo con 2 o 4 clicks por pulso
Con 2 (corcheas) el click de en medio cae al swing% del pulso. Con 4 (semicorcheas) cada PAR se reparte
swing% / (100 − swing)% de media negra, es decir: clicks en `0, s·h, h, h + s·h` y el siguiente pulso en su frame
exacto (h = media negra). Con 3 (tresillos) o 1 no hay par que desplazar y el swing se ignora, **muestra a muestra**
(test).

### D3 — El pulso nunca se mueve y el reloj no sabe nada del swing
El swing es una propiedad del CLICK: vive en `Metronome` y no toca `TempoClock`, la posición, el pulso publicado ni el
reloj MIDI. El tiempo fuerte cae siempre en su frame exacto con cualquier swing y subdivisión (test).

### D4 — Mecánica: huecos alternos con la fracción de muestra arrastrada
La cuenta atrás de la subdivisión usa dos duraciones, «primero del par» y «segundo del par» (`mSubFirst`, `mSubSecond`),
que alternan por hueco; sin swing son iguales y todo queda como antes (el test mide que a 50% los clicks equidistan
exactamente). Se calcula en cada pulso con el tempo del momento, así que un cambio de swing se aplica en el pulso
SIGUIENTE, nunca a mitad del actual (test). Sigue siendo independiente del tamaño de bloque (señal idéntica con 64,
480 y 4800 frames).

### D5 — UI: el selector solo aparece donde tiene efecto
«SWING» (Recto / 58% / 62% / Tresillo / 75%) se muestra en la sección del metrónomo únicamente con subdivisión de 1/8 o
1/16. Con otra subdivisión se oculta, y el valor elegido se conserva para cuando vuelva.

## Verificación realizada
- Nativo: `test_click_swing` mide DÓNDE cae cada click en el audio renderizado (envolvente por ventanas, porque un click
  es una oscilación amortiguada que cruza cualquier umbral varias veces: mi primer arnés lo ignoraba y falló hasta en
  el caso recto, y se corrigió). Cubre 2 y 4 por pulso con 5 valores de swing, el pulso inmóvil con 16 combinaciones,
  tresillos y sin subdivisión sin efecto, 50% = recto, cambio de swing en el pulso siguiente e independencia del
  bloque.
- Mutaciones: swing nunca aplicado, par invertido, swing también en tresillos y huecos que no alternan: **las 4
  detectadas**.
- Verificadores estáticos del repo: sin violaciones (48 funciones JNI enlazadas).

## Límites de la verificación (importante)
- **Kotlin y Compose NO se compilaron ni ejecutaron.** Tests Kotlin nuevos/actualizados sin ejecutar:
  `TransportControllerTest` (secuencias completas de `syncToNative` con `swing=50` tras la subdivisión, y dos tests de
  swing) y `TransportConstantsMatchNativeTest`.
- **No se ha oído en un dispositivo.** El swing se mide en muestras, pero si «suena bien» con cada sonido de click
  (sobre todo con un valor extremo) es una prueba de oído.

## Consecuencias aceptadas / NO hecho
- El swing no se guarda en los presets del metrónomo (igual que el conteo y el modo del click): ampliar el formato
  versionado se decide con la persistencia de proyecto.
- Solo cinco valores en la UI; el motor acepta cualquier entero de 50 a 75.
- El swing de la subdivisión no afecta al reloj MIDI ni al delay sincronizado.
- Siguen pendientes del click avanzado: sonidos personalizados, compases compuestos con agrupación y salida separada.
