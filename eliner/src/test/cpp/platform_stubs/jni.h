#pragma once
// STUB de verificación de compilación — NO es el jni.h real.
// Solo los tipos primitivos y macros que usan los puentes JNI de EliNer
// (ninguno invoca métodos de JNIEnv). La compilación real la hace el NDK.
#include <cstdint>

typedef std::uint8_t  jboolean;
typedef std::int32_t  jint;
typedef std::int64_t  jlong;
typedef float         jfloat;
typedef double        jdouble;
typedef void*         jobject;
struct JNIEnv_ {};
typedef JNIEnv_       JNIEnv;

#define JNI_FALSE 0
#define JNI_TRUE  1
#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL
