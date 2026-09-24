# EliNer — Services (Fase 2 — nuevo paquete)

**Por qué existe este paquete:** de los 10 servicios pedidos en la Fase 2,
4 ya tenían carpeta designada desde la Fase 3 (Logger → `diagnostics`,
Event Bus → `events`, Configuration → `configuration`, Resource Manager →
`resources`). Los otros 5 — Thread Manager, Task Scheduler, Time Service,
Device Capability Manager, Performance Profile Manager — son
infraestructura transversal que ningún paquete anterior anticipaba. En vez
de forzarlos dentro de una carpeta que no les correspondía, se creó
`eliner.services` como su hogar natural.

## Archivos

| Archivo | Servicio | Depende de |
|---|---|---|
| [`ThreadManager.kt`](ThreadManager.kt) | Thread Manager | Nada propio — solo `kotlinx.coroutines` |
| [`TaskScheduler.kt`](TaskScheduler.kt) | Task Scheduler | `TaskExecutor` (interfaz, no `ThreadManager` directo) |
| [`TimeService.kt`](TimeService.kt) | Time Service | Nada propio |
| [`DeviceCapabilityManager.kt`](DeviceCapabilityManager.kt) | Device Capability Manager | `android.content.Context` + APIs de sistema (no UI) |
| [`PerformanceProfileManager.kt`](PerformanceProfileManager.kt) | Performance Profile Manager | `CapabilityProvider` (interfaz, no `DeviceCapabilityManager` directo) |
| [`PerformanceStrategy.kt`](PerformanceStrategy.kt) | (Fase 2.5) Estrategias de `PerformanceProfileManager` | Nada propio |

**Fase 2.5 — interfaces agregadas:** `TimeProvider` (implementada por
`TimeService`) y `PerformanceProfileProvider` (implementada por
`PerformanceProfileManager`), para que `eliner.api.RuntimeContext` dependa
de contratos. `TaskExecutor` (ya existía) se amplió con `shutdown()`,
porque `EliNerRuntime` necesita apagar los hilos del motor durante su
propio apagado, no solo obtener scopes.

**Fase 2.5 — Performance Strategy:** `PerformanceProfileManager.
recommendedProfile()` dejó de ser un `when` en línea y pasó a evaluar una
lista de `PerformanceStrategy` (`CompatibilityStrategy`, `UltraStrategy`,
`AutomaticStrategy`, `CustomStrategy`) — mismo comportamiento por defecto,
pero ahora extensible sin modificar la clase (ver `PerformanceStrategy.kt`).


## Las dos excepciones documentadas a "ningún servicio depende de otro"

`TaskScheduler` depende de `TaskExecutor` y `PerformanceProfileManager` de
`CapabilityProvider` — ambas son **interfaces**, no las clases concretas.
Se consideran cumplimiento de la regla, no excepciones a ella: la regla
dice "mediante interfaces", y eso es exactamente lo que se hizo. La
relación en sí (Scheduler necesita un ejecutor; el Performance Manager
necesita saber las capacidades del dispositivo) está pedida explícitamente
por el propio prompt de esta fase, no es un atajo inventado.

## Sobre `DeviceCapabilityManager` y `Context`

Es el único archivo de esta fase que usa `android.content.Context`. Esto
**no** viola la regla de "cero dependencia de Android UI" — esa regla
prohíbe explícitamente `Activity`/`Fragment`/`View`/Compose/Material/XML,
y `Context` (junto con `ActivityManager`, `PackageManager`, `AudioManager`,
que son servicios de sistema, no UI) no está en esa lista. Detectar CPU,
RAM, OpenGL ES, Vulkan, soporte de audio de baja latencia, USB Host y
Bluetooth LE requiere genuinamente estas APIs — no hay forma de hacerlo
sin ellas.

**Simplificaciones deliberadas y documentadas** (ver comentarios en el
código): "soporte USB Audio" se aproxima con `FEATURE_USB_HOST` (el
dispositivo puede actuar como host USB — precondición necesaria, no
verificación completa de clase de audio USB). "Soporte Bluetooth MIDI" se
aproxima con `FEATURE_BLUETOOTH_LE` (precondición de BLE-MIDI). Verificación
real y completa de esos dos es trabajo de Hardware Layer / MIDI Engine,
cuando existan.

## Estado actual

Los 5 servicios son código real y funcional, no stubs. `ThreadManager` crea
hilos reales (nombrados, daemon, con shutdown ordenado). `PerformanceProfileManager.
recommendedProfile()` tiene lógica heurística real, documentada como
punto de partida no calibrado (no hay carga DSP real todavía contra la
cual calibrar).
