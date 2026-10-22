#include "TakeRecorder.h"

#include <algorithm>
#include <chrono>
#include <vector>

namespace eliner {

namespace {
constexpr std::size_t kChunkFrames = 4096;                       // frames que el escritor retira por vuelta
constexpr auto        kIdleSleep   = std::chrono::milliseconds(2); // espera del escritor con el anillo vacío
} // namespace

TakeRecorder::~TakeRecorder() { (void)end(); }

bool TakeRecorder::begin(const char* path, const Config& config) {
    std::lock_guard<std::mutex> lock(mControl);
    if (mBegun || config.trimFrames < 0) return false;
    const std::size_t ringFrames =
        config.ringFrames != 0 ? config.ringFrames : static_cast<std::size_t>(std::max(config.sampleRate, 1)) * 2;
    if (!mRing.init(ringFrames, config.channels)) return false;
    if (!mWav.open(path, config.sampleRate, config.channels)) return false;

    mChannels      = config.channels;
    mTrimRemaining = config.trimFrames;
    mTrimmed.store(0, std::memory_order_relaxed);
    mWriteFailed.store(false, std::memory_order_relaxed);
    mStopWriter.store(false, std::memory_order_relaxed);
    mWriter = std::thread([this] { writerLoop(); });
    mBegun  = true;
    mActive.store(true, std::memory_order_release);              // desde aquí onInput() acepta muestras
    return true;
}

void TakeRecorder::onInput(const float* frames, std::size_t frameCount) {
    if (!mActive.load(std::memory_order_acquire)) return;
    // «En vuelo»: end() espera a que este contador baje a 0 antes de cerrar, de modo que una llamada que ya pasó
    // la comprobación de arriba no escribe en un anillo que se está dando por terminado.
    mPushing.fetch_add(1, std::memory_order_acq_rel);
    if (mActive.load(std::memory_order_acquire)) (void)mRing.push(frames, frameCount);
    mPushing.fetch_sub(1, std::memory_order_acq_rel);
}

TakeRecorder::Result TakeRecorder::end() {
    std::lock_guard<std::mutex> lock(mControl);
    Result r;
    if (!mBegun) return r;

    mActive.store(false, std::memory_order_release);             // no se aceptan más muestras…
    while (mPushing.load(std::memory_order_acquire) != 0) std::this_thread::yield(); // …y se espera a las de vuelo
    mStopWriter.store(true, std::memory_order_release);          // el escritor vacía el anillo y termina
    if (mWriter.joinable()) mWriter.join();

    r.framesWritten = mWav.framesWritten();
    r.trimmedFrames = mTrimmed.load(std::memory_order_relaxed);
    r.droppedFrames = mRing.droppedFrames();
    r.ok            = mWav.close() && !mWriteFailed.load(std::memory_order_relaxed);
    mBegun          = false;
    return r;
}

void TakeRecorder::writerLoop() {
    std::vector<float> chunk(kChunkFrames * static_cast<std::size_t>(mChannels));
    const std::size_t ch = static_cast<std::size_t>(mChannels);
    for (;;) {
        // La señal de parada se lee ANTES de retirar del anillo. end() solo la activa cuando ya no hay pushes en
        // vuelo (mActive = false y mPushing = 0, con release), así que si aquí se ve «parando», todo lo aceptado ya
        // está en el anillo y un pop() que devuelva 0 significa vacío de verdad: salir no pierde nada. (Leerla
        // DESPUÉS exigiría una segunda comprobación para no perder una muestra aceptada entre medias.)
        const bool stopping = mStopWriter.load(std::memory_order_acquire);
        const std::size_t n = mRing.pop(chunk.data(), kChunkFrames);
        if (n == 0) {
            if (stopping) break;
            std::this_thread::sleep_for(kIdleSleep);
            continue;
        }
        std::size_t skip = 0;
        if (mTrimRemaining > 0) {
            skip = static_cast<std::size_t>(std::min<std::int64_t>(mTrimRemaining, static_cast<std::int64_t>(n)));
            mTrimRemaining -= static_cast<std::int64_t>(skip);
            mTrimmed.fetch_add(skip, std::memory_order_relaxed);
        }
        if (skip < n && !mWav.write(chunk.data() + skip * ch, n - skip)) {
            mWriteFailed.store(true, std::memory_order_relaxed); // se sigue vaciando el anillo para no atascarlo
        }
    }
}

} // namespace eliner
