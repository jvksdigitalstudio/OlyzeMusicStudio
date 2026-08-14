# EliNer — Modules / Adaptive Plugin System

**Responsabilidad:** carga, gestión y ejecución de plugins/instrumentos
adicionales dentro de EliNer.

**Objetivo:** permitir extender el motor con nuevos generadores/efectos sin
modificar el core — cumpliendo el principio arquitectónico obligatorio de
módulos reemplazables e independientes.

**Futuro uso:** instrumentos virtuales adicionales (ver Roadmap del
proyecto); eventualmente, un posible host de formatos externos.

**Dependencias:** se comunicará con `dsp`, `audio` y `events` a través de
interfaces — nunca con acceso directo a memoria/estado interno del motor.

**Estado actual:** no existe implementación todavía. Esta carpeta es
intencionalmente la más vacía de todas: es el punto de extensión a más
largo plazo del proyecto. Explícitamente fuera de alcance en esta fase
(ver regla 17: no implementar VST Host, Plugin Host ni plugins).
