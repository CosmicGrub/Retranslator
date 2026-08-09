plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.retroid.translator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.retroid.translator"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Target device (Retroid Pocket 2+) is arm64-v8a only; restricting the
        // ABI keeps the bundled native libs (espeak-ng, Vosk/JNA) from bloating
        // the APK with unused architectures.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Google ML Kit - on-device, free, no API key, no billing.
    implementation("com.google.mlkit:translate:17.0.2")
    implementation("com.google.mlkit:language-id:17.0.5")

    // Vosk - fully offline, open-source (Apache-2.0) on-device speech-to-text.
    // No cloud, no API key. Native libs + JNA come bundled in the AAR.
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    // eSpeak NG (GPL-3.0, bundled as jniLibs/libttsespeak.so + assets/espeak-ng-data,
    // driven via the vendored com.reecedunn.espeak JNI bridge) provides fully
    // offline, in-app text-to-speech with no separate engine app required.

    // sherpa-onnx (Apache-2.0, k2-fsa project) - neural TTS runtime that drives
    // downloaded Piper VITS voice models for natural-sounding speech. There is
    // no official Maven Central artifact, so this vendors the same two pieces
    // the upstream release AAR (github.com/k2-fsa/sherpa-onnx/releases, tag
    // v1.13.4, sherpa-onnx-1.13.4.aar) contains for our one target ABI: the
    // compiled Kotlin/Java API classes (libs/sherpa-onnx-classes.jar) and the
    // arm64-v8a native libs (jniLibs/arm64-v8a/lib{onnxruntime,sherpa-onnx-*}.so)
    // extracted from that AAR, mirroring how libttsespeak.so is already vendored
    // rather than pulling in the other 3 unused ABIs.
    implementation(files("libs/sherpa-onnx-classes.jar"))

    // Piper voice packs are distributed as .tar.bz2 (not .zip like the Vosk
    // packs), so the download path needs real tar+bzip2 support - the JDK's
    // java.util.zip only understands zip/gzip. Apache Commons Compress
    // (Apache-2.0, Maven Central) is the standard, well-known library for this.
    implementation("org.apache.commons:commons-compress:1.26.1")
}
