package com.hermesandroid.bridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.ui.FlipsiColors

enum class BadgeState { OK, WARN, OFF, ERROR }

/** Icon-Res des Badge-Zustands — Farbe allein ist nie der Info-Träger. */
private fun badgeIcon(state: BadgeState): Int = when (state) {
    BadgeState.OK -> StatusIcons.ok
    BadgeState.WARN -> StatusIcons.warn
    BadgeState.OFF -> StatusIcons.off
    BadgeState.ERROR -> StatusIcons.error
}

private fun badgeColor(state: BadgeState): androidx.compose.ui.graphics.Color = when (state) {
    BadgeState.OK -> FlipsiColors.SignalGreen
    BadgeState.WARN -> FlipsiColors.SignalAmber
    BadgeState.OFF -> FlipsiColors.TextSecondary
    BadgeState.ERROR -> FlipsiColors.SignalRedText
}

/**
 * StatusBadge (v0.12.0): Zustand = Icon + Text + Ton (Apple-HIG „nicht nur Farbe").
 * Alle Textkontraste ≥4,5:1 (SignalRedText 5,24:1 auf 16%-Tint — gemessen).
 */
@Composable
fun StatusBadge(
    text: String,
    state: BadgeState,
    modifier: Modifier = Modifier,
) {
    val fg = badgeColor(state)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(fg.copy(alpha = 0.16f), RoundedCornerShape(percent = 50))
            .padding(start = 10.dp, top = 4.dp, end = 12.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = text },
    ) {
        Icon(
            painterResource(badgeIcon(state)),
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}