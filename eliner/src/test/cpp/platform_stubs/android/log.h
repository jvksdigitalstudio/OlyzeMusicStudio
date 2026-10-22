#pragma once
// STUB de verificación de compilación — NO es la API real de Android.
// Solo declara lo que usa el código nativo de EliNer.
enum android_LogPriority { ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };
extern "C" int __android_log_print(int prio, const char* tag, const char* fmt, ...)
    __attribute__((format(printf, 3, 4)));
