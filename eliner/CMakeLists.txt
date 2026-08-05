cmake_minimum_required(VERSION 3.22.1)

# ──────────────────────────────────────────────────────────────────────────
# EliNer Audio Core — native engine for Olyze Music Studio (YeiViKas Digital
# Studio). This is the "EliNer Engine Core" layer of the architecture:
#
#   UI  →  EliNer API Layer (Kotlin)  →  EliNer Engine Core (this target)  →
#   independent native modules (dsp/, fx/, mixer/)
#
# NOTE: this target currently contains the audio engine that already existed
# in the project (voice synthesis, reverb, delay, mixer, Oboe I/O). It has
# only been reorganized into clearly-scoped folders; no new engine features
# (VST hosting, plugin system, full DSP suite, render engine, etc.) have been
# added — those remain future phases per the project roadmap.
# ──────────────────────────────────────────────────────────────────────────
project(eliner_audio_core VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Oboe — fetched directly from source (most reliable method) ──
# Official Google audio library: https://github.com/google/oboe
include(FetchContent)
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG        1.9.3
)
FetchContent_MakeAvailable(oboe)

# ── Source files, grouped by module responsibility ──
add_library(
    eliner_audio_core
    SHARED

    # core — engine orchestration + JNI bridge (EliNer API Layer entry point)
    src/main/cpp/core/AudioEngine.cpp
    src/main/cpp/core/EliNerAudioBridge.cpp

    # dsp — sound generation (oscillators, envelopes, filters, voices)
    src/main/cpp/dsp/SynthVoice.cpp

    # fx — time-based effects
    src/main/cpp/fx/Reverb.cpp
    src/main/cpp/fx/Delay.cpp
)

# ── Link libraries ──
target_link_libraries(
    eliner_audio_core
    oboe
    android
    log
    atomic
)

# ── Include directories ──
# Public headers now live under include/eliner/<module>/ (separated from
# their .cpp implementation under src/main/cpp/<module>/, per the
# include/src split of Fase 3). Each module folder is exposed so quoted
# includes (e.g. AudioEngine.h -> "SynthVoice.h", "Mixer.h", "Reverb.h",
# "Delay.h") resolve without hardcoding relative "../" paths between them.
target_include_directories(eliner_audio_core PRIVATE
    include/eliner
    include/eliner/core
    include/eliner/dsp
    include/eliner/fx
    include/eliner/mixer
    ${oboe_SOURCE_DIR}/include
)

# ── Compiler optimizations for audio performance ──
target_compile_options(eliner_audio_core PRIVATE
    -O3
    -ffast-math
    -funroll-loops
    -fno-exceptions
    -DNDEBUG
)
