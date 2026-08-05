# ADR 0005 — Foundation Services: reutilizar carpetas existentes, un paquete nuevo, DIP en 2 puntos

**Estado:** Aceptado, aplicado.

**Contexto:** la Fase 2 de EliNer Engine pide implementar 10 servicios:
Logger, Event Bus, Configuration, Resource Manager, Thread Manager, Task
Scheduler, Time Service, Device Capability Manager, Performance Profile
Manager (+ consolidación de la API Bridge).

**Decisiones:**

1. **4 servicios reutilizan carpetas ya documentadas desde la Fase 3**
   (`diagnostics`, `events`, `configuration`, `resources`) en vez de crear
   una jerarquía paralela — esas carpetas ya predecían exactamente estos
   servicios en su documentación.
2. **Los otros 5 (sin carpeta previa) se agrupan en un paquete nuevo:
   `eliner.services`.**
3. **`TaskScheduler` depende de `TaskExecutor` (interfaz), no de
   `ThreadManager` directo. `PerformanceProfileManager` depende de
   `CapabilityProvider` (interfaz), no de `DeviceCapabilityManager`
   directo.** Ambas son las únicas dos dependencias entre servicios de toda
   la fase, y ambas pasan por una interfaz — cumpliendo la regla "toda
   comunicación mediante interfaces" en vez de violarla.
4. **`EliNerCore.errors`/`EliNerCore.state` no se conectan automáticamente
   a `LoggerService`/`EventBus` en esta fase.** Requeriría un
   `CoroutineScope` propio de una raíz de composición del motor que todavía
   no existe. Se documentó el punto de integración exacto
   (`LoggerService.log(EngineError)`, `EngineStateChangedEvent`) sin
   inventar el cableado automático.
5. **Ninguna clase stub por cada uno de los módulos futuros** que
   `ResourceManager`/`ModuleRegistry`/`EventBus` deberán soportar — se
   verificó de nuevo el mismo principio aplicado en Fase 1 (ADR 0004):
   contratos genéricos, no implementaciones vacías por dominio.

**Alternativa descartada:** hacer que `LoggerService`/`EventBus` se
suscriban automáticamente a `EliNerCore` en su propio constructor (más
"completo" a primera vista). Se descartó porque obligaría a inventar de
dónde sale el `CoroutineScope` de esa suscripción antes de que
`ThreadManager` (construido en esta misma fase) tuviera un lugar
establecido para vivir esa decisión — se prefirió dejar el punto de
integración explícito y sin cablear a adivinar la arquitectura de
composición final.

**Consecuencias:** 13 archivos Kotlin nuevos, cada uno mapeado 1 a 1 a un
servicio o tipo de dato explícito del prompt. `DeviceCapabilityManager` es
el único que introduce una dependencia real de `android.content.Context`
en `:eliner` — ver el riesgo declarado en `ARCHITECTURE.md`.
