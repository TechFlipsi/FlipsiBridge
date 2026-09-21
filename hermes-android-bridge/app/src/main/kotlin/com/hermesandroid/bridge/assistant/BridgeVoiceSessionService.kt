package com.hermesandroid.bridge.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * FlipsiBridge Assistenten-Rolle (Phase 2).
 *
 * Wenn der User FlipsiBridge als "Digitale Assistenz-App" einträgt
 * (Einstellungen → Apps → Standard-Apps → Digitale Assistenz-App),
 * öffnet der Ecken-Swipe/Assistent-Geste diese Session → ChatActivity.
 * AssistStructure (Screen-Kontext) liest Phase 2b aus dem Bundle.
 */
class BridgeVoiceSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return BridgeVoiceSession(this)
    }
}

class BridgeVoiceSession(context: android.content.Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showSessionId: Int) {
        super.onShow(args, showSessionId)
        // Chat öffnen und Assistenten-Session direkt beenden
        val i = Intent(context, com.hermesandroid.bridge.chat.ChatActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        finish()
    }
}