import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hermesandroid.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "at.flipsi.bridge"
        minSdk = 26
        // targetSdk bewusst auf 34 belassen (Verhaltenssprung FGS/Rechte im Release
        // kontrolliert trennen; compileSdk 36 ist nur für Bibliotheksauswahl).
        targetSdk = 34
        versionCode = 29
        versionName = "0.12.4"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Release-Signierung nur, wenn der Keystore lokal vorhanden ist.
            // Props liegen außerhalb des Repos (/root/keystores/flipsibridge.signing.properties)
            // und werden NIEMALS committet. Ohne Keystore: Release bleibt unsigniert.
            val signingPropsFile = file("/root/keystores/flipsibridge.signing.properties")
            if (signingPropsFile.exists()) {
                val signingProps = Properties().apply {
                    signingPropsFile.inputStream().use { load(it) }
                }
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(signingProps["storeFile"] as String)
                    storePassword = signingProps["storePassword"] as String
                    keyAlias = signingProps["keyAlias"] as String
                    keyPassword = signingProps["keyPassword"] as String
                }
            }
        }
    }

    // Name the built APK `hermes-android-<version>.apk` instead of the default
    // `app-debug.apk`, for local builds, the CI artifact, and the release asset alike.
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "flipsibridge-${variant.versionName}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
            )
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Compose-Stack (BOM 2024.09.03 — compose 1.7.3/material3 1.3.0, Kotlin-2.0-Kette)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    // Health Connect (Gesundheitsdaten, read-only; 1.1.0 verlangt compileSdk 36)
    implementation(libs.health.connect.client)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}