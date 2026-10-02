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
| [`RuntimeContextFactory.kt`](RuntimeContextFactory.kt) | Construye un `RuntimeContext` con las implementaciones reales de cada Foundation Service |
| [`RuntimeContext.kt`](RuntimeContext.kt) · [`RuntimeState.kt`](RuntimeState.kt) · [`ServiceRegistry.kt`](ServiceRegistry.kt) · [`ModuleLoader.kt`](ModuleLoader.kt) · [`EliNerRuntimeApi.kt`](EliNerRuntimeApi.kt) | Contratos del kernel (ver abajo) |

## Los contratos del kernel viven aquí (ADR 0027)

En la Fase 2.5 (ADR 0006) `RuntimeState`, `RuntimeContext`, `ServiceRegistry`
y `ModuleLoader` se movieron a `eliner.api` para romper un ciclo real
`api ↔ runtime`. Eso trataba el síntoma: el ciclo existía porque el
contrato de la *aplicación* y el contrato del *kernel* compartían paquete.
ADR 0027 separa ambos — `eliner.api` es solo contrato de app y no depende
de nada fuera de `api.*` — y devuelve los contratos del kernel a su
paquete natural. El ciclo no reaparece (verificado por
`tools/verify_architecture.py`, que comprueba 0 ciclos en cada CI).

## Dependencia de una sola dirección

`eliner.runtime` depende de `eliner.api.event` (solo el marcador
`EliNerEvent`), `eliner.core` y las 5 carpetas de Foundation Services. Los
paquetes que se construyen *sobre* el Runtime (`audiofoundation`,
`dspfoundation`, `modules.audio`) dependen de `eliner.runtime`; `runtime`
no depende de ninguno de ellos. Matriz completa: `docs/eliner/ARCHITECTURE.md`.

## Estado actual

Código real y funcional. `EliNerRuntime` NO conoce Audio, MIDI, DSP ni
Plugins — cero imports de `eliner.modules`. `ModuleLoader` (en
este paquete) permite registrar `EliNerModule`s, pero ningún módulo real
se registra en esta fase.
