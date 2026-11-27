package com.hermesandroid.bridge.legal

/**
 * PH8-T7 (W22, Lane E) — kuratierte Open-Source-Liste (reine Daten + Regeln,
 * JVM-testbar, keine Android-Imports).
 *
 * Quelle der Kuratierung (nur LESEND ausgewertet, PH8-T7-Brief):
 *  - hermes-android-bridge/gradle/libs.versions.toml
 *  - hermes-android-bridge/app/build.gradle.kts (Dependencies-Block)
 *
 * Kuratierungs-Begründung: gelistet sind die APK-relevanten Runtimre-
 * Abhängigkeiten (13 Kern-Dependencies); Test-Instrumente (JUnit/MockK/
 * Robolectric/kotlinx-coroutines-test) landen NICHT im APK und stehen bewusst
 * nicht im Screen; debugImplementation (compose-ui-tooling) ebenso nicht.
 * Signifikante Transitive (Netty-Engine hinter ktor-server-netty, Gson-
 * Serialisierung hinter ktor-serialization-gson) sind über ihre Wrapper-
 * Artefakte abgedeckt — keine Einzel-Einträge (Brief: „Transitive nur wenn
 * signifikant“).
 *
 * Lizenznamen sind Eigenamen und werden NICHT übersetzt; Copyright-Angaben
 * sind (wie im Brief gefordert) de/en gleichlautend. Die Basis-App selbst
 * (hermes-android, MIT — Urheber laut LICENSE: Raul; Modifikationen: Fabian
 * Kirchweger / TechFlipsi) steht NICHT in dieser Dependency-Liste: ihr
 * MIT-Attribution zeigt der OSS-Screen als eigenen kurzen Text MIT Verweis
 * auf die vollständige LICENSE (Brief-Preference statt raw-Einbettung).
 */
data class OssComponent(
    val name: String,
    val version: String,
    val license: String,
    val copyright: String,
)

object OssLicenseRules {

    /**
     * Play-Exklusiv-Artefakt (build.gradle.kts: add("playImplementation", …)).
     * Der OSS-Screen listet es NUR in der Play-Edition (gerade ehrlich statt
     * „Github-Edition zeigt eine Abhängigkeit, die sie nicht hat“).
     */
    const val PLAY_ONLY_ARTIFACT = "com.android.billingclient:billing-ktx"

    val components: List<OssComponent> = listOf(
        OssComponent(
            "androidx.activity:activity-compose",
            "1.9.2",
            "Apache-2.0",
            "Copyright (C) The Android Open Source Project (Google LLC)",
        ),
        OssComponent(
            "androidx.compose.material3:material3",
            "1.3.0",
            "Apache-2.0",
            "Copyright (C) The Android Open Source Project (Google LLC)",
        ),
        OssComponent(
            "androidx.compose.ui:ui",
            "1.7.3",
            "Apache-2.0",
            "Copyright (C) The Android Open Source Project (Google LLC)",
        ),
        OssComponent(
            "androidx.compose.ui:ui-tooling-preview",
            "1.7.3",
            "Apache-2.0",
            "Copyright (C) The Android Open Source Project (Google LLC)",
        ),
        OssComponent(
            "androidx.core:core",
            "1.12.0",
            "Apache-2.0",
            "Copyright (C) The Android Open Source Project (Google LLC)",
        ),
        OssComponent(
            "androidx.health.connect:connect-client",
            "1.1.0",
            "Apache-2.0",
            "Copyright (C) The Android Open Source Project (Google LLC)",
        ),
        OssComponent(
            "androidx.lifecycle:lifecycle-runtime-ktx",
            "2.7.0",
            "Apache-2.0",
            "Copyright (C) The Android Open Source Project (Google LLC)",
        ),
        OssComponent(
            PLAY_ONLY_ARTIFACT,
            "8.0.0",
            "Apache-2.0",
            "Copyright (C) Google LLC",
        ),
        OssComponent(
            "com.google.code.gson:gson",
            "2.10.1",
            "Apache-2.0",
            "Copyright (C) Google Inc. / Google LLC",
        ),
        OssComponent(
            "com.squareup.okhttp3:okhttp",
            "4.12.0",
            "Apache-2.0",
            "Copyright (C) Square, Inc.",
        ),
        OssComponent(
            "io.ktor:ktor-server-content-negotiation",
            "2.3.13",
            "Apache-2.0",
            "Copyright (C) 2014-2024 JetBrains s.r.o.",
        ),
        OssComponent(
            "io.ktor:ktor-server-core",
            "2.3.13",
            "Apache-2.0",
            "Copyright (C) 2014-2024 JetBrains s.r.o.",
        ),
        OssComponent(
            "io.ktor:ktor-server-netty",
            "2.3.13",
            "Apache-2.0",
            "Copyright (C) 2014-2024 JetBrains s.r.o.",
        ),
        OssComponent(
            "io.ktor:ktor-serialization-gson",
            "2.3.13",
            "Apache-2.0",
            "Copyright (C) 2014-2024 JetBrains s.r.o.",
        ),
        OssComponent(
            "org.jetbrains.kotlinx:kotlinx-coroutines-android",
            "1.7.3",
            "Apache-2.0",
            "Copyright (C) 2016-2021 JetBrains s.r.o.",
        ),
    )

    /**
     * Sichtliste (alphabetisch, case-insensitiv — keine Gemischt-Sortierung
     * androidx/com/io untereinander). [editionPlay] = BuildConfig.EDITION_PLAY:
     * die Play-Exklusiv-Zeile erscheint nur in der Play-Edition.
     */
    fun sortedDisplay(editionPlay: Boolean): List<OssComponent> =
        components
            .filter { editionPlay || it.name != PLAY_ONLY_ARTIFACT }
            .sortedWith(compareBy { it.name.lowercase() })

    /** Zeile „Name — Version — Lizenz“; Copyright rendert die UI darunter. */
    fun lineLabel(c: OssComponent): String = "${c.name} — ${c.version} — ${c.license}"

    /** Kuratierungs-Integrität: 10-20 Einträge, keine Doppel, je Zeile vollständig. */
    fun curationValid(): Boolean {
        if (components.size !in 10..20) return false
        if (components.map { it.name }.distinct().size != components.size) return false
        return components.all { c ->
            c.name.isNotBlank() && c.version.isNotBlank() &&
                c.license.isNotBlank() && c.copyright.isNotBlank()
        }
    }

    /** Verwendete Lizenz-Eigenamen (Report/Diagnose, dedupliziert, alphabetisch). */
    fun licensesUsed(): List<String> = components.map { it.license }.distinct().sorted()
}
