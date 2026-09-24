# Formato de proyecto `.oms` — borrador conceptual

> **Estado: NO implementado.** Este documento es solo una referencia
> conceptual para cuando se diseñe el Project System real. No define un
> formato binario/serializado final, ni compromete decisiones técnicas
> (JSON vs binario, compresión, versión de esquema, etc.) — eso corresponde
> a una fase de implementación futura.

## Propósito

`Proyecto.oms` será el formato de archivo nativo de Olyze Music Studio,
análogo al `.flp` de FL Studio o el `.als` de Ableton: un único archivo (o
paquete) que representa toda una sesión de producción.

## Contenido previsto (conceptual, no final)

| Sección | Contenido previsto |
|---|---|
| Audio | Referencias a samples de audio usados en el proyecto |
| Samples | Metadata de samples (no el audio en sí — ver Resource Manager) |
| Record | Grabaciones de audio/MIDI hechas dentro del proyecto |
| Plugins | Estado/configuración de plugins usados (cuando exista Plugin System) |
| Instruments | Instrumentos virtuales y su configuración |
| Presets | Presets guardados de instrumentos/efectos |
| Automation | Curvas de automatización de parámetros |
| Project | Metadata general: tempo, compás, estructura del Timeline |
| Metadata | Nombre, autor, fecha, versión de la app que lo creó |
| Cache | Datos regenerables (waveforms, previews) — no crítico, puede omitirse en backups livianos |
| Snapshots | Puntos de recuperación para el Recovery System |

## Relación con otros módulos EliNer

- **Project System** (`eliner/modules/project`) — único módulo autorizado a
  leer/escribir `.oms`.
- **Resource Manager** (`eliner/resources`) — resuelve las referencias a
  samples/presets que el `.oms` almacena solo como rutas/IDs, no como datos
  binarios embebidos (para mantener el archivo liviano).
- **Recovery System** (`eliner/recovery`) — usa la sección `Snapshots` para
  guardar puntos de recuperación automáticos.

## Explícitamente fuera de alcance en esta fase

- Formato binario/JSON final.
- Versionado de esquema y migración entre versiones.
- Compresión/empaquetado.
- Cualquier código de serialización.
