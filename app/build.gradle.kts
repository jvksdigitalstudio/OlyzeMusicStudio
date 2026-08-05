plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yeivikas.olyze"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yeivikas.olyze"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // NDK ABI filters — must mirror :eliner's filters so the app
        // packages the same set of native .so ABIs that the engine module
        // builds. The engine itself (native audio code, CMake, C++20) now
        // lives entirely in the :eliner module — see eliner/build.gradle.kts
        // and eliner/CMakeLists.txt.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // EliNer — the engine module. This is the ONLY place :app is allowed to
    // depend on EliNer's compiled artifact; individual files inside :app
    // must never import EliNer's internals other than what's public in
    // eliner.api / eliner.bridge (see eliner/src/main/java/.../eliner).
    implementation(project(":eliner"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.kotlinx.coroutines.android)

    // NOTE: Oboe and all native/CMake config now live entirely in :eliner
    // (see eliner/CMakeLists.txt and eliner/build.gradle.kts). :app no
    // longer references native code directly in any way.

    debugImplementation(libs.androidx.ui.tooling)
}
