package com.hermesandroid.bridge.billing

import android.app.Activity
import androidx.compose.runtime.Composable

/**
 * GitHub-Edition: KEINE Play-Billing-Route — Support/Budget läuft außerhalb
 * von Google (GitHub Sponsors-Route, Repo-Konvention). Die play-Edition
 * definiert dieselbe Signatur (SupporterCard) mit echter Billing-Karte.
 * PH-9-Route, Sir 11.11.2026.
 */
@Composable
fun SupporterCard(activity: Activity?) {
    // bewusst leer — die Karte existiert nur im play-Flavor.
}
