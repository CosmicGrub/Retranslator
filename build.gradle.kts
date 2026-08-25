plugins {
    id("com.android.application") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    // :core is a plain JVM module (docs/specs/engineering-systems-pitch.md
    // system #2) - no android {} block, so no android.jar on its classpath
    // at all. That's the real point: unlike a com.android.library module
    // (which still has android.jar even with zero explicit Android deps),
    // an accidental `import android.content.Context` in :core becomes a
    // real compile failure instead of silently compiling.
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
}
