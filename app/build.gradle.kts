import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A clone remains buildable without private Firebase configuration. Adding the
// ignored app/google-services.json file enables the Google Services resource task.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("Firebase configuration is absent; online multiplayer will be disabled.")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

val firebaseProperties = Properties().apply {
    val file = rootProject.file("firebase.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun firebaseValue(environmentName: String, propertyName: String): String =
    System.getenv(environmentName)?.takeIf(String::isNotBlank)
        ?: firebaseProperties.getProperty(propertyName)?.takeIf(String::isNotBlank)
        ?: ""

fun quotedBuildConfigValue(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val firebaseDatabaseUrl = firebaseValue("FIREBASE_DATABASE_URL", "databaseUrl")

fun signingValue(environmentName: String, propertyName: String): String? =
    System.getenv(environmentName)?.takeIf(String::isNotBlank)
        ?: keystoreProperties.getProperty(propertyName)?.takeIf(String::isNotBlank)

val releaseStoreFile = signingValue("ANDROID_KEYSTORE_PATH", "storeFile")
val releaseStorePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { it != null }

android {
    namespace = "com.example.skyboundjumper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.skyboundjumper"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField(
            "String",
            "FIREBASE_DATABASE_URL",
            quotedBuildConfigValue(firebaseDatabaseUrl)
        )

        vectorDrawables.useSupportLibrary = false
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        resValues = false
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*"
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
}
