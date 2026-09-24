# EliNer — Modules / Project System

**Responsabilidad:** ciclo de vida del proyecto del usuario — crear, abrir,
guardar, cerrar; serialización a disco.

**Objetivo:** ser el único módulo que lee/escribe el futuro formato de
proyecto `.oms` (ver especificación preliminar en
`eliner/documentation/PROJECT_FORMAT_OMS.md`).

**Futuro uso:** guardar/cargar todo el estado de una sesión — timeline,
mezcla, presets, automatización, metadata.

**Dependencias:** coordina con `timeline`, `mixer`, `resources` (para
samples/presets referenciados) y `recovery` (para snapshots/backups).

**Estado actual:** no existe implementación todavía. Carpeta vacía a
propósito — el formato `.oms` en sí tampoco está implementado, solo
documentado como referencia futura.
