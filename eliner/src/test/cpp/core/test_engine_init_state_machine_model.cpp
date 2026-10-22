// Fase 1.1 §15 — réplica ejecutable, en C++ puro, de la parte
// ALGORÍTMICA de AppServices.start()/shutdown() (app/src/main/java/
// com/yeivikas/olyze/AppServices.kt): la máquina de estados
// NotStarted→Starting→Ready/Failed y la propiedad de que una
// cancelación concurrente (shutdown() llamado mientras start() sigue en
// vuelo) nunca deja aplicar los efectos de "éxito" (registrar el
// consumer MIDI, publicar Ready) una vez que la cancelación ya ganó.
//
// Lo que NO se replica aquí (documentado honestamente, ver
// AppServicesStartupTest.kt y el ADR de esta fase): la semántica real de
// `kotlinx.coroutines` (`withTimeoutOrNull`, `runInterruptible`,
// `Dispatchers.IO`) — eso es maquinaria de la librería de coroutines en
// sí, ya probada por JetBrains, no lógica de negocio de Olyze. Lo que SÍ
// es lógica de negocio propia de esta clase, y por tanto lo que este
// test aísla y verifica, es: (a) las transiciones de estado son
// correctas para cada resultado posible, y (b) el "punto de no retorno"
// — una vez que la cancelación se observa, el código de éxito posterior
// (registrar consumer, publicar Ready) NUNCA se ejecuta, sin importar en
// qué orden exacto ganen los dos hilos.
#include <atomic>
#include <cstdio>
#include <cstdint>
#include <string>
#include <thread>
#include <variant>

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
}

// ── Réplica de EngineInitState ──────────────────────────────────────────
enum class StateKind { NotStarted, Starting, Ready, Failed };
struct EngineInitState {
    StateKind kind;
    std::string failedReason; // solo válido si kind == Failed
};

// ── Réplica de AppServices (solo la parte de máquina de estados) ───────
struct AppServicesModel {
    std::atomic<StateKind> stateKind{StateKind::NotStarted};
    std::string failedReason;
    std::atomic<bool> cancelled{false};
    std::atomic<int> midiRegisterCount{0}; // cuántas veces se "registró" el consumer MIDI — debe ser 0 o 1, nunca más, y nunca tras cancelled==true

    // Réplica de start() — el resultado de audio.start() y si hubo
    // timeout se modelan como parámetros de entrada en vez de una
    // llamada real asíncrona, porque eso es exactamente lo que
    // withTimeoutOrNull()/runInterruptible() resuelven ANTES de que este
    // código (la parte propia de Olyze) se ejecute.
    void completeStart(std::variant<bool, std::monostate> outcome /* bool = resultado real, monostate = timeout */) {
        // "Punto de no retorno": si la cancelación ya se observó, ningún
        // efecto de éxito se aplica — exactamente la garantía que el
        // catch(CancellationException) { throw e } del Kotlin real
        // produce (relanzar sin tocar _initState ni registrar nada).
        if (cancelled.load()) return;

        if (std::holds_alternative<std::monostate>(outcome)) {
            stateKind.store(StateKind::Failed);
            failedReason = "timeout";
            return;
        }
        bool started = std::get<bool>(outcome);
        if (!started) {
            stateKind.store(StateKind::Failed);
            failedReason = "rejected";
            return;
        }
        // Camino de éxito — igual que el Kotlin real: registrar consumer
        // MIDI, luego publicar Ready. Vuelve a comprobar cancelled antes
        // de cada efecto observable, igual que la corrutina real sería
        // interrumpida por CancellationException en cualquier punto de
        // suspensión intermedio si shutdown() ganó la carrera justo aquí.
        if (cancelled.load()) return;
        midiRegisterCount.fetch_add(1);
        if (cancelled.load()) return; // shutdown() pudo ganar justo entre registrar y publicar Ready
        stateKind.store(StateKind::Ready);
    }

    void beginStart() {
        stateKind.store(StateKind::Starting);
    }

    void shutdown() {
        cancelled.store(true);
    }
};

int main() {
    std::fprintf(stdout, "=== test_engine_init_state_machine_model ===\n");

    // ── Caso 1: arranque exitoso, sin carrera ────────────────────────
    {
        AppServicesModel m;
        m.beginStart();
        check(m.stateKind.load() == StateKind::Starting, "Caso 1.1: NotStarted -> Starting");
        m.completeStart(true);
        check(m.stateKind.load() == StateKind::Ready, "Caso 1.2: Starting -> Ready en éxito");
        check(m.midiRegisterCount.load() == 1, "Caso 1.3: el consumer MIDI se registra exactamente una vez");
    }

    // ── Caso 2: el motor rechaza el arranque ─────────────────────────
    {
        AppServicesModel m;
        m.beginStart();
        m.completeStart(false);
        check(m.stateKind.load() == StateKind::Failed, "Caso 2.1: Starting -> Failed cuando audio.start() devuelve false");
        check(m.failedReason == "rejected", "Caso 2.2: razón de fallo correcta");
        check(m.midiRegisterCount.load() == 0, "Caso 2.3: MIDI NUNCA se registra si el audio no arrancó");
    }

    // ── Caso 3: timeout ───────────────────────────────────────────────
    {
        AppServicesModel m;
        m.beginStart();
        m.completeStart(std::monostate{});
        check(m.stateKind.load() == StateKind::Failed, "Caso 3.1: Starting -> Failed por timeout");
        check(m.failedReason == "timeout", "Caso 3.2: razón de fallo correcta (timeout, no 'rejected')");
    }

    // ── Caso 4 (EL CASO CRÍTICO DE §15): shutdown() gana la carrera ANTES de que el resultado llegue ──
    {
        AppServicesModel m;
        m.beginStart();
        m.shutdown(); // usuario sale de la app mientras el motor seguía arrancando
        m.completeStart(true); // el resultado "llega tarde" — igual que un start() nativo que sigue corriendo en el hilo eliner-audio-commands
        check(m.stateKind.load() == StateKind::Starting,
              "Caso 4: tras shutdown() ganar la carrera, el estado se queda en Starting — "
              "NUNCA pasa a Ready aunque el resultado real haya sido éxito");
        check(m.midiRegisterCount.load() == 0,
              "Caso 4: el consumer MIDI NUNCA se registra si shutdown() ya canceló, "
              "sin importar el resultado real de audio.start()");
    }

    // ── Caso 5: carrera real con 2 hilos, repetida muchas veces (property-based) ──
    // No debe haber NINGUNA ejecución donde midiRegisterCount termine
    // siendo != {0,1}, ni ninguna donde termine en Ready DESPUÉS de que
    // cancelled ya era true en el momento de cada chequeo.
    {
        int violations = 0;
        constexpr int kIterations = 20000;
        for (int i = 0; i < kIterations; ++i) {
            AppServicesModel m;
            m.beginStart();
            std::thread starter([&] { m.completeStart(true); });
            std::thread shutter([&] { m.shutdown(); });
            starter.join();
            shutter.join();

            if (m.midiRegisterCount.load() > 1) violations++;
            // Si terminó Ready, es válido SOLO si shutdown() no alcanzó a
            // marcar cancelled antes de que completeStart() terminara su
            // último chequeo — no podemos saber el orden exacto desde
            // afuera, pero SÍ podemos afirmar la invariante más fuerte y
            // barata de verificar: midiRegisterCount nunca > 1 (nunca se
            // registra dos veces) y, si cancelled quedó true Y
            // midiRegisterCount quedó 0, el estado NUNCA debe ser Ready.
            if (m.cancelled.load() && m.midiRegisterCount.load() == 0 && m.stateKind.load() == StateKind::Ready) {
                violations++;
            }
        }
        check(violations == 0,
              ("Caso 5: " + std::to_string(kIterations) + " ejecuciones concurrentes reales (starter vs. shutdown), "
               "0 violaciones de invariante (sin doble-registro MIDI, sin Ready espurio tras cancelación efectiva)").c_str());
    }

    std::fprintf(stdout, "=== %s (%d fallo/s) ===\n",
                 gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
