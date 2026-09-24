# EliNer — Modules / Timeline Engine

**Responsabilidad:** representación temporal del proyecto — patrones,
secuenciador, playlist, posición de reproducción/grabación, quantización.

**Objetivo:** desacoplar "qué suena y cuándo" (Timeline) de "cómo suena"
(DSP/Mixer) y de "cómo se guarda" (Project System).

**Futuro uso:** soporte para el Piano Roll, la Playlist/Secuenciador y la
grabación MIDI/audio listados en el Roadmap del proyecto.

**Dependencias:** orquesta llamadas hacia `midi`, `audio` y `mixer` según la
posición temporal; es consumido por el Project System al guardar/cargar.

**Estado actual:** no existe implementación todavía, ni siquiera parcial.
Carpeta vacía a propósito — únicamente reserva el espacio en la arquitectura.
