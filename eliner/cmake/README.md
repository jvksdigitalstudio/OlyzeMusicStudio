# EliNer — cmake/ (helpers de CMake, reservado)

**Responsabilidad:** alojar scripts `.cmake` auxiliares (find-modules,
toolchains, funciones reutilizables) cuando el build del motor los
necesite — separados del `CMakeLists.txt` principal.

**Por qué está vacío:** `CMakeLists.txt` permanece en la raíz de
`eliner/` (convención estándar de CMake para el archivo principal de un
proyecto/target), no dentro de esta carpeta. Esta carpeta es exclusivamente
para módulos `.cmake` *auxiliares* — hoy el build no necesita ninguno (solo
usa `FetchContent` para Oboe, ya incluido en `CMakeLists.txt`).

**Futuro uso:** por ejemplo, un `FindEliNerDeps.cmake` si el motor crece y
necesita localizar dependencias adicionales, o un toolchain file propio si
se necesita compilar fuera del entorno estándar del NDK.

**Estado actual:** carpeta vacía a propósito — se documenta la intención,
no se crea ningún archivo `.cmake` sin uso real (Fase 3: "no busco crear
carpetas vacías" se respeta documentando el motivo en vez de rellenar con
archivos decorativos).
