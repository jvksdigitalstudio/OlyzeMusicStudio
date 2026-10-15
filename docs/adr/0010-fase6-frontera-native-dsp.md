# ADR 0010 — Fase 6: frontera Native DSP y por qué el stack Kotlin AudioEngine/DspFoundation queda marcado como no-conectado

**Estado:** Aceptado y CERRADO. Decisión final del usuario: opción C —
mantener `modules.audio`/`dspfoundation` (Fases 4-5) exactamente como
están, documentados y sin conectar. No se retira código ni se fuerza una
integración sin build verificable. Ver "Decisión final" al fondo de este
documento.

**Contexto:** la auditoría de Fase 6 (Sección 1/20) confirmó dos motores
DSP que coexisten sin integrarse:

1. **Motor real** — 100% C++ nativo (`AudioEngine.cpp`, `SynthVoice`,
   `Reverb`, `Delay`), invocado desde `MainViewModel` vía
   `EliNerAudioBridge` (JNI). Este es el único camino que produce audio.
2. **Andamiaje Kotlin** — `eliner.modules.audio.AudioEngine`,
   `eliner.dspfoundation.DspFoundation`, `AudioEngineApi`, `DspApi`. Su
   propio KDoc ya declaraba "No DSP — nothing here processes a single
   sample". No está en la ruta de ejecución del audio real.

## Decisión

Por ahora, **(2) no se elimina ni se reescribe para delegar en (1)**.
Reescribir todo el árbol de llamadas de `MainViewModel` para que pase por
`AudioEngineApi → DspApi` en vez de `EliNerAudioBridge` directo, y hacer
que esas interfaces Kotlin de verdad reenvíen al motor nativo vía JNI, es
un cambio multi-archivo en Kotlin que esta fase no pudo verificar
compilando (sin acceso a red no se pudo ejecutar `./gradlew`). Cambiar esa
cantidad de superficie sin poder compilarla viola la Sección 33 del
prompt ("no digas 'compila' si no se ejecutó el build").

En su lugar, esta fase:

- Corrigió lo que sí se pudo verificar sin red: el motor nativo
  (mutex, command queue, sample rate, sharing mode — ver `AudioEngine.cpp`),
  con un test real de concurrencia para la cola lock-free.
- Deja documentada, explícita y sin ambigüedad, cuál capa es la autoridad
  real (`EliNerAudioBridge` + `AudioEngine.cpp`) y cuál es andamiaje sin
  conectar (`modules.audio.AudioEngine`, `dspfoundation.DspFoundation`).

## Confirmación encontrada en ARCHITECTURE.md

Esta no es una ambigüedad introducida por la Fase 6. El propio
`ARCHITECTURE.md` del proyecto, en la sección de la Fase 4, ya decía
explícitamente: "El motor nativo (Oboe, `eliner.bridge.EliNerAudioBridge`)
sigue exactamente igual desde antes de EliNer Engine — `AudioEngine` no lo
conoce todavía; conectarlos es trabajo de una fase futura de integración
de backend real." Es decir: el stack Kotlin de las Fases 4-5 fue
construido a propósito como infraestructura para un backend futuro, no
como el motor real de hoy. Esta ADR no descubre el problema — confirma que
la Fase 6 respetó el plan ya documentado.

## Pendiente (próxima fase, no ejecutado aquí)

Dos caminos válidos, a decidir con build real disponible:

- **A. Conectar**: hacer que `DspApi`/`AudioEngineApi` reenvíen a
  `EliNerAudioBridge`, y mover `MainViewModel` a depender de esas
  interfaces en vez del bridge directo. Requiere compilar y probar en
  dispositivo.
- **B. Retirar**: si el plan de producto no necesita una capa de control
  Kotlin sobre el motor nativo, remover `modules.audio.AudioEngine` y
  `dspfoundation.DspFoundation` en vez de mantenerlos sin uso.

No se eligió A ni B unilateralmente en esta fase porque ambas requieren
compilar para confirmar que no rompen nada — y esta fase no tuvo esa
capacidad. Ver informe de Fase 6 para el detalle.

## Decisión final (usuario, cierre de Fase 6)

Se evaluaron las tres opciones con el usuario directamente:

- **A. Conectar** — descartada: requiere reescribir múltiples archivos
  Kotlin sin poder verificar que compilen en este entorno.
- **B. Retirar** — descartada: borraría trabajo real de las Fases 4-5 sin
  necesidad; no le hace daño a nada quedándose donde está.
- **C. Dejarlo documentado, sin tocar código** — **elegida**. Es la que
  ya describía este ADR desde el principio como el estado real del
  proyecto; el usuario la confirmó explícitamente como decisión final.

No hay trabajo de código pendiente por esta decisión. Si en el futuro se
retoma un backend Kotlin real, este ADR y la sección "Fase 6" de
`ARCHITECTURE.md` son el punto de partida.
