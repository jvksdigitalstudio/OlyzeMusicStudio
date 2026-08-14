# EliNer — Recovery (Recovery System)

**Responsabilidad:** recuperación de proyectos ante fallos — backups
automáticos periódicos, snapshots de estado, recuperación tras un crash de
la app o del proceso de audio.

**Objetivo:** que un crash o cierre inesperado nunca implique perder el
trabajo del usuario en un proyecto `.oms`.

**Futuro uso:** autoguardado en segundo plano, historial de snapshots
recuperables, detección de cierre anómalo en el siguiente arranque.

**Dependencias:** trabaja junto a `modules/project` (qué guardar) y
`resources` (dónde guardarlo). Escucha `events` para detectar condiciones
de fallo.

**Estado actual:** no existe implementación todavía. Carpeta vacía a
propósito — únicamente reserva el espacio en la arquitectura.
