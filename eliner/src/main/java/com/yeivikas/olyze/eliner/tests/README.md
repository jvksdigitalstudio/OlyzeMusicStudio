# EliNer — Tests (estrategia, no código)

Esta carpeta **no contiene tests** — Gradle exige que vivan en los source
sets estándar del módulo `:eliner`:

- Unitarios (JVM): `eliner/src/test/java/com/yeivikas/olyze/eliner/...`
- Instrumentados (dispositivo/emulador): `eliner/src/androidTest/java/com/yeivikas/olyze/eliner/...`

**Responsabilidad de esta carpeta:** documentar la estrategia de testing
por categoría, para que quede decidida de antemano.

**Categorías previstas:**

| Categoría | Tipo de test | Ubicación futura |
|---|---|---|
| **Unit Tests** | Lógica pura Kotlin, contratos de `eliner.api`/`eliner.interfaces` con fakes | `eliner/src/test` |
| **Integration Tests** | Kotlin ↔ JNI ↔ nativo end-to-end (requiere `.so` cargado) | `eliner/src/androidTest` |
| **Performance Tests** | Latencia, uso de CPU/RAM del motor de audio | `eliner/src/androidTest` (Macrobenchmark, a evaluar) |
| **Stress Tests** | Polifonía máxima sostenida, buffers al límite | `eliner/src/androidTest` |
| **Regression Tests** | Snapshot de comportamiento conocido antes de cada release | Por definir (podría vivir en `:app` si involucra UI) |

**Estado actual:** no hay tests de EliNer todavía, ni carpetas creadas en
`test/`/`androidTest/` dentro de `:eliner` — se documenta la estrategia,
no se implementa.
