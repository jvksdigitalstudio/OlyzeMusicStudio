// Test del Objetivo F (auditoría Fase 1): "start() → requestStart failure
// → cleanup → start() nuevamente" no debe dejar recursos huérfanos ni
// impedir un reintento limpio.
//
// A DIFERENCIA de los tests de CommandQueue.h (test_command_queue_*.cpp),
// este test NO puede compilarse ni ejecutarse en este entorno: depende de
// AudioEngine.h/.cpp, que a su vez dependen de Oboe (oboe::AudioStream,
// oboe::AudioStreamBuilder) y del NDK de Android — ninguno de los dos está
// disponible aquí (sin red para descargar el NDK/Oboe, sin `cmake`
// instalado). Ver docs/adr/<esta fase> para la limitación de entorno
// documentada explícitamente, tal como exige la sección 16/19H del
// prompt de esta fase: "Nunca inventes una verificación."
//
// Este archivo SÍ es código real, pensado para integrarse al build NDK
// real (vía CMakeLists.txt, cuando el proyecto añada un target de test
// nativo — hoy no existe ninguno, ver informe de la sección M/Build) y
// ejecutarse ahí. Usa Oboe real (no un mock), porque el bug que corrige
// (stream huérfano) solo es observable con el objeto real de Oboe.
//
// EJECUTADO: NO — requiere NDK + Oboe, no disponibles en este entorno.
#include "AudioEngine.h"
#include <cassert>
#include <cstdio>

using eliner::AudioEngine;

// requestStart() puede fallar en un dispositivo real por múltiples
// motivos (audio en uso exclusivo por otra app, hardware desconectado,
// etc.) — en un entorno de test sin dispositivo de audio real (headless/
// CI), `oboe::AudioStreamBuilder::openStream()` normalmente también
// falla (no hay HAL de audio), lo cual ya ejercita una rama de cleanup
// relacionada (el fallback Exclusive->Shared, y si ambos fallan, el
// return false temprano) — pero NO ejercita específicamente el path de
// requestStart() fallando DESPUÉS de un openStream() exitoso, que es el
// bug que este fix corrige. Para eso, este test necesita ejecutarse en
// un dispositivo/emulador Android real con un HAL de audio disponible
// (o inyectar un stream fake vía un test double de Oboe — Oboe no ofrece
// uno oficial; ver "Riesgos restantes" del informe final sobre esta
// limitación de testabilidad).
int main() {
    std::fprintf(stdout, "== test_audio_engine_request_start_failure_cleanup ==\n");
    std::fprintf(stdout, "[SKIP] Requiere un dispositivo/emulador Android con HAL de "
        "audio real para ejercer el path de requestStart() fallando tras un "
        "openStream() exitoso — no ejecutable en este entorno (sin NDK/Oboe/dispositivo). "
        "Ver comentario de este archivo y la sección de limitaciones del informe final.\n");

    // Pseudo-secuencia que SÍ debe ejecutarse cuando este test corra en un
    // entorno con NDK+dispositivo real (dejado documentado, no ejecutado):
    //
    //   AudioEngine engine;
    //   bool first = engine.start(/*profile=*/0);
    //   // Forzar el fallo de requestStart() requiere o bien un dispositivo
    //   // en un estado que lo provoque (audio ya en uso exclusivo por otra
    //   // app), o instrumentar Oboe con un AudioStreamBuilder que inyecte
    //   // un stream cuyo requestStart() devuelva != OK — Oboe no expone
    //   // ese hook públicamente hoy, así que en la práctica este escenario
    //   // se reproduce hoy solo manualmente en dispositivo (ver informe).
    //   //
    //   // Lo que SÍ se puede afirmar por inspección del código (ver el fix
    //   // en AudioEngine.cpp): tras el fallo, engine.start() llama a
    //   // stop() internamente, lo cual dejará mStream == nullptr,
    //   // mIsRunning == false, mDspReady == false, mFxChain vacío — el
    //   // mismo estado que tras un stop() normal. Un segundo
    //   // engine.start(profile) inmediatamente después debe poder abrir
    //   // un nuevo stream sin ningún estado residual del intento fallido.
    //
    //   bool second = engine.start(/*profile=*/0);
    //   assert(second); // en un dispositivo sano, el segundo intento debe
    //                    // poder tener éxito si el primero falló por un
    //                    // motivo transitorio.

    return 0; // SKIP, no PASS — ver nota EJECUTADO: NO arriba.
}
