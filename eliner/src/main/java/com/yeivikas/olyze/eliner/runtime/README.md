# EliNer — Runtime (Fase 2.5 — nuevo paquete)

**Responsabilidad:** implementación real del Runtime — el composition root
que orquesta Core Foundation (Fase 1) y los Foundation Services (Fase 2)
en un único ciclo de vida coherente.

## Archivos

| Archivo | Responsabilidad |
|---|---|
| [`EliNerRuntime.kt`](EliNerRuntime.kt) | La implementación — construye/usa `EliNerCore` + `RuntimeContext`, orquesta `initialize()`/`pause()`/`resume()`/`shutdown()`, reenvía errores del Core al `Logger` |
| [`LifecycleManager.kt`](LifecycleManager.kt) | Dueño de `RuntimeState` y sus transiciones — responsabilidad separada de `EliNerRuntime` (SRP: uno decide *si* una transición es legal, el otro decide *qué pasa* en cada transición) |
| [`RuntimeEvents.kt`](RuntimeEvents.kt) | `RuntimeStateChangedEvent` — el único evento nuevo de esta fase |

## Por qué este paquete NO contiene `RuntimeState`, `RuntimeContext`, `ServiceRegistry` ni `ModuleLoader`

Estaban aquí originalmente. Durante la auditoría interna obligatoria de
esta fase se detectó que causaban un ciclo real de paquetes con
`eliner.api` (`EliNerRuntimeApi` los necesitaba para tipar su propia
interfaz; `EliNerRuntime` necesitaba importar `EliNerRuntimeApi` para
implementarla). Se movieron a `eliner.api` — ahí es donde vive el
"vocabulario público" del contrato. Ver
[`docs/adr/0006-runtime-foundation.md`](../../../../../../../../../docs/adr/0006-runtime-foundation.md)
para el detalle completo.

## Dependencia de una sola dirección

`eliner.runtime` depende de `eliner.api` (para implementar
`EliNerRuntimeApi` y usar `RuntimeState`/`RuntimeContext`/etc.),
`eliner.core`, y las 5 carpetas de Foundation Services. Ningún paquete de
capa inferior depende de `eliner.runtime` — verificado con un análisis de
grafo de imports antes de cada entrega, no solo asumido.

## Estado actual

Código real y funcional. `EliNerRuntime` NO conoce Audio, MIDI, DSP ni
Plugins — cero imports de `eliner.modules`. `ModuleLoader` (en
`eliner.api`) permite registrar `EliNerModule`s, pero ningún módulo real
se registra en esta fase.
