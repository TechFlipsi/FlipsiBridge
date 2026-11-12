package com.hermesandroid.bridge.billing

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@Composable
fun SupporterDialog(activity: Activity?, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var badgeActive by remember { mutableStateOf(SupporterKeys.isBadgeActive(context)) }
    var productDetails by remember { mutableStateOf<ProductDetails?>(null) }
    var billingState by remember { mutableStateOf("prüfe …") }

    val prefs = remember { context.getSharedPreferences(SupporterKeys.PREFS, Context.MODE_PRIVATE) }
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
            val list = result.productDetailsList
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK &&
                !list.isNullOrEmpty()
            ) {
                productDetails = list.firstOrNull()
                billingState = "verfügbar"
            } else {
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
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProducts()
                } else {
                    billingState = "Play-Dienste nicht verfügbar"
                }
            }

            override fun onBillingServiceDisconnected() {
                // kein Spam-Reconnect — der Dialog retryt bei jeder Composition
            }
        })
    }

    DisposableEffect(Unit) {
        onDispose { client.endConnection() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    if (badgeActive) "Unterstützer" else "FlipsiBridge unterstützen",
                    fontWeight = FontWeight.SemiBold,
                    color = if (badgeActive) com.hermesandroid.bridge.ui.FlipsiColors.Ember
                            else com.hermesandroid.bridge.ui.FlipsiColors.TextPrimary,
                )
                if (badgeActive) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "♥",
                        color = com.hermesandroid.bridge.ui.FlipsiColors.Ember,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        },
        text = {
            Column {
                Text(
                    if (badgeActive) {
                        "Danke! Dein Unterstützer-Badge ist aktiv — die App bleibt komplett, nichts wird gesperrt."
                    } else {
                        "Einmalig 2,99 € über Google Play — sperrt KEINE Funktion und steckt in keine Abos. " +
                            "Ein ehrliches Danke für die laufende Entwicklung."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = com.hermesandroid.bridge.ui.FlipsiColors.TextSecondary,
                )
                if (!badgeActive && activity != null) {
                    val product = productDetails
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            if (product != null) {
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
                        modifier = Modifier.fillMaxWidth(),
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(if (badgeActive) "Danke" else "Später") }
        },
    )
}

/** GitHub-Edition-Stub-Signatur bleibt identisch (src/github/ gleiche Signatur). */
