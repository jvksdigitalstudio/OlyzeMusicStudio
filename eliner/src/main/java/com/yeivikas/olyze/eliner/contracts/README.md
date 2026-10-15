# EliNer — Contratos entre módulos (`eliner.contracts`)

**Responsabilidad:** contratos Kotlin que un módulo expone a otros módulos
*hermanos* sin que estos dependan de su implementación. Es un paquete
**hoja**: no importa nada de EliNer.

| Archivo | Contenido |
|---|---|
| [`DspApi.kt`](DspApi.kt) | Contrato del DSP Foundation (`DspApi`) |
| [`DspState.kt`](DspState.kt) | Estado del DSP Foundation |
| [`DspMetricsSnapshot.kt`](DspMetricsSnapshot.kt) | Métricas del DSP Foundation |

**Por qué existe (ADR 0027).** `AudioEngine` (`modules.audio`) acepta
opcionalmente un `DspApi`; `DspFoundation` (`dspfoundation`) lo implementa.
Si el contrato viviera en `dspfoundation`, `modules.audio` tendría que
importarlo y los dos módulos quedarían acoplados; si viviera en `eliner.api`,
el contrato de la *aplicación* se mezclaría con contratos internos del
kernel (la causa de las 13 violaciones de capa que motivaron el ADR). Un
paquete neutral deja que ambos dependan del contrato y no el uno del otro.

**Criterio de admisión.** Un contrato solo entra aquí si lo usan **dos o más
módulos hermanos**. Un contrato usado por un solo módulo vive con ese módulo
(p. ej. `AudioEngineApi` en `modules.audio`). Si un módulo futuro (Mixer,
Timeline…) necesita hablar con otro, su contrato nace aquí.

**Dependencias:** ninguna. Importan de aquí: `dspfoundation`,
`modules.audio` (matriz en `docs/eliner/ARCHITECTURE.md`).
