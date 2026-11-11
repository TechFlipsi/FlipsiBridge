package com.hermesandroid.bridge.billing

import android.app.Activity
import androidx.compose.runtime.Composable

/**
 * GitHub-Edition: KEINE Play-Billing-Route — Support/Budget läuft außerhalb
 * von Google (GitHub Sponsors-Route, Repo-Konvention). Die play-Edition
 * definiert dieselbe Signatur mit echtem Billing-Dialog.
 * WELLE 13 (Herz-Knopf): auch der Dialog-Signatur-Stub bleibt identisch.
 */
@Composable
fun SupporterCard(activity: Activity?) {
    // bewusst leer — die Karte existiert nur im play-Flavor.
}

@Composable
fun SupporterDialog(activity: Activity?, onDismiss: () -> Unit) {
    // bewusst leer im GitHub-Flavor (kein Play-Billing).
}
