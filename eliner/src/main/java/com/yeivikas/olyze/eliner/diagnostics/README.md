# EliNer — Diagnostics — Logger Service IMPLEMENTADO (Fase 2, ampliado en Fase 2.5)

**Responsabilidad:** registro centralizado de eventos del motor — 5 niveles
(`DEBUG`/`INFO`/`WARNING`/`ERROR`/`CRITICAL`), consumible de forma reactiva
(`Flow`) o mediante sinks imperativos.

**Archivo:** [`LoggerService.kt`](LoggerService.kt) — interfaz `Logger`,
`LogLevel`, `LogEntry`, `LogSink`, `LoggerService`, y
`LoggerService.log(EngineError)`.

**Fase 2.5:** se agregó la interfaz `Logger` (implementada por
`LoggerService`) para que `eliner.api.RuntimeContext` pueda depender del
contrato, no de la clase concreta — y para que `EliNerRuntime` registre
`Logger` en su `ServiceRegistry` por tipo, no por implementación.

**Integración real con Core Foundation:** `LoggerService.log(error:
EngineError)` mapea `EngineErrorSeverity` → `LogLevel`
(`WARNING`→`WARNING`, `ERROR`→`ERROR`, `FATAL`→`CRITICAL`). Desde la Fase
2.5, esto **sí** está conectado automáticamente: `EliNerRuntime` reenvía
`EliNerCore.errors` a `Logger.log(error)` mientras el motor está corriendo
(ver `eliner.runtime.EliNerRuntime`).

**Dependencias:** `eliner.core` (por `EngineError`/`EngineErrorSeverity` —
una capa inferior, no un servicio par). Cero Android, cero otros servicios.

**Subsistemas de la Fase 3 revisados contra lo implementado:**

| Subsistema (documentado en Fase 3) | Estado real ahora |
|---|---|
| **Logger** | ✅ Implementado — `LoggerService`, integrado con `EliNerRuntime` |
| **Crash** | No implementado |
| **Performance** | No implementado |
| **Reports** | No implementado |
| **Future Debug** | No implementado, sigue siendo especulativo |

**Estado actual:** `LoggerService` es real y funcional, y ahora recibe
automáticamente los errores del motor mientras corre. La futura pantalla
"Logs / Registro de errores" se construirá consumiendo `LoggerService.
entries` — no se implementa esa UI en esta fase.
