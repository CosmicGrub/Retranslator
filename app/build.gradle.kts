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

    // Wear OS Data Layer API - phone-side scaffold for the
    // watch6-classic-adaptation (docs/specs/watch6-classic-adaptation.md,
    // com.retroid.translator.wearsync.PhoneWearSyncService). Same version
    // as the :wear module's own dependency.
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    // Fold-state / hinge-geometry detection for the Z Fold 5 adaptation
    // (docs/specs/fold5-adaptation.md §2/§3) - WindowInfoTracker/FoldingFeature.
    // 1.5.1 is the latest stable release (1.6.0 is alpha-only as of writing;
    // checked against Google's Maven metadata rather than assumed).
    implementation("androidx.window:window:1.5.1")
    // Flow collection (WindowInfoTracker.windowLayoutInfo is Flow-based) and
    // lifecycleScope/repeatOnLifecycle both need a real coroutines dependency
    // on the classpath rather than relying on it arriving transitively.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Google ML Kit - on-device, free, no API key, no billing.
    // translate bumped 17.0.2 -> 17.0.3: 17.0.2's libtranslate_jni.so ships
    // with a 4KB-only PT_LOAD alignment, flagged by Android's 16KB page-size
    // compatibility check (confirmed via direct ELF inspection); 17.0.3 is
    // 16KB-aligned. See docs/specs/fold5-adaptation.md for the full audit.
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.5")

    // Camera OCR translate (docs/specs/fold5-adaptation.md "Camera OCR
    // translate" section). CameraX for the capture UI - this app had zero
    // existing camera code before this feature. The actual latest stable
    // release is 1.5.1, but it requires compileSdk 35 + AGP 8.6+ (confirmed
    // by a real, failed build attempt: `checkDebugAarMetadata` rejected it
    // against this project's compileSdk 34 / AGP 8.3.2) - bumping the
    // whole project's compileSdk/AGP/Gradle wrapper to chase the newest
    // CameraX point release was judged out of scope for this feature (a
    // wider-reaching, riskier change than this task called for), so this
    // pins to 1.3.4, the last 1.3.x stable release, confirmed compatible
    // with this project's existing compileSdk 34 by that same real build
    // succeeding once pinned here. camera-core comes in transitively via
    // camera-camera2/camera-lifecycle/camera-view, not declared separately.
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // ML Kit Text Recognition v2, Latin script - bundled variant
    // (com.google.mlkit, not com.google.android.gms:play-services-mlkit-*):
    // the model ships inside the APK (~4MB) and is available immediately,
    // no RemoteModelManager-style download step exists for it at all (this
    // differs from Translate/Language-ID above, which DO use
    // RemoteModelManager - Text Recognition's bundled variant simply has no
    // download to gate). Covers every Latin-alphabet language this app
    // already supports (English, Spanish, French, German, ...) - the large
    // majority of real camera-OCR use.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // One additional, on-demand non-Latin script pack - Chinese - using the
    // UNBUNDLED Play-services variant instead, specifically because it DOES
    // have genuine on-demand-download semantics (unlike the bundled
    // artifacts above), which lets this feature's "script pack not
    // downloaded yet" edge case be real rather than hypothetical. This is a
    // real, different download mechanism from RemoteModelManager -
    // com.google.android.gms.common.moduleinstall.ModuleInstallClient - not
    // a re-skin of the existing pattern; see OcrEngine.kt's doc comment for
    // why. Japanese/Korean/Devanagari packs are NOT added this pass: they'd
    // mirror this exact same ModuleInstallClient code path per-script, but
    // no real Japanese/Korean/Devanagari text sample was available this
    // session to verify them end-to-end on-device, and this project's house
    // style doesn't ship an unverified code path just because it would
    // compile - see the spec's Camera OCR section for the full reasoning.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")
    implementation("com.google.android.gms:play-services-base:18.5.0")

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
