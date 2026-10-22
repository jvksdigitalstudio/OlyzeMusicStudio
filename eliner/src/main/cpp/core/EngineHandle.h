#pragma once
// Acceso compartido a la instancia global del motor, para TODOS los puentes
// JNI (EliNerAudioBridge, EliNerTransportBridge…).
//
// Antes de ADR 0028 estas dos variables eran `static` dentro de
// EliNerAudioBridge.cpp, lo que obligaba a meter todo el JNI en un solo
// archivo. Se extraen aquí, SIN cambiar su semántica, para que cada dominio
// (audio, transporte, futuros) tenga su propio puente JNI y su propia clase
// Kotlin sin duplicar el estado ni el candado.
//
// Contrato (idéntico al documentado en EliNerAudioBridge.cpp, Fase 1 —
// Objetivo G): `gEngineMutex` serializa TODO acceso —lectura y escritura— a
// `gEngine`. Cada función JNI toma el candado, comprueba el puntero y llama
// al motor. Solo corre en hilos de control (UI, eliner-dsp), nunca en el hilo
// de audio, que no toca este puntero: Oboe invoca el callback directamente
// sobre el AudioEngine. Por eso el candado es realtime-safe por construcción.
//
// Consecuencia útil para la cola de comandos SPSC: como el candado serializa
// a todos los productores (aunque vengan de hilos distintos), nunca hay dos
// productores simultáneos sobre la cola.

#include <memory>
#include <mutex>
#include "AudioEngine.h"

namespace eliner::jni {

extern std::mutex                   gEngineMutex;
extern std::unique_ptr<AudioEngine> gEngine;

} // namespace eliner::jni
