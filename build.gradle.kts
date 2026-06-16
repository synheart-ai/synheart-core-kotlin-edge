plugins {
    id("com.android.library") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    // Maven Central publishing via the Sonatype Central Portal. Provides the
    // `publishAndReleaseToMavenCentral` task the release workflow invokes, and
    // wires the POM from the POM_*/GROUP/VERSION_NAME gradle.properties values.
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}
