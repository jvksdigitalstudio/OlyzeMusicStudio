#pragma once
// Anillo SPSC (un productor, un consumidor) sin bloqueos para llevar las muestras capturadas del hilo de audio al
// hilo que escribe en disco (ADR 0038).
//
// El hilo de audio NO puede escribir en disco, ni bloquear, ni reservar memoria: solo copia sus muestras aquí
// (O(n), sin llamadas al sistema) y otro hilo las retira y las escribe cuando puede. Si el disco se atrasa y el
// anillo se llena, las muestras que no caben se DESCARTAN y se cuentan (droppedFrames), nunca se bloquea el audio:
// un hueco en la toma es un fallo recuperable; un xrun en el callback, no.
//
// ── Contrato de hilos ─────────────────────────────────────────────────────────────────────────────────
//   init():                       cualquier hilo, SIN actividad concurrente (reserva memoria).
//   push():                       SOLO el productor (hilo de audio). Tiempo real: sin reservas ni candados.
//   pop(), available():           SOLO el consumidor. (available() es seguro desde ambos, es una instantánea.)
//   droppedFrames():              cualquier hilo.
// Los índices son contadores de 64 bits que solo crecen (nunca se reinician entre push/pop), de modo que
// «lleno» y «vacío» se distinguen sin desperdiciar una posición y no hay ABA que valga.
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace eliner {

class CaptureRing {
public:
    // Reserva espacio para al menos [minFrames] frames de [channels] canales (se redondea a potencia de 2).
    // Devuelve false con parámetros inválidos. Reinicia el contenido y los contadores.
    bool init(std::size_t minFrames, int channels) {
        if (minFrames == 0 || channels < 1 || channels > kMaxChannels) return false;
        std::size_t cap = 1;
        while (cap < minFrames) cap <<= 1;
        mChannels = channels;
        mCapacity = cap;
        mMask     = cap - 1;
        mBuffer.assign(cap * static_cast<std::size_t>(channels), 0.0f);
        mWrite.store(0, std::memory_order_relaxed);
        mRead.store(0, std::memory_order_relaxed);
        mDropped.store(0, std::memory_order_relaxed);
        return true;
    }

    int         channels() const { return mChannels; }
    std::size_t capacityFrames() const { return mCapacity; }

    // PRODUCTOR. Copia hasta [frames] frames intercalados desde [src]; devuelve cuántos cupieron. Lo que no cabe
    // se descarta y se suma a droppedFrames().
    std::size_t push(const float* src, std::size_t frames) {
        if (mCapacity == 0 || src == nullptr || frames == 0) return 0;
        const std::uint64_t w    = mWrite.load(std::memory_order_relaxed);
        const std::uint64_t r    = mRead.load(std::memory_order_acquire);
        const std::size_t   free = mCapacity - static_cast<std::size_t>(w - r);
        const std::size_t   n    = frames < free ? frames : free;
        if (n < frames) mDropped.fetch_add(frames - n, std::memory_order_relaxed);
        if (n == 0) return 0;
        copyIn(static_cast<std::size_t>(w & mMask), src, n);
        mWrite.store(w + n, std::memory_order_release);
        return n;
    }

    // CONSUMIDOR. Retira hasta [maxFrames] frames hacia [dst]; devuelve cuántos.
    std::size_t pop(float* dst, std::size_t maxFrames) {
        if (mCapacity == 0 || dst == nullptr || maxFrames == 0) return 0;
        const std::uint64_t r     = mRead.load(std::memory_order_relaxed);
        const std::uint64_t w     = mWrite.load(std::memory_order_acquire);
        const std::size_t   avail = static_cast<std::size_t>(w - r);
        const std::size_t   n     = maxFrames < avail ? maxFrames : avail;
        if (n == 0) return 0;
        copyOut(static_cast<std::size_t>(r & mMask), dst, n);
        mRead.store(r + n, std::memory_order_release);
        return n;
    }

    std::size_t available() const {
        const std::uint64_t w = mWrite.load(std::memory_order_acquire);
        const std::uint64_t r = mRead.load(std::memory_order_acquire);
        return static_cast<std::size_t>(w - r);
    }

    std::uint64_t droppedFrames() const { return mDropped.load(std::memory_order_relaxed); }

    static constexpr int kMaxChannels = 8;

private:
    void copyIn(std::size_t startFrame, const float* src, std::size_t n) {
        const std::size_t ch    = static_cast<std::size_t>(mChannels);
        const std::size_t first = (mCapacity - startFrame) < n ? (mCapacity - startFrame) : n;
        std::memcpy(&mBuffer[startFrame * ch], src, first * ch * sizeof(float));
        if (first < n) std::memcpy(&mBuffer[0], src + first * ch, (n - first) * ch * sizeof(float));
    }
    void copyOut(std::size_t startFrame, float* dst, std::size_t n) {
        const std::size_t ch    = static_cast<std::size_t>(mChannels);
        const std::size_t first = (mCapacity - startFrame) < n ? (mCapacity - startFrame) : n;
        std::memcpy(dst, &mBuffer[startFrame * ch], first * ch * sizeof(float));
        if (first < n) std::memcpy(dst + first * ch, &mBuffer[0], (n - first) * ch * sizeof(float));
    }

    std::vector<float>           mBuffer;
    std::size_t                  mCapacity = 0;
    std::size_t                  mMask     = 0;
    int                          mChannels = 0;
    std::atomic<std::uint64_t>   mWrite{0};
    std::atomic<std::uint64_t>   mRead{0};
    std::atomic<std::uint64_t>   mDropped{0};
};

} // namespace eliner
