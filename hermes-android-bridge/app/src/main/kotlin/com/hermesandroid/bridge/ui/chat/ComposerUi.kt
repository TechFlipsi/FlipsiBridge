package com.hermesandroid.bridge.ui.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.ui.FlipsiColors

/**
 * Komposer Welle 12 (Referenz-Übernahme, Flipsi-Farben): Modell- und
 * Denken-Auswahl als Chips an der Eingabeleiste; die Auswahl liegt pro Agent
 * (SharedPreferences) und reist optional mit jeder chat.send-Nachricht
 * (RelayClient.sendChat → Relay → Flipsi-Adapter → Gateway /model-Befehl,
 * session-scoped). 'Auto' und 'aus' senden KEINE Felder — der Gateway
 * behält sein Profil-Modell, nichts wird still verschlechtert.
 */

/**
 * W19 (17.11.26): Denken-Stufen + Slider-/Sende-Regeln leben in
 * ModelSheetRules.kt (reine JVM-Logik, getestet) — THINK_STAGES,
 * effortStageIndex (App-Auswahl gewinnt, Gateway-Fallback) und sendableModel
 * (NUR Server-Liste, Freitext-Pfad AUS).
 */

/**
 * W15 (I18n): Denken-Stufen-Label als RESSOURCE statt deutschem Literal —
 * auflöst unter dem AppLanguageGate live in DE/EN. null → composer_effort_off
 * („Aus“/„Off“). Die ANZEIGE läuft rein über den Resource-Umweg; ein deutsches
 * Literal-Paar (effortGermanLabel) gab es ohne Aufrufer und wurde 17.11.
 * entfernt.
 */
fun effortLabelRes(level: String?): Int = when (level) {
    "low" -> R.string.composer_effort_light
    "high" -> R.string.composer_effort_high
    "max" -> R.string.composer_effort_max
    else -> R.string.composer_effort_off
}

// W19 (17.11.26): die Nutzer-Modell-Liste (chat_models_csv + add/remove) ist
// ENTFERNT — Sir: „das fliegt komplett raus, nur Dropdown, nichts selbst
// einzugeben“. Auswahl nur noch aus der Server-Liste (agent.models); diese
// Prefs halten NUR die je-Agent-Auswahl (model_<agentId>) + Denk-Stufe.
object ChatComposerPrefs {
    private const val FILE = "composer_prefs"

    // 17.11.26-Räumung: Alt-Geräte halten noch den entfernten Pref-Key
    // 'chat_models_csv' (Nutzer-Modell-Liste). Beim Prefs-Zugriff einmal
    // entfernt — KEINE Migration des Inhalts (die Liste lebt nur noch
    // serverseitig in agent.models).
    private const val LEGACY_MODELS_CSV = "chat_models_csv"

    private fun prefs(context: Context): SharedPreferences {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (p.contains(LEGACY_MODELS_CSV)) p.edit().remove(LEGACY_MODELS_CSV).apply()
        return p
    }

    fun model(context: Context, agentId: String): String? {
        if (agentId.isBlank()) return null
        val p = prefs(context)
        return p.getString("model_$agentId", null)
    }

    fun setModel(context: Context, agentId: String, model: String?) {
        if (agentId.isBlank()) return
        val p = prefs(context)
        if (model.isNullOrBlank() || model == "Auto") p.edit().remove("model_$agentId").apply()
        else p.edit().putString("model_$agentId", model).apply()
    }

    fun effort(context: Context, agentId: String): String? {
        if (agentId.isBlank()) return null
        val p = prefs(context)
        return p.getString("effort_$agentId", null)
    }

    fun setEffort(context: Context, agentId: String, level: String?) {
        if (agentId.isBlank()) return
        val p = prefs(context)
        if (level.isNullOrBlank()) p.edit().remove("effort_$agentId").apply()
        else p.edit().putString("effort_$agentId", level).apply()
    }
}

/**
 * Bottom-Sheet (Referenz: das 'Auto'-Chip öffnet die Auswahl): Modell-Liste
 * NUR aus dem Gateway-Katalog (agent.models) — das Freitext-Feld ist AUS
 * (Sir 0.16.9: „nur Dropdown, nichts selbst einzugeben“, Senden fremder Namen
 * via sendableModel technisch zu), Denk-Schieber (4 Rasten) und — WELLE-13
 * P9 — der Vorlesen-Regler je Agent (Gerät spricht Agenten-Antworten). Jede
 * Änderung wirkt SOFORT (die Chips am Komposer spiegeln den Stand) und gilt
 * für die NÄCHSTE Nachricht dieses Chats.
 *
 * W19 (17.11.26): Aktiv-Zeile — der GATEWAY-Stand (agent.models 'current' +
 * 'effort') wird im Sheet angezeigt; ohne 'current' bleibt die Zeile weg
 * (kein Platzhalter-Geraffel). Ohne App-Auswahl ist der Gateway-'effort' die
 * Slider-Suggestion ([effortStageIndex]); die App-Auswahl GEWINNT weiter.
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
    // W16 (Fixliste 35): Modell-Katalog DIESES Agenten aus agent.models /
    // agent.models.enc (RelayClient.agentModels). null/leer = fail-soft:
    // nur Auto-Modus + Hinweis ('keine Modell-Liste') — KEIN Eingabefeld mehr.
    gatewayModels: List<String>? = null,
    // W19 (17.11.26): GATEWAY-Stand dieses Agenten — das aktuell laufende
    // Modell (agent.models 'current') + Denk-Stufe ('effort'), je Frame-
    // Verband (RelayClient.agentModelCurrent / agentModelEffort). Fail-soft:
    // null = Zeile/Suggestion weglassen. Werte sind REIN serverseitig
    // (Sir-Generik-Regel: keine Modell-Namen, keine Defaults in der App).
    gatewayCurrent: String? = null,
    gatewayEffort: String? = null,
) {
    val context = LocalContext.current
    // W19 (17.11.26): Slider-Raste — die App-Auswahl GEWINNT (setEffort schreibt
    // die App-Pref beim Slider-Zug); ohne App-Pref ist der Gateway-Stand
    // ('effort') die Vorschlags-Raste (Regel in ModelSheetRules, JVM-getestet;
    // Befund-Regression: der Slider stand ohne App-Pref dauerhaft auf Raste 0).
    val stageIndex = effortStageIndex(currentEffort, gatewayEffort)

    // WELLE-13 P9: Engine-Zustand für den ehrlichen Fail-Hinweis (Checkliste K).
    val speechState by ChatSpeechSpeaker.state.collectAsState()

    // W18-4 („Composer-Eingabe kaputt nach Klicks im Auto-Modell-Menü“, Fix):
    // BEIM SHEET-SCHLUSS FOKUS + TASTATUR EXPLIZIT RÄUMEN. Der Abbruch-Pfad
    // (Outside-Tap/Back → onDismissRequest) und der Speichern-Pfad (Auswahl-
    // klick, schließt programmatisch im Caller) enden beide in closeSheet —
    // ein hängender Fokus (Sheet-Clickables) darf die Komposer-Eingabe nicht
    // mehr blockieren. Beweis, dass der 0.16.9-Freitext-Weg den HALBEN Bug
    // (IME über dem Sheet) strukturell ENTFERNT hat: dieses Sheet trägt KEIN
    // Textfeld mehr (kein TextField/BasicTextField/KeyboardOptions im File —
    // Auswahl nur ChipRow/Slider/Switch, 0.16.9 „nur Dropdown“) — die
    // clearFocus/hideKeyboard-Schiene ist der Backstop für die Fokus-Rück-
    // fälligkeit (Sheet schließt, Tastatur blieb, Fokus fiel zurück).
    // LocalFocusManager.clearFocus lebt DOPPELT: hier (onDismissRequest-Weg)
    // und im Caller (ChatScreen.onPickModel/onDismiss — der programmatische
    // Schluss feuert onDismissRequest nicht).
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeSheet() {
        keyboard?.hide()
        focusManager.clearFocus()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = ::closeSheet,
        containerColor = FlipsiColors.Panel,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            // WELLE-13 P6: Demo-Eintrag — Sheet öffnet trotzdem (kein stiller
            // No-op), sagt aber ehrlich, dass die Auswahl nicht persistiert.
            if (agentId.isBlank()) {
                Text(
                    // W15: Ressource statt deutschem Literal (composer_demo_hint).
                    stringResource(R.string.composer_demo_hint),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.SignalAmber,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(stringResource(R.string.composer_title),
                 style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                 color = FlipsiColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            // W19 (17.11.26): Gateway-Stand ANZEIGEN — 'Aktiv: <current> ·
            // Denken: <effort>' aus den agent.models-Frames (gateway-seitiger
            // Stand, NUR Serverwissen). Ohne 'current' KEINE Zeile (kein
            // Platzhalter-Geraffel); die Anzeige ist rein lesend — gewählt
            // wird darunter aus der Server-Liste / Auto.
            if (gatewayCurrent != null) {
                Text(
                    text = if (gatewayEffort.isNullOrBlank())
                        stringResource(R.string.composer_active_line_model, gatewayCurrent)
                    else
                        stringResource(
                            R.string.composer_active_line, gatewayCurrent, gatewayEffort,
                        ),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
            }
            // W16 (Fixliste 35): „Auto" bleibt STAND OBEN — der Gateway-Standard.
            ChipRow(
                label = stringResource(R.string.composer_auto),
                sub = stringResource(R.string.composer_auto_sub),
                selected = currentModel == null,
            ) {
                ChatComposerPrefs.setModel(context, agentId, null)
                onPickModel(null)
            }
            // W19 (17.11.26): fail-soft ohne Liste — nur Auto-Modus + Hinweis
            // (Liste fehlt = Adapter/Gateway liefert agent.models noch nicht).
            // KEIN Eingabefeld, KEINE Nutzer-Liste mehr: freie Modell-Namen
            // können die App technisch nicht mehr verlassen (Sendepfad via
            // sendableModel serverlisten-gebunden, Ende der Freitext-Weg).
            if (gatewayModels.isNullOrEmpty()) {
                Text(
                    stringResource(R.string.composer_no_list),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = FlipsiColors.TextSecondary,
                )
                Spacer(Modifier.height(4.dp))
            }
            // W16 (Fixliste 35): wählbare Liste — NUR die vom Gateway gelieferten
            // Modell-IDs (agent.models), KEINE Entfern-Wege (Quelle ist der Server).
            gatewayModels.orEmpty().forEach { m ->
                ChipRow(
                    label = m,
                    selected = currentModel == m,
                ) {
                    ChatComposerPrefs.setModel(context, agentId, m)
                    onPickModel(m)
                }
            }
            // W19 (17.11.26): Eigene Einträge + Freitext-Eingabe sind ENTFERNT
            // (Sir: „nur Dropdown, nichts selbst einzugeben“). NUR die vom
            // Gateway gelieferten Modell-IDs (agent.models) bleiben wählbar;
            // sendbar ist allein ein exakter Listen-Wert (ModelSheetRules.
            // sendableModel — Freitext-Pfad technisch zu).

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.composer_thinking_title),
                 style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                 color = FlipsiColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            // W15: Stufen-Labels als Ressource (DE/EN) statt THINK_STAGES-Literalen.
            val stageLabels = THINK_STAGES.map { stringResource(effortLabelRes(it.second)) }
            val effortA11y = stringResource(R.string.composer_effort_a11y)
            val effortState = stageLabels.getOrNull(stageIndex)
                ?: stringResource(R.string.composer_effort_off)
            Slider(
                value = stageIndex.toFloat(),
                modifier = Modifier.semantics {
                    this.contentDescription = effortA11y
                    stateDescription = effortState
                },
                onValueChange = { idx ->
                    val stage = THINK_STAGES[idx.toInt().coerceIn(0, THINK_STAGES.size - 1)]
                    // B1 (Flagship-Review 17.11.): Raste 0 = bewusstes „aus“ wird
                    // als EFFORT_OFF persistiert (NICHT Key löschen) — sonst war
                    // „aus“ von „nie gewählt“ ununterscheidbar und der Daumen
                    // sprang bei vorhandenem Gateway-effort auf dessen Raste
                    // zurück (Springback-Regression). „aus“ ist jetzt stabil.
                    val level = stage.second ?: EFFORT_OFF
                    ChatComposerPrefs.setEffort(context, agentId, level)
                    onPickEffort(level)
                },
                valueRange = 0f..(THINK_STAGES.size - 1).toFloat(),
                steps = THINK_STAGES.size - 2,
                colors = SliderDefaults.colors(
                    thumbColor = FlipsiColors.Ember,
                    activeTrackColor = FlipsiColors.Ember,
                    // HIG-W19: inaktiver Track = LineStrong (3,09:1) — PanelElev
                    // lag bei 1,11:1 (Regler unsichtbar).
                    inactiveTrackColor = FlipsiColors.LineStrong,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                THINK_STAGES.forEachIndexed { idx, (_, _) ->
                    Text(
                        stageLabels[idx],
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = if (idx == stageIndex) FlipsiColors.Ember
                                else FlipsiColors.TextSecondary,
                        fontWeight = if (idx == stageIndex) FontWeight.SemiBold else null,
                    )
                }
            }

            // ── WELLE-13 P9: Vorlesen-Regler (pro Agent, „wie Denken“) ────────
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.composer_read_aloud_title),
                 style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                 color = FlipsiColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.composer_read_aloud),
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
                        // HIG-W19: Daumen im Aus-Zustand = TextSecondary
                        // (5,97:1 auf PanelElev-Track, war Material-Default
                        // 2,79:1) — der Schalter bleibt sichtbar.
                        uncheckedThumbColor = FlipsiColors.TextSecondary,
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

/**
 * Eine Auswahl-Zeile im Sheet (Entfernen-Knopf entfiel mit der Nutzer-Liste —
 * Auswahl ausschließlich aus der Server-Liste; Auto-Zeile mit Sub-Label).
 */
@Composable
private fun ChipRow(
    label: String,
    selected: Boolean,
    sub: String? = null,
    onClick: () -> Unit,
) {
    // W15: Aktiv-Text als Ressource (DE/EN) statt deutscher Literale.
    val activeLabel = stringResource(R.string.badge_active)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                color = if (selected) FlipsiColors.EmberDim else FlipsiColors.PanelElev,
                shape = RoundedCornerShape(12.dp),
            )
            // HIG-W19 (A11y): Auswahl-Zustand für TalkBack — Role.RadioButton
            // + ausgewählt/State-Beschreibung (stateDescription „Aktiv“), die
            // ganze Zeile ist das Ziel; Touch-Ziel 48 dp (inneres Padding
            // vertikal 14, war 12 → war ~44 dp).
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { onClick() },
            )
            .semantics {
                if (selected) stateDescription = activeLabel
            }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            // selected/ungelesen teilen dieselbe Textfarbe (Kontrast trägt das
            // EmberDim-Background + SemiBold) — die if/else-Zweige waren identisch.
            color = FlipsiColors.TextPrimary,
            fontWeight = if (selected) FontWeight.SemiBold else null,
        )
        if (sub != null) {
            Spacer(Modifier.width(8.dp))
            Text(sub, style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                 color = FlipsiColors.TextSecondary)
        }
        if (selected) {
            Spacer(Modifier.weight(1f))
            Text(activeLabel, style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                 color = FlipsiColors.SignalGreen, fontWeight = FontWeight.SemiBold)
        }
    }
}
