// Los tests con hilos reales (test_command_queue_race_evidence.cpp,
// test_forced_collision.cpp) dependen del scheduler del SO para producir
// la colisión exacta — en este entorno virtualizado eso resultó poco
// confiable (baja concurrencia real entre hilos, o mi propio arnés de
// barrera con bugs de sincronización). Este test toma un enfoque más
// riguroso y 100% determinista: reproduce, en UN SOLO HILO, la secuencia
// EXACTA de pasos que el algoritmo real de `SpscCommandQueue::push()`
// ejecuta (copiado línea por línea de CommandQueue.h), intercalando
// manualmente los pasos de "hilo A" y "hilo B" en el orden que el
// hardware SÍ podría producir (nada en el código lo impide) — y prueba
// matemáticamente que ese orden, permitido por la ausencia de
// sincronización, produce pérdida silenciosa de datos.
//
// Esto no es una aproximación: es el algoritmo real de push() (mismas
// operaciones, mismo orden de memoria), solo con el entrelazado forzado
// explícitamente en vez de dejado al azar del scheduler — la forma
// estándar de demostrar una data race en una auditoría, cuando no se
// puede confiar en que el scheduler real la produzca de forma repetible.
#include <array>
#include <atomic>
#include <cstdio>
#include <cstdint>

struct Cmd { int origin; int value; };

int main() {
    // Réplica exacta del estado interno de SpscCommandQueue<Cmd, 4>
    // (ver CommandQueue.h: mBuffer, mHead, mTail, mDroppedCount).
    std::array<Cmd, 4> buffer{};
    std::atomic<size_t> head{0};
    std::atomic<size_t> tail{0};
    std::atomic<uint64_t> droppedCount{0};
    constexpr size_t kMask = 4 - 1;

    std::fprintf(stdout, "== test_interleaving_proof ==\n");
    std::fprintf(stdout,
        "[INFO] Reproduciendo paso a paso el algoritmo REAL de push() (copiado de "
        "CommandQueue.h), intercalando manualmente 'hilo A' (UI) y 'hilo B' (MIDI) "
        "en el orden que el hardware puede producir sin ninguna sincronización "
        "que lo impida.\n\n");

    // ── Secuencia intercalada, paso a paso, EXACTAMENTE como push() la ejecuta ──

    // Paso 1 (hilo A - UI): lee tail ANTES de que nadie haya escrito nada.
    const size_t tailA = tail.load(std::memory_order_relaxed);
    std::fprintf(stdout, "[A] lee tail = %zu\n", tailA);

    // Paso 2 (hilo B - MIDI): el scheduler cede la CPU a B ANTES de que A
    // alcance a hacer su store — B lee EL MISMO tail, porque nada se lo impide.
    const size_t tailB = tail.load(std::memory_order_relaxed);
    std::fprintf(stdout, "[B] lee tail = %zu  <-- MISMO valor que A leyó (nada lo impide: ambos leen con memory_order_relaxed, sin ningún lock)\n", tailB);

    // Paso 3 (hilo A): comprueba que no está llena y escribe su comando.
    const size_t nextTailA = (tailA + 1) & kMask;
    bool fullA = (nextTailA == head.load(std::memory_order_acquire));
    if (!fullA) {
        buffer[tailA] = Cmd{/*origin=*/1 /*UI*/, /*value=*/111};
        std::fprintf(stdout, "[A] escribe buffer[%zu] = {origin=UI, value=111}\n", tailA);
    }

    // Paso 4 (hilo B): comprueba (con SU copia de tailB, igual a tailA) y
    // escribe SU comando en el MISMO índice — pisando lo que A acaba de escribir.
    const size_t nextTailB = (tailB + 1) & kMask;
    bool fullB = (nextTailB == head.load(std::memory_order_acquire));
    if (!fullB) {
        buffer[tailB] = Cmd{/*origin=*/2 /*MIDI*/, /*value=*/222};
        std::fprintf(stdout, "[B] escribe buffer[%zu] = {origin=MIDI, value=222}  <-- PISA el comando de A, que nunca llegará al consumidor\n", tailB);
    }

    // Paso 5 (hilo A): publica su avance de tail.
    tail.store(nextTailA, std::memory_order_release);
    std::fprintf(stdout, "[A] publica tail = %zu\n", nextTailA);

    // Paso 6 (hilo B): publica SU avance — como tailA == tailB, nextTailA ==
    // nextTailB, así que el índice no se corrompe, pero el slot ya fue
    // pisado en el paso 4. El índice queda consistente; el DATO no.
    tail.store(nextTailB, std::memory_order_release);
    std::fprintf(stdout, "[B] publica tail = %zu\n\n", nextTailB);

    // ── Ahora, el consumidor (audio thread) drena exactamente como pop() lo haría ──
    std::fprintf(stdout, "[CONSUMER] drenando...\n");
    int consumedCount = 0;
    while (true) {
        const size_t h = head.load(std::memory_order_relaxed);
        if (h == tail.load(std::memory_order_acquire)) break;
        Cmd out = buffer[h];
        head.store((h + 1) & kMask, std::memory_order_release);
        std::fprintf(stdout, "[CONSUMER] recibe {origin=%s, value=%d}\n",
                     out.origin == 1 ? "UI" : "MIDI", out.value);
        ++consumedCount;
    }

    std::fprintf(stdout,
        "\n[RESULTADO] Se ejecutaron 2 push() (uno de A, uno de B), ambos "
        "reportando \u00e9xito (fullA=%s, fullB=%s) — es decir, el llamador "
        "de push() en ambos hilos cree que su comando fue encolado. Pero el "
        "consumidor solo recibi\u00f3 %d comando(s).\n",
        fullA ? "true" : "false", fullB ? "true" : "false", consumedCount);

    if (consumedCount < 2) {
        std::fprintf(stdout,
            "[CONFIRMADO] El comando de origen UI se PERDI\u00f3 silenciosamente "
            "(pisado por el de MIDI) sin que droppedCount() lo reflejara "
            "(la escritura fue \"exitosa\" desde el punto de vista de cada "
            "hilo por separado) — esta es la data race real: dos hilos "
            "productores concurrentes sin serializar pueden perder comandos "
            "SIN que el mecanismo de diagn\u00f3stico existente (droppedCount) "
            "lo detecte. Esto es alcanzable con el algoritmo REAL de "
            "CommandQueue.h tal como est\u00e1 escrito hoy, sin ninguna "
            "modificaci\u00f3n — el \u00fanico motivo por el que NO ocurre siempre "
            "es que la ventana de colisi\u00f3n (ambos hilos leyendo `tail` antes "
            "de que cualquiera lo publique) es estrecha en tiempo real, no "
            "que el c\u00f3digo lo prevenga.\n");
        return 1;
    }
    return 0;
}
