// CaptureRing (ADR 0038): anillo SPSC sin bloqueos entre el hilo de audio y el escritor.
//   · orden y contenido (también con envoltura del anillo y bloques de tamaños variables),
//   · «lleno»: lo que no cabe se descarta Y se cuenta, sin bloquear,
//   · parámetros inválidos, canales múltiples,
//   · concurrencia real productor/consumidor (bajo TSan): sin pérdidas ni reordenaciones cuando el consumidor sigue
//     el ritmo, y conservación total (aceptado + descartado = intentado) cuando no.
#include <atomic>
#include <cstdio>
#include <thread>
#include <vector>

#include "CaptureRing.h"

using eliner::CaptureRing;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
} // namespace

int main() {
    // ── init ──
    {
        CaptureRing r;
        check(!r.init(0, 1) && !r.init(8, 0) && !r.init(8, CaptureRing::kMaxChannels + 1), "init rechaza 0 frames y canales fuera de 1..8");
        check(r.init(100, 2) && r.capacityFrames() == 128 && r.channels() == 2, "100 frames → capacidad 128 (potencia de 2)");
        check(r.init(128, 1) && r.capacityFrames() == 128, "una potencia exacta se respeta");
        float x = 1.f;
        CaptureRing e;
        check(e.push(&x, 1) == 0 && e.pop(&x, 1) == 0 && e.available() == 0, "sin init: push y pop no hacen nada");
    }
    // ── contenido, orden y envoltura ──
    {
        CaptureRing r; r.init(8, 1);
        float in[6] = {1, 2, 3, 4, 5, 6}, out[8] = {};
        check(r.push(in, 6) == 6 && r.available() == 6, "push de 6 en un anillo de 8");
        check(r.pop(out, 4) == 4 && out[0] == 1 && out[3] == 4, "pop de 4: 1..4 en orden");
        float in2[5] = {7, 8, 9, 10, 11};
        check(r.push(in2, 5) == 5, "push de 5 envolviendo el final del anillo");
        float all[8] = {};
        check(r.pop(all, 8) == 7, "quedan 7 (2 + 5)");
        const float expect[7] = {5, 6, 7, 8, 9, 10, 11};
        bool ok = true; for (int i = 0; i < 7; ++i) ok = ok && all[i] == expect[i];
        check(ok, "el contenido tras envolver es 5..11 en orden");
        check(r.droppedFrames() == 0, "sin descartes mientras cupo todo");
    }
    // ── lleno ──
    {
        CaptureRing r; r.init(8, 1);
        float in[12]; for (int i = 0; i < 12; ++i) in[i] = static_cast<float>(i);
        check(r.push(in, 12) == 8, "12 en un anillo de 8: caben 8");
        check(r.droppedFrames() == 4, "los 4 que no cupieron se cuentan");
        check(r.push(in, 3) == 0 && r.droppedFrames() == 7, "anillo lleno: no entra nada y se cuenta");
        float out[8];
        check(r.pop(out, 8) == 8 && out[0] == 0 && out[7] == 7, "lo aceptado es lo PRIMERO que llegó (0..7)");
        check(r.push(in, 3) == 3, "con hueco vuelve a aceptar");
    }
    // ── varios canales ──
    {
        CaptureRing r; r.init(4, 2);
        float in[10]; for (int i = 0; i < 10; ++i) in[i] = static_cast<float>(i);   // 5 frames estéreo
        check(r.push(in, 5) == 4 && r.droppedFrames() == 1, "estéreo: 5 frames en 4 → caben 4");
        float out[8] = {};
        check(r.pop(out, 4) == 4 && out[0] == 0 && out[1] == 1 && out[6] == 6 && out[7] == 7, "los frames estéreo no se mezclan");
    }
    // ── nulos y ceros ──
    {
        CaptureRing r; r.init(8, 1);
        float x = 1;
        check(r.push(nullptr, 4) == 0 && r.push(&x, 0) == 0 && r.pop(nullptr, 4) == 0 && r.pop(&x, 0) == 0, "punteros nulos y 0 frames: no-op");
    }
    // ── concurrencia: el consumidor sigue el ritmo → sin pérdidas ni reordenación ──
    {
        CaptureRing r; r.init(1 << 12, 1);
        constexpr long kTotal = 2'000'000;
        std::atomic<bool> done{false};
        std::atomic<long> bad{0}, got{0};
        std::thread consumer([&] {
            std::vector<float> buf(777);
            float expected = 0;
            while (true) {
                const size_t n = r.pop(buf.data(), buf.size());
                for (size_t i = 0; i < n; ++i) { if (buf[i] != expected) ++bad; expected += 1; }
                got += static_cast<long>(n);
                if (n == 0) { if (done.load(std::memory_order_acquire) && r.available() == 0) break; std::this_thread::yield(); }
            }
        });
        std::thread producer([&] {
            std::vector<float> blk(331);
            long sent = 0; float next = 0;
            while (sent < kTotal) {
                const size_t n = static_cast<size_t>(std::min<long>(static_cast<long>(blk.size()), kTotal - sent));
                for (size_t i = 0; i < n; ++i) blk[i] = next + static_cast<float>(i);
                size_t pushed = 0;
                while (pushed < n) {                                    // reintenta lo que no cupió: aquí no se pierde nada
                    const size_t k = r.push(blk.data() + pushed, n - pushed);
                    pushed += k; if (k == 0) std::this_thread::yield();
                }
                sent += static_cast<long>(n); next += static_cast<float>(n);
            }
            done.store(true, std::memory_order_release);
        });
        producer.join(); consumer.join();
        check(got.load() == kTotal && bad.load() == 0, "2 M de frames entre dos hilos: todos, en orden y sin corrupción");
    }
    // ── concurrencia: productor sin esperar → aceptado + descartado = intentado ──
    {
        CaptureRing r; r.init(256, 1);
        constexpr long kTotal = 1'000'000;
        std::atomic<bool> done{false};
        std::atomic<long> popped{0}, accepted{0};
        std::thread consumer([&] {
            std::vector<float> buf(100);
            while (true) {
                const size_t n = r.pop(buf.data(), buf.size());
                popped += static_cast<long>(n);
                if (n == 0) { if (done.load(std::memory_order_acquire) && r.available() == 0) break; std::this_thread::yield(); }
            }
        });
        std::thread producer([&] {
            std::vector<float> blk(64, 1.0f);
            long sent = 0;
            while (sent < kTotal) { accepted += static_cast<long>(r.push(blk.data(), blk.size())); sent += static_cast<long>(blk.size()); }
            done.store(true, std::memory_order_release);
        });
        producer.join(); consumer.join();
        const long attempted = (kTotal + 63) / 64 * 64;
        check(popped.load() == accepted.load(), "todo lo aceptado se retiró");
        check(static_cast<long>(r.droppedFrames()) + accepted.load() == attempted, "aceptado + descartado = intentado (se conserva la cuenta)");
    }

    std::fprintf(stdout, "\n%s (%d fallo(s))\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
