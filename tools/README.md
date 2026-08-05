# tools/ — reservado

Espacio reservado para scripts/herramientas de desarrollo a nivel de
proyecto (por ejemplo: generación de código, validación de convenciones de
nombres entre módulos, scripts de release).

**Por qué está vacío:** ningún script real es necesario todavía — el
proyecto se compila con `./gradlew` directamente y el único "tooling"
existente hoy es el workflow de CI (`.github/workflows/build.yml`), que no
pertenece aquí porque GitHub Actions requiere que viva en `.github/`.

No se crean archivos placeholder sin uso ("no busco crear carpetas
vacías" — Fase 3). Esta carpeta se documenta ahora para que, cuando
aparezca la primera necesidad real de un script de proyecto, ya exista un
lugar obvio y acordado donde ponerlo, en vez de decidirlo ad-hoc.
