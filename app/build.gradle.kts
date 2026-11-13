plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.st0n89.bathyapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.st0n89.bathyapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        val ksPath: String? = System.getenv("BATHY_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
        val ksPass: String? = System.getenv("BATHY_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
        if (ksPath != null && ksPass != null) {
            create("ci") {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = System.getenv("BATHY_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "bathyapp"
                keyPassword = System.getenv("BATHY_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("BATHY_KEYSTORE_PATH")?.takeIf { it.isNotBlank() } != null) {
                signingConfigs.getByName("ci")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
