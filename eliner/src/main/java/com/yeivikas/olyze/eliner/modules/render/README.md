# EliNer — Modules / Render Engine

**Responsabilidad:** renderizado offline (bounce) del proyecto o de pistas
individuales a archivos de audio (WAV/otros formatos, a definir).

**Objetivo:** separar la reproducción en tiempo real (Audio Engine) del
procesamiento no-realtime necesario para exportar un proyecto completo.

**Futuro uso:** exportación de proyectos, bounce de pistas, mezcla final.

**Dependencias:** consume `dsp`, `mixer` y `timeline` para reconstruir el
proyecto completo fuera del hilo de audio en tiempo real.

**Estado actual:** no existe implementación todavía. Carpeta vacía a
propósito — únicamente reserva el espacio en la arquitectura. Ver también
Fase 9 / regla explícita de no implementar render profesional todavía.
