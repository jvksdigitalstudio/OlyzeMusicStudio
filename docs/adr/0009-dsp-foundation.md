# ADR 0009 — DSP Foundation: dos generalizaciones nuevas, un enganche aditivo a Audio Engine

**Estado:** Aceptado, aplicado.

**Contexto:** la Fase 5 pide la infraestructura DSP completa (Context,
Processor, Node, Graph, Chain, Bus, Buffer, Frame, Scheduler, Parameter,
Parameter Manager, State, Metrics, Diagnostics, API) sin implementar
ningún efecto/instrumento real.

## Decisión 1: dos utilitarios genéricos nuevos en `eliner.core`

`DspGraph` necesitaba la misma mecánica de nodos+conexiones que
`AudioRoutingGraph` (Fase 3) ya implementa; `DspBufferPool` necesitaba la
misma mecánica de pool sin bloqueos que `AudioBufferPool` (Fase 4) ya
implementa. Se extrajeron `ConnectionGraph<N>` y `FloatBufferPool` en
`eliner.core` — mismo patrón ya establecido con `StateMachine<S>` en la
Fase 3 (ver ADR 0007): generalizar para el código nuevo, sin retocar el
código ya verificado de fases anteriores.

**Motivo adicional para no reutilizar `AudioBufferPool` directamente:**
vive en `eliner.modules.audio`, y la arquitectura coloca DSP Foundation
*debajo* de Audio Engine. Si `dspfoundation` importara de `modules.audio`
ahora, y una fase futura (correctamente) hace que Audio Engine importe de
DSP Foundation para usarla de verdad, eso sería un ciclo garantizado. Se
evitó por diseño, no se corrigió después.

## Decisión 2: `DspState`/`DspMetricsSnapshot` en `eliner.api` desde el inicio

Misma lección aplicada preventivamente por segunda vez consecutiva (ver
ADR 0006 y 0008): `DspApi` necesita estos tipos, `DspFoundation` necesita
implementar `DspApi` — viven en `eliner.api` para que el grafo de
dependencias nunca pueda ciclarse entre `api` y `dspfoundation`.

## Decisión 3 (autorizada explícitamente por el usuario): enganche aditivo en `AudioEngine`

Se agregó `var dsp: DspApi? = null` a `AudioEngine` (Fase 4) — un campo
público, nullable, con valor por defecto `null`. **Ningún constructor,
firma de método, ni comportamiento existente cambió** para código que no
lo use. Cuando se asigna: `initialize()` también inicializa el DSP
(best-effort — un fallo se reporta como `WARNING` vía `AudioErrorManager`
pero no bloquea el arranque del motor de audio), y `shutdown()` también lo
apaga. Se verificó explícitamente que los 8 métodos públicos de
`AudioEngineApi`/`EliNerModule` que `AudioEngine` ya implementaba
siguen exactamente iguales — el diff es puramente aditivo.

**Por qué no se hizo al revés (que `DspFoundation` conozca a `AudioEngine`):**
habría invertido la dirección de la arquitectura ("Audio Engine ↓ DSP
Foundation"). El enganche debía vivir en la capa de arriba, no abajo.

## Consecuencias

`dspfoundation -> [api, core, diagnostics, events]` — nunca importa
`modules.audio` ni `audiofoundation` directamente. `modules.audio` tampoco
importa `dspfoundation` — el enganche pasa enteramente por `DspApi`
(`eliner.api`), así que ambos paquetes quedan mutuamente desacoplados en
ambas direcciones, no solo en una. 15 archivos Kotlin nuevos, 1 archivo
existente (`AudioEngine.kt`) modificado de forma estrictamente aditiva.
