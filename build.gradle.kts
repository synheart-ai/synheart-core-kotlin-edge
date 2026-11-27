plugins {
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    // Maven Central publishing via the Sonatype Central Portal. Provides the
    // `publishAndReleaseToMavenCentral` task the release workflow invokes, and
    // wires the POM from the POM_*/GROUP/VERSION_NAME gradle.properties values.
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}
