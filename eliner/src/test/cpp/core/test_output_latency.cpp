// Latencia de salida (ADR 0037): estimador puro (mediana robusta) y su muestreo por el AudioEngine real
// contra el stream Oboe falso, cuya latencia programan los tests.
//
// Qué fija este test:
//   · Estimador: descarta muestras corruptas (no finitas, ≤ 0, > 1000 ms), la mediana ignora atípicos aislados,
//     ventana de 9 con reemplazo circular, par/impar, reset.
//   · Motor: -1 mientras no hay medida; con stream en marcha publica la estimación; un resultado no válido del
//     dispositivo (aún sin marcas de tiempo) no destruye la medida anterior; parado vuelve a -1; reiniciar
//     el stream descarta la medida vieja.
#include "AudioEngine.h"
#include "OutputLatency.h"

#include <cmath>
#include <cstdio>
#include <memory>

extern "C" void eliner_fake_set_latency(double ms, bool ok);

using eliner::AudioEngine;
using eliner::OutputLatencyEstimator;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
bool near(double a, double b, double eps = 1e-9) { return std::fabs(a - b) <= eps; }
} // namespace

int main() {
    // ── Estimador ──
    {
        OutputLatencyEstimator e;
        check(!e.valid() && e.count() == 0 && e.millis() == 0.0, "vacío: sin medida y millis() = 0");
        check(!e.add(NAN) && !e.add(INFINITY) && !e.add(-INFINITY), "no finitas: se descartan");
        check(!e.add(0.0) && !e.add(-3.0), "cero y negativas: se descartan");
        check(!e.add(1000.01) && !e.add(1e9), "implausibles (> 1000 ms): se descartan, no se recortan");
        check(!e.valid(), "tras descartar todo sigue sin haber medida");
        check(e.add(1000.0) && e.add(0.001), "los límites válidos (1000 y 0,001) se aceptan");
    }
    {
        OutputLatencyEstimator e;
        e.add(10.0);
        check(e.valid() && near(e.millis(), 10.0), "una muestra: esa");
        e.add(20.0);
        check(near(e.millis(), 15.0), "dos muestras: media de las centrales");
        e.add(30.0);
        check(near(e.millis(), 20.0), "tres muestras: la central");
    }
    {
        OutputLatencyEstimator e;
        for (int i = 0; i < 8; ++i) e.add(12.0);
        e.add(900.0);                                           // atípico aislado
        check(near(e.millis(), 12.0), "un atípico aislado (900) no mueve la mediana");
        e.add(4.0);                                             // otro, por abajo: ventana circular
        check(near(e.millis(), 12.0), "otro atípico por abajo tampoco");
    }
    {
        OutputLatencyEstimator e;
        for (int i = 0; i < OutputLatencyEstimator::kWindow; ++i) e.add(50.0);
        for (int i = 0; i < OutputLatencyEstimator::kWindow; ++i) e.add(8.0);   // la ruta cambió
        check(near(e.millis(), 8.0) && e.count() == OutputLatencyEstimator::kWindow,
              "ventana de 9 con reemplazo circular: tras 9 muestras nuevas, solo cuentan las nuevas");
        e.reset();
        check(!e.valid() && e.count() == 0, "reset(): sin medida");
        e.add(7.0);
        check(near(e.millis(), 7.0), "tras reset() vuelve a funcionar desde cero");
    }

    // ── Motor ──
    {
        eliner_fake_set_latency(12.5, true);
        auto e = std::make_unique<AudioEngine>();
        check(e->outputLatencyMs() == -1.0f, "motor sin arrancar: -1");
        check(e->sampleOutputLatency() == -1.0f, "muestrear con el stream parado: -1");
        e->start(0);
        check(e->outputLatencyMs() == -1.0f, "recién arrancado y sin muestrear: -1");
        check(std::fabs(e->sampleOutputLatency() - 12.5f) < 1e-4f, "con el stream en marcha publica la estimación (12,5 ms)");
        check(std::fabs(e->outputLatencyMs() - 12.5f) < 1e-4f, "…y queda legible desde cualquier hilo");

        eliner_fake_set_latency(0.0, false);                    // el dispositivo aún no da marcas de tiempo
        check(std::fabs(e->sampleOutputLatency() - 12.5f) < 1e-4f, "un resultado no válido NO destruye la medida anterior");

        eliner_fake_set_latency(12.5, true);
        for (int i = 0; i < 4; ++i) (void)e->sampleOutputLatency();
        eliner_fake_set_latency(900.0, true);
        (void)e->sampleOutputLatency();
        check(std::fabs(e->outputLatencyMs() - 12.5f) < 1e-4f, "un atípico entre muestras buenas no mueve la estimación");

        e->stop();
        check(e->sampleOutputLatency() == -1.0f && e->outputLatencyMs() == -1.0f, "parado: vuelve a -1");

        eliner_fake_set_latency(30.0, true);
        e->start(0);
        check(e->outputLatencyMs() == -1.0f, "reiniciar el stream descarta la medida vieja");
        check(std::fabs(e->sampleOutputLatency() - 30.0f) < 1e-4f, "…y la nueva no hereda la anterior");
        e->stop();
    }
    {
        eliner_fake_set_latency(0.0, false);                    // nunca hay marcas de tiempo
        auto e = std::make_unique<AudioEngine>();
        e->start(0);
        check(e->sampleOutputLatency() == -1.0f, "si el dispositivo nunca da marcas, sigue en -1 (sin inventar valor)");
        e->stop();
        eliner_fake_set_latency(12.5, true);
    }

    std::fprintf(stdout, "\n%s (%d fallo(s))\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
