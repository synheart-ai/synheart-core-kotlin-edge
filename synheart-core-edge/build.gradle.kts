plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

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
    kotlinOptions { jvmTarget = "17" }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Session lifecycle + BiosignalProvider abstraction (mirrors what the
    // phone SDK uses). Pulls SessionEngine + BiosignalSample +
    // HealthConnectBiosignalProvider.
    implementation("ai.synheart:synheart-session:0.1.0")
    // Multi-device wearable SDK; provides HealthConnectAdapter that
    // HealthConnectBiosignalProvider wraps. Required when the watch app uses
    // the Health Connect path on Wear OS 3+.
    implementation("ai.synheart:synheart-wear:0.3.0")

    implementation("androidx.health:health-services-client:1.1.0-alpha05")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Provides `Tasks.await()` extension for Play Services tasks
    // consumed by `relay/PhoneRelay.kt` (Wearable Data Layer client).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Real org.json impl for unit tests — the android.jar version is a stub
    // that throws RuntimeException("Stub!") on every method call. Without
    // this, any test that constructs a JSONObject (e.g. ComputeProfile
    // fromJson tests) fails at runtime.
    testImplementation("org.json:json:20240303")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = providers.gradleProperty("GROUP").get()
            artifactId = providers.gradleProperty("POM_ARTIFACT_ID").get()
            version = providers.gradleProperty("VERSION_NAME").get()

            afterEvaluate { from(components["release"]) }

            pom {
                name.set(providers.gradleProperty("POM_NAME"))
                description.set(providers.gradleProperty("POM_DESCRIPTION"))
                url.set(providers.gradleProperty("POM_URL"))
                licenses {
                    license {
                        name.set(providers.gradleProperty("POM_LICENSE_NAME"))
                        url.set(providers.gradleProperty("POM_LICENSE_URL"))
                    }
                }
                scm {
                    url.set(providers.gradleProperty("POM_URL"))
                }
            }
        }
    }
}

signing {
    val signingKey: String? = System.getenv("SIGNING_KEY")
    val signingPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")
    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}
