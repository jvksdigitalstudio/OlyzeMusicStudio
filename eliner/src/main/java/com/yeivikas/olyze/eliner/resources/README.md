# EliNer — Resources — Resource Manager IMPLEMENTADO (Fase 2, ampliado en Fase 2.5; solo contratos)

**Responsabilidad:** localizar dónde vive un recurso (`Samples/`,
`Instruments/`, `Plugins/`, `Presets/`, `Automation/`, `Record/`, `MIDI/`,
`Metadata/`) — **no** cargar sus bytes. Eso es explícitamente trabajo
futuro ("no implementar todavía carga de audio").

**Archivos:**
- [`ResourceTypes.kt`](ResourceTypes.kt) — `ResourceCategory` (las 8
  categorías exactas del prompt, ninguna inventada), `ResourceId`,
  `ResourceLocation`.
- [`ResourceManager.kt`](ResourceManager.kt) — interfaz `Resources`,
  `ResourceProvider` (interfaz, sin implementaciones todavía),
  `ResourceManager` (registro + resolución, mismo patrón que
  `ModuleRegistry` de `eliner.core`).

**Fase 2.5:** se agregó la interfaz `Resources` — deliberadamente solo
expone `locate()`, no el registro de providers (eso sigue siendo exclusivo
de la clase concreta `ResourceManager`, porque solo la raíz de composición
debería registrar providers, no cualquier consumidor con acceso a
`RuntimeContext`).

**Qué SÍ hace `locate()`:** resuelve un `ResourceId` a un
`ResourceLocation` (un `uri: String` — la ubicación, no el contenido).
**Qué NO hace:** abrir el archivo, leer bytes, decodificar audio, cachear
nada.

**Dependencias:** ninguna — cero Android, cero otros servicios.

**Estado actual:** arquitectura y contratos reales y funcionales. Cero
`ResourceProvider` implementado todavía — eso es, literalmente, "carga de
audio", fuera de alcance.
