import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hermesandroid.bridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hermesandroid.bridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.5.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Release-Signing: path to a .properties file (storeFile/storePassword/
            // keyAlias/keyPassword) passed via the BRIDGE_SIGNING_PROPS env var or the
            // bridgeSigningProps gradle property. Never committed. Without it the
            // release build stays unsigned (CI can set the env var).
            val signingPropsFile = (System.getenv("BRIDGE_SIGNING_PROPS")
                ?: findProperty("bridgeSigningProps"))?.let { file(it) }
            if (signingPropsFile != null && signingPropsFile.exists()) {
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
                .outputFileName = "hermes-android-${variant.versionName}.apk"
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
