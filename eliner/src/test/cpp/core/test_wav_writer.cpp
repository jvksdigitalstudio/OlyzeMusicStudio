// WavWriter (ADR 0038): WAV float32 en streaming. Se comprueba el archivo REAL leyéndolo de vuelta.
//   · encabezado correcto (RIFF/fmt/fact/data, IEEE float, tamaños reescritos al cerrar),
//   · las muestras se conservan bit a bit (incluidos valores extremos, subnormales y negativos),
//   · estéreo y bloques de tamaños variables, archivo vacío,
//   · uso incorrecto: parámetros inválidos, doble apertura, escribir sin abrir/tras cerrar, ruta imposible,
//   · cierre idempotente y destructor que cierra.
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "WavWriter.h"
#include "wav_read.h"

using eliner::WavWriter;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
const std::string kDir = "/tmp/eliner_wav_test_";
bool sameBits(float a, float b) { return std::memcmp(&a, &b, 4) == 0; }
} // namespace

int main() {
    // ── Un archivo mono con bloques de tamaños distintos ──
    {
        const std::string p = kDir + "mono.wav";
        WavWriter w;
        check(w.open(p.c_str(), 44100, 1) && w.isOpen(), "abre un archivo mono a 44,1 kHz");
        std::vector<float> all;
        float seed = 0.0f;
        for (int blk : {1, 7, 512, 3, 1000}) {
            std::vector<float> b(static_cast<size_t>(blk));
            for (auto& s : b) { s = std::sin(seed) ; seed += 0.37f; all.push_back(s); }
            check(w.write(b.data(), b.size()), "escribe un bloque");
        }
        check(w.framesWritten() == all.size(), "framesWritten cuenta todos los frames");
        check(w.close() && !w.isOpen(), "cierra sin errores");
        const WavData d = readWav(p);
        check(d.valid && d.formatTag == 3 && d.bits == 32 && d.channels == 1 && d.sampleRate == 44100, "encabezado: IEEE float, 32 bits, mono, 44100");
        check(d.factFrames == all.size() && d.dataBytes == all.size() * 4, "fact y data reescritos al cerrar");
        check(d.riffSize == 56 - 8 + d.dataBytes, "tamaño RIFF coherente");
        check(d.fileBytes == 56 + all.size() * 4, "el archivo mide encabezado + datos");
        bool same = d.samples.size() == all.size();
        for (size_t i = 0; same && i < all.size(); ++i) same = sameBits(d.samples[i], all[i]);
        check(same, "las muestras se conservan bit a bit");
        std::remove(p.c_str());
    }
    // ── Valores extremos ──
    {
        const std::string p = kDir + "extreme.wav";
        WavWriter w; w.open(p.c_str(), 48000, 1);
        const float v[] = {0.0f, -0.0f, 1.0f, -1.0f, 3.4e38f, -3.4e38f, 1e-45f, 0.5f, -0.25f};
        w.write(v, sizeof(v) / sizeof(v[0])); w.close();
        const WavData d = readWav(p);
        bool same = d.samples.size() == sizeof(v) / sizeof(v[0]);
        for (size_t i = 0; same && i < d.samples.size(); ++i) same = sameBits(d.samples[i], v[i]);
        check(same, "ceros con signo, ±1, extremos y subnormales se guardan idénticos");
        std::remove(p.c_str());
    }
    // ── Estéreo ──
    {
        const std::string p = kDir + "stereo.wav";
        WavWriter w; w.open(p.c_str(), 96000, 2);
        std::vector<float> b; for (int i = 0; i < 200; ++i) { b.push_back(static_cast<float>(i)); b.push_back(-static_cast<float>(i)); }
        w.write(b.data(), 200); w.close();
        const WavData d = readWav(p);
        check(d.valid && d.channels == 2 && d.sampleRate == 96000 && d.frames() == 200, "estéreo a 96 kHz: 200 frames");
        check(d.samples[2] == 1.0f && d.samples[3] == -1.0f && d.samples[398] == 199.0f && d.samples[399] == -199.0f, "los canales no se cruzan");
        check(d.dataBytes == 200 * 2 * 4, "bytes de datos = frames × canales × 4");
        std::remove(p.c_str());
    }
    // ── Vacío ──
    {
        const std::string p = kDir + "empty.wav";
        WavWriter w; w.open(p.c_str(), 48000, 1);
        check(w.close(), "un archivo sin datos se cierra bien");
        const WavData d = readWav(p);
        check(d.valid && d.frames() == 0 && d.dataBytes == 0 && d.factFrames == 0 && d.fileBytes == 56, "…y es un WAV válido de 0 frames");
        std::remove(p.c_str());
    }
    // ── Uso incorrecto ──
    {
        const std::string p = kDir + "bad.wav";
        WavWriter w;
        float x = 0.5f;
        check(!w.write(&x, 1), "escribir sin abrir: false");
        check(!w.open(nullptr, 48000, 1) && !w.open(p.c_str(), 0, 1) && !w.open(p.c_str(), -1, 1)
                  && !w.open(p.c_str(), 48000, 0) && !w.open(p.c_str(), 48000, 9), "parámetros inválidos: false");
        check(!w.open("/no/existe/de/ninguna/manera/x.wav", 48000, 1) && !w.isOpen(), "ruta imposible: false y sigue cerrado");
        check(w.open(p.c_str(), 48000, 1), "una apertura válida funciona tras los fallos");
        check(!w.open(p.c_str(), 48000, 1), "abrir con uno ya abierto: false");
        check(w.write(&x, 1) && w.write(nullptr, 0), "escribir 0 frames siempre es válido");
        check(!w.write(nullptr, 5), "datos nulos con frames > 0: false");
        check(w.close(), "cierra");
        check(w.close(), "cerrar dos veces devuelve el resultado del último cierre (true)");
        check(!w.write(&x, 1), "escribir tras cerrar: false");
        check(w.open(p.c_str(), 22050, 1), "se puede reabrir tras cerrar");
        w.close();
        check(readWav(p).sampleRate == 22050 && readWav(p).frames() == 0, "la reapertura sobrescribe el archivo anterior");
        std::remove(p.c_str());
    }
    // ── El destructor cierra y deja el archivo válido ──
    {
        const std::string p = kDir + "dtor.wav";
        { WavWriter w; w.open(p.c_str(), 48000, 1); float v[4] = {1, 2, 3, 4}; w.write(v, 4); }
        const WavData d = readWav(p);
        check(d.valid && d.frames() == 4 && d.factFrames == 4, "sin close() explícito, el destructor deja un WAV completo");
        std::remove(p.c_str());
    }
    // ── Disco lleno: /dev/full falla en escribir ──
    {
        WavWriter w;
        if (w.open("/dev/full", 48000, 1)) {
            std::vector<float> big(1 << 16, 0.1f);
            bool failed = false;
            for (int i = 0; i < 64 && !failed; ++i) failed = !w.write(big.data(), big.size());
            check(failed || !w.close(), "escribir en un dispositivo lleno acaba informando el fallo");
            check(!w.close(), "y el cierre informa false");
            check(!w.write(big.data(), 1), "tras un fallo no se acepta más");
        } else {
            check(true, "(sin /dev/full en este sistema: caso omitido)");
        }
    }

    std::fprintf(stdout, "\n%s (%d fallo(s))\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
