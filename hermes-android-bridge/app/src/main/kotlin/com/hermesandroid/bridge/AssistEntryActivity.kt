package com.hermesandroid.bridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * WELLE 13 P10 (Sir: „Gemini ersetzbar"): Einstiegs-Activity für die
 * ASSISTENTEN-Rolle. Wenn FlipsiBridge als Standard-Assistent eingestellt
 * ist, öffnet der System-Assistenten-Trigger (Long-Press-Power/Home-Sweep/
 * Assistenten-Aktion) DIESE Activity: sie trampolint in die App (Chat-Route)
 * mit dem Vorlesen/Voice-Verband — der Nutzer spricht, das Telefon sendet.
 * Keine eigene UI, kein State — reines Tor (finish() nach Dispatch).
 * Manifest: android.intent.action.ASSIST + category DEFAULT (Rolle-Signatur).
 */
class AssistEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AssistLaunch.pendingVoice = true
        val main = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(main)
        finish()
    }
}

/**
 * Bus für den Assistenten-Start (P10): ConversationBody konsumiert den
 * Merker einmalig und startet die Sprachaufnahme (der Nutzer redet, die
 * Sendung geht als Chat-Nachricht zum Agenten).
 */
object AssistLaunch {
    @Volatile
    var pendingVoice: Boolean = false
}
