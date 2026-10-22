# ADR 0004 — EliNer Core Foundation: sin clases stub por módulo futuro

**Estado:** Aceptado, aplicado.

**Contexto:** la especificación de "EliNer Core Foundation" pide que el
Core esté preparado para registrar en el futuro 12 módulos (Audio, DSP,
MIDI, Mixer, Timeline, Render, Project, Resource Manager, Configuration,
Diagnostics, Recovery, Plugin System) sin implementar ninguno todavía.

**Decisión:** el `ModuleRegistry` no conoce ni menciona ninguno de esos 12
nombres. Solo trabaja con la interfaz genérica `EliNerModule`. Cualquier
clase futura que implemente esa interfaz puede registrarse, sin que
`ModuleRegistry` ni `EliNerCore` necesiten cambiar.

**Alternativa descartada:** crear una clase "stub" vacía por cada uno de
los 12 módulos (`AudioEngineModule`, `DspEngineModule`, ...) implementando
`EliNerModule` con cuerpos vacíos, "para dejarlos preparados". Se descartó
explícitamente — son archivos sin responsabilidad real, que existirían
solo "por si acaso", exactamente el patrón que se pidió evitar. El
mecanismo genérico (interfaz + registro) ya resuelve "preparar el registro
para 12 módulos futuros" sin necesitar 12 archivos vacíos.

**Consecuencias:** el Core Foundation de esta fase son 7 archivos Kotlin,
cada uno mapeado 1 a 1 a una responsabilidad explícita del prompt de la
fase (ciclo de vida, registro, versión, información, errores, contrato de
módulo, fachada) — ninguno especulativo. Cuando se implemente el primer
módulo real, se sabrá si la interfaz `EliNerModule` necesita crecer (por
ejemplo, hooks async) — no se adivinó esa necesidad de antemano.
