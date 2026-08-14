# ADR 0006 — Runtime Foundation: EliNerRuntime, y un ciclo real detectado y corregido

**Estado:** Aceptado, aplicado.

**Contexto:** la Fase 2.5 pide un Runtime que orqueste Core Foundation
(Fase 1) y los Foundation Services (Fase 2), con su propia API pública
(`EliNerRuntimeApi`) siguiendo el mismo patrón que `EliNerAudioApi`.

## Decisión principal: `EliNerRuntime` como composition root

Nuevo paquete `eliner.runtime` con `EliNerRuntime` (implementación),
`LifecycleManager` (dueño de `RuntimeState`), y `RuntimeEvents.kt`
(`RuntimeStateChangedEvent`, el único evento nuevo — se evitó a propósito
crear una taxonomía grande).

`RuntimeState` es un enum **distinto** de `EngineState` (Fase 1), con su
propia tabla de transiciones — representan cosas distintas: `EngineState`
es solo Core; `RuntimeState` es Core + Services + módulos, visto desde
`EliNerRuntime`.

## Refactors a Fase 2 (autorizados explícitamente por el usuario para esta fase)

Se agregaron 6 interfaces delgadas a servicios de Fase 2 que no las tenían,
para que `RuntimeContext` pudiera depender de contratos, nunca de clases
concretas (regla explícita de esta fase): `Logger` (LoggerService),
`Configuration` (ConfigurationService), `Resources` (ResourceManager),
`TimeProvider` (TimeService), `PerformanceProfileProvider`
(PerformanceProfileManager). `TaskExecutor` (ya existía desde Fase 2) se
amplió con `shutdown()`, porque `EliNerRuntime` necesita apagar los hilos
del motor, no solo obtener scopes. Ningún método cambió de comportamiento
— son extracciones mecánicas de interfaz, cero riesgo de romper Fase 2
(nada fuera de estos archivos dependía todavía de las clases concretas).

**Excepción documentada:** `RuntimeContext.events` es la clase concreta
`EventBus`, no una interfaz. `EventBus.subscribe<T>()` es una función
`inline`/`reified`, que Kotlin no puede expresar en una interfaz sin perder
la ergonomía genérica — es un límite real del lenguaje, no un atajo.

**Performance Strategy:** `PerformanceProfileManager.recommendedProfile()`
se refactorizó de un `when` en línea a una lista de `PerformanceStrategy`
(`CompatibilityStrategy`, `UltraStrategy`, `AutomaticStrategy`,
`CustomStrategy`), tal como pide explícitamente esta fase. Mismo API
público, mismo comportamiento por defecto — el cambio es puramente interno.

## El error real: ciclo `api` ↔ `runtime`

Durante la auditoría interna que esta misma fase exige ("✔ No existen
dependencias circulares"), se detectó un ciclo real: `EliNerRuntimeApi`
(en `eliner.api`) referenciaba tipos concretos de `eliner.runtime`
(`RuntimeState`, `RuntimeContext`, `ServiceRegistry`, `ModuleLoader`) para
tipar su propia interfaz, mientras que `EliNerRuntime` (en `eliner.runtime`)
necesitaba importar `EliNerRuntimeApi` para implementarla. Dos paquetes
importándose mutuamente.

A diferencia de un ciclo entre módulos Gradle (que falla duro en tiempo de
build), un ciclo de paquetes dentro del mismo módulo **sí compila** en
Kotlin/JVM — por eso hacía falta la verificación explícita por grafo, no
alcanzaba con "si compila, está bien".

**Corrección:** se movieron `RuntimeState`, `RuntimeContext`,
`ServiceRegistry` y `ModuleLoader` físicamente a `eliner.api` — pasan a ser
parte del "vocabulario público" del contrato, junto a la interfaz que los
expone. `eliner.runtime` quedó como implementación pura, dependiendo de
`eliner.api` en una sola dirección. Grafo final verificado sin ciclos de
ningún tamaño.

**Por qué esto es una mejora real, no un parche:** el resultado final es
más limpio que el diseño original — `eliner.api` ahora contiene tanto los
contratos (interfaces) como los tipos de datos que fluyen a través de
ellos (`RuntimeState`, `RuntimeContext`), mientras que `eliner.runtime`
contiene únicamente la implementación que satisface esos contratos. Es el
mismo patrón "entidades e interfaces hacia afuera, implementación hacia
adentro" que Clean Architecture pide, aplicado consistentemente.

## Consecuencias

`ModuleLoader` (ahora en `eliner.api`) depende de `EliNerCore` (concreto,
sin interfaz) y de `Logger` (interfaz). Se decidió no crear una interfaz
extra sobre `EliNerCore` — ya es la fachada pública que Core Foundation
ofrece por diseño desde la Fase 1; envolverla en otra interfaz sin una
segunda implementación real sería la complejidad innecesaria que esta
fase prohíbe explícitamente.
