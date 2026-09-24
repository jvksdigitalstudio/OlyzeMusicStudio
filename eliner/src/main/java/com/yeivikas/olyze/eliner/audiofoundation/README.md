# EliNer — Audio Foundation (Fase 3 de EliNer Engine — nuevo paquete)

**Responsabilidad:** infraestructura administrativa alrededor del audio —
sesión, dispositivos, backend, formato, sample rate, buffer, reloj,
latencia, ruteo, canales. **Ningún archivo de este paquete reproduce,
procesa o abre realmente un stream de audio.**

**No confundir con** [`eliner.modules.audio`](../modules/audio/README.md)
— ese sigue siendo el futuro "Audio Engine" real (síntesis, DSP,
procesamiento). Este paquete es la capa que existe *alrededor* de eso, no
el motor en sí.

## Archivos

| Archivo | Responsabilidad | Depende de |
|---|---|---|
| [`AudioSessionManager.kt`](AudioSessionManager.kt) | Ciclo de vida de sesión (`AudioSessionState`, 7 estados) | `EventBus` (concreto, misma excepción documentada que `RuntimeContext`) |
| [`AudioDeviceManager.kt`](AudioDeviceManager.kt) | Enumeración real de dispositivos vía `AudioManager` | `android.content.Context` (sistema, no UI) |
| [`AudioBackendManager.kt`](AudioBackendManager.kt) | Recomendación Oboe/AAudio/OpenSL ES según SDK + capacidades | `DeviceCapabilities` (dato, Fase 2) |
| [`AudioFormatManager.kt`](AudioFormatManager.kt) | Formatos de entrada/procesamiento/exportación + validación | Nada propio |
| [`SampleRateManager.kt`](SampleRateManager.kt) | Sample rate actual, sembrado desde el dispositivo real | `CapabilityProvider` (interfaz, Fase 2) |
| [`BufferManager.kt`](BufferManager.kt) | Tamaño de buffer derivado del perfil de rendimiento | `CapabilityProvider` + `PerformanceProfileProvider` (interfaces, Fase 2) |
| [`AudioClock.kt`](AudioClock.kt) | Reloj único basado en frames, no en wall-clock | `SampleRateProvider` (interfaz, este paquete) |
| [`LatencyManager.kt`](LatencyManager.kt) | Reporte de latencia estimada por perfil | `CapabilityProvider` + `PerformanceProfileProvider` |
| [`AudioRouting.kt`](AudioRouting.kt) | Grafo de ruteo (nodos + conexiones) — sin procesamiento | Nada propio |
| [`AudioChannelConfiguration.kt`](AudioChannelConfiguration.kt) | Mono/Stereo hoy, preparado para multicanal | Nada propio |
| [`AudioFoundationContext.kt`](AudioFoundationContext.kt) | Agregador — construido a partir de un `RuntimeContext` existente | `eliner.api.RuntimeContext` |

## Por qué `AudioFoundationContext` recibe un `RuntimeContext` en vez de construir sus propias referencias

Esto es la respuesta concreta a "Audio Capability Integration... la
infraestructura de audio deberá obtener toda la información mediante
interfaces. Nunca depender directamente de implementaciones concretas."
`AudioFoundationContext` no crea un segundo `CapabilityProvider` ni un
segundo `PerformanceProfileProvider` — reutiliza exactamente las mismas
instancias que ya vive dentro del `RuntimeContext` que `EliNerRuntime`
(Fase 2.5) ya construyó. Esto evita tanto duplicación como el riesgo de
que dos referencias al "mismo" servicio se desincronicen.

## Sobre el uso de `StateMachine<S>` (Core, Fase 3)

`AudioSessionManager` es la primera clase que usa el nuevo
`com.yeivikas.olyze.eliner.core.StateMachine<S>` genérico, extraído en
esta fase para no triplicar el patrón de `EngineState`/`RuntimeState`. Ver
`docs/adr/0007-audio-foundation.md` para el detalle completo, incluyendo
por qué `EliNerCore`/`LifecycleManager` (Fase 1/2.5) **no** se
retrofitearon a usarlo.

## Estado actual

11 componentes reales, ninguno decorativo. Cero Android UI (solo
`Context`/`AudioManager`, sistema, no UI). Cero dependencias circulares —
verificado con análisis de grafo de imports antes de cada entrega.
