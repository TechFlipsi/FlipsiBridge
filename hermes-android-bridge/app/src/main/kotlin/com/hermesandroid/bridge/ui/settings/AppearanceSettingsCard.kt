package com.hermesandroid.bridge.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.ui.FlipsiColors
import com.hermesandroid.bridge.ui.components.SettingsIcons
import com.hermesandroid.bridge.ui.theme.FlipsiScheme
import com.hermesandroid.bridge.ui.theme.ThemeEngine
import com.hermesandroid.bridge.ui.theme.backgroundC
import com.hermesandroid.bridge.ui.theme.accentC
import com.hermesandroid.bridge.ui.theme.bubbleAgentC
import com.hermesandroid.bridge.ui.theme.bubbleOwnC
import com.hermesandroid.bridge.ui.theme.onAccentC
import com.hermesandroid.bridge.ui.theme.onBackgroundC
import com.hermesandroid.bridge.ui.theme.onBackgroundMutedC
import com.hermesandroid.bridge.ui.theme.onBubbleAgentC
import com.hermesandroid.bridge.ui.theme.onBubbleOwnC
import com.hermesandroid.bridge.ui.theme.rememberFlipsiScheme

/**
 * Welle 13 / P12: Einstellungen → „Darstellung“.
 *
 * Vier Swatch-Grids (Akzent, eigene Bubble, Agenten-Bubble, Hintergrund)
 * + Live-Preview-Mini-Chat (zeigt die NUTZER-Kombination mit den abgeleiteten
 * Textfarben VOR dem Speichern — jeder Tap schreibt sofort via ThemeEngine,
 * die Preview re-rendert live) + „Zurücksetzen auf Flipsi-Standard“.
 *
 * Harte Nebenregel (P12): Textfarben werden NIE gewählt — sie kommen
 * algorithmisch aus ThemeMath (Kontrast ≥ 4,5:1). Extrem-Farb-Swatches
 * (hell auf hell) zeigen die Selbstkorrektur direkt in der Preview.
 *
 * A11y/Apple-Pass: Swatches = 48-dp-Touchfeld, Auswahl-Zustand über
 * semantics(selected) + Rahmen (nie nur Farbe), Zeilen ≥ 8 dp Rhythmus.
 */
@Composable
fun AppearanceSettingsCard(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = rememberFlipsiScheme()

    SettingsCard(
        titleRes = R.string.settings_appearance_title,
        subtitle = stringResource(R.string.settings_appearance_desc),
        iconRes = SettingsIcons.appearance, // Palette-Drawable (haus-eigen, Apache-2.0-Pfad)
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        SwatchSlot(
            label = stringResource(R.string.appearance_slot_accent),
            presets = ACCENT_PRESETS,
            selected = scheme.accent,
            onSelect = { ThemeEngine.setAccent(it) },
        )
        SwatchSlot(
            label = stringResource(R.string.appearance_slot_bubble_own),
            presets = BUBBLE_OWN_PRESETS,
            selected = scheme.bubbleOwn,
            onSelect = { ThemeEngine.setBubbleOwn(it) },
        )
        SwatchSlot(
            label = stringResource(R.string.appearance_slot_bubble_agent),
            presets = BUBBLE_AGENT_PRESETS,
            selected = scheme.bubbleAgent,
            onSelect = { ThemeEngine.setBubbleAgent(it) },
        )
        SwatchSlot(
            label = stringResource(R.string.appearance_slot_background),
            presets = BACKGROUND_PRESETS,
            selected = scheme.background,
            onSelect = { ThemeEngine.setBackground(it) },
        )

        ThemePreviewChat(scheme)

        OutlinedButton(
            onClick = { ThemeEngine.reset() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.appearance_reset))
        }
    }
}

/** Ein Swatch-Grid-Slot: Label + Farb-Reihe (48-dp-Touchfeld je Farb-Feld). */
@Composable
private fun SwatchSlot(
    label: String,
    presets: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = FlipsiColors.TextSecondary,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { argb ->
                Swatch(
                    argb = argb,
                    selected = argb == selected,
                    label = label,
                    onClick = { onSelect(argb) },
                )
            }
        }
    }
}

@Composable
private fun Swatch(
    argb: Int,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val hex = "#%06X".format(argb and 0xFFFFFF)
    // 48-dp-Touchfeld (Apple-HIG ≥44 pt), Farb-Feld 36 dp darin — der
    // Auswahl-Zustand ist Rahmen + semantics(selected), nie nur Farbe.
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClickLabel = label) { onClick() }
            .semantics {
                contentDescription = "$label $hex"
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(Color(argb), RoundedCornerShape(10.dp))
                .then(
                    if (selected) {
                        Modifier.border(2.dp, FlipsiColors.TextPrimary, RoundedCornerShape(10.dp))
                    } else {
                        Modifier.border(1.dp, FlipsiColors.LineStrong, RoundedCornerShape(10.dp))
                    },
                ),
        )
    }
}

/**
 * Live-Preview-Mini-Chat (P12: „Live-Preview-Karte direkt im Sheet“):
 * Hintergrund, beide Bubbles und der Akzent-Send-Kreis im NUTZER-Theme;
 * ALLE Textfarben kommen aus der algorithmischen Ableitung — die Preview
 * beweist die Lesbarkeits-Garantie, bevor irgendwo gespeichert wird.
 */
@Composable
private fun ThemePreviewChat(scheme: FlipsiScheme) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.backgroundC, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.appearance_preview_label),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onBackgroundMutedC,
        )
        // Agenten-Bubble (links, kleine untere Ecke — P11-Silhouette).
        Row {
            Box(
                Modifier
                    .background(
                        scheme.bubbleAgentC,
                        RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column {
                    Text(
                        stringResource(R.string.appearance_preview_agent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onBubbleAgentC,
                    )
                    Text(
                        "12:04",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onBubbleAgentC,
                    )
                }
            }
        }
        // Eigene Bubble (rechts, kleine untere Ecke rechts).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .background(
                        scheme.bubbleOwnC,
                        RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        stringResource(R.string.appearance_preview_own),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onBubbleOwnC,
                    )
                    Text(
                        "12:05  ✓✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onBubbleOwnC,
                    )
                }
            }
        }
        // Komposer-Zeile: Akzent = Send-Kreis mit abgeleitetem Icon-Kontrast.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .background(scheme.onBackgroundC.copy(alpha = 0.06f), CircleShape),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    stringResource(R.string.appearance_preview_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onBackgroundMutedC,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(36.dp)
                    .background(scheme.accentC, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // Pfeil als Text-Glyph (kein neues Drawable nötig).
                Text(
                    "➤",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onAccentC,
                )
            }
        }
    }
}

// ── Voreinstellungen je Slot (P12: Flipsi-Standard zuerst, dann kuratierte
// Palette; BEWUSST auch helle Flächen dabei — sie zeigen die automatische
// Text-Korrektur live in der Preview). Alle Werte ARGB-Ints.

/** Akzent: Flipsi-Standard Ember + Signal-Töne der Hauspalette. */
private val ACCENT_PRESETS = listOf(
    0xFFED7931.toInt(), // Ember (Standard)
    0xFF2FBF71.toInt(), // SignalGreen
    0xFFD3A02F.toInt(), // SignalAmber
    0xFF4C9AFF.toInt(), // Blau
    0xFFB57BFF.toInt(), // Violett
    0xFFE5484D.toInt(), // SignalRot
    0xFF141414.toInt(), // Fast-Schwarz (→ heller Text)
    0xFFE8EAED.toInt(), // Fast-Weiß (→ dunkler Text)
)

/** Eigene Bubble: Standard EmberDim + dunkle/helle Alternativen. */
private val BUBBLE_OWN_PRESETS = listOf(
    0xFF3A2A1F.toInt(), // EmberDim (Standard)
    0xFF1F3A2A.toInt(), // Grün-Dunkel
    0xFF1F2A3A.toInt(), // Blau-Dunkel
    0xFF333742.toInt(), // Grau-Dunkel
    0xFF4ECDC4.toInt(), // Helles Türkis (Auto-Korrektur-Demo)
    0xFFE8DCC8.toInt(), // Helles Warmweiß
)

/** Agenten-Bubble: Standard Panel + ruhige/helle Alternativen. */
private val BUBBLE_AGENT_PRESETS = listOf(
    0xFF1A1D23.toInt(), // Panel (Standard)
    0xFF2A2F38.toInt(), // Line-Ton
    0xFF243038.toInt(), // Petrol-Dunkel
    0xFF3A2A1F.toInt(), // EmberDim
    0xFFECEFF4.toInt(), // Hell (→ dunkler Text)
    0xFF331F2A.toInt(), // Brombeer-Dunkel
)

/** Hintergrund: Standard Void + dunkle/helle Alternativen. */
private val BACKGROUND_PRESETS = listOf(
    0xFF0E1013.toInt(), // Void (Standard)
    0xFF10151D.toInt(), // Nachtblau
    0xFF1A1D23.toInt(), // Panel-Ton
    0xFF241B2F.toInt(), // Aubergine-Dunkel
    0xFFECEFF4.toInt(), // Hell (→ dunkler Fließtext)
    0xFFF3EDE3.toInt(), // Warm-Hell
)
