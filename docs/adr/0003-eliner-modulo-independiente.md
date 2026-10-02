# ADR 0003 — EliNer como módulo Gradle independiente (`:eliner`)

**Estado:** Aceptado, aplicado. Esta es la decisión arquitectónica más
importante hasta ahora en el proyecto.

**Contexto:** hasta la Fase 2, EliNer era un paquete Kotlin dentro de
`:app` y su motor nativo vivía en `app/src/main/cpp/`. Esto significaba que
EliNer, en la práctica, **era** parte del binario de Olyze Music Studio —
no podía reutilizarse en otra app (por ejemplo, la futura Olyze Movie
Creator) sin copiar y pegar código, violando el objetivo explícito de que
EliNer sea un motor reutilizable.

**Decisión:** crear un módulo Gradle Android library `:eliner`, físicamente
separado de `:app`:
- Todo el paquete Kotlin `com.yeivikas.olyze.eliner.*` se movió de
  `app/src/main/java/...` a `eliner/src/main/java/...`.
- Todo el motor nativo se movió de `app/src/main/cpp/` a `eliner/`, con una
  separación adicional entre headers públicos (`eliner/include/eliner/`) e
  implementación (`eliner/src/main/cpp/`).
- `eliner/build.gradle.kts` no depende de Compose ni de ningún artefacto
  específico de `:app` — su única dependencia externa es
  `kotlinx-coroutines-android`.
- `app/build.gradle.kts` agrega `implementation(project(":eliner"))` y
  elimina toda su configuración nativa/CMake (ya no le pertenece).

**Alternativas consideradas:**
- *Mantener todo en un solo módulo, solo con buena disciplina de paquetes*
  — descartada: sin un límite de módulo Gradle real, nada impide en el
  futuro que un archivo de UI importe accidentalmente una clase interna del
  motor; el límite de módulo lo hace imposible en tiempo de compilación,
  no solo por convención.
- *Renombrar el paquete de `com.yeivikas.olyze.eliner` a algo
  independiente de "olyze" (ej. `com.yeivikas.eliner`)* — evaluada como
  "más correcta" a largo plazo para reutilización entre apps, pero
  **descartada por ahora**: es un tercer rename de paquete en el mismo
  proyecto, alto riesgo (rompe nombres JNI, imports) para un beneficio que
  hoy es solo cosmético (el límite real de reutilización ya lo da el
  módulo Gradle, no el nombre del paquete). Queda documentada como mejora
  futura recomendada, no aplicada.

**Consecuencias:**
- `:app` → `:eliner` es ahora una dependencia verificable por Gradle, no
  solo una intención documentada.
- El primer build en CI tras este cambio es la validación real — no se
  pudo compilar en el entorno de esta migración (sin SDK/NDK/red). Ver
  riesgo declarado en `eliner/documentation/ARCHITECTURE.md`.
