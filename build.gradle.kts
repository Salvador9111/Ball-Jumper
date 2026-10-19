buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 8.7 bundles an older shrinker. Firebase Auth 24 is compiled with
        // Kotlin 2.3 metadata, whose supported optimizer is R8 8.13.19+.
        classpath("com.android.tools:r8:8.13.19")
    }
}

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
}
