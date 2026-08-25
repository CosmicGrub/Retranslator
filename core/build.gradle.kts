// :core - pure-Kotlin engine-layer decision logic, split out of :app
// (docs/specs/engineering-systems-pitch.md system #2). Deliberately a plain
// kotlin("jvm") module, NOT com.android.library - that distinction is the
// real enforcement: a library module still has android.jar on its compile
// classpath even with zero explicit Android dependencies, so an accidental
// `import android.content.Context` would still silently compile. Only a
// plain JVM module has no Android SDK on the classpath at all.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // compileOnly, not implementation: VoskResultParsing.kt uses
    // org.json.JSONObject, which ships INSIDE the real Android platform at
    // runtime (android.jar's org.json.* classes). If this were
    // `implementation`, the standalone org.json:json artifact's classes
    // would get packaged into the APK and collide with the platform's own
    // org.json at dex-merge time - a real duplicate-class build failure,
    // not hypothetical. compileOnly gives :core the real method signatures
    // to compile against without shipping a second copy, mirroring how
    // android.jar itself already works for every other file in :app
    // (compile-time stub, real platform implementation at runtime).
    compileOnly("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    // Needed at test runtime specifically: plain JVM unit tests have no
    // Android platform org.json to fall back on, so a real implementation
    // (not just compileOnly) is required here - same distinction
    // app/build.gradle.kts's own test dependency block already draws.
    testImplementation("org.json:json:20240303")
}

// No tasks.test { useJUnitPlatform() } here on purpose: this project uses
// plain JUnit 4 (@Test from org.junit.Test), matching every other test file
// in this repo - useJUnitPlatform() configures JUnit 5/Platform discovery
// instead, which would need the JUnit vintage engine to even find these
// tests. Gradle's default `test` task already discovers JUnit 4 tests off
// the testImplementation("junit:junit:...") dependency with no extra config.
