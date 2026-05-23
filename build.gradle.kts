plugins {
    // Pinned to match synheart-life-mobile-app's AGP so the SDK can be
    // consumed via `includeBuild` (RFC §7 consolidation). Gradle composite
    // builds require a single AGP version across the participating
    // projects; otherwise dependency resolution fails with
    // `Using multiple versions of the Android Gradle plugin ... is not allowed`.
    id("com.android.library") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
