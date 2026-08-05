# EliNer — Modules / Audio Engine — IMPLEMENTADO (Fase 4 de EliNer Engine)

**Responsabilidad:** el primer motor funcional real de EliNer. Inicializa
el sistema de audio, abre la sesión, mantiene el ciclo de procesamiento,
detiene el motor correctamente, libera recursos. **Cero DSP** — ningún
archivo de este paquete procesa una sola muestra de audio.

## Archivos

| Archivo | Responsabilidad |
|---|---|
| [`AudioEngine.kt`](AudioEngine.kt) | La clase real — compone todo lo demás. Implementa `EliNerModule` (Core, Fase 1) **y** `AudioEngineApi` (`eliner.api`, Fase 4) |
| [`AudioStreamController.kt`](AudioStreamController.kt) | Máquina de estados (`AudioEngineState`, en `eliner.api`) + start/pause/resume/stop/flush/restart |
| [`AudioCallback.kt`](AudioCallback.kt) | Contrato de callback agnóstico de backend — infraestructura, nada lo invoca todavía |
| [`AudioPipeline.kt`](AudioPipeline.kt) | Orden fijo de etapas (Input→Engine→DSP→Mixer→Master→Output) — sin procesamiento |
| [`AudioBufferPool.kt`](AudioBufferPool.kt) | Pool de buffers real, sin bloqueos (`ConcurrentLinkedQueue`, no `synchronized`) |
| [`AudioErrorManager.kt`](AudioErrorManager.kt) | Reporta errores reutilizando `EngineError`/`Logger` — sin tipo paralelo |
| [`AudioMetrics.kt`](AudioMetrics.kt) | Contadores atómicos reales (xruns, underruns, overruns, timing, CPU) |
| [`AudioPerformanceMonitor.kt`](AudioPerformanceMonitor.kt) | Lee (nunca modifica) perfil + buffer + latencia + configuración |

## Primer módulo real registrado a través del mecanismo de la Fase 1

`AudioEngine` es la primera clase que realmente implementa
`com.yeivikas.olyze.eliner.core.EliNerModule` con contenido real — hasta
ahora, `ModuleRegistry`/`ModuleLoader` (Fase 1/2.5) solo tenían mecanismo
genérico, nunca un módulo de verdad para validar. Esta fase es la primera
prueba real de que ese diseño funciona: `AudioEngine` no necesitó ningún
cambio en `EliNerCore`, `ModuleRegistry` ni `ModuleLoader` para integrarse.

## Decisiones de no-duplicación (auditoría obligatoria de esta fase)

- **"Audio Thread"** — no se creó una clase nueva. Se reutiliza
  `ExecutionLane.AUDIO` de `ThreadManager` (Fase 2), que ya es un hilo
  dedicado, independiente del resto, sin compartir responsabilidades.
  Crear un segundo mecanismo habría sido la duplicación que esta fase pide
  verificar explícitamente que no exista.
- **"Audio Error Manager"** — reutiliza `EngineError`/`EngineErrorSeverity`
  (Core, Fase 1) y `Logger` (Diagnostics, Fase 2/2.5) en vez de inventar un
  tipo `AudioError` paralelo.
- **"Audio State Machine"** — usa `StateMachine<S>` (Core, Fase 3) en vez
  de una cuarta copia manual del patrón de transiciones. `AudioEngineState`
  es un tipo nuevo y distinto de `EngineState`/`RuntimeState`, tal como
  exige el prompt — solo el mecanismo se reutiliza, no el tipo.

## Dónde vive `AudioEngineState` (y por qué no aquí)

`AudioEngineState` y `AudioMetricsSnapshot` viven en `eliner.api`, no en
este paquete. Se aplicó la lección de la Fase 2.5 (ADR 0006) de forma
preventiva: `AudioEngineApi` necesita referenciar estos tipos, y
`AudioEngine` necesita implementar `AudioEngineApi` — si los tipos
vivieran aquí, se recrearía el mismo ciclo `api ↔ modules.audio` que ya se
encontró y corrigió una vez. Esta vez se evitó desde el diseño, no
después de una auditoría.

## Estado actual

100% Kotlin puro — cero imports de `android.*` en todo el paquete (todo lo
que necesita de Android llega ya resuelto vía `AudioFoundationContext`/
`RuntimeContext`, nunca importado directamente aquí). Cero dependencias
circulares — verificado con análisis de grafo antes de cada entrega. El
motor nativo existente (`eliner.bridge.EliNerAudioBridge`, Oboe en C++) no
se tocó — sigue siendo el mismo desde la Fase 3 del proyecto, sin
cambios.
