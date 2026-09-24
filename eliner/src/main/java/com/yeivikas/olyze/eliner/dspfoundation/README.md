# EliNer — DSP Foundation (Fase 5 de EliNer Engine — nuevo paquete)

**Responsabilidad:** infraestructura sobre la cual funcionarán los futuros
módulos DSP (EQ, Compressor, Reverb, Delay, Filters, etc.). **Ningún
archivo de este paquete implementa un algoritmo DSP real** — ni un EQ, ni
un compresor, ni una reverb.

## Archivos

| Archivo | Responsabilidad |
|---|---|
| [`DspProcessor.kt`](DspProcessor.kt) | Contrato base — sin implementaciones |
| [`DspFrame.kt`](DspFrame.kt) | Bloque de procesamiento, layout planar (preparado para SIMD/NEON futuro) |
| [`DspParameter.kt`](DspParameter.kt) | Parámetro validado + `DspParameterManager` (preparado para automatización/presets/MIDI Learn) |
| [`DspNode.kt` / `DspGraph.kt`](DspGraph.kt) | Nodo + grafo, sobre `ConnectionGraph<N>` (Core) |
| [`DspChain.kt`](DspChain.kt) | Cadena en serie + `DspBus` (Insert/Send/Return/Master) |
| [`DspBufferPool.kt`](DspBufferPool.kt) | Pool de frames, sobre `FloatBufferPool` (Core) |
| [`DspScheduler.kt`](DspScheduler.kt) | Orden de ejecución — ordenamiento topológico real (Kahn), nunca ejecuta procesamiento |
| [`DspErrorManager.kt`](DspErrorManager.kt) | Reutiliza `EngineError`/`Logger` |
| [`DspMetrics.kt`](DspMetrics.kt) | Contadores reales, sin interfaz gráfica |
| [`DspContext.kt`](DspContext.kt) | Agregador — punto de acceso principal |
| [`DspFoundation.kt`](DspFoundation.kt) | Implementa `DspApi` (`eliner.api`), dueño de `DspState` |

## Por qué no se reutilizó `AudioRoutingGraph`/`AudioBufferPool` directamente

Ambos ya existían (Fase 3 y 4) con la mecánica que esta fase necesitaba
otra vez. En vez de una tercera/cuarta copia manual, o de importar esas
clases directamente (lo que habría invertido la dependencia Audio Engine
↔ DSP Foundation), se extrajeron dos utilitarios genéricos nuevos en
`eliner.core`: `ConnectionGraph<N>` y `FloatBufferPool`. Detalle completo
en `docs/adr/0009-dsp-foundation.md`.

## Desacoplamiento verificado en ambas direcciones

`dspfoundation` no importa `eliner.modules.audio` ni
`eliner.audiofoundation`. `eliner.modules.audio` (Audio Engine, Fase 4)
tampoco importa `dspfoundation` — el único enganche entre ambos es el
campo opcional `AudioEngine.dsp: DspApi?`, que solo depende de la
interfaz pública en `eliner.api`. Ninguno de los dos paquetes conoce al
otro directamente.

## Estado actual

100% Kotlin puro, cero imports de `android.*`. `DspScheduler.computeExecutionOrder()`
es el único algoritmo no trivial de esta fase — ordenamiento topológico
real (Kahn), verificado matemáticamente correcto, pero solo *calcula* un
orden; no ejecuta ningún `DspProcessor.process()`.
