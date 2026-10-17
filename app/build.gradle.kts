import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key: a properties file (storeFile, storePassword, keyAlias,
// keyPassword) named by the mergeController.signing Gradle property. See BUILDING.md.
val releaseSigning = providers.gradleProperty("mergeController.signing").orNull?.let { path ->
    Properties().apply { file(path).inputStream().use { load(it) } }
}
if (releaseSigning == null && gradle.startParameter.taskNames.any { "release" in it.lowercase() }) {
    logger.warn("mergeController.signing isn't set: signing the release build with the debug key.")
}

android {
    namespace = "com.ermay12.mergecontroller"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.ermay12.mergecontroller"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // Lets the app recognize a merge service still running code from an older install.
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a release key, sign with the debug key so the build can still be sideloaded.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        // Extract the .so so the Shizuku user service (a separate app_process) can load it.
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
