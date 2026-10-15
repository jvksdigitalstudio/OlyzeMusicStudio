# ADR 0002 — `minSdk` 24 y C++20 como preparación de plataforma

**Estado:** Aceptado, aplicado.

**Contexto:** el proyecto declaraba `minSdk = 26` y C++17 sin una razón
documentada — ningún código requería realmente API 26.

**Decisión:**
- Bajar `minSdk` a **24** (Android 7.0), verificado contra el uso real de
  `@RequiresApi(Build.VERSION_CODES.M)` (API 23) en el código — 24 es
  compatible. `ANDROID_PLATFORM` del NDK sincronizado a `android-24` para
  evitar que el `.so` nativo exija una API más alta que la declarada en
  Gradle (causa clásica de crashes silenciosos en dispositivos viejos).
- Subir el estándar C++ de C++17 a **C++20**, como preparación para el
  desarrollo futuro del motor EliNer, sin usar todavía ninguna
  característica específica de C++20.

**Consecuencias:** ampliar el `minSdk` soportado (más dispositivos
elegibles) sin agregar código nuevo. El cambio de estándar C++ no pudo
verificarse compilando en el entorno de esta migración — se marcó como
punto a vigilar en el primer build de CI.
