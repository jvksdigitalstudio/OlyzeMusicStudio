# ADR 0001 — Migración de identidad: Jvk's Studio Mobile → Olyze Music Studio

**Estado:** Aceptado, aplicado.

**Contexto:** el proyecto nació como "Jvk's Studio Mobile" (paquete
`com.jvk.studio`). Se decidió relanzarlo bajo la marca YeiViKas Digital
Studio con el nombre "Olyze Music Studio" y el motor/API "EliNer".

**Decisión:** renombrar `applicationId`/`namespace` a `com.yeivikas.olyze`,
tema, biblioteca nativa, namespace C++, funciones JNI, artefactos de CI y
toda referencia de marca — sin dejar restos del nombre anterior.

**Consecuencias:** el repositorio se entregó sin historial de Git previo
(decisión del usuario, no técnica) para evitar arrastrar commits con el
nombre/remote anterior.
