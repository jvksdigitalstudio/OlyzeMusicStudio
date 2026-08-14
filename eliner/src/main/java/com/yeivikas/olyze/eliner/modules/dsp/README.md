# EliNer — Modules / DSP Engine

**Responsabilidad:** procesamiento digital de señal — síntesis (osciladores,
envolventes, filtros), efectos (reverb, delay) y, a futuro, procesamiento
avanzado (EQ paramétrico, compresión, saturación, convolución).

**Objetivo:** aislar todo el procesamiento matemático de señal en un solo
lugar, independiente de cómo entra o sale el audio del dispositivo.

**Futuro uso:** el Mixer Engine y el Audio Engine invocan este módulo para
procesar buffers; el Plugin System (a futuro) se apoyará en las mismas
interfaces de procesamiento.

**Dependencias:** ninguna hacia otros módulos de más alto nivel. Puede ser
usado por `mixer`, `render` y, más adelante, por el Plugin System.

**Estado actual:** ya existe una implementación real del lado nativo, dentro
del propio módulo `:eliner`:
- Síntesis — headers: `eliner/include/eliner/dsp/` (Oscillator, Envelope,
  Filter, SynthVoice); implementación: `eliner/src/main/cpp/dsp/`
- Efectos — headers: `eliner/include/eliner/fx/` (Reverb, Delay);
  implementación: `eliner/src/main/cpp/fx/`

Esta carpeta Kotlin es el punto de extensión reservado para lógica DSP
futura del lado Kotlin/JVM (por ejemplo, generación/edición de presets). No
se implementa DSP nuevo en esta fase.

**No confundir con `eliner.dspfoundation`** (Fase 5 de EliNer Engine —
"DSP Foundation"): esa es la infraestructura administrativa alrededor del
DSP (contrato de procesador, grafo, cadena, buses, parámetros, scheduler)
— no implementa ningún algoritmo. Esta carpeta (`modules/dsp`) sigue
siendo el futuro "DSP Engine" real, todavía sin implementar del lado
Kotlin.

