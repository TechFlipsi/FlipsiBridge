package com.hermesandroid.bridge.billing

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Supporter-IAP (PH-9-Route, Sir 11.11.2026): einmaliger „Unterstützer"-Badge
 * (Produkt-ID `supporter`, Ziel 2,99 €) über den google Play In-App-Kauf —
 * sperrt NICHTS (das Badge selbst ist der perk; reine Spenden ohne perk wären
 * Play-policy-Verstoß). NUR play-Flavor (Quelle in src/play/), GitHub-Edition
 * kennt diese Karte nicht (Stub in src/github/).
 *
 * Zustandsmaschine: NICHT_VERFUEGBAR (Play/Produkt fehlt — bis zur Konsole-
 * Einrichtung ist das der Normalfall) → verfügbar → KAUF_AUSSTEHEND → Gekauft
 * (Prefs dauerhaft). Purchase-Acknowledge pflichtig (3-Tage-Fenster).
 */
object SupporterKeys {
    const val PRODUCT_ID = "supporter"
    const val PREFS = "hermes_bridge_prefs"
    const val KEY_BADGE = "supporter_badge"

    fun isBadgeActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BADGE, false)
}

@Composable
fun SupporterCard(activity: Activity?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var badgeActive by remember { mutableStateOf(SupporterKeys.isBadgeActive(context)) }
    var productDetails by remember { mutableStateOf<ProductDetails?>(null) }
    var billingState by remember { mutableStateOf("prüfe …") }

    val prefs = remember { context.getSharedPreferences(SupporterKeys.PREFS, Context.MODE_PRIVATE) }
    // clientRef-Muster (Welle-6-Beweis: Self-Reference im remember-Initializer
    // kompiliert nicht — der Purchase-Listener greift über die Variable zu).
    var clientRef by remember { mutableStateOf<BillingClient?>(null) }

    val client = remember {
        BillingClient.newBuilder(context)
            .setListener { billingResult: BillingResult, purchases: List<Purchase>? ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    for (purchase in purchases) {
                        if (SupporterKeys.PRODUCT_ID in purchase.products &&
                            purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                        ) {
                            prefs.edit().putBoolean(SupporterKeys.KEY_BADGE, true).apply()
                            badgeActive = true
                            if (!purchase.isAcknowledged) {
                                clientRef?.acknowledgePurchase(
                                    AcknowledgePurchaseParams.newBuilder()
                                        .setPurchaseToken(purchase.purchaseToken)
                                        .build(),
                                ) { }
                            }
                        } else if (SupporterKeys.PRODUCT_ID in purchase.products &&
                            purchase.purchaseState == Purchase.PurchaseState.PENDING
                        ) {
                            billingState = "Kauf ausstehend (Zahlung in Bearbeitung)"
                        }
                    }
                }
            }
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .build()
    }

    fun queryProducts() {
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(SupporterKeys.PRODUCT_ID)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build(),
                    ),
                )
                .build(),
        ) { billingResult, result ->
            // Billing-8.0-KTX (javap-Ground-Truth, Welle 10b): zwei Parameter —
            // (BillingResult, QueryProductDetailsResult); die Liste kommt vom
            // result.getProductDetailsList().
            val list = result.productDetailsList
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK &&
                !list.isNullOrEmpty()
            ) {
                productDetails = list.firstOrNull()
                billingState = "verfügbar"
            } else {
                // Solange das Produkt in der Play-Konsole nicht angelegt ist
                // (Sir-Gemeinschaftssitzung), ist das der ERLWARTE Zustand.
                billingState = "nicht verfügbar (Play-Konsole)"
            }
        }
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
        ) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                for (purchase in purchases) {
                    if (SupporterKeys.PRODUCT_ID in purchase.products &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                    ) {
                        prefs.edit().putBoolean(SupporterKeys.KEY_BADGE, true).apply()
                        badgeActive = true
                        if (!purchase.isAcknowledged) {
                            clientRef?.acknowledgePurchase(
                                AcknowledgePurchaseParams.newBuilder()
                                    .setPurchaseToken(purchase.purchaseToken)
                                    .build(),
                            ) { }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        clientRef = client
        client.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryProducts()
                    } else {
                        billingState = "Play-Dienste nicht verfügbar"
                    }
                }

                override fun onBillingServiceDisconnected() {
                    // Kein Spam-Reconnet-Loop — die UI-Karte retryt bei jeder
                    // neuen Composition (Wiedereinstieg in die Settings).
                }
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose { client.endConnection() }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = com.hermesandroid.bridge.ui.FlipsiColors.Panel),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    if (badgeActive) "Unterstützer" else "FlipsiBridge unterstützen",
                    fontWeight = FontWeight.SemiBold,
                    color = if (badgeActive) com.hermesandroid.bridge.ui.FlipsiColors.Ember
                            else com.hermesandroid.bridge.ui.FlipsiColors.TextPrimary,
                )
                if (badgeActive) {
                    Spacer(Modifier.width(8.dp))
                    // Sir 12.11.: Herz-Status sichtbar (Ember-Herz als Glyph, A11y-Aufzaehlung folgt im Text).
                    Text(
                        "♥",
                        color = com.hermesandroid.bridge.ui.FlipsiColors.Ember,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Text(
                if (badgeActive) {
                    "Danke! Dein Unterstützer-Badge ist aktiv — die App bleibt komplett, nichts wird gesperrt."
                } else {
                    "Einmalig 2,99 € über Google Play — sperrt KEINE Funktion und steckt in keine Abos. " +
                        "Ein ehrliches Danke für die laufende Entwicklung."
                },
                style = MaterialTheme.typography.bodySmall,
                color = com.hermesandroid.bridge.ui.FlipsiColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (!badgeActive && activity != null) {
                val product = productDetails
                Button(
                    onClick = {
                        if (product != null) {
                            // startForeground-Regel beachten: BillingFlow braucht
                            // eine Activity — der Knopf liegt im sichtbaren Screen.
                            client.launchBillingFlow(
                                activity,
                                BillingFlowParams.newBuilder()
                                    .setProductDetailsParamsList(
                                        listOf(
                                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                                .setProductDetails(product)
                                                .build(),
                                        ),
                                    )
                                    .build(),
                            )
                        }
                    },
                    enabled = product != null,
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = com.hermesandroid.bridge.ui.FlipsiColors.Ember,
                        contentColor = com.hermesandroid.bridge.ui.FlipsiColors.OnEmber,
                        disabledContainerColor = com.hermesandroid.bridge.ui.FlipsiColors.PanelElev,
                        disabledContentColor = com.hermesandroid.bridge.ui.FlipsiColors.TextSecondary,
                    ),
                ) { Text("Unterstützer-In-App erwerben") }
                Text(
                    when {
                        product != null -> "Produkt bereit (${billingState})"
                        billingState.startsWith("Kauf ausstehend") -> billingState
                        else -> "In-App-Produkt: $billingState"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = com.hermesandroid.bridge.ui.FlipsiColors.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
