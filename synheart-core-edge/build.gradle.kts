import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

// Project-level coordinates so Gradle composite-build substitution can match
// the requested Maven coordinate `ai.synheart:synheart-core-edge:<VERSION_NAME>`
// to this included build. The vanniktech plugin reads the same GROUP /
// VERSION_NAME / POM_* values from gradle.properties for the published POM.
group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "ai.synheart.core.edge"
    compileSdk = 34

    defaultConfig {
        minSdk = 30   // Wear OS 3.0
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

dependencies {
    // Session lifecycle + BiosignalProvider abstraction (mirrors what the
    // phone SDK uses). Pulls SessionEngine + BiosignalSample +
    // HealthConnectBiosignalProvider — and transitively pulls
    // synheart-wear (HealthConnectAdapter), so no separate dep needed here.
    implementation("ai.synheart:synheart-session:0.2.1")

    // Play Services Wearable Data Layer — consumed by relay/PhoneRelay.kt
    // (DataClient + MessageClient + NodeClient) and the WearableListenerService
    // base class in PhoneListenerService.kt.
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    // JNA — FFI binding to synheart_core_edge_*.so (RuntimeBridge.kt).
    // ~2MB AAR; mandatory for the native runtime boundary.
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // Jetpack Security — EncryptedFile (AES-256-GCM via Android Keystore) for
    // encrypting the durable outbox artifacts + session manifests at rest (H3).
    // The on-the-wire/JSON shape is unchanged; only the at-rest bytes differ.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Provides `Tasks.await()` extension for Play Services tasks
    // consumed by relay/PhoneRelay.kt.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Real org.json impl for unit tests — the android.jar version is a stub
    // that throws RuntimeException("Stub!") on every method call. Without
    // this, any test that constructs a JSONObject (e.g. ComputeProfile
    // fromJson tests) fails at runtime.
    testImplementation("org.json:json:20240303")
}

// Maven Central publishing via the Sonatype Central Portal. Coordinates,
// POM name/description/url and the Apache-2.0 license come from the
// POM_*/GROUP/VERSION_NAME values in gradle.properties (the plugin reads them
// automatically). GPG signing is enabled; the in-memory key + passphrase and
// the Central Portal credentials are supplied at release time via the
// ORG_GRADLE_PROJECT_* / SIGNING_KEY environment variables set in the CI
// workflow, so a local build without secrets still configures cleanly.
mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    if (!System.getenv("SIGNING_KEY").isNullOrBlank()) {
        signAllPublications()
    }
    coordinates(
        providers.gradleProperty("GROUP").get(),
        providers.gradleProperty("POM_ARTIFACT_ID").get(),
        providers.gradleProperty("VERSION_NAME").get(),
    )
    // name / description / url / license are populated automatically from the
    // POM_*/GROUP/VERSION_NAME gradle.properties values; only the SCM url needs
    // setting explicitly here.
    pom {
        scm {
            url.set(providers.gradleProperty("POM_URL"))
        }
    }
}
