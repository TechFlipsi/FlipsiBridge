package com.hermesandroid.bridge.ui.call

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.client.CallRules
import com.hermesandroid.bridge.client.CallRules.CallSession
import com.hermesandroid.bridge.ui.FlipsiColors

/**
 * W21 ANRUF (P4-P6): Anruf-UI — bewusst Presentation-only. Alle Frame-/Timer-/
 * Freigabe-Verbände liegen in [CallRules] (JVM-getestet) und RelayClient
 * (StateFlow + Signale); die Call-Mount-Lane in ChatScreen bindet die Aktionen
 * (Annehmen/Ablehnen/Auflegen/Sprechen/Screenshot/Pill) an sie.
 *
 * Apple-Design (Kontraste nachgerechnet ≥ 4,5:1, Touch-Ziele ≥ 48 dp, sentence
 * case, kein Doppel-Anker):
 * - Annehmen: SignalGreen-Fläche + Void-Text = 7,99:1
 * - Ablehnen/Auflegen: SignalRed + Void-Text = 4,87:1
 * - Sprechen/Bestätigungs-Chip: Ember + Void-Text = 6,70:1
 * - Karte: Void-Fläche (96 %) + TextPrimary = 15,81:1, TextSecondary = 7,48:1
 * - Freigabe-Pill an: EmberDim + SignalGreen = 5,76:1
 *
 * Strings (MERGE-LANE 18.11.): alle 25 call_*-UI-Texte leben je Locale in
 * strings.xml (DE = values/ + values-de/, EN = values-en/) — der Screen liest
 * sie exklusiv über stringResource(R.string.call_*); die Klingel-Kanal-/-
 * Notification-Texte des RelayClient löst context.getString auf (Parität ×3,
 * kein DE-Literal mehr in der UI).
 */

/** Ankommender Anruf — Vollbild-Karte (WA-Muster) mit Annehmen/Ablehnen 48 dp. */
@Composable
fun CallRingingOverlay(
    session: CallSession,
    alias: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    // A11y-Label einmal hoisten (stringResource lebt nicht im semantics-Lambda).
    val ringingA11y = stringResource(R.string.call_ringing_a11y, alias)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FlipsiColors.Void.copy(alpha = 0.96f))
            .systemBarsPadding()
            .semantics { contentDescription = ringingA11y },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Generisches Anruf-Signet (ic_call) — Alias/Kennung generisch:
            // Anzeigename (relay.state alias) oder ehrlich die agent_id.
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(FlipsiColors.PanelElev),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_call),
                    contentDescription = stringResource(R.string.call_ring_icon_a11y),
                    tint = FlipsiColors.Ember,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = if (session.kind == "voice") {
                    stringResource(R.string.call_ring_kind_voice)
                } else {
                    stringResource(R.string.call_ring_kind_generic)
                },
                color = FlipsiColors.TextSecondary,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = alias,
                color = FlipsiColors.TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (session.reason.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = session.reason,
                    color = FlipsiColors.TextSecondary,
                    fontSize = 16.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(36.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Ablehnen: Void-Text statt Weiß — weiß auf SignalRed fiele
                // (≈ 3,2:1) unter die 4,5:1-Grenze, Void hält 4,87:1.
                CallPillButton(
                    label = stringResource(R.string.call_action_decline),
                    a11y = stringResource(R.string.call_action_decline_a11y),
                    background = FlipsiColors.SignalRed,
                    content = FlipsiColors.Void,
                    modifier = Modifier.weight(1f),
                    onClick = onDecline,
                )
                CallPillButton(
                    label = stringResource(R.string.call_action_accept),
                    a11y = stringResource(R.string.call_action_accept_a11y),
                    background = FlipsiColors.SignalGreen,
                    content = FlipsiColors.Void,
                    modifier = Modifier.weight(1f),
                    onClick = onAccept,
                )
            }
        }
    }
}

/**
 * Laufender Anruf — Status-Zeile (WA-Verband: Dauer + Agentalias) mit
 * Auflegen, Sprechen-Modus, G11-Freigabe-Pill und G12-Screenshot
 * (3-s-Bestätigungs-Chip nach §7.2). Alle Knöpfe ≥ 48 dp Touch-Ziel.
 *
 * W22 A1 (STT on-device v1): der Mikro-Knopf ist EIN Aufenthaltsort für
 * BEIDE Sprach-Modi (KEIN-DOPPEL):
 * - [handsFree] = true: Freihand — die on-device-Erkennung lauscht (grüne
 *   Fläche + Lauscht-Label, [sttListening]); TIPPEN wechselt zurück auf
 *   Push-to-Talk (Erkennung stoppt in der Bindung).
 * - [handsFree] = false: Push-to-Talk wie gehabt (tippen startet den Chunk,
 *   nochmal tippen sendet); LANG DRÜCKEN wählt den Freihand-Modus
 *   (Verfügbarkeits-/Mikrofon-Gate + sichtbarer Fail-Weg in der Bindung).
 */
@Composable
fun CallActiveStrip(
    session: CallSession,
    alias: String,
    durationSeconds: Long,
    steerCapabilityOn: Boolean,
    screengrabPendingConfirmation: Boolean,
    talkRecording: Boolean,
    handsFree: Boolean,
    sttListening: Boolean,
    infoLine: String?,
    onToggleSteer: () -> Unit,
    onScreengrabRequest: () -> Unit,
    onTalkStart: () -> Unit,
    onTalkEnd: () -> Unit,
    onToggleHandsFree: (toHandsFree: Boolean) -> Unit,
    onHangUp: () -> Unit,
) {
    // A11y-Label einmal hoisten (stringResource lebt nicht im semantics-Lambda)
    // — die Dauer je Aktualisierung korrekt gelöst, vor der Column deklariert.
    val activeA11y = stringResource(
        R.string.call_active_a11y, alias, CallRules.formatDuration(durationSeconds),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FlipsiColors.Panel)
            .semantics { contentDescription = activeA11y },
    ) {
        // Zeile 1 (Status-Zeile + Auflegen): Alias, Dauer, Ende.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = alias,
                    color = FlipsiColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.call_active_status, CallRules.formatDuration(durationSeconds),
                    ),
                    color = FlipsiColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CallPillButton(
                label = stringResource(R.string.call_action_hangup),
                a11y = stringResource(R.string.call_action_hangup_a11y),
                background = FlipsiColors.SignalRed,
                content = FlipsiColors.Void,
                modifier = Modifier.width(110.dp),
                onClick = onHangUp,
            )
        }
        // Zeile 2 (Call-Verband): Sprechen (P5), Wisch-Freigabe (G11), Screenshot (G12).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // W22 A1 (STT v1) + P5 (§3.1): EIN Mikro-Knopf, ZWEI Modi.
            // Freihand: on-device-Erkennung lauscht (grün + Lauscht-Label),
            // TIPPEN wechselt zurück auf Push-to-Talk.
            // Push-to-Talk: drücken startet den Chunk (Barge-in stoppt die
            // Agent-Ausgabe), loslassen sendet ihn; LANG DRÜCKEN = Modus-
            // Auswahl Freihand (Gate + Fail-Weg in der Bindung, nicht hier —
            // der Strip bleibt Presentation-only).
            if (handsFree) {
                CallPillButton(
                    label = if (sttListening) {
                        stringResource(R.string.call_stt_handsfree_listening)
                    } else {
                        stringResource(R.string.call_stt_handsfree)
                    },
                    a11y = if (sttListening) {
                        stringResource(R.string.call_stt_handsfree_listening_a11y)
                    } else {
                        stringResource(R.string.call_stt_handsfree_a11y)
                    },
                    background = if (sttListening) FlipsiColors.SignalGreen else FlipsiColors.Ember,
                    content = FlipsiColors.Void,
                    modifier = Modifier.weight(1.1f),
                    onClick = { onToggleHandsFree(false) },
                )
            } else {
                CallPillButton(
                    label = if (talkRecording) {
                        stringResource(R.string.call_action_talk_recording)
                    } else {
                        stringResource(R.string.call_action_talk)
                    },
                    a11y = if (talkRecording) {
                        stringResource(R.string.call_action_talk_recording_a11y)
                    } else {
                        stringResource(R.string.call_action_talk_a11y)
                    },
                    background = if (talkRecording) FlipsiColors.SignalRed else FlipsiColors.Ember,
                    content = FlipsiColors.Void,
                    modifier = Modifier.weight(1.1f),
                    onClick = if (talkRecording) onTalkEnd else onTalkStart,
                    // LANG DRÜCKEN = Modus-Auswahl Freihand (Mikro-Button lang,
                    // EIN Aufenthaltsort — s. KDoc oben).
                    onLongClick = { onToggleHandsFree(true) },
                    longClickA11y = stringResource(R.string.call_stt_long_a11y),
                )
            }
            SteerPill(
                session = session,
                steerCapabilityOn = steerCapabilityOn,
                modifier = Modifier.weight(1.2f),
                onToggle = onToggleSteer,
            )
            // G12 (§7.2): Screenshot NUR für den Agenten — Standard = 3-s-
            // Bestätigungs-Chip (zweiter Tap im Fenster sendet).
            CallPillButton(
                label = if (screengrabPendingConfirmation) {
                    stringResource(R.string.call_action_screengrab_confirm)
                } else {
                    stringResource(R.string.call_action_screengrab)
                },
                a11y = if (screengrabPendingConfirmation) {
                    stringResource(R.string.call_action_screengrab_confirm_a11y)
                } else {
                    stringResource(R.string.call_action_screengrab_a11y)
                },
                background = if (screengrabPendingConfirmation) FlipsiColors.Ember else FlipsiColors.PanelElev,
                content = if (screengrabPendingConfirmation) FlipsiColors.Void else FlipsiColors.TextPrimary,
                modifier = Modifier.weight(1.2f),
                onClick = onScreengrabRequest,
            )
        }
        if (!infoLine.isNullOrBlank()) {
            Text(
                text = infoLine,
                color = FlipsiColors.TextSecondary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 48dp-Pill-Knopf (Kontrast-Kombos oben dokumentiert, alles ≥ 4,5:1).
 * W22 A1: [onLongClick] != null → combinedClickable (Lang-Drücken für die
 * Freihand-Modus-Wahl am Mikro-Knopf); sonst klassisches clickable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallPillButton(
    label: String,
    a11y: String,
    background: Color,
    content: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickA11y: String? = null,
) {
    // Lang-Press-Wirkung zusätzlich ins KLICK-Label (TalkBack liest beides,
    // die Geste bleibt auffindbar); null-Check hält den klassischen Pfad
    // (reines clickable) unangetastet.
    val clickLabel = if (longClickA11y != null) "$a11y. $longClickA11y" else a11y
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        onClickLabel = clickLabel,
                        onLongClickLabel = longClickA11y,
                        onLongClick = onLongClick,
                    ) { onClick() }
                } else {
                    Modifier.clickable(onClickLabel = clickLabel) { onClick() }
                },
            )
            .semantics { contentDescription = a11y }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = content,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * G11-Freigabe-Pill: an = EmberDim-Fläche + SignalGreen-Text (5,76:1),
 * aus = PanelElev + TextSecondary (sichtbare Line-Grenze); Capability
 * „Gerät bedienen“ fehlend = gedimmt ohne Toggle (ehrlich — der bestehende
 * interaction-Verband kann ohnehin nicht swipen).
 */
@Composable
private fun SteerPill(
    session: CallSession,
    steerCapabilityOn: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val on = session.agentScreenSteer && steerCapabilityOn
    val background = if (on) FlipsiColors.EmberDim else FlipsiColors.PanelElev
    val content = when {
        !steerCapabilityOn -> FlipsiColors.TextSecondary.copy(alpha = 0.5f)
        on -> FlipsiColors.SignalGreen
        else -> FlipsiColors.TextSecondary
    }
    val a11y = if (on) {
        stringResource(R.string.call_steer_on_a11y)
    } else {
        stringResource(R.string.call_steer_off_a11y)
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .then(
                if (steerCapabilityOn) {
                    Modifier.border(1.dp, FlipsiColors.Line, RoundedCornerShape(24.dp))
                } else {
                    Modifier
                },
            )
            .clickable(
                enabled = steerCapabilityOn,
                onClickLabel = a11y,
            ) { onToggle() }
            .semantics { contentDescription = a11y }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when {
                !steerCapabilityOn -> stringResource(R.string.call_steer_cap_missing)
                on -> stringResource(R.string.call_steer_on)
                else -> stringResource(R.string.call_steer_off)
            },
            color = content,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
