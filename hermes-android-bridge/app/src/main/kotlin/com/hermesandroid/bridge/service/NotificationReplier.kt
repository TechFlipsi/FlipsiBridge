package com.hermesandroid.bridge.service

import android.app.Notification
import android.app.RemoteInput
import android.service.notification.StatusBarNotification

/**
 * NotificationReplier - antwortet in eine Notification (z.B. WhatsApp/Telegram)
 * ueber deren Direct-Reply-Action mit RemoteInput. Kein Tippen noetig.
 */
object NotificationReplier {

    /**
     * Sucht in der Benachrichtigung mit dem gegebenen Key eine Action mit
     * RemoteInput und schreibt `text` hinein. Gibt eine Fehlermeldung oder null zurueck.
     */
    fun reply(key: String, text: String): String? {
        val listener = BridgeNotificationListener.instance
            ?: return "Notification-Listener läuft nicht (Fähigkeit 'notifications' in der App aktivieren)"
        val active = listener.activeNotifications
        val sbn: StatusBarNotification = active.firstOrNull { it.key == key }
            ?: active.firstOrNull { it.key.contains(key) }
            ?: return "Benachrichtigung nicht gefunden (key=$key)"
        return replyInto(sbn, text)
    }

    /** Antworten in die erste gefundene Benachrichtigung der App (paketName), die Direct-Reply hat. */
    fun replyLatestForPackage(packageName: String, text: String): String? {
        val listener = BridgeNotificationListener.instance
            ?: return "Notification-Listener läuft nicht"
        val sbn = listener.activeNotifications
            .filter { it.packageName == packageName && replyAction(it.notification) != null }
            .maxByOrNull { it.postTime }
            ?: return "Keine antwortfähige Benachrichtigung der App $packageName gefunden"
        return replyInto(sbn, text)
    }

    private fun replyAction(n: Notification): android.app.Notification.Action? {
        val actions = n.actions ?: return null
        for (a in actions) {
            val remote = a.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: continue
            return a
        }
        return null
    }

    private fun replyInto(sbn: StatusBarNotification, text: String): String? {
        val listener = BridgeNotificationListener.instance ?: return "Listener weg"
        val n = sbn.notification
        val action = replyAction(n) ?: return "Benachrichtigung hat keine Direkt-Antwort-Aktion"
        val remote = action.remoteInputs.firstOrNull { it.allowFreeFormInput }
            ?: return "Keine freie Texteingabe in der Aktion"
        val intent = android.content.Intent()
        val results = android.os.Bundle()
        results.putCharSequence(remote.resultKey, text)
        // Was addResultsTo macht, manuell nachgebaut (SDK-stub-unabhaengig):
        val extras = android.os.Bundle()
        extras.putBundle("android.remoteinput.results", android.os.Bundle().apply {
            putCharSequence(remote.resultKey, text)
        })
        intent.putExtras(extras)
        intent.putExtra(RemoteInput.RESULTS_CLIP_LABEL, results)
        return try {
            action.actionIntent.send(listener, 0, intent)
            null // Erfolg
        } catch (e: Exception) {
            "Antwort fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }
}
