package com.hermesandroid.bridge.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.ui.FlipsiColors

/**
 * StatusBanner v2 (v0.12.0): Icon-Chip (Zustand auch ohne Farbwahrnehmung
 * lesbar) + Titel/Text + Status-Pill rechts. EINE Warn-Instanz bleibt die
 * Regel — der Banner ist die einzige Stelle, die den Verbindungszustand malt.
 * Die 14%-Tint-Fläche wird als gemischte Konstfarbe übergeben (Kontrast
 * gemessen: Titel grün 5,57:1, amber 5,60:1, rot via SignalRedText 5,38:1).
 *
 * W23 Lane U5 (Retry-Backoff sichtbar): optionale Detail-Zeile unter dem
 * Text (Handlungs-Hint, Besitzers Fehler-Regel „Fehler + was man tun kann“)
 * mit dismissbarem Schließen-Griff — NUR wenn ein Detail gesetzt ist; das
 * Grundlayout (Chip/Titel/Text) bleibt unverändert stabil.
 */
@Composable
fun StateBanner(
    tint: Color,
    title: String,
    text: String,
    busy: Boolean = false,
    badge: Pair<String, BadgeState>? = null,
    detail: String? = null,
    detailDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // W15c (KLEIN A, Jäger): Tint-Fläche MIT Form — ohne shape
                // läuft der 14%-Hintergrund als Rechteck hart über die Karten-
                // Rundung (die Card-Ecken blitzen durch), MaterialTheme.shapes
                // .large hält die Fläche in der Karten-Silhouette.
                .background(tint.copy(alpha = 0.14f), MaterialTheme.shapes.large)
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(32.dp)
                        .background(tint.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    ResourceIcon(bannerIconOf(tint, badge), tint = tint, size = 16.dp)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                    modifier = Modifier.weight(1f),
                )
                // Besitzer-Design-Befund 17.11. (Screenshot img_2f233f93): bei langem
                // Titel („Verbunden – Brücke steht“) klebte der letzte Buchstabe
                // direkt am AKTIV-Badge — ein fester Atem-Abstand gehört dazwischen.
                Spacer(Modifier.width(8.dp))
                if (busy) {
                    CircularProgressIndicator(
                        color = tint,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else if (badge != null) {
                    StatusBadge(badge.first, badge.second)
                }
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = FlipsiColors.TextPrimary,
                modifier = Modifier.padding(top = 4.dp),
            )
            // W23 Lane U5: Detail-Zeile (Handlungs-Hint) + Schließen-Griff —
            // fixe Minimal-Höhe pro Zeile (24.dp-Griff), kein Höhen-Flackern
            // durch sich je-Sekunde ändernde Countdown-Texte im Haupt-Text.
            if (detail != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    if (detailDismiss != null) {
                        IconButton(
                            onClick = detailDismiss,
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.banner_detail_dismiss),
                                tint = FlipsiColors.TextSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun bannerIconOf(
    @Suppress("UNUSED_PARAMETER") tint: Color,
    badge: Pair<String, BadgeState>?,
): Int = when (badge?.second) {
    BadgeState.OK -> StatusIcons.ok
    BadgeState.WARN -> StatusIcons.warn
    BadgeState.ERROR -> StatusIcons.error
    BadgeState.OFF -> StatusIcons.off
    else -> StatusIcons.warn
}