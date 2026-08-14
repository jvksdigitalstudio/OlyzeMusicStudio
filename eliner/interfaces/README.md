# EliNer — interfaces/ (contratos C++, nativo)

**Responsabilidad:** clases base abstractas (interfaces C++, típicamente
`class IFoo { public: virtual ~IFoo() = default; virtual ... = 0; };`) que
definirán los contratos entre módulos nativos del motor — por ejemplo, un
futuro `IDspProcessor` que tanto `Reverb` como `Delay` implementarían, para
que `AudioEngine` los procese de forma polimórfica sin conocer el tipo
concreto.

**No confundir con** [`eliner.interfaces`](../src/main/java/com/yeivikas/olyze/eliner/interfaces/README.md)
(paquete Kotlin) — esa carpeta define contratos del lado Kotlin/JVM entre
módulos Kotlin; esta define contratos del lado C++ entre módulos nativos.
Son capas de interfaz paralelas, una por lenguaje, ambas cumpliendo el
mismo principio arquitectónico obligatorio (módulos independientes,
comunicación vía interfaces).

**Objetivo:** hoy `AudioEngine.h` conoce directamente los tipos concretos
`Reverb`, `Delay`, `Mixer`, `SynthVoice` (ver
`include/eliner/core/AudioEngine.h`). Eso es aceptable para el motor actual
(pequeño, estable), pero si el DSP Engine crece con más efectos/plugins,
esta carpeta es donde se definirán las interfaces que permitan
desacoplarlos sin modificar `AudioEngine`.

**Futuro uso:** `IDspProcessor`, `IAudioSource`, `IMidiSink`, u otros
contratos nativos, según lo que necesite cada módulo cuando se implemente.

**Estado actual:** no existe ninguna interfaz nativa todavía — el motor
actual es pequeño y su acoplamiento directo (`AudioEngine` → `Reverb`/
`Delay`/`Mixer` concretos) es aceptable en este tamaño. No se introduce
abstracción especulativa sin necesidad real (evitar over-engineering).
Carpeta vacía a propósito.
