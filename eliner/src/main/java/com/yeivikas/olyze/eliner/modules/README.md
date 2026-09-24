# EliNer — Modules

**Responsabilidad:** contenedor de los módulos independientes de EliNer.
Cada subcarpeta es un módulo con responsabilidad única.

**Objetivo:** que cada motor (audio, DSP, MIDI, mixer, timeline, render,
proyecto, plugins) pueda añadirse, modificarse o eliminarse sin romper el
resto del proyecto, comunicándose solo a través de `eliner.api` /
`eliner.interfaces` y `eliner.events`.

**Dependencias:** varían por módulo, documentadas individualmente. Ningún
módulo debe importar directamente a otro módulo hermano.

**Estado actual:** todos los submódulos están en fase de preparación
arquitectónica — documentación y carpetas listas, sin lógica implementada,
salvo donde se indica explícitamente que ya existe una base nativa previa.

| Módulo | Carpeta | Estado |
|---|---|---|
| Audio Engine | [`audio/`](audio/README.md) | Base real ya existe — nativo, en `eliner/include/eliner/core` + `eliner/src/main/cpp/core` |
| DSP Engine | [`dsp/`](dsp/README.md) | Base real ya existe — nativo, en `eliner/include/eliner/dsp` + `eliner/src/main/cpp/dsp`, y `eliner/include/eliner/fx` + `eliner/src/main/cpp/fx` |
| MIDI Engine | [`midi/`](midi/README.md) | Base real ya existe, fuera de `:eliner` por ahora (`com.yeivikas.olyze.midi`, dentro de `:app`) |
| Mixer Engine | [`mixer/`](mixer/README.md) | Base real ya existe — nativo, en `eliner/include/eliner/mixer` |
| Timeline Engine | [`timeline/`](timeline/README.md) | No implementado |
| Render Engine | [`render/`](render/README.md) | No implementado |
| Project System | [`project/`](project/README.md) | No implementado |
| Adaptive Plugin System | [`plugin/`](plugin/README.md) | No implementado |

Nota: **Resource Manager** y **Hardware Layer** no viven dentro de
`modules/` — son transversales a varios módulos, por eso están como
carpetas hermanas: [`eliner/resources`](../resources/README.md) y
[`eliner/hardware`](../hardware/README.md) (paquetes Kotlin dentro de
`:eliner`).
