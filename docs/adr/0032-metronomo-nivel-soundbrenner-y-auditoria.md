# ADR 0032 — Metrónomo a nivel Soundbrenner y auditoría del código reciente

**Estado:** Aceptado — **pendiente de confirmación por compilación Kotlin/NDK y prueba en dispositivo**
(ver «Límites de la verificación»).
**Relacionado:** ADR 0028 (reloj y metrónomo), 0029 (pulso), 0030 (desacople), 0031 (metrónomo y panel).

## Contexto

Se pidió comparar el metrónomo con el de Soundbrenner (captura de referencia), implementar lo que
faltase y **auditar todo el código reciente** antes de entregar, corrigiendo bugs, duplicados,
incoherencias y parches. Este ADR registra las dos cosas.

## 1. Comparativa con Soundbrenner (lo visible en la captura)

| Función | Antes | Ahora |
|---|---|---|
| Dial rotatorio, BPM, Tap, Play | ✅ | ✅ |
| **Nombre del tempo** («Allegro») | ❌ | ✅ `TempoMarking` |
| **Intensidad por tiempo** (columnas apiladas) | ⚠️ solo acento sí/no del 1.er tiempo | ✅ patrón de 4 niveles por tiempo |
| **Compás con denominador** (4/4, 6/8…) | ⚠️ solo x/4 | ✅ x/2, x/4, x/8, x/16, 1–16 pulsos |
| Subdivisión | ✅ | ✅ |
| Sonidos | ✅ | ✅ |
| **Crear / Cargar** | ❌ | ✅ presets guardables |

No se afirma nada de lo que la captura no muestra (p. ej. el contenido de los menús «♩» y «…»).

## 2. Decisión

### 2.1 Intensidad por tiempo (REEMPLAZA al interruptor de acento)

El antiguo `MetronomeAccent` (sí/no) era el caso particular «primer tiempo acentuado, resto normales».
Mantenerlo junto al patrón habría sido una duplicidad con dos fuentes de verdad, así que **se eliminó**.
- Niveles `BeatLevel`: **Mute** (no suena, ni sus subdivisiones), **Soft**, **Medium**, **Accent**.
  `Medium` y `Accent` son exactamente el sonido de antes (0,75 y 1,0): el patrón por defecto
  (`Accent, Medium × 15`) reproduce el comportamiento histórico.
- **Empaquetado:** 2 bits por tiempo en dos palabras de 16 bits (tiempos 0–7 y 8–15), que viajan en **un
  solo comando** `SetMetronomeBeatLevels`. Un `SetParameter` por palabra habría permitido que el hilo de
  audio oyera un patrón a medias durante un bloque; el comando propio lo impide.
- Las constantes viven en `ClickSound.h` y las replica `BeatPattern.kt`; el test de contrato lee el
  header (niveles, bits, palabras, 16 tiempos = `MAX_BEATS_PER_BAR`).

### 2.2 Compás con denominador (unidad de pulso)

`TempoClock::setQuartersPerPulse`: un pulso dura `4/denominador` negras. **El tempo sigue SIEMPRE en
negras por minuto**; por eso el **delay sincronizado y el reloj MIDI no cambian** (ambos miden en negras)
y un test lo fija. Cambiar la unidad en marcha reescala el tiempo restante del pulso (fase continua, sin
pulsos repetidos ni omitidos). 6/8 = 6 pulsos de corchea con el patrón agrupado de tres en tres por defecto
(`BeatPattern.defaultFor`); el dial muestra la cadencia real («1/8 = 240.0») cuando el pulso no es la negra.
- **Decisión de producto declarada:** el BPM es siempre en negras. Un usuario que piense «6/8 a negra con
  puntillo = 60» debe introducir 120 negras/min (el dial lo aclara con la cadencia de pulsos). Poder
  expresar el tempo en la figura elegida es posible, pero cambia la semántica del BPM en todo el sistema;
  se deja como decisión abierta. (Nota ADR 0034: el TAP sí convierte desde entonces los pulsos marcados a negras
  según la figura, `quarterNoteBpmFor`, sin necesitar esa decisión.)
- `MainViewModel.setTimeSignature` es el único camino de cambio de compás y restablece el patrón al de ese
  compás (el patrón de un 4/4 no tiene sentido en un 6/8); RESET lo restablece sin cambiar de compás.

### 2.3 Nombre del tempo

`TempoMarking`: partición **sin huecos ni solapes** por límite inferior (las convenciones históricas se
solapan). Función pura con test (cada límite, monotonía en todo el rango 20–300, NaN y fuera de rango).

### 2.4 Presets (Crear / Cargar)

Separados por responsabilidad: `MetronomePreset` (valor), `MetronomePresetCodec` (texto), `PresetStore`
(+ `SharedPreferencesPresetStore`), `MetronomePresetLibrary` (reglas) y `applyPreset` (motor).
- **Decodificación estricta:** lo guardado es entrada no fiable y acaba en el motor. Se valida cada campo
  contra los rangos del motor y cualquier duda descarta el preset **entero** (nunca uno «arreglado»);
  formato versionado (`v1;…`); solo dígitos ASCII `0–3` en el patrón.
- **Sin duplicados y con tope (20):** al llenarse no se descarta nada en silencio (GUARDAR se atenúa).
- Cargar un preset cambia **cómo suena**, no si el metrónomo o el transporte están en marcha; el patrón se
  aplica tal cual se guardó (no se restablece).
- Nombres automáticos («Allegro 120.0 · 4/4»): sin campo de texto para no añadir superficie de entrada.

### 2.5 UI

`BeatPatternEditor`: una columna por tiempo con tres segmentos apilados; tocar un nivel lo fija y tocar el
actual lo baja un escalón. Es **a la vez el indicador de pulso** (la columna que suena se ilumina), de modo
que sustituye a los puntos `BeatDots` y al interruptor de acento (menos piezas, no más). Selector de pulsos
(1–16) y de figura (1/2…1/16), RESET del patrón, y `PresetsSection`. Los textos de los enums viven en un solo
sitio (`TempoLabels`).

## 3. Auditoría del código reciente (hallazgos y correcciones)

Cada hallazgo se reprodujo o se comprobó antes de corregirlo.

| # | Hallazgo | Evidencia | Corrección |
|---|---|---|---|
| 1 | **Bug:** apagar y reactivar el metrónomo a mitad de pulso (ventana ≈ 60–125 ms, p. ej. doble toque en ♪) con subdivisión hacía sonar un **click intermedio huérfano** (pico 0,30 sin pulso que lo origine). Con el metrónomo apagado `render()` deja de procesar y la cuenta de subdivisión quedaba congelada | experimento reproducible (0,30 con 3000–6000 frames apagado; 0 con 20000, por eso mi primera prueba no lo vio) | `setEnabled(false)` cancela la subdivisión pendiente. Test de regresión **validado por mutación** (falla 3/3 sin el arreglo, pasa con él) |
| 2 | **Duplicado:** `stepBpm(Int)` y `stepTempo(Float)` hacían lo mismo con **redondeo distinto** (el header dejaba tempos como 98.43 y el panel 98.4) | lectura + uso | Un único camino `stepTempo` (header y panel) |
| 3 | **Código muerto:** `setBpm(Int)` sin ningún llamador | búsqueda sistemática de miembros públicos sin uso | Eliminado |
| 4 | **Duplicado de constante:** la altura de la barra (56 dp) escrita en `AppHeader` y en `TempoPanel` | lectura | `AppHeaderHeight`, una sola fuente de verdad |
| 5 | **Comentario engañoso** en `AudioEngine.h`: `setMetronomeSubdivision` heredó el `// 0.0 – 1.0` del volumen por una edición mía | lectura | Corregido |
| 6 | **Duplicado de test:** tres copias de un doble de `EliNerTransportApi` | lectura | `FakeTransport` único y compartido |
| 7 | **Duplicado de UI:** etiquetas de enums y `LabeledChips` repetidos | lectura | `TempoLabels.kt` y `LabeledChips` compartido |
| 8 | **Documentación obsoleta:** ADR 0028 citaba `stepBpm`, un comentario de `Metronome.h` decía que el acento «se puede desactivar», README y `ARCHITECTURE.md` describían el acento | búsqueda de referencias | Corregidos |
| 9 | Restos de depuración, `TODO`/`FIXME`, `printf`/`cout`, o `LOG*` en el hilo de audio | búsqueda | **Ninguno** (los `Log.*` existentes son del hilo de control y de errores reales) |

**Robustez:** `test_transport_fuzz` (4 semillas × 6000 operaciones aleatorias, incluidos NaN, ±∞, valores
negativos y palabras de patrón > 16 bits, con bloques de 1–1024 frames, bajo ASan/UBSan): la salida es
siempre finita y bajo el techo de nivel, la secuencia de pulsos nunca retrocede, el delay siempre queda en
rango y el apagado da silencio absoluto.

## 4. Verificación realizada

| Comprobación | Resultado |
|---|---|
| Suite nativa (ASan+UBSan; TSan en los tests con hilos) | **38 ejecuciones, 0 problemas** (antes 36) |
| Nuevos nativos | `test_beat_unit`, `test_transport_fuzz`; ampliados `test_metronome_click` y `test_engine_click_settings` (patrón, unidad de pulso, inválidos, atomicidad) |
| `check_engine_compile.sh` (`-Wall -Wextra`) | sin warnings |
| `verify_architecture.py` | OK; **39** funciones JNI (V8) |
| `verify_kotlin_references.py` (R1–R4) | OK |
| Dobles de test frente a las interfaces | los 4 implementan **todos** los miembros (comprobado por script) |
| Cifras de los tests Kotlin nuevos (`BeatPattern`, `TempoMarking`, codec) | portadas a Python y verificadas (empaquetado 0xAAAB/0xAAAA, 6/8, 12/8, 24 casos de nombre de tempo…) |

## 5. Límites de la verificación (declarados)

- **Kotlin NO se compiló en este entorno** y los tests JVM nuevos no se ejecutaron aquí. Los verificadores
  estáticos, la revisión de imports, el balance de llaves y la comprobación de nombres de test pasan, pero no
  sustituyen al compilador. La entrega anterior (ADR 0031) sí compiló y abrió en tu dispositivo.
- **La UI nueva no se ha visto en pantalla**: el editor de patrón, el selector de compás de 16 chips, la
  lista de presets y la altura del panel con todo desplegado hay que verlos en el dispositivo.
- **El sonido de Mute/Soft/Accent y de las subdivisiones en x/8 lo valida el oído**; los tests prueban
  niveles, temporización y robustez, no musicalidad.
- Persistencia (`SharedPreferences`): la lógica de lista, codec y reglas está probada en JVM con un almacén en
  memoria; la escritura real en el dispositivo no.

## 6. Fuera de alcance (decisiones para el siguiente ciclo)

1. **BPM en la figura elegida** (negra con puntillo para 6/8): cambia la semántica del tempo en todo el motor.
2. **Cuenta atrás / pre-roll** antes de grabar: pertenece al secuenciador (aún no existe).
3. **Entrenador de tempo** (subir BPM cada N compases) y **compases de silencio** de práctica.
4. **Teclear el BPM** y **renombrar presets** (requieren campo de texto).
5. **Polirritmos** y patrones de subdivisión por tiempo.
6. **Retirar los miembros públicos sin uso** de `MainViewModel` que no son de esta entrega
   (`toggleKeyboard`, `engineInitState`, `setMasterVolume`, `setReverbMix`, `setDelayMix`): son API plausible
   para un futuro mezclador y no se tocaron sin decisión.
