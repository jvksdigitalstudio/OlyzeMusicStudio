// TakeRecorder (ADR 0038): hilo de audio → anillo → hilo escritor → WAV, con hilos REALES y archivos REALES.
//   · contenido exacto con bloques de tamaños variables y recorte de latencia (frame 0 de la toma = posición de grabación),
//   · el recorte puede ser mayor que la toma, y se reparte entre varios bloques,
//   · end() es determinista: todo lo aceptado antes está en el archivo,
//   · anillo lleno: se descarta y se cuenta (aceptado + descartado = enviado), sin bloquear al productor,
//   · uso incorrecto: doble begin, end sin begin, ruta imposible, parámetros inválidos, onInput en reposo,
//   · reutilización (begin/end repetidos), destructor, end() mientras el hilo de audio sigue enviando (TSan).
#include <atomic>
#include <chrono>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

#include "TakeRecorder.h"
#include "wav_read.h"

using eliner::TakeRecorder;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
const std::string kDir = "/tmp/eliner_take_test_";

// Señal reconocible: el valor de cada muestra es su índice (exacto en float hasta 2^24).
std::vector<float> ramp(size_t frames, int channels, size_t firstIndex = 0) {
    std::vector<float> v(frames * static_cast<size_t>(channels));
    for (size_t f = 0; f < frames; ++f)
        for (int c = 0; c < channels; ++c) v[f * static_cast<size_t>(channels) + static_cast<size_t>(c)] = static_cast<float>(firstIndex + f) + 0.25f * static_cast<float>(c);
    return v;
}
bool isRamp(const std::vector<float>& s, int channels, size_t firstIndex) {
    for (size_t f = 0; f * static_cast<size_t>(channels) < s.size(); ++f)
        for (int c = 0; c < channels; ++c)
            if (s[f * static_cast<size_t>(channels) + static_cast<size_t>(c)] != static_cast<float>(firstIndex + f) + 0.25f * static_cast<float>(c)) return false;
    return true;
}
} // namespace

int main() {
    // ── 1. Toma mono con bloques de tamaño variable y recorte de latencia ──
    {
        const std::string p = kDir + "a.wav";
        TakeRecorder t;
        TakeRecorder::Config c; c.sampleRate = 48000; c.channels = 1; c.trimFrames = 960;
        check(t.begin(p.c_str(), c) && t.recording(), "begin: empieza a grabar");
        size_t sent = 0;
        for (size_t blk : {64u, 480u, 1u, 4096u, 333u, 10000u, 7u}) {
            const auto b = ramp(blk, 1, sent);
            t.onInput(b.data(), blk); sent += blk;
        }
        const auto r = t.end();
        check(r.ok && !t.recording(), "end: cierra bien y deja de grabar");
        check(r.trimmedFrames == 960 && r.framesWritten == sent - 960 && r.droppedFrames == 0, "recortó 960 y escribió todo lo demás, sin descartes");
        const WavData d = readWav(p);
        check(d.valid && d.frames() == sent - 960 && d.sampleRate == 48000 && d.channels == 1, "el archivo tiene los frames esperados");
        check(isRamp(d.samples, 1, 960), "el frame 0 de la toma es el 960 de la captura: contenido exacto y sin huecos");
        std::remove(p.c_str());
    }
    // ── 2. Estéreo sin recorte ──
    {
        const std::string p = kDir + "b.wav";
        TakeRecorder t; TakeRecorder::Config c; c.channels = 2;
        t.begin(p.c_str(), c);
        for (size_t i = 0; i < 50; ++i) { const auto b = ramp(100, 2, i * 100); t.onInput(b.data(), 100); }
        const auto r = t.end();
        const WavData d = readWav(p);
        check(r.ok && d.frames() == 5000 && isRamp(d.samples, 2, 0), "estéreo: 5000 frames, canales sin mezclar");
        std::remove(p.c_str());
    }
    // ── 3. El recorte supera a la toma ──
    {
        const std::string p = kDir + "c.wav";
        TakeRecorder t; TakeRecorder::Config c; c.trimFrames = 100000;
        t.begin(p.c_str(), c);
        const auto b = ramp(500, 1); t.onInput(b.data(), 500);
        const auto r = t.end();
        const WavData d = readWav(p);
        check(r.ok && r.framesWritten == 0 && r.trimmedFrames == 500 && d.valid && d.frames() == 0, "recorte mayor que la toma: archivo válido y vacío");
        std::remove(p.c_str());
    }
    // ── 4. El recorte se reparte entre varios bloques (más pequeños que el recorte) ──
    {
        const std::string p = kDir + "d.wav";
        TakeRecorder t; TakeRecorder::Config c; c.trimFrames = 1000;
        t.begin(p.c_str(), c);
        size_t sent = 0;
        for (int i = 0; i < 30; ++i) { const auto b = ramp(100, 1, sent); t.onInput(b.data(), 100); sent += 100; }
        const auto r = t.end(); const WavData d = readWav(p);
        check(r.ok && d.frames() == 2000 && isRamp(d.samples, 1, 1000), "recorte de 1000 repartido en 10 bloques de 100: el resto, exacto");
        std::remove(p.c_str());
    }
    // ── 5. end() es determinista: lo aceptado antes de end() está en el archivo ──
    {
        const std::string p = kDir + "e.wav";
        TakeRecorder t; TakeRecorder::Config c; c.ringFrames = 1 << 20;
        t.begin(p.c_str(), c);
        const auto b = ramp(100000, 1); t.onInput(b.data(), 100000);   // de golpe y sin dar tiempo al escritor
        const auto r = t.end();
        check(r.ok && r.framesWritten == 100000 && readWav(p).frames() == 100000, "100000 frames enviados justo antes de end(): todos en el archivo");
        std::remove(p.c_str());
    }
    // ── 6. Anillo lleno: se descarta y se cuenta, sin bloquear ──
    {
        const std::string p = kDir + "f.wav";
        TakeRecorder t; TakeRecorder::Config c; c.ringFrames = 64;
        t.begin(p.c_str(), c);
        const auto b = ramp(1000, 1);
        const auto t0 = std::chrono::steady_clock::now();
        t.onInput(b.data(), 1000);
        const auto dt = std::chrono::steady_clock::now() - t0;
        const auto r = t.end(); const WavData d = readWav(p);
        check(r.framesWritten + r.droppedFrames == 1000 && r.droppedFrames >= 936, "1000 frames en un anillo de 64: aceptado + descartado = 1000");
        check(isRamp(d.samples, 1, 0), "lo aceptado es el comienzo del bloque, en orden");
        check(dt < std::chrono::milliseconds(50), "onInput no se bloquea ni espera al escritor");
        std::remove(p.c_str());
    }
    // ── 7. Uso incorrecto ──
    {
        const std::string p = kDir + "g.wav";
        TakeRecorder t;
        check(!t.end().ok, "end sin begin: no ok");
        float x[4] = {1, 2, 3, 4};
        t.onInput(x, 4); check(!t.recording(), "onInput en reposo: no hace nada");
        TakeRecorder::Config bad; bad.channels = 0;
        check(!t.begin(p.c_str(), bad) && !t.recording(), "canales inválidos: begin falla");
        TakeRecorder::Config neg; neg.trimFrames = -5;
        check(!t.begin(p.c_str(), neg), "recorte negativo: begin falla");
        TakeRecorder::Config bad2; bad2.sampleRate = 0;
        check(!t.begin(p.c_str(), bad2), "frecuencia 0: begin falla");
        check(!t.begin("/no/existe/de/ninguna/manera/x.wav", TakeRecorder::Config{}) && !t.recording(), "ruta imposible: begin falla y no queda grabando");
        check(!t.begin(nullptr, TakeRecorder::Config{}), "ruta nula: begin falla");
        check(t.begin(p.c_str(), TakeRecorder::Config{}), "tras los fallos, una apertura válida funciona");
        check(!t.begin(p.c_str(), TakeRecorder::Config{}), "begin con una toma en curso: false");
        check(t.end().ok, "end");
        check(!t.end().ok, "segundo end: no ok (ya terminó)");
        std::remove(p.c_str());
    }
    // ── 8. Reutilización: varias tomas seguidas con el mismo objeto ──
    {
        TakeRecorder t;
        bool all = true;
        for (int k = 0; k < 5; ++k) {
            const std::string p = kDir + "h" + std::to_string(k) + ".wav";
            TakeRecorder::Config c; c.trimFrames = k * 10;
            all = all && t.begin(p.c_str(), c);
            const auto b = ramp(1000, 1, static_cast<size_t>(k) * 1000); t.onInput(b.data(), 1000);
            const auto r = t.end(); const WavData d = readWav(p);
            all = all && r.ok && d.frames() == 1000 - static_cast<size_t>(k) * 10 && isRamp(d.samples, 1, static_cast<size_t>(k) * 1000 + static_cast<size_t>(k) * 10);
            std::remove(p.c_str());
        }
        check(all, "5 tomas seguidas con el mismo objeto: cada una correcta y sin restos de la anterior");
    }
    // ── 9. El destructor termina la toma ──
    {
        const std::string p = kDir + "i.wav";
        { TakeRecorder t; t.begin(p.c_str(), TakeRecorder::Config{}); const auto b = ramp(300, 1); t.onInput(b.data(), 300); }
        const WavData d = readWav(p);
        check(d.valid && d.frames() == 300, "destruir el grabador con una toma abierta la cierra y deja un WAV completo");
        std::remove(p.c_str());
    }
    // ── 10. end() mientras el hilo de audio SIGUE enviando (bajo TSan: sin carreras ni pérdidas de lo aceptado) ──
    {
        const std::string p = kDir + "j.wav";
        TakeRecorder t; TakeRecorder::Config c; c.ringFrames = 1 << 16;
        t.begin(p.c_str(), c);
        std::atomic<bool> stop{false};
        std::thread audio([&] {
            const auto b = ramp(256, 1);
            while (!stop.load(std::memory_order_acquire)) { t.onInput(b.data(), 256); std::this_thread::yield(); }
        });
        std::this_thread::sleep_for(std::chrono::milliseconds(60));
        const auto r = t.end();                           // el hilo de audio no se ha detenido
        stop.store(true, std::memory_order_release);
        audio.join();
        const WavData d = readWav(p);
        check(r.ok && d.valid && d.frames() == r.framesWritten && d.factFrames == r.framesWritten,
              "end() con el audio aún enviando: archivo coherente con lo informado");
        check(r.framesWritten > 0 && (r.framesWritten + r.droppedFrames + r.trimmedFrames) % 256 == 0,
              "se grabó audio y la cuenta (escrito + descartado) son bloques completos: nada a medias tras end()");
        std::remove(p.c_str());
    }
    // ── 11. Muchas tomas cortas con el audio enviando sin parar ──
    {
        TakeRecorder t;
        std::atomic<bool> stop{false};
        std::thread audio([&] { const auto b = ramp(64, 1); while (!stop.load(std::memory_order_acquire)) t.onInput(b.data(), 64); });
        bool all = true;
        for (int k = 0; k < 20; ++k) {
            const std::string p = kDir + "k.wav";
            all = all && t.begin(p.c_str(), TakeRecorder::Config{});
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
            const auto r = t.end(); const WavData d = readWav(p);
            all = all && r.ok && d.valid && d.frames() == r.framesWritten;
        }
        stop.store(true, std::memory_order_release); audio.join();
        std::remove((kDir + "k.wav").c_str());
        check(all, "20 begin/end seguidos con el audio enviando sin parar: ningún archivo corrupto");
    }

    std::fprintf(stdout, "\n%s (%d fallo(s))\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
