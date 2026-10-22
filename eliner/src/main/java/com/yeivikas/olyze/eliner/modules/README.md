# EliNer — Modules

**Responsabilidad:** contenedor de los módulos de EliNer que ya existen
como código. Cada subcarpeta es un módulo con responsabilidad única.

**Regla:** un módulo no importa a otro módulo hermano. Se comunican solo a
través de `eliner.api` y `eliner.events`. Las dependencias permitidas de
cada paquete están en la matriz de capas de
[`docs/eliner/ARCHITECTURE.md`](../../../../../../../../../docs/eliner/ARCHITECTURE.md#estructura-vigente-adr-0027),
que hace cumplir `tools/verify_architecture.py` en CI.

| Módulo | Carpeta | Estado |
|---|---|---|
| Audio | [`audio/`](audio/README.md) | Implementado — módulo del kernel Kotlin (`AudioEngine`, `AudioEngineApi`). El camino de audio real de la app es nativo (`bridge` → C++) y no pasa por aquí: ver ADR 0010 |
| MIDI | [`midi/`](midi/README.md) | Implementado — MIDI Foundation (backend Android, router, reloj, cola de eventos, bindings) |

Los módulos previstos que **no** existen todavía (DSP de alto nivel, Mixer,
Timeline, Render, Project System, Plugin System) y las capas transversales
previstas (Hardware, Recovery, contratos entre módulos) están descritos en
[`docs/eliner/ROADMAP_MODULES.md`](../../../../../../../../../docs/eliner/ROADMAP_MODULES.md).
No tienen directorio hasta que tengan código.

El **Resource Manager** no vive dentro de `modules/`: es transversal y está
en [`eliner/resources`](../resources/README.md).
