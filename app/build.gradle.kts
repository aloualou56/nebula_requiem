import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from ../keystore.properties (git-ignored; see keystore.properties.example).
// Without it, release builds fall back to the local debug key so they can still be installed.
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply { if (keystoreFile.isFile) keystoreFile.inputStream().use { load(it) } }

android {
    namespace = "io.github.aloualou56.nebularequiem"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        // Keep the application id stable: a release signed with the same key installs as an update.
        applicationId = "io.github.aloualou56.nebularequiem"
        minSdk = 33          // Android 13
        targetSdk = 36
        versionCode = 142
        versionName = "0.4.2"
    }

    signingConfigs {
        if (keystoreFile.isFile) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            // Debug builds install next to a release for side-by-side comparison.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.maxHeapSize = "2g"
            test.systemProperty("robolectric.graphicsMode", "NATIVE")
            test.systemProperty("nebula.screenshotDir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
}
