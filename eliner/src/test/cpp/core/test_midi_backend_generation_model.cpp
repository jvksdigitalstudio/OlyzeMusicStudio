// Fase 1.1 §18 — réplica ejecutable, en C++ puro, de la lógica de
// generation/token añadida a AndroidMidiBackend.kt (openInputPorts() /
// closeDevice() / shutdown()). Existe porque este entorno no tiene
// kotlinc/JDK completo disponible (ver ADR 0016) — mismo patrón ya
// establecido en la Fase 1 anterior, donde
// test_dispatcher_pattern_multi_producer.cpp verifica en C++ puro el
// comportamiento real de AudioCommandDispatcher.kt sin poder compilar
// Kotlin en este entorno. AndroidMidiBackendGenerationTest.kt (el test
// Kotlin real, con kotlin.test) queda escrito y listo para CI, pero NO
// se declara ejecutado aquí — ver ADR 0016 para el estado exacto de
// cada uno de los dos.
#include <cstdio>
#include <cstdint>
#include <map>
#include <string>

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
}

// Réplica exacta de la lógica real de AndroidMidiBackend (mismos nombres,
// mismas comparaciones) — sin las líneas que tocan android.media.midi.*.
struct BackendModel {
    bool isShutDown = false;
    uint64_t generationCounter = 0;
    std::map<std::string, uint64_t> openGenerationByDevice;
    std::map<std::string, std::string> openDevices;
    int lateCallbacksDiscarded = 0;

    // Retorna 0 (sentinel "sin generación") si el backend ya está apagado —
    // en el Kotlin real esto es `return null`/`return false` desde
    // openInputPorts(); aquí, dado que uint64_t no tiene null, 0 nunca es
    // una generación válida (generationCounter empieza en 0 y SIEMPRE se
    // pre-incrementa antes de asignarse).
    uint64_t beginOpen(const std::string& deviceId) {
        if (isShutDown) return 0;
        uint64_t gen = ++generationCounter;
        openGenerationByDevice[deviceId] = gen;
        return gen;
    }

    void deliverOpenCallback(const std::string& deviceId, uint64_t myGeneration) {
        bool accepted;
        auto it = openGenerationByDevice.find(deviceId);
        if (isShutDown || it == openGenerationByDevice.end() || it->second != myGeneration) {
            accepted = false;
        } else {
            openDevices[deviceId] = "handle-gen-" + std::to_string(myGeneration);
            accepted = true;
        }
        if (!accepted) ++lateCallbacksDiscarded;
    }

    void closeDevice(const std::string& deviceId) {
        openGenerationByDevice.erase(deviceId);
        openDevices.erase(deviceId);
    }

    void shutdown() {
        isShutDown = true;
        openGenerationByDevice.clear();
        openDevices.clear();
    }
};

int main() {
    std::fprintf(stdout, "=== test_midi_backend_generation_model ===\n");

    // Caso 1: callback normal sin carrera.
    {
        BackendModel b;
        uint64_t gen = b.beginOpen("dev1");
        b.deliverOpenCallback("dev1", gen);
        check(b.openDevices.count("dev1") == 1, "Caso 1: callback normal se acepta");
        check(b.lateCallbacksDiscarded == 0, "Caso 1: sin descartes");
    }

    // Caso 2 (EL BUG DE §18): callback tardío tras shutdown().
    {
        BackendModel b;
        uint64_t gen = b.beginOpen("dev1");
        b.shutdown();
        b.deliverOpenCallback("dev1", gen); // llega DESPUÉS del shutdown
        check(b.openDevices.count("dev1") == 0,
              "Caso 2 [BUG §18]: shutdown() -> callback antiguo NO reintroduce el handle");
        check(b.lateCallbacksDiscarded == 1, "Caso 2: descartado y contabilizado");
    }

    // Caso 3: callback tardío tras closeDevice() del mismo id (sin shutdown global).
    {
        BackendModel b;
        uint64_t gen = b.beginOpen("dev1");
        b.closeDevice("dev1");
        b.deliverOpenCallback("dev1", gen);
        check(b.openDevices.count("dev1") == 0,
              "Caso 3: close() de un id invalida su open() en vuelo");
    }

    // Caso 4: reconexión rápida — solo la generación más nueva gana.
    {
        BackendModel b;
        uint64_t stale = b.beginOpen("dev1");
        b.closeDevice("dev1");
        uint64_t fresh = b.beginOpen("dev1");
        b.deliverOpenCallback("dev1", stale); // llega tarde
        check(b.openDevices.count("dev1") == 0, "Caso 4a: la generación vieja no pisa la nueva sesión");
        b.deliverOpenCallback("dev1", fresh); // llega después, es la vigente
        check(b.openDevices["dev1"] == "handle-gen-" + std::to_string(fresh),
              "Caso 4b: la generación nueva se acepta correctamente");
    }

    // Caso 5: beginOpen() tras shutdown() se rechaza de entrada.
    {
        BackendModel b;
        b.shutdown();
        uint64_t gen = b.beginOpen("dev1");
        check(gen == 0, "Caso 5: beginOpen() tras shutdown() no reserva generación (sentinel 0)");
    }

    // Caso 6: dos dispositivos concurrentes no se interfieren.
    {
        BackendModel b;
        uint64_t genA = b.beginOpen("devA");
        uint64_t genB = b.beginOpen("devB");
        b.closeDevice("devA");
        b.deliverOpenCallback("devA", genA);
        b.deliverOpenCallback("devB", genB);
        check(b.openDevices.count("devA") == 0, "Caso 6a: devA descartado (fue cerrado)");
        check(b.openDevices.count("devB") == 1, "Caso 6b: devB aceptado (no relacionado con devA)");
    }

    std::fprintf(stdout, "=== %s (%d fallo/s) ===\n",
                 gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
