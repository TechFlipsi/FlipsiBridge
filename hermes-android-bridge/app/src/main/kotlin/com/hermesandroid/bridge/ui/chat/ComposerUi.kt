package com.hermesandroid.bridge.ui.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.ui.FlipsiColors

/**
 * Komposer Welle 12 (Referenz-Übernahme, Flipsi-Farben): Modell- und
 * Denken-Auswahl als Chips an der Eingabeleiste; die Auswahl liegt pro Agent
 * (SharedPreferences) und reist optional mit jeder chat.send-Nachricht
 * (RelayClient.sendChat → Relay → Flipsi-Adapter → Gateway /model-Befehl,
 * session-scoped). 'Auto' und 'aus' senden KEINE Felder — der Gateway
 * behält sein Profil-Modell, nichts wird still verschlechtert.
 */

/** Denken-Stufen: Anzeige (DE) + Leitungs-Wert (Gateway-Grammatik). */
val THINK_STAGES = listOf(
    "aus" to null,
    "leicht" to "low",
    "hoch" to "high",
    "max" to "max",
)

/** Chip-Beschriftung für die Denken-Stufe (null = 'aus' → kein Chip). */
fun effortGermanLabel(level: String?): String? = when (level) {
    null -> null
    "low" -> "leicht"
    "high" -> "hoch"
    "max" -> "max"
    else -> null
}

object ChatComposerPrefs {
    private const val FILE = "composer_prefs"
    private const val KEY_MODELS = "chat_models_csv"

    fun model(context: Context, agentId: String): String? {
        if (agentId.isBlank()) return null
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return p.getString("model_$agentId", null)
    }

    fun setModel(context: Context, agentId: String, model: String?) {
        if (agentId.isBlank()) return
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (model.isNullOrBlank() || model == "Auto") p.edit().remove("model_$agentId").apply()
        else p.edit().putString("model_$agentId", model).apply()
    }

    fun effort(context: Context, agentId: String): String? {
        if (agentId.isBlank()) return null
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return p.getString("effort_$agentId", null)
    }

    fun setEffort(context: Context, agentId: String, level: String?) {
        if (agentId.isBlank()) return
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (level.isNullOrBlank()) p.edit().remove("effort_$agentId").apply()
        else p.edit().putString("effort_$agentId", level).apply()
    }

    fun models(context: Context): List<String> {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return (p.getString(KEY_MODELS, "") ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    fun addModel(context: Context, model: String) {
        val m = model.trim()
        if (m.isEmpty()) return
        val cur = models(context).toMutableList()
        if (m !in cur) cur.add(m)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODELS, cur.joinToString(",")).apply()
    }

    fun removeModel(context: Context, model: String) {
        val cur = models(context).toMutableList()
        cur.remove(model.trim())
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODELS, cur.joinToString(",")).apply()
    }
}

/**
 * Bottom-Sheet (Referenz: das 'Auto'-Chip öffnet die Auswahl): Modell-Liste
 * (Auto + eigene Einträge, ergänz- und entfernbar), Denk-Schieber (4 Rasten)
 * und — WELLE-13 P9 — der Vorlesen-Regler je Agent (Gerät spricht Agenten-
 * Antworten). Jede Änderung wirkt SOFORT (die Chips am Komposer spiegeln
 * den Stand) und gilt für die NÄCHSTE Nachricht dieses Chats.
 *
 * WELLE-13 P6 (Auto-Chip-Bug, Fix-Bein 2): das FRÜHERE `if (agentId.isBlank())
 * return` war die eigentliche tote Stelle — bei Demo-Agenten (Emu-Kette ohne
 * Relay) öffnete der Tap auf „Auto“ GAR NICHTS, weil das Composable still
 * returnete, obwohl onOpenPicker → composerPickerOpen korrekt feuerte. Jetzt
 * öffnet das Sheet IMMER; bei blank agentId steht ein ehrlicher Hinweis
 * (Auswahl wirkt nur lokal, Persistenz braucht einen gekoppelten Agenten).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatModelSheet(
    agentId: String,
    currentModel: String?,
    currentEffort: String?,
    onPickModel: (String?) -> Unit,
    onPickEffort: (String?) -> Unit,
    onDismiss: () -> Unit,
    // WELLE-13 P9: Vorlesen-Regler (pro Agent, Persistenz via ChatSpeechPrefs).
    currentReadAloud: Boolean = false,
    onToggleReadAloud: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var customRefresh by remember { mutableIntStateOf(0) }
    var addText by remember { mutableStateOf("") }
    val custom = remember(customRefresh) { ChatComposerPrefs.models(context) }
    val stageIndex = THINK_STAGES.indexOfFirst { it.second == currentEffort }
        .takeIf { it >= 0 } ?: 0

    // WELLE-13 P9: Engine-Zustand für den ehrlichen Fail-Hinweis (Checkliste K).
    val speechState by ChatSpeechSpeaker.state.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = FlipsiColors.Panel) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            // WELLE-13 P6: Demo-Eintrag — Sheet öffnet trotzdem (kein stiller
            // No-op), sagt aber ehrlich, dass die Auswahl nicht persistiert.
            if (agentId.isBlank()) {
                Text(
                    "Demo-Eintrag (nicht gekoppelt) — Auswahl wirkt nur lokal, " +
                        "bis der Agent gekoppelt ist.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.SignalAmber,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text("Modell", style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                 color = FlipsiColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            ChipRow(label = "Auto", sub = "Agenten-Standard", selected = currentModel == null) {
                ChatComposerPrefs.setModel(context, agentId, null)
                onPickModel(null)
            }
            custom.forEach { m ->
                ChipRow(
                    label = m,
                    selected = currentModel == m,
                    onRemove = {
                        ChatComposerPrefs.removeModel(context, m)
                        if (currentModel == m) onPickModel(null)
                        customRefresh++
                    },
                ) {
                    ChatComposerPrefs.setModel(context, agentId, m)
                    onPickModel(m)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = addText,
                    onValueChange = { addText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Eigenes Modell hinzufügen …",
                                         color = FlipsiColors.TextSecondary) },
                )
                TextButton(
                    onClick = {
                        ChatComposerPrefs.addModel(context, addText)
                        addText = ""
                        customRefresh++
                    },
                    enabled = addText.isNotBlank(),
                ) { Text("Hinzu", color = if (addText.isNotBlank()) FlipsiColors.Ember
                                         else FlipsiColors.TextSecondary) }
            }

            Spacer(Modifier.height(16.dp))
            Text("Denken", style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                 color = FlipsiColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Slider(
                value = stageIndex.toFloat(),
                onValueChange = { idx ->
                    val stage = THINK_STAGES[idx.toInt().coerceIn(0, THINK_STAGES.size - 1)]
                    ChatComposerPrefs.setEffort(context, agentId, stage.second)
                    onPickEffort(stage.second)
                },
                valueRange = 0f..(THINK_STAGES.size - 1).toFloat(),
                steps = THINK_STAGES.size - 2,
                colors = SliderDefaults.colors(
                    thumbColor = FlipsiColors.Ember,
                    activeTrackColor = FlipsiColors.Ember,
                    inactiveTrackColor = FlipsiColors.PanelElev,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                THINK_STAGES.forEachIndexed { idx, (de, _) ->
                    Text(
                        de.replaceFirstChar { it.uppercase() },
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = if (idx == stageIndex) FlipsiColors.Ember
                                else FlipsiColors.TextSecondary,
                        fontWeight = if (idx == stageIndex) FontWeight.SemiBold else null,
                    )
                }
            }

            // ── WELLE-13 P9: Vorlesen-Regler (pro Agent, „wie Denken“) ────────
            Spacer(Modifier.height(16.dp))
            Text("Vorlesen", style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                 color = FlipsiColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Agenten-Antworten vom Gerät vorlesen",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.TextSecondary,
                    )
                }
                Switch(
                    checked = currentReadAloud,
                    onCheckedChange = { enabled ->
                        // Persistenz sofort (vorlesen_<agentId>); beim Anschalten
                        // die Engine anlegen, damit der Zustand beim ersten Sprechen
                        // bekannt ist — Fail-Fall zeigt der Hinweis darunter.
                        ChatSpeechPrefs.setEnabled(context, agentId, enabled)
                        if (enabled) ChatSpeechSpeaker.ensure(context)
                        onToggleReadAloud(enabled)
                    },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = FlipsiColors.OnEmber,
                        checkedTrackColor = FlipsiColors.Ember,
                        uncheckedTrackColor = FlipsiColors.PanelElev,
                    ),
                )
            }
            // WELLE-13 P9 / Checkliste K: ehrlicher Engine-Fail-Hinweis — nie
            // ein stiller Aus (der Regler steht an, aber nichts spricht).
            if (currentReadAloud && speechState is ChatSpeechState.Failed) {
                Spacer(Modifier.height(6.dp))
                Text(
                    (speechState as ChatSpeechState.Failed).reason,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.SignalRedText,
                )
            }
        }
    }
}

/** Eine Auswahl-Zeile im Sheet (Auto-Zeile ohne Entfernen-Knopf). */
@Composable
private fun ChipRow(
    label: String,
    selected: Boolean,
    sub: String? = null,
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                color = if (selected) FlipsiColors.EmberDim else FlipsiColors.PanelElev,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(if (onRemove != null) 44.dp else 0.dp),
            horizontalArrangement = Arrangement.Start) {
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.height(28.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "Modell $label entfernen",
                         tint = FlipsiColors.TextSecondary)
                }
            }
        }
        Text(
            label,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = if (selected) FlipsiColors.TextPrimary else FlipsiColors.TextPrimary,
            fontWeight = if (selected) FontWeight.SemiBold else null,
        )
        if (sub != null) {
            Spacer(Modifier.width(8.dp))
            Text(sub, style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                 color = FlipsiColors.TextSecondary)
        }
        if (selected) {
            Spacer(Modifier.weight(1f))
            Text("aktiv", style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                 color = FlipsiColors.SignalGreen, fontWeight = FontWeight.SemiBold)
        }
    }
}
