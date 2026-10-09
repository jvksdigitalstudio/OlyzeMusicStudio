// BACKEND DE AUDIO FALSO para tests nativos — NO es Oboe.
//
// Implementa las funciones declaradas en platform_stubs/oboe/Oboe.h con la
// mínima lógica para que AudioEngine::start() abra un "stream" y el test
// pueda invocar AudioEngine::onAudioReady() a mano, sin hardware, sin hilo de
// audio y sin NDK.
//
// QUÉ permite probar: la lógica propia del motor (cola de comandos, reloj,
// metrónomo, cadena de FX) ejecutada de verdad, de forma determinista.
// QUÉ NO prueba: el comportamiento real de Oboe/AAudio (latencia, xruns,
// desconexiones, conversión de frecuencia). Esos aspectos solo son
// observables en un dispositivo.
#include <oboe/Oboe.h>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <memory>

// Frecuencia de muestreo que "negocia" el stream falso. Configurable desde el
// test para comprobar que el motor prepara sus componentes con la frecuencia
// REAL del stream y no con un valor por defecto. Por defecto 48000.
static int gFakeSampleRate = 48000;
extern "C" void eliner_fake_set_sample_rate(int sampleRate) { gFakeSampleRate = sampleRate; }

namespace oboe {

const char* convertToText(Result)      { return "fake-result"; }
const char* convertToText(SharingMode) { return "fake-sharing"; }
const char* convertToText(AudioApi)    { return "fake-api"; }

Result  AudioStream::requestStart()            { return Result::OK; }
Result  AudioStream::requestStop()             { return Result::OK; }
Result  AudioStream::close()                   { return Result::OK; }
int32_t AudioStream::getSampleRate() const     { return gFakeSampleRate; }
int32_t AudioStream::getFramesPerBurst()       { return 192; }
int32_t AudioStream::getBufferSizeInFrames()   { return 384; }
ResultWithValue<int32_t> AudioStream::setBufferSizeInFrames(int32_t n) { return {Result::OK, n}; }
ResultWithValue<int32_t> AudioStream::getXRunCount()                   { return {Result::OK, 0}; }
SharingMode AudioStream::getSharingMode() const { return SharingMode::Shared; }
AudioApi    AudioStream::getAudioApi() const    { return AudioApi::AAudio; }

AudioStreamBuilder* AudioStreamBuilder::setPerformanceMode(PerformanceMode)   { return this; }
AudioStreamBuilder* AudioStreamBuilder::setSharingMode(SharingMode)           { return this; }
AudioStreamBuilder* AudioStreamBuilder::setFormat(AudioFormat)                { return this; }
AudioStreamBuilder* AudioStreamBuilder::setChannelCount(int32_t)              { return this; }
AudioStreamBuilder* AudioStreamBuilder::setSampleRate(int32_t)                { return this; }
AudioStreamBuilder* AudioStreamBuilder::setSampleRateConversionQuality(SampleRateConversionQuality) { return this; }
AudioStreamBuilder* AudioStreamBuilder::setDataCallback(AudioStreamDataCallback*)   { return this; }
AudioStreamBuilder* AudioStreamBuilder::setErrorCallback(AudioStreamErrorCallback*) { return this; }
Result AudioStreamBuilder::openStream(std::shared_ptr<AudioStream>& stream) {
    stream = std::make_shared<AudioStream>();
    return Result::OK;
}

} // namespace oboe

// Log de Android: silencioso salvo que se defina ELINER_FAKE_LOG.
extern "C" int __android_log_print(int, const char* tag, const char* fmt, ...) {
    if (!std::getenv("ELINER_FAKE_LOG")) return 0;
    std::va_list ap; va_start(ap, fmt);
    std::fprintf(stderr, "[%s] ", tag);
    std::vfprintf(stderr, fmt, ap);
    std::fprintf(stderr, "\n");
    va_end(ap);
    return 0;
}
