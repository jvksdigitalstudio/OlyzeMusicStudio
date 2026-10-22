#pragma once
// Lector mínimo de WAV float32 para los tests de captura (ADR 0038). Solo lo que el escritor produce.
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

struct WavData {
    bool          valid = false;
    int           channels = 0;
    int           sampleRate = 0;
    int           formatTag = 0;
    int           bits = 0;
    std::uint32_t riffSize = 0, factFrames = 0, dataBytes = 0;
    std::uint64_t fileBytes = 0;
    std::vector<float> samples;      // intercaladas
    std::uint64_t frames() const { return channels ? samples.size() / static_cast<std::uint64_t>(channels) : 0; }
};

inline std::uint32_t rd32(const unsigned char* p) { return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<std::uint32_t>(p[3]) << 24); }
inline std::uint16_t rd16(const unsigned char* p) { return static_cast<std::uint16_t>(p[0] | (p[1] << 8)); }

inline WavData readWav(const std::string& path) {
    WavData w;
    std::FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return w;
    std::fseek(f, 0, SEEK_END);
    const long size = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    std::vector<unsigned char> b(static_cast<size_t>(size));
    if (size > 0) { if (std::fread(b.data(), 1, b.size(), f) != b.size()) { std::fclose(f); return w; } }
    std::fclose(f);
    w.fileBytes = static_cast<std::uint64_t>(size);
    if (size < 56) return w;
    if (std::memcmp(&b[0], "RIFF", 4) || std::memcmp(&b[8], "WAVE", 4) || std::memcmp(&b[12], "fmt ", 4)
        || std::memcmp(&b[36], "fact", 4) || std::memcmp(&b[48], "data", 4)) return w;
    w.riffSize   = rd32(&b[4]);
    w.formatTag  = rd16(&b[20]);
    w.channels   = rd16(&b[22]);
    w.sampleRate = static_cast<int>(rd32(&b[24]));
    w.bits       = rd16(&b[34]);
    w.factFrames = rd32(&b[44]);
    w.dataBytes  = rd32(&b[52]);
    const size_t n = (b.size() - 56) / 4;
    w.samples.resize(n);
    if (n) std::memcpy(w.samples.data(), &b[56], n * 4);
    w.valid = true;
    return w;
}
