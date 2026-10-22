#pragma once
// Macros de log de Android compartidas por las unidades de compilación de
// AudioEngine (AudioEngine*.cpp). Privado de src/: NO es API pública y NO debe
// incluirse desde un header de include/ (hacerlo arrastraría <android/log.h> a
// todos los consumidores). Nunca usar LOGx en el hilo de audio (hace E/S).
#include <android/log.h>

#define LOG_TAG "EliNerAudioCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
