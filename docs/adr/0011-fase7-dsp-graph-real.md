# ADR 0011 — Fase 7: DSP Graph real en el motor nativo (vertical slice, un canal)

**Estado:** Implementado en este entorno (sin red, sin NDK — ver "Build"
más abajo), pendiente de confirmación en dispositivo vía GitHub Actions,
igual que todas las fases anteriores.

**Contexto:** ADR 0010 (cierre de Fase 6) dejó dos caminos abiertos para
"próxima fase" sobre el motor nativo: A) conectar el stack Kotlin
desconectado (`modules.audio`/`dspfoundation`), o B) construir un DSP
Graph real *dentro* del motor nativo que ya es la única autoridad de
audio. El usuario, retomando la conversación, pidió evaluar Mixer UI +
Channel Rack estilo FL Studio Mobile. La recomendación — aceptada — fue
no construir esa UI directamente sobre el chain fijo hardcodeado
(`voices → mReverb → mDelay → master`), sino primero validar con un
**vertical slice real**: un solo canal (el único que existe hoy — el
motor no tiene enrutamiento multicanal, ver roadmap del README) con una
cadena de efectos de verdad cargable/reordenable/removible en runtime,
antes de construir cualquier UI encima.

## Decisión

Se construyó el DSP Graph real **en C++, no en Kotlin** — deliberadamente
del lado de la autoridad de audio real (ver ADR 0010: "el motor nativo es
la única autoridad de audio real"). No se tocó nada de
`modules.audio`/`dspfoundation` (siguen exactamente como los dejó la
Fase 6, opción C).

### Piezas nuevas

- **`DspModule`** (`eliner/include/eliner/dsp/DspModule.h`) — interfaz
  base (`process()`, `setParameter()`, `type()`) + enum `DspModuleType`
  (hoy: `Reverb`, `Delay`; append-only para futuros tipos — EQ,
  Compressor, etc., fase de efectos comerciales, explícitamente fuera de
  alcance aquí).
- **`Reverb`/`Delay`** — retrofit para implementar `DspModule`, sin
  cambiar su API pública existente (mismo principio que ADR 0009 Decisión
  3: aditivo, no rompe firmas).
- **`DspModuleFactory`** — asignación (`new`) exclusivamente en el hilo
  de control; nunca en el hilo de audio.
- **`DspChain`** (`eliner/include/eliner/dsp/DspChain.h`) — 8 slots
  ordenados, propiedad exclusiva del hilo de audio, reemplaza la
  secuencia fija `mReverb->process(); mDelay->process();` por un array
  reconfigurable en runtime (`insert`/`remove`/`move`/`setParameter`).
- **4 comandos nuevos** en `CommandQueue.h` (`InsertModule`,
  `RemoveModule`, `SetModuleParameter`, `MoveModule`) — mismo mecanismo
  SPSC lock-free ya verificado en Fase 6 (§3-6), no uno nuevo.
- **Cola de retiro** (`mRetireQueue`, audio→control) — el hilo de audio
  nunca hace `delete`; empuja el puntero saliente a esta cola y el hilo de
  control la drena (`collectGarbage()`). Misma clase de patrón SPSC que
  `EngineCommandQueue`, con los roles de productor/consumidor invertidos.
- **`mSlotTypesShadow`** — espejo *solo del hilo de control* de qué tipo
  de módulo hay en cada slot, para que `getModuleType()` pueda responder
  sin leer `mFxChain` (que es propiedad exclusiva del hilo de audio) desde
  el otro hilo.

### Compatibilidad con la API legacy

`setReverbMix/Room/Damp` y `setDelayMix/Time/Feedback` (Fase 4-6) **no
cambiaron de firma**. Internamente ahora buscan el primer módulo de ese
tipo en `mFxChain` (`findFirstOfType`) y aplican el parámetro ahí — si el
usuario removió ese módulo con `removeModule()`, la llamada es un no-op
seguro, no un crash.

## Realtime safety — mismas garantías de Fase 6, extendidas

- **Sin `new`/`delete` en el hilo de audio:** `insertModule()` asigna en
  el hilo de control; el hilo de audio solo intercambia punteros
  (`DspChain::insert/remove/move`) y empuja el puntero saliente a
  `mRetireQueue`. `collectGarbage()` (que sí llama `delete`) es
  exclusivamente hilo de control.
- **Sin locks:** `mFxChain`/`mRetireQueue` siguen el mismo patrón SPSC
  lock-free que el resto del motor — ver comentarios en `DspChain.h` y
  `AudioEngine.h` sobre qué hilo puede tocar qué.
- **Caso límite documentado, no ocultado:** si `mRetireQueue` (capacidad
  32) se llena, el puntero se pierde silenciosamente en cuanto a
  liberación (fuga de memoria, no corrupción) — se loguea con `LOGE`. Con
  8 slots máximo y patrones de uso normales (un usuario reordenando un
  canal, no un flood), esto no debería ocurrir en la práctica; queda
  anotado igual que `EngineCommandQueue` anota su propio caso de overflow
  (Fase 6 §3-6).
- **Teardown sin fugas:** `AudioEngine::stop()` ahora drena
  `mRetireQueue` y limpia `mFxChain` explícitamente — antes, con
  `unique_ptr<Reverb>`/`unique_ptr<Delay>`, esto era automático; con
  punteros crudos en `mFxChain` había que hacerlo a mano. Se verificó el
  orden correcto (drenar retiros pendientes antes de `clear()`) para no
  liberar nada dos veces.

## Qué se dejó fuera, a propósito

- **Persistencia** (guardar/cargar qué hay en cada slot) — no existe
  formato de proyecto (`PROJECT_FORMAT_OMS`) implementado aún; fuera de
  alcance de esta fase.
- **Más de un canal** — el motor sigue sin enrutamiento multicanal real
  (`Mixer` sigue siendo el stub de Fase 3). Este ADR es explícitamente el
  *vertical slice de un canal*, no el Mixer multicanal del roadmap.
- **Tipos de efecto nuevos** (EQ, Compressor, Sampler) — `DspModuleFactory`
  devuelve `nullptr` para cualquier `DspModuleType` sin fábrica; no hay
  stub que finja ser un efecto real (mismo principio que ADR 0004 para
  Core Foundation: "cero stubs").
- **UI (Mixer/Channel Rack)** — deliberadamente no tocada en esta fase.
  Es la decisión de orden acordada con el usuario: motor real primero,
  UI después de validar que el motor escala.

## Build

Mismo entorno sin red que Fase 6 — no se pudo correr `./gradlew` ni el
NDK real aquí. Se revisó manualmente cada archivo tocado (búsqueda de
referencias colgantes a `mReverb`/`mDelay`, includes, coherencia de
firmas) pero, siguiendo la Sección 33 del prompt (Fase 6), esto **no** se
declara "compila" hasta confirmarlo en GitHub Actions — igual que todo lo
anterior. Próximo paso natural: correr el build real y, si compila,
decidir si la UI (Mixer/Channel Rack) empieza a construirse sobre este
slice.
