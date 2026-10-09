#pragma once
// STUB de verificación de compilación — NO es Oboe.
//
// Declara únicamente las firmas de la API de Oboe que usa EliNer, con la
// misma forma (tipos, constness, retornos) que la API real, para que
// `g++ -fsyntax-only` pueda COMPILAR AudioEngine.cpp y los puentes JNI sin
// NDK ni descargar Oboe (check_engine_compile.sh). Las definiciones de
// oboe_fake.cpp son un BACKEND DE AUDIO FALSO que permite además EJECUTAR el
// motor en tests (invocando onAudioReady a mano); NO es Oboe ni modela su
// comportamiento. La compilación contra Oboe real la hace el CI (NDK).
#include <cstdint>
#include <memory>

namespace oboe {

enum class Result : int32_t { OK = 0, ErrorDisconnected = -899, ErrorInternal = -896 };
enum class DataCallbackResult : int32_t { Continue = 0, Stop = 1 };
enum class PerformanceMode : int32_t { None = 10, PowerSaving = 11, LowLatency = 12 };
enum class SharingMode : int32_t { Exclusive = 0, Shared = 1 };
enum class AudioFormat : int32_t { Invalid = -1, Unspecified = 0, I16 = 1, Float = 2 };
enum class AudioApi : int32_t { Unspecified = 0, OpenSLES = 1, AAudio = 2 };
enum class SampleRateConversionQuality : int32_t { None, Fastest, Low, Medium, High, Best };
enum ChannelCount : int32_t { Unspecified = 0, Mono = 1, Stereo = 2 };

const char* convertToText(Result);
const char* convertToText(SharingMode);
const char* convertToText(AudioApi);

template <typename T>
class ResultWithValue {
public:
    ResultWithValue(Result r, T v) : mResult(r), mValue(v) {}
    explicit operator bool() const { return mResult == Result::OK; }
    T value() const { return mValue; }
    Result error() const { return mResult; }
private:
    Result mResult = Result::OK;
    T      mValue{};
};

class AudioStream;

class AudioStreamDataCallback {
public:
    virtual ~AudioStreamDataCallback() = default;
    virtual DataCallbackResult onAudioReady(AudioStream* stream, void* audioData, int32_t numFrames) = 0;
};

class AudioStreamErrorCallback {
public:
    virtual ~AudioStreamErrorCallback() = default;
    virtual void onErrorAfterClose(AudioStream* stream, Result error) { (void)stream; (void)error; }
};

class AudioStream {
public:
    virtual ~AudioStream() = default;
    Result requestStart();
    Result requestStop();
    Result close();
    int32_t getSampleRate() const;
    int32_t getFramesPerBurst();
    int32_t getBufferSizeInFrames();
    ResultWithValue<int32_t> setBufferSizeInFrames(int32_t requestedFrames);
    ResultWithValue<int32_t> getXRunCount();
    SharingMode getSharingMode() const;
    AudioApi getAudioApi() const;
};

class AudioStreamBuilder {
public:
    AudioStreamBuilder* setPerformanceMode(PerformanceMode);
    AudioStreamBuilder* setSharingMode(SharingMode);
    AudioStreamBuilder* setFormat(AudioFormat);
    AudioStreamBuilder* setChannelCount(int32_t);
    AudioStreamBuilder* setSampleRate(int32_t);
    AudioStreamBuilder* setSampleRateConversionQuality(SampleRateConversionQuality);
    AudioStreamBuilder* setDataCallback(AudioStreamDataCallback*);
    AudioStreamBuilder* setErrorCallback(AudioStreamErrorCallback*);
    Result openStream(std::shared_ptr<AudioStream>& stream);
};

} // namespace oboe
