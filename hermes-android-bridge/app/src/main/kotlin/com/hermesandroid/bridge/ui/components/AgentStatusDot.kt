package com.hermesandroid.bridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.ui.FlipsiColors

/**
 * W23-8: EIN Status-Dot-Stil überall — derselbe Punkt hängt am
 * Chat-Header-Avatar, in der Agenten-Zeile (Agenten-Tab) und in der
 * Dashboard-StatusPille. Zwei Ebenen:
 *
 *  - [StatusDot] = der visuelle Kern (farbiger Kreis, optional
 *    Hintergrund-Ring).
 *  - [AgentStatusDot] = die online/offline-Verband-Ebene darüber
 *    (Farb-Mapping + A11y-Text) für den Chat/die Agenten-Liste.
 *
 * Farb-Regel (W19): Ember = beschäftigt, SignalGreen = frei,
 * SignalRed = Störung — der Dot setzt SignalRed NIE für offline; offline
 * ist Neutral/Grau ([FlipsiColors.TextSecondary]).
 */

/**
 * Kern-Dot: ein farbiger Kreis mit optionaler Randung ([ringColor]) —
 * am Profilbild-Kreis bekommt der Dot einen 2dp-Ring in der Umgebungsfarbe,
 * damit er nicht randlos ins Bild ragt (saubere Begrenzung, WhatsApp-Muster).
 *
 * [label] nur setzen, wenn der Punkt SELBST der A11y-Träger ist — hängt ein
 * Status-Wort direkt daneben (StatusPille), bleibt der Dot stumm, sonst
 * liest TalkBack den Zustand doppelt.
 */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    dotSize: Dp = 10.dp,
    ringColor: Color? = null,
    label: String? = null,
) {
    val base = if (label != null) {
        modifier.semantics { contentDescription = label }
    } else {
        modifier
    }
    if (ringColor != null) {
        Box(
            modifier = base
                .size(dotSize + 4.dp)
                .background(ringColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(dotSize).background(color, CircleShape))
        }
    } else {
        Box(
            modifier = base
                .size(dotSize)
                .background(color, CircleShape),
        )
    }
}

/**
 * Agenten-Online/Offline-Dot: SignalGreen (0xFF2FBF71) = online/verbunden,
 * TextSecondary (grau) = offline. Zustand nie nur über Farbe (A11y-Regel):
 * contentDescription „Online“/„Offline“ — REUSE chat_filter_online/offline
 * (Strings ×3 Locale, KEIN-DOPPEL ohne neue Keys).
 */
@Composable
fun AgentStatusDot(
    online: Boolean,
    modifier: Modifier = Modifier,
    dotSize: Dp = 10.dp,
    ringColor: Color? = null,
) {
    StatusDot(
        color = if (online) FlipsiColors.SignalGreen else FlipsiColors.TextSecondary,
        modifier = modifier,
        dotSize = dotSize,
        ringColor = ringColor,
        label = stringResource(
            if (online) R.string.chat_filter_online else R.string.chat_filter_offline,
        ),
    )
}
