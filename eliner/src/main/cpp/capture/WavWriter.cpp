#include "WavWriter.h"

#include <cstring>

namespace eliner {

static_assert(sizeof(float) == 4, "WAV float32 exige float de 4 bytes");

namespace {

constexpr bool isLittleEndian() {
#if defined(__BYTE_ORDER__) && defined(__ORDER_LITTLE_ENDIAN__)
    return __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__;
#else
    return true;
#endif
}
static_assert(isLittleEndian(), "WavWriter escribe campos little-endian directamente: solo ABIs little-endian");

void put32(unsigned char* p, std::uint32_t v) {
    p[0] = static_cast<unsigned char>(v);
    p[1] = static_cast<unsigned char>(v >> 8);
    p[2] = static_cast<unsigned char>(v >> 16);
    p[3] = static_cast<unsigned char>(v >> 24);
}
void put16(unsigned char* p, std::uint16_t v) {
    p[0] = static_cast<unsigned char>(v);
    p[1] = static_cast<unsigned char>(v >> 8);
}

// Un campo de 32 bits del encabezado no puede representar más: se satura (archivos de > 4 GiB no son WAV válidos
// de todos modos y el RF64 queda fuera de alcance).
std::uint32_t clamp32(std::uint64_t v) { return v > 0xFFFFFFFFull ? 0xFFFFFFFFu : static_cast<std::uint32_t>(v); }

} // namespace

WavWriter::~WavWriter() { (void)close(); }

bool WavWriter::writeHeader(std::uint64_t frames) {
    unsigned char h[kHeaderBytes] = {};
    const std::uint64_t dataBytes = frames * static_cast<std::uint64_t>(mChannels) * 4u;
    std::memcpy(h + 0, "RIFF", 4);
    put32(h + 4, clamp32(kHeaderBytes - 8 + dataBytes));
    std::memcpy(h + 8, "WAVE", 4);
    std::memcpy(h + 12, "fmt ", 4);
    put32(h + 16, 16);                                            // tamaño del bloque fmt
    put16(h + 20, 3);                                             // WAVE_FORMAT_IEEE_FLOAT
    put16(h + 22, static_cast<std::uint16_t>(mChannels));
    put32(h + 24, static_cast<std::uint32_t>(mRate));
    put32(h + 28, static_cast<std::uint32_t>(mRate) * static_cast<std::uint32_t>(mChannels) * 4u); // byte rate
    put16(h + 32, static_cast<std::uint16_t>(mChannels * 4));      // block align
    put16(h + 34, 32);                                            // bits por muestra
    std::memcpy(h + 36, "fact", 4);
    put32(h + 40, 4);
    put32(h + 44, clamp32(frames));                               // frames por canal
    std::memcpy(h + 48, "data", 4);
    put32(h + 52, clamp32(dataBytes));
    return std::fwrite(h, 1, kHeaderBytes, mFile) == kHeaderBytes;
}

bool WavWriter::open(const char* path, int sampleRate, int channels) {
    if (mFile != nullptr || path == nullptr || sampleRate <= 0 || channels < 1 || channels > 8) return false;
    std::FILE* f = std::fopen(path, "wb");
    if (f == nullptr) return false;
    mFile = f; mRate = sampleRate; mChannels = channels; mFrames = 0; mFailed = false;
    if (!writeHeader(0)) {                 // reserva el encabezado; se reescribe al cerrar
        std::fclose(mFile); mFile = nullptr;
        return false;
    }
    mLastClose = true;
    return true;
}

bool WavWriter::write(const float* frames, std::uint64_t frameCount) {
    if (mFile == nullptr || mFailed) return false;
    if (frameCount == 0) return true;
    if (frames == nullptr) return false;
    const std::size_t samples = static_cast<std::size_t>(frameCount) * static_cast<std::size_t>(mChannels);
    if (std::fwrite(frames, sizeof(float), samples, mFile) != samples) { mFailed = true; return false; }
    mFrames += frameCount;
    return true;
}

bool WavWriter::close() {
    if (mFile == nullptr) return mLastClose;
    bool ok = !mFailed;
    if (ok) ok = std::fseek(mFile, 0, SEEK_SET) == 0 && writeHeader(mFrames);
    if (std::fclose(mFile) != 0) ok = false;      // el volcado final puede fallar (disco lleno)
    mFile = nullptr;
    mLastClose = ok;
    return ok;
}

} // namespace eliner
