# EliNer — Interfaces (contratos Kotlin, comunicación interna)

**Responsabilidad:** contratos (interfaces Kotlin) que definen cómo se
comunican entre sí los distintos módulos *internos* de EliNer — a
diferencia de `eliner.api`, que define el contrato hacia la UI (`:app`).

**No confundir con** [`eliner/interfaces/`](../../../../../../../../interfaces/README.md)
(carpeta nativa, en la raíz del módulo) — esa define contratos C++ entre
componentes nativos; esta define contratos Kotlin entre módulos Kotlin.

**Objetivo:** que la comunicación interna entre módulos (por ejemplo, un
futuro Mixer Engine pidiéndole un buffer procesado al DSP Engine, del lado
Kotlin) también pase por interfaces, no por referencias directas a clases
concretas de otro módulo.

**Futuro uso:** contratos como `AudioModuleContract`, `MidiModuleContract`,
`ResourceProviderContract`, a medida que cada módulo de `eliner.modules` se
implemente del lado Kotlin.

**Dependencias:** ninguna — es, junto con `eliner.api` y `eliner.events`,
una de las carpetas más independientes de todo EliNer.

**Estado actual:** el primer contrato del proyecto ya existe y sirve como
plantilla de referencia: [`eliner.api.EliNerAudioApi`](../api/EliNerAudioApi.kt)
(contrato hacia la UI, no interno — pero mismo patrón a replicar). Esta
carpeta permanece vacía hasta que exista un segundo módulo Kotlin real que
necesite comunicarse con otro.
