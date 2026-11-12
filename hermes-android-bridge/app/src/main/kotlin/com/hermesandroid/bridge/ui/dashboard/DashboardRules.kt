package com.hermesandroid.bridge.ui.dashboard

import com.hermesandroid.bridge.ui.chat.WorkingStatusRules
import java.util.Locale

/**
 * W15 (Sir-Fixliste 9): Regeln des Status-Dashboards — rein, JVM-testbar,
 * KEINE Android-Imports. Die UI hängt die Sichtwerte an diese Objekt-Methoden
 * (gleiche Faser wie WorkingStatusRules/ChatStatusRank).
 *
 * Datenquellen-Verband: ALLES leitet sich aus vorhandenen RelayClient-Flows ab
 * (relay.state → agents, chat.activity → agentWorking, chat.event → chatEvents)
 * — KEIN neuer Server-Endpunkt, KEIN neuer Zustandsspeicher.
 */
object DashboardRules {

    /** Sichtbarer Zustand eines Agenten-Slots (Zustand nie nur über Farbe). */
    enum class AgentStatus { ONLINE, BUSY, OFFLINE }

    /**
     * Zustand je Agent: „beschäftigt“ zählt nur, solange der working-Frame
     * FRISCH ist — WorkingStatusRules.timedOut ist der 10-Minuten-Deckel
     * (working=true ohne je ankommendes false darf nie endlos „arbeitet“
     * zeigen; PIPELINE-BUGS BUG 3). Offline schlägt alles: ohne Verbindung
     * „arbeitet“ niemand.
     */
    fun statusOf(online: Boolean, working: Boolean?, startedAt: Long, nowMs: Long): AgentStatus = when {
        !online -> AgentStatus.OFFLINE
        working == true && !workingTimedOut(startedAt, nowMs) -> AgentStatus.BUSY
        else -> AgentStatus.ONLINE
    }

    /**
     * Deckel-Delegation für die UI: „Beschäftigt“ gilt nur mit FRISCHEM
     * working-Frame. Der 10-Minuten-Deckel lebt in WorkingStatusRules —
     * EINE Faser, keine zweite Zeitkonstante (DashboardScreen zählt
     * busy-Slots über diese Funktion nach derselben Regel wie statusOf).
     */
    fun workingTimedOut(startedAt: Long, nowMs: Long): Boolean =
        WorkingStatusRules.timedOut(startedAt, nowMs)

    /**
     * mm:ss seit startedAt für die Live-Zeile „arbeitet … (m:ss)“ — gleiche
     * Format-Konvention wie der Conversation-Kopf (Locale.ROOT, keine
     * länderspezifischen Doppelpunkte).
     */
    fun workingElapsedLabel(startedAt: Long, nowMs: Long): String {
        val capped = WorkingStatusRules.WORKING_TIMEOUT_MS / 1000L
        val sec = ((nowMs - startedAt) / 1000L).coerceIn(0L, capped)
        return String.format(Locale.ROOT, "%d:%02d", sec / 60L, sec % 60L)
    }

    /**
     * Relative Zeit der letzten Aktivität (chat.event-ts je Agent):
     * „gerade eben“, „vor 4 min“, „vor 2 Std.“, „vor 1 Tag“, „vor 3 Tagen“.
     * ts <= 0 = kein Event → Em-dash (nie leere Zeile, nie Fake-Uhrzeit).
     */
    fun relativeTime(ts: Long, nowMs: Long): String = when {
        ts <= 0L -> "—"
        nowMs - ts < 60_000L -> "gerade eben"
        nowMs - ts < 60L * 60_000L ->
            "vor ${((nowMs - ts) / 60_000L).toInt()} min"
        nowMs - ts < 24L * 60L * 60_000L ->
            "vor ${((nowMs - ts) / 3_600_000L).toInt()} Std."
        else -> {
            val days = ((nowMs - ts) / (24L * 60L * 60_000L)).toInt()
            if (days == 1) "vor 1 Tag" else "vor $days Tagen"
        }
    }

    /**
     * Sicht-Pille je Zustand: (Text, Farbe-Paar = Vordergrund/Fläche) — die
     * Farb-Rollen bleiben der UI überlassen, hier steht nur der WOHLGEORDNETE
     * Text je Status (de-AT, kurz, keine Debug-Worte).
     */
    fun statusLabel(status: AgentStatus): String = when (status) {
        AgentStatus.BUSY -> "Beschäftigt"
        AgentStatus.ONLINE -> "Online"
        AgentStatus.OFFLINE -> "Offline"
    }

    /**
     * Zusammenfassungs-Zeile über der Agenten-Liste: „3 Agenten · 2 online ·
     * 1 beschäftigt“ — nur EHRLICHE Teile (0 online → Teil entfällt, 0 Agenten
     * → leerer Text, die UI zeigt dann ihren Leer-Hinweis).
     */
    fun summaryLine(total: Int, online: Int, busy: Int): String {
        if (total <= 0) return ""
        val parts = mutableListOf(agentCountLabel(total))
        if (online > 0) parts += "$online online"
        if (busy > 0) parts += "$busy beschäftigt"
        return parts.joinToString(" · ")
    }

    /** Singular-Rettung: „1 Agenten“ wäre falsch — 1 Agent bleibt Singular. */
    fun agentCountLabel(total: Int): String =
        if (total == 1) "1 Agent" else "$total Agenten"
}
