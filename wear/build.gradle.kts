plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.retroid.translator.wear"
    // Real Watch6 Classic (SM-R965U) confirmed API 36 (Android 16) this
    // session via `adb shell getprop ro.build.version.sdk`; targeting/
    // compiling against 34 (Wear OS 5) rather than 36 because that's the
    // newest Wear-tagged platform+system-image actually available in this
    // machine's SDK (`sdkmanager --list` had no android-36 android-wear
    // image at time of writing) - Android's backward compat means a 34-
    // targeted app runs fine on the real API-36 device, confirmed by the
    // real-device install/launch evidence in
    // docs/specs/watch6-classic-adaptation.md.
    compileSdk = 34

    defaultConfig {
        applicationId = "com.retroid.translator.wear"
        // Wear OS 3 (API 30) is Google's current realistic floor for new
        // Wear Compose apps (Wear OS 1/2's XML-based Activity model is
        // effectively dead; Wear OS 3 unified Samsung/Google's platforms in
        // 2021 and is what current androidx.wear.compose targets) - not the
        // phone app's minSdk 28, which predates Wear OS 3 entirely and
        // would be wrong here.
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Real hardware finding (docs/specs/watch6-classic-adaptation.md
        // §Hard technical question): the connected real Galaxy Watch6
        // Classic (SM-R965U, Exynos W930) reports
        // `ro.product.cpu.abilist=armeabi-v7a,armeabi` - NO 64-bit ABI at
        // all, despite the Exynos W930 being a 64-bit-capable chip. This is
        // a real, verified constraint, not an assumption - Samsung's Wear OS
        // "Powered by Samsung" line (Watch4/5/6) ships a 32-bit-only
        // userspace. x86_64 is kept alongside it purely so this module can
        // also build+run on this dev machine's Wear OS emulator (Windows
        // host, no ARM hardware acceleration available, so only x86_64
        // Wear system images are practical to actually boot) - it is NOT
        // what would ship to the real device. arm64-v8a is deliberately
        // OMITTED here (unlike :app's phone build) - it would never load on
        // this real target hardware and there is no arm64 Wear emulator
        // image that runs at usable speed on this Windows x86_64 host
        // either, so including it would be dead weight in both directions.
        ndk {
            abiFilters += listOf("armeabi-v7a", "x86_64")
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
        compose = true
    }

    // Kotlin 1.9.22 (this project's version, set in the root build.gradle.kts)
    // pairs with Compose Compiler 1.5.8 per the official JetBrains
    // compatibility map - not the Kotlin 2.0+ Compose-compiler-as-a-plugin
    // model, since this project hasn't moved to Kotlin 2.x.
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    // --- Wear Compose (androidx.wear.compose), NOT the phone app's XML+ViewBinding ---
    // Deliberate platform-fit decision - see docs/specs/watch6-classic-adaptation.md
    // "Design decisions" section. This makes :wear a genuinely separate UI
    // codebase from :app, sharing engine/business logic only.
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.wear.compose:compose-foundation:1.4.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-splashscreen:1.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Wear OS Data Layer API (CapabilityClient/MessageClient/DataClient) -
    // phone-sync scaffold only in this pass (see WearSyncClient.kt); not
    // wired into any UI flow yet.
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    // Google ML Kit - same on-device, no-API-key translation engine as the
    // phone app (com.retroid.translator.engine.TranslationEngine on the
    // phone side). No native/JNI component, so no ABI concerns porting it
    // here - pure Kotlin/Java + Play-Services-backed downloads.
    // Bumped to 17.0.3 in lockstep with the phone app's dependency (17.0.2's
    // libtranslate_jni.so fails the 16KB page-size alignment check on arm64;
    // :wear doesn't ship arm64-v8a so isn't itself affected, but keeping the
    // version pinned the same across modules avoids drift).
    implementation("com.google.mlkit:translate:17.0.3")

    // Vosk - same offline on-device STT engine as the phone app. This is
    // this pass's central open question (see spec's "hard technical
    // question" section) - does its JNI/native layer actually load and run
    // under Wear OS's API surface and (critically, a real finding this
    // session) the real target device's 32-bit-only ABI. The vosk-android
    // AAR bundles armeabi-v7a + x86_64 (+ arm64-v8a + x86) natives, so no
    // extra vendoring was needed to get a matching build for either target
    // in ndk.abiFilters above - confirmed by unzipping the AAR directly,
    // see spec.
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    // eSpeak NG - the phone app's own offline TTS floor - IS now ported.
    // A follow-up pass (see spec's "eSpeak NG on :wear" section) found
    // upstream espeak-ng's own official signed release APK
    // (github.com/espeak-ng/espeak-ng/releases/download/1.52.0/
    // espeak-1.52.0-signed.apk) already bundles a prebuilt
    // lib/armeabi-v7a/libttsespeak.so alongside arm64-v8a - no NDK
    // cross-compile needed, just extracting a second ABI from a release
    // artifact this project's own arm64-v8a binary was already sourced
    // from (confirmed byte-identical by sha256). Vendored at
    // wear/src/main/jniLibs/armeabi-v7a/libttsespeak.so +
    // wear/src/main/assets/espeak-ng-data (same asset :app ships). See
    // com.retroid.translator.wear.tts.WearEspeakEngine's doc comment for
    // the full verification trail and how this became the preferred TTS
    // path, with SystemTtsSpeaker (below) as fallback.
    //
    // sherpa-onnx (Piper neural voices) was NOT ported this pass, despite
    // the same investigation also finding official prebuilt armeabi-v7a
    // binaries for both sherpa-onnx (the exact v1.13.4 release AAR :app
    // already vendors bundles jni/armeabi-v7a/*.so, confirmed identical
    // JNI symbol set to the vendored arm64-v8a build) AND ONNX Runtime
    // itself (Maven Central's onnxruntime-android AAR ships
    // jni/armeabi-v7a/libonnxruntime.so) - i.e. this path turned out more
    // tractable than expected too, not a dead end. It was deliberately not
    // wired into a live TTS path this pass because sherpa-onnx's Kotlin
    // OfflineTts wrapper has a documented native-crash risk (see
    // PiperTtsEngine.kt's own comment: an invalid/incomplete model config
    // doesn't fail cleanly at construction, it segfaults on the next native
    // call) when probed without a real, fully-downloaded Piper voice pack,
    // and downloading one (~65MB) requires explicit user permission this
    // unattended pass could not obtain - see spec for the full reasoning
    // and what a follow-up pass would need to do to finish this.
    //
    // This pass uses the Android system TextToSpeech API as the remaining
    // fallback (com.google.android.tts, confirmed present and selectable as
    // the default engine on the real Watch6 Classic via
    // `adb shell pm list packages` - see spec) for languages/moments eSpeak
    // doesn't cover, not a silent gap.

    // Plain-JVM unit tests (src/test) - JUnit 4 only, no Robolectric/Espresso.
    // Scope: pure-Kotlin logic with no Android-framework dependency (see
    // docs/ENGINES.md's testing-infrastructure note for what's covered).
    testImplementation("junit:junit:4.13.2")
}
