// ADR 0028 — Integración del TRANSPORTE en AudioEngine (ejecutada, no solo compilada).
//
// Ejecuta el AudioEngine REAL contra un backend de audio FALSO
// (platform_stubs/oboe_fake.cpp) e invoca onAudioReady() a mano. Verifica de
// extremo a extremo la ruta  setTempo()/setMetronome…() → cola de comandos →
// hilo de audio → TempoClock → Metronome / sync del Delay.
//
// ALCANCE (declarado): prueba la lógica propia del motor. NO prueba Oboe ni un
// dispositivo real (latencia, xruns, desconexión); ver oboe_fake.cpp.
//
// Medición del tiempo de delay SIN ambigüedad: el motor es determinista, así que
// se comparan dos ejecuciones idénticas, una con el delay a mix=0 (solo seco) y
// otra con mix=1. Su DIFERENCIA es exactamente el eco; (primer sample del eco) −
// (primer sample del seco) = tiempo de delay en muestras, exacto al sample.
#include "AudioEngine.h"
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <memory>
#include <new>
#include <vector>

// ── Contador de asignaciones (para comprobar que el hilo de audio no asigna) ──
// Reemplaza TODAS las variantes de operator new/delete (simple, array, alineada y
// nothrow) y cuenta solo mientras gCounting sea true. Hay que cubrirlas todas:
// con -fsanitize=address, ASan intercepta operator new[] por su cuenta y un
// reemplazo parcial dejaría pasar `new float[n]` sin contarlo.
//
// [[gnu::noinline]]: evita el falso positivo -Wmismatched-new-delete de GCC, que al
// expandir en línea estos reemplazos empareja el malloc de `new` con el free de `delete`.
namespace {
std::atomic<long> gAllocCount{0};
std::atomic<bool> gCounting{false};
inline void countAlloc() {
    if (gCounting.load(std::memory_order_relaxed)) gAllocCount.fetch_add(1, std::memory_order_relaxed);
}
[[gnu::noinline]] void* rawAlloc(std::size_t n)                      { countAlloc(); return std::malloc(n ? n : 1); }
[[gnu::noinline]] void* rawAllocAligned(std::size_t n, std::size_t a) {
    countAlloc();
    const std::size_t sz = ((n ? n : 1) + a - 1) / a * a;             // aligned_alloc exige múltiplo de a
    return std::aligned_alloc(a, sz);
}
}
[[gnu::noinline]] void* operator new(std::size_t n)                    { void* p = rawAlloc(n); if (!p) throw std::bad_alloc(); return p; }
[[gnu::noinline]] void* operator new[](std::size_t n)                  { void* p = rawAlloc(n); if (!p) throw std::bad_alloc(); return p; }
[[gnu::noinline]] void* operator new(std::size_t n, const std::nothrow_t&) noexcept   { return rawAlloc(n); }
[[gnu::noinline]] void* operator new[](std::size_t n, const std::nothrow_t&) noexcept { return rawAlloc(n); }
[[gnu::noinline]] void* operator new(std::size_t n, std::align_val_t a)   { void* p = rawAllocAligned(n, static_cast<std::size_t>(a)); if (!p) throw std::bad_alloc(); return p; }
[[gnu::noinline]] void* operator new[](std::size_t n, std::align_val_t a) { void* p = rawAllocAligned(n, static_cast<std::size_t>(a)); if (!p) throw std::bad_alloc(); return p; }
[[gnu::noinline]] void operator delete(void* p) noexcept                                  { std::free(p); }
[[gnu::noinline]] void operator delete[](void* p) noexcept                                { std::free(p); }
[[gnu::noinline]] void operator delete(void* p, std::size_t) noexcept                     { std::free(p); }
[[gnu::noinline]] void operator delete[](void* p, std::size_t) noexcept                   { std::free(p); }
[[gnu::noinline]] void operator delete(void* p, std::align_val_t) noexcept                { std::free(p); }
[[gnu::noinline]] void operator delete[](void* p, std::align_val_t) noexcept              { std::free(p); }
[[gnu::noinline]] void operator delete(void* p, std::size_t, std::align_val_t) noexcept   { std::free(p); }
[[gnu::noinline]] void operator delete[](void* p, std::size_t, std::align_val_t) noexcept { std::free(p); }

using eliner::AudioEngine;

// Definido en platform_stubs/oboe_fake.cpp
extern "C" void eliner_fake_set_sample_rate(int sampleRate);

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
constexpr int kSR = 48000;

// Un motor arrancado sobre el backend falso, con utilidades de render.
struct Rig {
    std::unique_ptr<AudioEngine> e = std::make_unique<AudioEngine>();
    bool started;
    Rig() { started = e->start(0); }
    ~Rig() { e->stop(); }
    // Renderiza [frames] en bloques de [blk]; devuelve estéreo intercalado.
    std::vector<float> render(int frames, int blk = 480) {
        std::vector<float> out(2 * static_cast<std::size_t>(frames), 0.0f);
        for (int pos = 0; pos < frames; pos += blk) {
            const int n = std::min(blk, frames - pos);
            e->onAudioReady(nullptr, out.data() + 2 * pos, n);
        }
        return out;
    }
    void silence() {                       // FX neutros: solo lo que se pruebe es audible
        e->setReverbMix(0.0f); e->setDelayMix(0.0f); e->setDelayFeedback(0.0f);
    }
};

float peak(const std::vector<float>& b, int from, int to) {
    float p = 0.f; for (int i = from; i < to; ++i) p = std::fmax(p, std::fabs(b[2 * i])); return p;
}
int zeroCrossings(const std::vector<float>& b, int from, int to) {
    int z = 0; for (int i = from + 1; i < to; ++i) if ((b[2 * (i - 1)] < 0) != (b[2 * i] < 0)) ++z; return z;
}
// Onsets de click: primer frame de cada ventana de [gap] frames con energía.
std::vector<int> clickOnsets(const std::vector<float>& b, int frames, float thr = 0.01f) {
    std::vector<int> on; int quiet = 1 << 20;
    for (int i = 0; i < frames; ++i) {
        const bool loud = std::fabs(b[2 * i]) > thr;
        if (loud && quiet > 2400) on.push_back(i);   // ≥50 ms de silencio previo
        quiet = loud ? 0 : quiet + 1;
    }
    return on;
}

// Mide el tiempo de delay (en muestras) tras fijar las condiciones con [setup].
template <typename Setup>
long measureDelaySamples(Setup setup, int settleFrames) {
    auto run = [&](float delayMix) {
        Rig r; if (!r.started) return std::vector<float>();
        r.e->setReverbMix(0.0f);
        r.e->setDelayFeedback(0.0f);
        setup(*r.e);
        r.e->setDelayMix(0.0f);                           // el ajuste de tiempo se asienta en bypass
        (void)r.render(settleFrames);
        r.e->setDelayMix(delayMix);
        (void)r.render(480);                              // aplica el cambio de mix antes de la nota
        r.e->noteOn(0, 69, 100);
        return r.render(kSR * 3);
    };
    const auto dry = run(0.0f), wet = run(1.0f);
    if (dry.empty() || wet.empty()) return -1;
    long t0 = -1, tE = -1;
    for (std::size_t i = 0; i < dry.size() / 2; ++i)
        if (t0 < 0 && std::fabs(dry[2 * i]) > 1.0e-7f) { t0 = static_cast<long>(i); break; }
    for (std::size_t i = 0; i < dry.size() / 2; ++i)
        if (std::fabs(wet[2 * i] - dry[2 * i]) > 0.5e-7f) { tE = static_cast<long>(i); break; }
    return (t0 < 0 || tE < 0) ? -1 : tE - t0;
}
}

int main() {
    // ── 0. Precondición: el motor arranca contra el backend falso y es determinista ──
    {
        Rig a, b;
        check(a.started && b.started, "0a. AudioEngine::start() abre el stream falso y construye el grafo DSP");
        a.silence(); b.silence(); a.e->noteOn(0, 60, 100); b.e->noteOn(0, 60, 100);
        const auto x = a.render(kSR / 2), y = b.render(kSR / 2);
        check(x == y && peak(x, 0, kSR / 2) > 0.01f, "0b. dos motores con la misma secuencia producen salida IDÉNTICA (determinismo) y no vacía");
    }

    // ── 1. Metrónomo: clicks en el frame exacto, a 120 BPM ──
    {
        Rig r; r.silence();
        r.e->setTempo(120.0f); r.e->setBeatsPerBar(4);
        r.e->setMetronomeVolume(1.0f); r.e->setMetronomeEnabled(true);
        auto idle = r.render(4800);
        check(peak(idle, 0, 4800) == 0.0f, "1a. con el transporte detenido el metrónomo NO suena, aunque esté activado");
        r.e->setTransportRunning(true);
        auto out = r.render(kSR * 5);
        const auto on = clickOnsets(out, kSR * 5);
        bool exact = on.size() == 10;
        for (std::size_t k = 0; k < on.size(); ++k) exact = exact && std::abs(on[k] - static_cast<int>(k) * 24000) <= 5;
        check(exact, "1b. 120 BPM, 5 s: 10 clicks, cada uno en el frame k*24000 (±5 por el ataque del seno)");
        check(peak(out, 0, 480) > 0.1f, "1c. el primer click suena en el primer bloque tras arrancar");
        const int zA = zeroCrossings(out, 0, 480), zN = zeroCrossings(out, 24000, 24480);
        check(zA > zN, "1d. el primer tiempo del compás es más agudo que el segundo (acento)");
        const int z5 = zeroCrossings(out, 4 * 24000, 4 * 24000 + 480);
        check(z5 > zN, "1e. el acento se repite en el primer tiempo del compás siguiente (pulso 4 → 0)");
    }

    // ── 2. Cambio de tempo en caliente ──
    {
        Rig r; r.silence();
        r.e->setTempo(120.0f); r.e->setMetronomeVolume(1.0f); r.e->setMetronomeEnabled(true); r.e->setTransportRunning(true);
        auto a = r.render(24000 * 2);                 // dos pulsos a 120
        r.e->setTempo(240.0f);
        auto b = r.render(12000 * 5);
        const auto on = clickOnsets(b, 12000 * 5);
        // Tras el cambio en el frame 48000: el pulso 2 estaba en 48000 → llega en el frame 0 de b, luego cada 12000.
        bool ok = on.size() == 5;
        for (std::size_t k = 0; k < on.size(); ++k) ok = ok && std::abs(on[k] - static_cast<int>(k) * 12000) <= 5;
        check(ok, "2. 120→240 BPM en caliente: los siguientes pulsos llegan cada 12000 frames, sin salto de fase");
        (void)a;
    }

    // ── 3. El click NO pasa por los FX ni lo afecta el volumen master ──
    {
        Rig r;
        r.e->setReverbMix(1.0f); r.e->setDelayMix(1.0f); r.e->setDelayFeedback(0.9f);   // FX al máximo
        r.e->setTempo(120.0f); r.e->setMetronomeVolume(1.0f); r.e->setMetronomeEnabled(true); r.e->setTransportRunning(true);
        auto out = r.render(24000 * 4);
        // sin voces, la cadena de FX no recibe señal; el click va por su bus: tras cada click (≥250 ms) todo es 0 exacto
        check(peak(out, 12000, 24000) == 0.0f && peak(out, 36000, 48000) == 0.0f,
              "3a. con reverb y delay al máximo NO hay colas ni ecos del click (bus propio, posterior a los FX)");
        r.e->setMasterVolume(0.0f);
        auto m = r.render(24000 * 2);
        check(peak(m, 0, 24000) > 0.1f, "3b. con volumen master a 0 el click sigue sonando (independiente del master)");
    }

    // ── 4. Volumen y activación ──
    {
        Rig r; r.silence();
        r.e->setTempo(120.0f); r.e->setMetronomeVolume(1.0f); r.e->setMetronomeEnabled(true); r.e->setTransportRunning(true);
        auto loud = r.render(24000);
        r.e->setMetronomeVolume(0.0f);
        (void)r.render(4800);                                  // deja asentar la ganancia suavizada
        auto mute = r.render(24000);
        check(peak(loud, 0, 2400) > 0.1f && peak(mute, 0, 24000) == 0.0f, "4a. volumen 0 → silencio; volumen 1 → audible");
        r.e->setMetronomeVolume(1.0f); r.e->setMetronomeEnabled(false);
        (void)r.render(4800);
        auto off = r.render(24000 * 2);
        check(peak(off, 0, 24000 * 2) == 0.0f, "4b. metrónomo desactivado → silencio, el transporte sigue corriendo");
    }

    // ── 4c. El reloj corre aunque el metrónomo esté apagado: al activarlo cae en la REJILLA ──
    {
        Rig r; r.silence();
        r.e->setTempo(120.0f); r.e->setMetronomeVolume(1.0f);     // metrónomo APAGADO
        r.e->setTransportRunning(true);
        (void)r.render(30000);                                    // 1.25 pulsos sin sonar
        r.e->setMetronomeEnabled(true);
        auto g = r.render(24000);
        const auto on = clickOnsets(g, 24000);
        // El siguiente pulso de la rejilla es el 2, en el frame absoluto 48000 → 18000 desde aquí
        // (si el reloj se hubiera parado con el metrónomo apagado, el click saldría en otro sitio).
        check(on.size() == 1 && std::abs(on[0] - 18000) <= 480,
              "4c. con el metrónomo apagado el reloj sigue: al activarlo a mitad de compás el click cae en la rejilla (18000 frames más tarde)");
    }

    // ── 5. stop/start del transporte: vuelve al primer tiempo ──
    {
        Rig r; r.silence();
        r.e->setTempo(120.0f); r.e->setMetronomeVolume(1.0f); r.e->setMetronomeEnabled(true); r.e->setTransportRunning(true);
        (void)r.render(30000);
        r.e->setTransportRunning(false);
        auto s = r.render(24000);
        check(peak(s, 480, 24000) == 0.0f, "5a. stop: deja de sonar (tras terminar el click en curso)");
        r.e->setTransportRunning(true);
        auto g = r.render(2400);
        check(peak(g, 0, 480) > 0.1f && zeroCrossings(g, 0, 480) > 25, "5b. start: vuelve a sonar en el primer frame y con el acento del primer tiempo");
    }

    // ── 6. Entradas inválidas en el hilo de control ──
    {
        Rig r; r.silence();
        r.e->setTempo(120.0f); r.e->setMetronomeVolume(1.0f); r.e->setMetronomeEnabled(true); r.e->setTransportRunning(true);
        r.e->setTempo(std::numeric_limits<float>::quiet_NaN());
        r.e->setTempo(std::numeric_limits<float>::infinity());
        r.e->setMetronomeVolume(std::numeric_limits<float>::quiet_NaN());
        auto out = r.render(24000 * 6);                         // 144000 frames = 3 s = 6 pulsos a 120 BPM
        const auto on = clickOnsets(out, 24000 * 6);
        bool steady = on.size() == 6;
        for (std::size_t k = 0; k < on.size(); ++k) steady = steady && std::abs(on[k] - static_cast<int>(k) * 24000) <= 5;
        check(steady, "6a. setTempo(NaN/Inf) y setMetronomeVolume(NaN) se ignoran: 6 pulsos exactos cada 24000 frames (tempo 120) y audibles");
        r.e->setTempo(100000.0f);                               // se limita a 300 BPM = 9600 frames/pulso
        (void)r.render(24000);                                  // deja que el pulso en curso (120 BPM) termine
        auto fast = r.render(9600 * 5);
        const auto onF = clickOnsets(fast, 9600 * 5);
        bool spaced = onF.size() >= 4;
        for (std::size_t k = 2; spaced && k < onF.size(); ++k) spaced = std::abs((onF[k] - onF[k - 1]) - 9600) <= 10;
        check(spaced, "6b. setTempo(100000) se limita a 300 BPM: pulsos cada 9600 frames");
        r.e->setTempo(1.0f);                                    // se limita a 20 BPM = 144000 frames/pulso
        (void)r.render(9600 * 2);
        auto slow = r.render(144000 / 2);                       // medio pulso a 20 BPM: como mucho 1 click nuevo (el en curso)
        check(clickOnsets(slow, 144000 / 2).size() <= 1, "6c. setTempo(1) se limita a 20 BPM: ≤1 click en medio pulso lento (no ráfaga)");
    }

    // ── 7. Sync del delay con el tempo: tiempo exacto al sample ──
    {
        const long d120 = measureDelaySamples([](AudioEngine& e) { e.setTempo(120.0f); }, kSR);
        check(d120 == 18000, "7a. 120 BPM, corchea con puntillo: el eco llega a 18000 muestras (0.375 s) — idéntico al valor histórico por defecto");
        std::fprintf(stdout, "       (medido: %ld)\n", d120);

        const long d60 = measureDelaySamples([](AudioEngine& e) { e.setTempo(60.0f); }, kSR / 2);
        check(d60 == 36000, "7b. 60 BPM → 0.75 s = 36000 muestras");
        std::fprintf(stdout, "       (medido: %ld)\n", d60);

        const long d240 = measureDelaySamples([](AudioEngine& e) { e.setTempo(240.0f); }, kSR / 2);
        check(d240 == 9000, "7c. 240 BPM → 0.1875 s = 9000 muestras");
        std::fprintf(stdout, "       (medido: %ld)\n", d240);

        const long d20 = measureDelaySamples([](AudioEngine& e) { e.setTempo(20.0f); }, kSR / 2);
        check(d20 == 54000, "7d. 20 BPM: 2.25 s no cabe en el buffer → se pliega a la mitad, 1.125 s = 54000 muestras (relación rítmica conservada)");
        std::fprintf(stdout, "       (medido: %ld)\n", d20);

        const long dDiv = measureDelaySamples([](AudioEngine& e) { e.setTempo(120.0f); e.setDelayTempoSync(true, 0.5f); }, kSR / 2);
        check(dDiv == 12000, "7e. 120 BPM con división de 1/8 (0.5 pulsos) → 0.25 s = 12000 muestras");
        std::fprintf(stdout, "       (medido: %ld)\n", dDiv);

        const long dTrip = measureDelaySamples([](AudioEngine& e) { e.setTempo(120.0f); e.setDelayTempoSync(true, 2.0f / 3.0f); }, kSR / 2);
        check(std::labs(dTrip - 16000) <= 1, "7f. 120 BPM con tresillo de negra (2/3 pulso) → 0.3333 s ≈ 16000 muestras");
        std::fprintf(stdout, "       (medido: %ld)\n", dTrip);

        const long dMan = measureDelaySamples([](AudioEngine& e) { e.setTempo(60.0f); e.setDelayTime(0.2f); }, kSR / 2);
        check(dMan == 9600, "7g. fijar DelayTime a mano DESACTIVA el sync: sigue en 0.2 s = 9600 aunque el tempo sea 60");
        std::fprintf(stdout, "       (medido: %ld)\n", dMan);

        const long dMan2 = measureDelaySamples([](AudioEngine& e) { e.setDelayTime(0.2f); e.setTempo(60.0f); }, kSR / 2);
        check(dMan2 == 9600, "7g2. tiempo manual y LUEGO cambio de tempo: el delay se queda en 0.2 s (el sync quedó desactivado)");
        std::fprintf(stdout, "       (medido: %ld)\n", dMan2);

        const long dRe = measureDelaySamples([](AudioEngine& e) { e.setDelayTime(0.2f); e.setTempo(60.0f); e.setDelayTempoSync(true, 0.5f); }, kSR / 2);
        check(dRe == 24000, "7h. setDelayTempoSync(true, …) REACTIVA el sync: 60 BPM, 0.5 pulsos → 0.5 s = 24000 muestras");
        std::fprintf(stdout, "       (medido: %ld)\n", dRe);

        const long dOff = measureDelaySamples([](AudioEngine& e) { e.setTempo(120.0f); e.setDelayTempoSync(false, 0.75f); e.setTempo(60.0f); }, kSR / 2);
        check(dOff == 18000, "7i. con el sync desactivado, cambiar el tempo NO mueve el delay (queda en 0.375 s)");
        std::fprintf(stdout, "       (medido: %ld)\n", dOff);
    }

    // ── 8. El motor usa la frecuencia REAL del stream (no el valor por defecto) ──
    {
        for (int sr : {44100, 96000}) {
            eliner_fake_set_sample_rate(sr);
            Rig r; r.silence();
            r.e->setTempo(120.0f); r.e->setMetronomeVolume(1.0f); r.e->setMetronomeEnabled(true); r.e->setTransportRunning(true);
            const int perBeat = sr / 2;                                   // 120 BPM = 2 pulsos por segundo
            auto out = r.render(perBeat * 5);
            const auto on = clickOnsets(out, perBeat * 5);
            bool ok = on.size() == 5;
            for (std::size_t k = 0; k < on.size(); ++k) ok = ok && std::abs(on[k] - static_cast<int>(k) * perBeat) <= 5;
            char msg[160];
            std::snprintf(msg, sizeof msg, "8a. stream a %d Hz: los pulsos a 120 BPM caen cada %d frames", sr, perBeat);
            check(ok, msg);
            // la altura del click depende de la frecuencia: 1500 Hz de acento → cruces en 10 ms
            const int win = sr / 100, zc = zeroCrossings(out, 0, win);
            std::snprintf(msg, sizeof msg, "8b. stream a %d Hz: el acento sigue siendo ≈1500 Hz (%d cruces en 10 ms, esperados 30±2)", sr, zc);
            check(zc >= 28 && zc <= 32, msg);
        }
        eliner_fake_set_sample_rate(96000);
        const long d96 = measureDelaySamples([](AudioEngine& e) { e.setTempo(120.0f); }, 96000 / 2);
        check(d96 == 36000, "8c. stream a 96000 Hz: el delay sincronizado (0.375 s) = 36000 muestras");
        std::fprintf(stdout, "       (medido a 96000 Hz: %ld)\n", d96);
        eliner_fake_set_sample_rate(44100);
        const long d44 = measureDelaySamples([](AudioEngine& e) { e.setTempo(120.0f); }, 44100 / 2);
        check(d44 == 16537, "8d. stream a 44100 Hz: el delay sincronizado (0.375 s) = 16537 muestras (truncado como siempre)");
        std::fprintf(stdout, "       (medido a 44100 Hz: %ld)\n", d44);
        eliner_fake_set_sample_rate(48000);
    }

    // ── 9. Tiempo real: el camino del hilo de audio NO asigna memoria ──
    {
        Rig r;
        r.e->setReverbMix(0.4f); r.e->setDelayMix(0.6f); r.e->setDelayFeedback(0.5f);
        r.e->setMetronomeVolume(1.0f); r.e->setMetronomeEnabled(true);
        r.e->setTempo(120.0f); r.e->setTransportRunning(true);
        r.e->noteOn(0, 60, 100); r.e->noteOn(0, 64, 100); r.e->noteOn(0, 67, 100);
        std::vector<float> buf(2 * 480, 0.0f);
        for (int i = 0; i < 20; ++i) r.e->onAudioReady(nullptr, buf.data(), 480);   // calentamiento (primeras ejecuciones)

        gAllocCount = 0; gCounting = true;
        for (int block = 0; block < 600; ++block) {
            if (block % 25 == 0) r.e->setTempo(60.0f + static_cast<float>((block / 25) * 7 % 180));   // el delay se desliza
            if (block % 100 == 0) r.e->setBeatsPerBar(2 + (block / 100) % 6);
            if (block == 300)     r.e->setDelayTempoSync(true, 1.0f / 3.0f);
            if (block == 450)     r.e->setTransportRunning(false);
            if (block == 500)     r.e->setTransportRunning(true);
            r.e->onAudioReady(nullptr, buf.data(), 480);
        }
        gCounting = false;
        const long allocs = gAllocCount.load();
        std::fprintf(stdout, "       (asignaciones en 600 bloques de audio con transporte activo: %ld)\n", allocs);
        check(allocs == 0, "9. el camino de audio (comandos de transporte, reloj, metrónomo, delay deslizándose, voces) hace CERO asignaciones");
    }

    std::fprintf(stdout, "=== %s (%d fallo/s) ===\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
