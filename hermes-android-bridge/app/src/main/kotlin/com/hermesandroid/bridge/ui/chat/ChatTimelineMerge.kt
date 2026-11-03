package com.hermesandroid.bridge.ui.chat

/**
 * PH-FIX-CHAT-A (F1, Sortierung): Merge-Regeln des Chat-Verlaufs — rein,
 * JVM-testbar (die Composables in ChatScreen/ChatWidgets rendern nur).
 *
 * Hintergrund: Der Verlauf ist EINE Liste (`messages` in ChatScreen);
 * Agenten-Antworten (chat.event/chat.event.enc) werden beim Eintreffen
 * übernommen, der Disk-Restore (ChatHistoryStore) legt dieselben Beiträge
 * einmal als persistierte Zwillinge hinein. Wer den Restore-Zeitpunkt
 * überlappt (App im Hintergrund, Agent antwortet, Sir öffnet die App),
 * hat sonst JEDE Antwort doppelt in der Anzeige.
 */
object ChatTimelineMerge {

    /**
     * Dedup-Schlüssel eines Beitrags: (agentId-Hash, fromAgent, Text, HH:mm).
     * ChatMessage trägt bewusst keinen Unix-ts (Saver bleibt primitiv —
     * Fix-Konzept B, ts-Feld, wäre das Folge-Refactoring); der Schlüssel
     * trennt Restore-Dubletten trotzdem sicher von echten Neuankömmlingen:
     * persistiert wird mit demselben HH:mm-Formatter (RelayClient) wie die
     * Live-Übernahme (ChatScreen.formatTimestamp).
     *
     * Das Längen-Präfix („length:") verhindert, dass Trennzeichen im Text
     * zwei verschiedene Beiträge gleich machen („ab|c" ≠ „a|bc").
     */
    fun dedupeKey(agentId: Int, fromAgent: Boolean, text: String, time: String): String =
        "$agentId\u0000$fromAgent\u0000${text.length}:$text\u0000$time"

    /**
     * Identitäts-Schlüssel eines chat.event-Frames (chatId + ts) — je Frame
     * eindeutig (der Relay/Adapter vergibt chat_id je Session und ts je
     * Nachricht). JÄGER-J1: die Konsum-Markierung in ChatScreen hängt an
     * DIESEM Schlüssel statt an der Listenposition — der 200er-Cap von
     * chatEvents verschiebt Positionen, Schlüssel bleiben stabil; nach
     * Prozess-Tod sind neue Events andere Schlüssel, nichts wird verworfen.
     * Separator \u0000, damit chatId/ts nie einen falschen Gleichklang
     * erzeugen können.
     */
    fun eventKey(chatId: String, ts: Long): String = "$chatId\u0000$ts"

    /**
     * Nur die frischen Ereignisse, die NICHT schon in [messages] liegen —
     * frische chat.event-Frames, deren persistierter Zwilling schon vom
     * Disk-Restore in der Liste steht, werden gefiltert (Restore-Dublette),
     * echte Neuankömmlinge bleiben in Empfangs-Reihenfolge erhalten.
     * Frische Ereignisse sind IMMER Agenten-Antworten (fromAgent = true);
     * eigene Nachrichten landen nie über den Ereignis-Pfad in der Liste.
     */
    fun <T> freshUnseen(
        messages: List<ChatMessage>,
        fresh: List<T>,
        agentIdOf: (T) -> Int,
        textOf: (T) -> String,
        timeOf: (T) -> String,
    ): List<T> {
        val seen = messages.map { dedupeKey(it.agentId, it.fromAgent, it.text, it.time) }.toHashSet()
        return fresh.filter { event ->
            dedupeKey(agentIdOf(event), fromAgent = true, textOf(event), timeOf(event)) !in seen
        }
    }
}