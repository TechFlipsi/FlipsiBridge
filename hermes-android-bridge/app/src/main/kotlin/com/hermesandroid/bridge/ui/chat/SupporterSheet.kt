package com.hermesandroid.bridge.ui.chat

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.billing.SupporterCard
import com.hermesandroid.bridge.ui.FlipsiColors

/**
 * W16 (Fixliste 34): „Unterstützen“-Bottom-Sheet — der Herz-Tap im Chat-
 * Header öffnet DIREKT dieses Sheet (play), statt Umweg über Einstellungen;
 * die play-Billing-SupporterCard sitzt IM Sheet. Die Karte bleibt ZUSÄTZLICH
 * im Einstellungen-App-Accordion (BridgeScreen, play-gated). GitHub-Edition:
 * kein Herz im Chat-Header (ConversationHeader-Gate) und kein Sheet — die
 * Supporter-Route existiert dort nicht (Stub src/github/billing/).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSupporterSheet(activity: Activity?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = FlipsiColors.Panel) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.support_sheet_title),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = FlipsiColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.support_sheet_info),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextSecondary,
            )
            Spacer(Modifier.height(16.dp))
            SupporterCard(activity)
        }
    }
}
