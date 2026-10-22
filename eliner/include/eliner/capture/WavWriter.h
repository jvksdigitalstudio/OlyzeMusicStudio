#pragma once
// Escritor de archivos WAV de 32 bits en coma flotante (IEEE float, formato 3), en streaming (ADR 0038).
//
// Hilo NO de tiempo real: abre, escribe y cierra archivos (E/S bloqueante). Lo usa únicamente el hilo escritor de
// TakeRecorder, jamás el de audio.
//
// Formato: RIFF/WAVE con `fmt ` (16 bytes, tag 3 = IEEE_FLOAT), `fact` (obligatorio para formatos que no son PCM:
// número de frames) y `data`. Las muestras se guardan tal cual, sin conversión ni pérdida: la toma original se
// conserva a la calidad con que entró y cualquier conversión posterior es decisión del usuario.
// Los tamaños del encabezado se reescriben al cerrar, así que un archivo cuyo cierre no llegó a ejecutarse
// (cierre brusco de la app) queda con tamaños a 0 y es recuperable leyendo hasta el final del archivo.
//
// Little-endian: es el orden de todas las ABI de Android (ARM, ARM64, x86, x86_64); se verifica en compilación.
#include <cstdint>
#include <cstdio>

namespace eliner {

class WavWriter {
public:
    WavWriter() = default;
    ~WavWriter();
    WavWriter(const WavWriter&) = delete;
    WavWriter& operator=(const WavWriter&) = delete;

    // Abre [path] (lo crea o lo sobrescribe) y escribe el encabezado. false si falla o los parámetros no son
    // válidos (frecuencia ≤ 0, canales fuera de 1..8). Si ya hay un archivo abierto, devuelve false.
    bool open(const char* path, int sampleRate, int channels);

    // Añade [frames] frames intercalados. false si no hay archivo abierto o la escritura falla (disco lleno): tras
    // un fallo el escritor deja de aceptar datos y close() devuelve false.
    bool write(const float* frames, std::uint64_t frameCount);

    // Reescribe los tamaños del encabezado y cierra. Devuelve true si TODO fue bien (también el volcado final).
    // Idempotente: sin archivo abierto devuelve el resultado del último cierre.
    bool close();

    bool          isOpen() const { return mFile != nullptr; }
    std::uint64_t framesWritten() const { return mFrames; }

    // Tamaño del encabezado en bytes: RIFF(12) + fmt(24) + fact(12) + data(8).
    static constexpr std::uint32_t kHeaderBytes = 56;

private:
    bool writeHeader(std::uint64_t frames);

    std::FILE*    mFile     = nullptr;
    int           mRate     = 0;
    int           mChannels = 0;
    std::uint64_t mFrames   = 0;
    bool          mFailed   = false;
    bool          mLastClose = true;
};

} // namespace eliner
