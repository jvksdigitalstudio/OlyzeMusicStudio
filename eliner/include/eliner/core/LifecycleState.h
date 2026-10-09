#pragma once
// Estado formal del ciclo de vida de AudioEngine.
//
// Se separa de AudioEngine.h: es un vocabulario (enum + sus transiciones
// válidas) que los consumidores pueden necesitar sin conocer la clase del motor.

#include <cstdint>

namespace eliner {

// ── Lifecycle FSM formal (Fase 1 de estabilización — Objetivo C) ───────────
// Antes de esta fase, el estado del motor vivía disperso en dos booleans
// independientes (mIsRunning, mDspReady) sin una política única y
// explícita de transiciones. Este enum formaliza ese comportamiento —
// deliberadamente ADITIVO: no reemplaza mIsRunning/mDspReady (que siguen
// existiendo, con su semántica exacta de antes, para no arriesgar romper
// código que ya depende de ellos sin poder recompilar y verificar en este
// entorno — ver limitaciones de build de esta fase). mLifecycleState es
// la fuente de verdad NUEVA y más granular, actualizada en los mismos
// puntos donde mIsRunning/mDspReady ya se actualizaban, bajo el mismo
// mLifecycleMutex que ahora serializa start()/stop()/reopenStream().
//
// Transiciones válidas (todas bajo mLifecycleMutex — ver startLocked()/
// stopLocked()/reopenStream() en el .cpp):
//   STOPPED    -> STARTING    (start() invocado)
//   STARTING   -> RUNNING     (requestStart() == OK)
//   STARTING   -> FAILED      (requestStart() != OK — PERSISTE, ver nota
//                               de semántica más abajo; stopLocked() se
//                               ejecuta igual para el cleanup de recursos,
//                               pero ya NO sobrescribe este valor)
//   STARTING   -> STOPPED     (openStream() != OK — nunca hubo nada que
//                               limpiar, sin pasar por FAILED: esta rama
//                               nunca llegó a construir el DSP graph ni
//                               abrir el stream, así que no hay una
//                               condición de "fallo tras haber arrancado
//                               algo" que valga la pena distinguir de un
//                               STOPPED simple)
//   RUNNING    -> STOPPING -> STOPPED   (stop() invocado)
//   RUNNING    -> RECOVERING  (onErrorAfterClose() marca el inicio del
//                               ciclo — luego sigue el mismo camino
//                               normal de cualquier stop()+start(): pasa
//                               por STOPPING/STOPPED y termina en RUNNING
//                               si startLocked() tiene éxito, o en FAILED
//                               (persistente) si no — RECOVERING es un
//                               marcador de "quién disparó este ciclo",
//                               no un estado con su propia transición de
//                               salida directa)
//   FAILED     -> STARTING    (ÚNICA salida de FAILED — un start()
//                               explícito, del hilo de control;
//                               reopenStream() nunca reintenta solo tras
//                               un FAILED, ver onErrorAfterClose())
//
// Semántica de FAILED — decisión explícita (Fase 1, cierre, sección 8):
// FAILED es un estado OBSERVABLE PERSISTENTE, no transitorio. Antes de
// esta decisión, stopLocked() sobrescribía Failed con Stopped de forma
// incondicional, en la misma región bajo lock donde Failed se había
// escrito microsegundos antes — ningún observador externo podía verlo
// jamás. Ahora: si stopLocked() es invocada con el motor ya en Failed
// (el path de cleanup tras un fallo de requestStart(), dentro de
// startLocked()), el estado queda en Failed tras el cleanup, no en
// Stopped — útil para que un futuro diagnóstico distinga "el usuario
// detuvo el motor" de "el último intento de arrancar falló". La ÚNICA
// forma de salir de Failed es un start() explícito (que ya lo lleva a
// Starting incondicionalmente, sin importar el estado previo).
//
// DESTROYED no es un valor de este enum: lo representa gEngine == nullptr
// en EliNerAudioBridge.cpp (fuera de este objeto) — decisión explícita,
// no una omisión. Razón: DESTROYED es un concepto de OWNERSHIP (¿existe
// la instancia de AudioEngine en absoluto?), no de estado interno de una
// instancia que sí existe — modelarlo como un valor más de este enum
// implicaría que un AudioEngine podría "estar" en estado DESTROYED y
// seguir respondiendo a getLifecycleState(), lo cual es contradictorio
// (un objeto ya destruido no puede ejecutar ningún método sobre sí
// mismo). El propio unique_ptr (gEngine == nullptr) ya modela esto
// correctamente sin necesitar un valor de enum redundante.
enum class LifecycleState : uint8_t {
    Stopped = 0,
    Starting,
    Running,
    Stopping,
    Recovering,
    Failed,
};

} // namespace eliner
