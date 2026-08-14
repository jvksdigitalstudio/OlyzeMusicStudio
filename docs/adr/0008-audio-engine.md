# ADR 0008 — Audio Engine: evitar el ciclo por diseño, tres reutilizaciones deliberadas

**Estado:** Aceptado, aplicado.

**Contexto:** la Fase 4 de EliNer Engine pide el primer motor funcional
real (sin DSP todavía) — Audio Engine, Audio Thread, Audio Callback, Audio
Pipeline, Audio Buffer Flow, Audio Stream Controller, Audio State Machine,
Audio Error Manager, Audio Metrics, Audio Performance Monitor, y una API
pública.

## Decisión 1: `AudioEngineState`/`AudioMetricsSnapshot` en `eliner.api` desde el diseño

En la Fase 2.5, el mismo problema (una interfaz en `eliner.api` necesitando
tipos definidos en el paquete de implementación) se descubrió *después*,
durante la auditoría, y hubo que corregirlo moviendo archivos. Esta vez se
aplicó la lección por adelantado: `AudioEngineState` y
`AudioMetricsSnapshot` se crearon directamente en `eliner.api`, junto a
`AudioEngineApi`, antes de escribir la implementación. El grafo de
dependencias se verificó sin ciclos en el primer intento.

## Decisión 2: reutilizar `ExecutionLane.AUDIO`, no crear un "Audio Thread" nuevo

`ThreadManager` (Fase 2) ya construye un executor de un solo hilo dedicado
para `ExecutionLane.AUDIO`, con las propiedades exactas que pide esta fase
para el "Audio Thread": independiente del resto, nunca comparte
responsabilidades. Crear una clase `AudioThread` nueva habría sido
duplicación — se documentó explícitamente esta decisión en el KDoc de
`AudioEngine` y en su README, en vez de dejar que un lector se preguntara
por qué "falta" ese componente.

## Decisión 3: `AudioErrorManager` reutiliza `EngineError`/`Logger`, no inventa `AudioError`

Un solo tipo de error para todo el motor, no uno por subsistema. Esto
también resuelve "integrar con Logger, Diagnostics, Runtime" de forma
directa: `Logger` ya *es* la integración con Diagnostics, y cualquier
fallo de `AudioEngine.onStart()`/`onStop()` (como `EliNerModule`) ya llega
a `Logger` a través del camino que `EliNerRuntime` construyó en la Fase
2.5 — sin cablear nada nuevo.

## Decisión 4 (considerada y descartada): extraer el patrón `moveTo()`

`EliNerRuntime`, `AudioSessionManager` (Fase 3) y ahora
`AudioStreamController` repiten un mismo patrón de ~5 líneas: capturar el
estado previo, intentar la transición vía `StateMachine`, y si tuvo éxito,
publicar un evento en el `EventBus`. Se evaluó extraerlo a un helper
genérico y se descartó: a diferencia de la tabla de transiciones completa
(que sí se extrajo como `StateMachine<S>` por su complejidad y riesgo
real), este patrón es trivialmente pequeño, cada copia es verificable a
simple vista, y generalizarlo requeriría un helper genérico sobre tipos de
evento distintos que viviría en un paquete nuevo solo para esto —
indirección real a cambio de un beneficio marginal. Se documenta la
decisión en vez de dejarla sin explicar.

## Consecuencias

`AudioEngine` es el primer `EliNerModule` con contenido real jamás
registrado — valida que el mecanismo de `ModuleRegistry`/`ModuleLoader`
construido en Fase 1/2.5 funciona sin cambios. Cero archivos de este
paquete importan `android.*` — toda dependencia de plataforma llega ya
resuelta vía `AudioFoundationContext`/`RuntimeContext`.
