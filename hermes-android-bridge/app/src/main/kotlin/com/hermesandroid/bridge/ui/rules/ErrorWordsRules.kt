package com.hermesandroid.bridge.ui.rules

import com.hermesandroid.bridge.R

/**
 * W26d (UX-Report F1): Klartext-Wörter je Backend-Fehler-Familien-Code — die
 * Snacks zeigen kein Maschinen-Wort mehr („too-many“/„not-found“ …), sondern
 * den verständlichen Grund (i18n je values ×3); der rohe Code bleibt am
 * DEBUG-Log/Draht, NIEMALS als Snack-Wort für den verwirrten Nutzer.
 *
 * Die Familie ist am Backend GELESEN (Report-Zitate):
 *  - agent.invite-*-ok (tools/android_relay.py): „denied“ (:1388, kein
 *    Admin), „too-many“ (:1407, Cap offener Einladungen), „not-found“
 *    (:1461, Revoke-Ziel weg),
 *  - cron.*_ok (contrib/hermes-chat-plugin/adapter.py:4640/4644/4647 ff.):
 *    „not-found“ (Job weg), „invalid“ (Gateway lehnte Form ab), „error“
 *    (Gateway-Ausnahme) + Relay-NACK „agent-unknown“ (tools/android_relay.py
 *    :1662/:1672 — der genannte Agent ist unverortet).
 *
 * „not-supported“ läuft NICHT durch diese Faser: die Kontexte haben eigene
 * ehrliche not-supported-Snacks (CronRemove/CronPauseResume/CronAdd-Rules).
 * Unbekannter/leerer Code → null: die UI zeigt den rohen Code EHRlich weiter
 * (nie ein erfundenes Klartext-Wort — never-wrong vor always-right).
 */
object ErrorWordsRules {

    /**
     * Ressourcen-Verweis des Klartext-Worts je Fehlfamilie — null = kein
     * Klartext-Wort bekannt (der rohe Code bleibt sichtbar).
     */
    fun plainWordRes(error: String?): Int? = when ((error ?: "").trim().lowercase()) {
        "too-many" -> R.string.error_word_too_many
        "not-found" -> R.string.error_word_not_found
        "denied" -> R.string.error_word_denied
        "invalid" -> R.string.error_word_invalid
        "error" -> R.string.error_word_error
        "agent-unknown" -> R.string.error_word_agent_unknown
        else -> null
    }
}
