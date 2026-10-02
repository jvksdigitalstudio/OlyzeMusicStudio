# ADR 0007 — Audio Foundation: StateMachine genérico y reutilización de RuntimeContext

**Estado:** Aceptado, aplicado.

**Contexto:** la Fase 3 de EliNer Engine pide 11 componentes de
infraestructura de audio (sesión, dispositivos, backend, formato, sample
rate, buffer, reloj, latencia, ruteo, canales), todos integrados con Core,
Runtime, Foundation Services, API, Bridge y Event Bus vía interfaces.

## Decisión 1: `StateMachine<S>` genérico en `eliner.core`

`AudioSessionManager` necesitaba una tercera máquina de estados
(`AudioSessionState`, 7 estados) — ya existían dos implementaciones casi
idénticas: `EliNerCore`'s `transitionTo` privado (Fase 1, `EngineState`) y
`LifecycleManager` (Fase 2.5, `RuntimeState`). Escribir una tercera copia
habría sido exactamente el "código duplicado" que esta misma fase pide
verificar en su auditoría.

Se extrajo `com.yeivikas.olyze.eliner.core.StateMachine<S>` — genérico,
recibe la función de validación de transiciones por constructor. Vive en
`eliner.core` porque es la única capa de la que todo lo demás ya puede
depender sin crear ciclos.

**Decisión explícita de NO retrofitear `EliNerCore`/`LifecycleManager`**
para usar esta nueva clase. Ambos son código ya compilado y verificado en
CI de fases anteriores. La regla #2 de esta misma fase ("no romper ninguna
arquitectura implementada anteriormente") pesa más que la consolidación
estilística — tocar una máquina de estados que ya funciona, sin poder
recompilar para reverificarla, es peor trade-off que dejar dos
implementaciones pequeñas e independientes tal como están. El código
nuevo (`AudioSessionManager`, y cualquier máquina de estados futura) usa
`StateMachine<S>` desde ya.

## Decisión 2: `AudioFoundationContext` se construye desde un `RuntimeContext` existente

En vez de que cada manager de audio reciba su propia copia de
`CapabilityProvider`/`PerformanceProfileProvider`, `AudioFoundationContext`
recibe el `RuntimeContext` que `EliNerRuntime` ya construyó (Fase 2.5) y
reutiliza exactamente esas instancias. Esto es la implementación concreta
de "Audio Capability Integration... nunca depender de implementaciones
concretas" — y además evita que dos referencias al "mismo" servicio
pudieran desincronizarse si fueran instancias separadas.

## Decisión 3: paquete `eliner.audiofoundation`, no `eliner.audio`

Ya existe `eliner.modules.audio` (placeholder documentado desde la Fase
2/3 del proyecto, para el futuro Audio Engine real). Usar `eliner.audio`
para esta fase habría creado ambigüedad de nombres — se usó
`eliner.audiofoundation`, y se actualizó el README de `modules/audio` para
señalar la distinción explícitamente (mismo tipo de aclaración ya hecha
antes entre `eliner.interfaces` y la carpeta nativa `eliner/interfaces/`).

## Consecuencias

12 archivos Kotlin nuevos (11 de Audio Foundation + `StateMachine.kt`).
Grafo de dependencias verificado sin ciclos: `audiofoundation` depende de
`api`, `core`, `events`, `services` — nunca al revés. Cero Android UI —
solo `Context`/`AudioManager` (APIs de sistema, ya usadas con el mismo
criterio en `DeviceCapabilityManager`, Fase 2).
