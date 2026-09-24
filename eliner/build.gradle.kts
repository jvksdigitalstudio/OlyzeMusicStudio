plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// ─────────────────────────────────────────────────────────────────────────
// :eliner — the EliNer engine module.
//
// This module is INDEPENDENT of the Olyze Music Studio app: it has zero
// dependency on `:app` and zero dependency on any UI framework (no Compose
// here — notice there's no `kotlin.compose` plugin and no `buildFeatures {
// compose = true }`, unlike `:app`). That is intentional and is the whole
// point of Fase 3: EliNer must be reusable by other apps in the future
// (Olyze Movie Creator, etc.) without dragging along Compose, Activities,
// or anything Olyze-Music-Studio-specific.
//
// Dependency direction: :app -> :eliner (never the other way around).
// ─────────────────────────────────────────────────────────────────────────
android {
    namespace = "com.yeivikas.olyze.eliner"
    compileSdk = 35

    // Same pin, same reasoning, as app/build.gradle.kts — must match
    // exactly, since :app and :eliner build native code against the same
    // toolchain in the same CI run.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 24

        // Must mirror :app's ABI filters so the native .so ships for every
        // ABI the app actually packages.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // C++20 + android-24: see eliner/CMakeLists.txt for the engine
        // itself. No engine behavior changes here — same native code as
        // before Fase 3, just relocated into its own module.
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-O3", "-ffast-math")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-24"
                )
            }
        }
    }

    // External native build — CMakeLists.txt now lives at the root of this
    // module (eliner/CMakeLists.txt), not inside src/main/.
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // The only external dependency EliNer's Kotlin layer needs today:
    // kotlinx.coroutines.flow.{StateFlow, MutableStateFlow} in EliNerAudioApi
    // / EliNerAudioBridge. Deliberately NOT depending on any AndroidX
    // lifecycle/Compose artifact — those belong to :app, not to the engine.
    implementation(libs.kotlinx.coroutines.android)

    // First test dependency this project has ever had (MIDI Foundation
    // phase, §41). JUnit4 only, no Robolectric/instrumented-test
    // framework — every test added so far targets pure Kotlin logic with
    // zero `android.*` dependency (MidiStreamParser, MidiParameterBinding),
    // which is exactly the "Core testeable sin Android" principle §24 of
    // the Fase 6 hardening prompt already established for this project.
    testImplementation(libs.junit)
}
