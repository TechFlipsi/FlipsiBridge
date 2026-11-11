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
        // PH-7/PS-G-1 (11.11.2026): der play-Flavor überschreibt targetSdk = 36
        // (hartes Play-Gate seit 31.08.2026: neue Apps + Updates = API 36;
        // die Verlängerung bis 01.11.2026 ist verpasst) — GitHub-Edition bleibt 34.
        targetSdk = 34
        versionCode = 38
        versionName = "0.15.0"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // DUAL-EDITION (ROADMAP DE-1/DE-2, Sir-Order 01.11.2026): zwei Editionen EINER
    // Codebase per productFlavors — github = volle Funktion (bestehende App-ID und
    // Installiert-Basis bleiben), play = Google-konforme Subset-App-ID.
    // Skeleton-Phase: NUR Flavor-Gerüst + BuildConfig-Flags. KEIN Manifest-Minus-Set
    // (kommt in PH-7), KEINE Permission-Änderung. Skeleton-Phase kein Bump; spätere
    // Wellen bumped regulär (30/0.12.5, 31/0.12.6) — Regel: versionCode-Bump +
    // CHANGELOG im selben Commit je App-Änderung.
    flavorDimensions += "edition"
    productFlavors {
        // GitHub-Edition (Full Edition): Self-Update via Relay, volle Permissions.
        create("github") {
            dimension = "edition"
            // App-ID UNVERÄNDERT = bestehende Installiert-Basis bleibt update-fähig.
            applicationId = "at.flipsi.bridge"
            buildConfigField("boolean", "FLAG_FULL_EDITION", "true")
            buildConfigField("boolean", "EDITION_GITHUB", "true")
            buildConfigField("boolean", "EDITION_PLAY", "false")
            // J4-F4 (DE-2-Konformität): je-Editions-suffix im versionName der
            // INSTALLIERTEN App (0.15.0-github) — Dateiname unverändert.
            versionNameSuffix = "-github"
        }
        // Play-Edition: Google-konformes Subset der Funktion (Flags; Feature-Zäune
        // folgen später via BuildConfig-Auswertung, nicht per Code-Fork).
        create("play") {
            dimension = "edition"
            applicationId = "at.flipsi.bridge.play"
            buildConfigField("boolean", "FLAG_FULL_EDITION", "false")
            buildConfigField("boolean", "EDITION_PLAY", "true")
            buildConfigField("boolean", "EDITION_GITHUB", "false")
            // PH-7/DE-2: eigener Play-Zähler (3-stellig, Start 100), versionName-
            // Suffix je Edition, targetSdk 36 = Play-Gate (neue Apps 31.08.2026).
            versionCode = 100
            versionNameSuffix = "-play"
            targetSdk = 36
        }
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

    // Name der gebauten APK `flipsibridge-<version>-<flavor>.apk` statt des
    // Defaults `app-debug.apk` — für lokale Builds, das CI-Artefakt und das
    // Release-Asset gleich. Je Flavor ein eigener Dateiname (keine Kollision
    // zwischen den Editionen); der Relay-/apk/latest-Parser liest die erste
    // x.y.z-Zahlengruppe aus dem Namen — der Flavor-Suffix stört ihn nicht.
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = if (variant.versionName.endsWith("-${variant.flavorName}"))
                    "flipsibridge-${variant.versionName}.apk"
                else
                    "flipsibridge-${variant.versionName}-${variant.flavorName}.apk"
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

    // Supporter-IAP (PH-9-Route, Sir 11.11.2026): NUR im play-Flavor —
    // die GitHub-Edition hat keine Play-Billing-Abhängigkeit (kein APK-Ballast,
    // keine API-Referenz). Produkt = einmaliger „Unterstützer"-Badge (2,99 €,
    // sperrt nichts — perk macht die IAP Play-policy-konform).
    // … — Welle-5-Compile-Beweis: create()-Flavors erzeugen KES typed
    // Accessors in KTS (AGP 8.9.1) → add() ist die korrekte Form.
    add("playImplementation", "com.android.billingclient:billing-ktx:8.0.0")
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