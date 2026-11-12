package com.hermesandroid.bridge.billing

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

/**
 * WELLE 13 Support-Fassade (Sir 12.11.): EINE Schiene in main — der Dialog
 * delegiert NUR im play-Flavor an die echte Billing-Karte (SupporterCard-
 * Maschine), im GitHub-Flavor bleibt er bewusst leer (Support läuft außer-
 * halb Google). So existiert EIN Import-Verband im Chat-Kopf ohne
 * Flavor-Conflicts, und die Settings-Karte (SupporterCard) bleibt die SSOT
 * der Billing-Maschine.
 *
 * Die echte Dialog-UI lebt als @Composable `SupporterDialogImpl` in
 * `SupporterDialogPlay.kt` (nur play-Flavor) — diese Fassade wählt per
 * BuildConfig aus. main kennt KEIN Billing-SDK; alle Play-API-Aufrufe
 * bleiben damit hinter der Flavor-Grenze.
 */
@Suppress("unused")
private val supportFacadeTag = "SupporterFacade"
