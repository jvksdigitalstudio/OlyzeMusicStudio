# EliNer — Configuration — Configuration Service IMPLEMENTADO (Fase 2, ampliado en Fase 2.5)

**Responsabilidad:** configuración interna centralizada del motor —
mecanismo genérico, no valores específicos de un dominio todavía.

**Archivos:**
- [`ConfigValue.kt`](ConfigValue.kt) — 4 variantes tipadas (`Int`, `Float`,
  `Boolean`, `String`) — suficiente para "buffers, calidad DSP, perfiles"
  mencionados en el prompt, sin inventar estructuras anidadas sin uso real.
- [`ConfigurationService.kt`](ConfigurationService.kt) — interfaz
  `Configuration`, `ConfigurationStore` (interfaz de almacenamiento),
  `InMemoryConfigurationStore` (única implementación hoy), `ConfigChange`,
  `ConfigurationService`.

**Fase 2.5:** se agregó la interfaz `Configuration` (implementada por
`ConfigurationService`) por la misma razón que `Logger` en diagnostics —
para que `RuntimeContext` dependa del contrato, no de la clase concreta.

**Por qué `ConfigurationStore` es una interfaz aparte de `Configuration`:**
son dos contratos distintos a propósito. `Configuration` es lo que
consumen los módulos (get/set tipado). `ConfigurationStore` es de dónde
`ConfigurationService` obtiene esos valores (memoria hoy, persistencia
futura). Ningún consumidor de `Configuration` necesita saber que
`ConfigurationStore` existe.

**No se definieron claves todavía** (ej. `"audio.bufferSize"`,
`"dsp.quality"`) — esas pertenecen a cada módulo cuando se implemente.

**Dependencias:** ninguna — cero Android, cero otros servicios, cero
`eliner.core`.

**Estado actual:** mecanismo real y funcional (get/set tipado + stream de
cambios). Dominios previstos (Audio/Graphics/Performance/Plugins/Project/UI,
documentados en la Fase 3) siguen siendo un mapa conceptual de qué claves
existirán, no código.
