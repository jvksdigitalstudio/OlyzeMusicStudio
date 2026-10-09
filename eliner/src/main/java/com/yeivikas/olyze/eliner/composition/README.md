# EliNer — Composición (`eliner.composition`)

**Responsabilidad:** ensamblar el motor. Es la única capa autorizada a
conocer *todas* las demás: construye hilos, bus de eventos, logger, reloj,
perfiles de rendimiento, el despachador de audio, la MIDI Foundation y los
puentes, y los entrega ya cableados como un [`EliNerEngine`](../api/EliNerEngine.kt).

| Clase | Visibilidad | Rol |
|---|---|---|
| `EliNerEngineFactory` | pública | Único punto de construcción: `EliNerEngineFactory.create(application)` |
| `DefaultEliNerEngine` | `internal` | Implementación: orden de arranque (asíncrono, con timeout de 5 s y estado observable) y de apagado (MIDI antes que audio) |

**Regla:** nada fuera de esta capa construye piezas internas del motor.
`:app` solo ve `eliner.api.*` y `EliNerEngineFactory`. Una app nueva (p. ej.
Olyze Movie Creator) obtiene el ensamblado correcto sin copiarlo.

**Límite conocido:** la instancia la sigue creando el `ViewModel` de la app,
no una subclase de `Application` por proceso. Pasar a `Application` es un
cambio de una línea en `:app` (más el manifiesto); no requiere tocar EliNer.
Ver ADR 0014 y ADR 0027.
