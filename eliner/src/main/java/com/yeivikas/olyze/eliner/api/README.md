# EliNer — API

**Responsabilidad:** el "EliNer API Layer" de la arquitectura — la única
capa que la UI (`:app`) puede importar directamente. Contiene tanto los
contratos (interfaces) como el vocabulario de datos que fluye a través de
ellos.

## Archivos

| Archivo | Qué es | Implementado por |
|---|---|---|
| [`EliNerAudioApi.kt`](EliNerAudioApi.kt) | Contrato de audio (Fase 3 del proyecto) | `eliner.bridge.EliNerAudioBridge` |
| [`EliNerRuntimeApi.kt`](EliNerRuntimeApi.kt) | Contrato del Runtime (Fase 2.5 de EliNer Engine) | `eliner.runtime.EliNerRuntime` |
| [`RuntimeState.kt`](RuntimeState.kt) | Estados del Runtime + tabla de transiciones | — (tipo de datos) |
| [`RuntimeContext.kt`](RuntimeContext.kt) | Referencias compartidas a los Foundation Services, todas por interfaz salvo `EventBus` (excepción documentada) | — (tipo de datos) |
| [`ServiceRegistry.kt`](ServiceRegistry.kt) | Registro de servicios por contrato — explícitamente NO un Service Locator global | — |
| [`ModuleLoader.kt`](ModuleLoader.kt) | Infraestructura para registrar `EliNerModule`s vía `EliNerCore` | — |

## Por qué `RuntimeState`/`RuntimeContext`/`ServiceRegistry`/`ModuleLoader` viven aquí y no en `eliner.runtime`

No fue la decisión original — se movieron aquí durante la Fase 2.5 al
detectar (y corregir) un ciclo real de paquetes entre `eliner.api` y
`eliner.runtime`. El resultado es, de hecho, un límite más correcto:
`eliner.api` es "el contrato + su vocabulario de datos" (entidades e
interfaces, en términos de Clean Architecture); `eliner.runtime` es
"la implementación que satisface ese contrato". Ver
[`docs/adr/0006-runtime-foundation.md`](../../../../../../../../../docs/adr/0006-runtime-foundation.md).

## Regla de dependencia

`eliner.api` puede depender de cualquier capa inferior (`core`,
`diagnostics`, `events`, `configuration`, `resources`, `services`) —  es la
capa más alta después de la UI, así que eso es exactamente lo esperado.
Ninguna capa inferior depende de `eliner.api` — verificado con análisis de
grafo de imports antes de cada entrega.
