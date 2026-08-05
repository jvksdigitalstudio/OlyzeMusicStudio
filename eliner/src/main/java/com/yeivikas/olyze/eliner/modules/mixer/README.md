# EliNer — Modules / Mixer Engine

**Responsabilidad:** mezcla multicanal — niveles, pan, sends de efectos,
buses, salida master.

**Objetivo:** ser el punto único donde las señales de múltiples voces/pistas
se combinan antes de llegar al Audio Engine.

**Futuro uso:** base para la futura UI de mezclador multicanal (ver Roadmap
en el README raíz del proyecto).

**Dependencias:** consume `dsp` (efectos por canal) y entrega su salida al
`audio` (Audio Engine).

**Estado actual:** ya existe una implementación real y mínima del lado
nativo en `eliner/include/eliner/mixer/Mixer.h` (mezcla estéreo simple usada
por el motor actual — header-only, sin `.cpp` propio). Esta carpeta Kotlin
queda reservada para la lógica de UI/estado del futuro mezclador
multicanal. No se implementa lógica nueva en esta fase.
