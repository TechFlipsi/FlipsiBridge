package com.hermesandroid.bridge.ui.dashboard

import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.ui.chat.WorkingStatusRules
import java.util.Locale

/**
 * W15 (Sir-Fixliste 9): Regeln des Status-Dashboards — rein, JVM-testbar,
 * KEINE Android-Framework-Imports. Die UI hängt die Sichtwerte an diese
 * Objekt-Methoden (gleiche Faser wie WorkingStatusRules/ChatStatusRank).
 *
 * W15c (Jäger B5/A, i18n-Blocker): die Regel-Funktionen geben KEINE deutschen
 * Literale mehr zurück, sondern Ressourcen-Verweise ([LabelRes]) — die UI
 * löst per stringResource/getQuantityString live auf (gleiches Muster wie
 * SettingsNav.openSectionRes: Auflösung erst bei der Anzeige, Sprachwechsel
 * wirkt sofort). Die R-Konstanten sind plain Ints — die JVM-Tests bleiben
 * ohne Android-Laufzeit grün.
 *
 * Datenquellen-Verband: ALLES leitet sich aus vorhandenen RelayClient-Flows ab
 * (relay.state → agents, chat.activity → agentWorking, chat.event → chatEvents)
 * — KEIN neuer Server-Endpunkt, KEIN neuer Zustandsspeicher.
 */
object DashboardRules {

    /** Sichtbarer Zustand eines Agenten-Slots (Zustand nie nur über Farbe). */
    enum class AgentStatus { ONLINE, BUSY, OFFLINE }

    /**
     * W15c: Label-Verweis statt Literal — res ist eine R.string-Konstante
     * (args gefüllt) oder eine R.plurals-Konstante (count != null markiert
     * den Plural-Fall, count ist zugleich die Mengen-Zahl für
     * getQuantityString). Die Regel bleibt locale-blind und JVM-testbar,
     * die UI besorgt die Worte.
     */
    data class LabelRes(
        val res: Int,
        val count: Int? = null,
        val args: List<Any> = emptyList(),
    )

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
     * busy-Slots über diese Funktion nach derselben Regel wie statusOf;
     * W15c: der Zähler trägt zusätzlich das Online-Gate — Offline schlägt
     * working, sonst zählt die Zusammenfassung „Beschäftigte“, die gar
     * nicht arbeiten können).
     */
    fun workingTimedOut(startedAt: Long, nowMs: Long): Boolean =
        WorkingStatusRules.timedOut(startedAt, nowMs)

    /**
     * mm:ss seit startedAt für die Live-Zeile „arbeitet … (m:ss)“ — gleiche
     * Format-Konvention wie der Conversation-Kopf (Locale.ROOT, keine
     * länderspezifischen Doppelpunkte). Sprachneutral, bleibt String.
     */
    fun workingElapsedLabel(startedAt: Long, nowMs: Long): String {
        val capped = WorkingStatusRules.WORKING_TIMEOUT_MS / 1000L
        val sec = ((nowMs - startedAt) / 1000L).coerceIn(0L, capped)
        return String.format(Locale.ROOT, "%d:%02d", sec / 60L, sec % 60L)
    }

    /**
     * Relative Zeit der letzten Aktivität (chat.event-ts je Agent):
     * „gerade eben“, „vor 4 min“, „vor 2 Std.“, „vor 1 Tag“, „vor 3 Tagen“.
     * W15c: LabelRes statt deutscher Wörter — Tage als Plural-Ressource
     * („vor 1 Tag“ bleibt Singular-richtig). ts <= 0 = kein brauchbarer
     * Event → null (die UI zeigt den Em-dash, nie eine Fake-Uhrzeit).
     * Bekannt (nicht gefixt): die Uhr-Gegenüberstellung ist reines nowMs —
     * eine Geräte-Uhr-Abweichung verschiebt die Eimer (Jäger-Doku W15c).
     */
    fun relativeTime(ts: Long, nowMs: Long): LabelRes? = when {
        ts <= 0L -> null
        nowMs - ts < 60_000L -> LabelRes(R.string.dash_just_now)
        nowMs - ts < 60L * 60_000L ->
            LabelRes(R.string.dash_minutes_ago, args = listOf(((nowMs - ts) / 60_000L).toInt()))
        nowMs - ts < 24L * 60L * 60_000L ->
            LabelRes(R.string.dash_hours_ago, args = listOf(((nowMs - ts) / 3_600_000L).toInt()))
        else -> {
            val days = ((nowMs - ts) / (24L * 60L * 60_000L)).toInt()
            LabelRes(R.plurals.dash_days_ago, count = days, args = listOf(days))
        }
    }

    /**
     * Sicht-Pille je Zustand — W15c als LabelRes: Online/Offline teilen die
     * Ressourcen der Chat-Filter-Chips (chat_filter_online/offline, keine
     * Doppel-Keys), „Beschäftigt“ hat die eigene dash_busy. Die Farb-Rollen
     * bleiben der UI überlassen, hier steht nur der WOHLGEORDNETE Verweis
     * je Status (de-AT, kurz, keine Debug-Worte).
     */
    fun statusLabel(status: AgentStatus): LabelRes = when (status) {
        AgentStatus.BUSY -> LabelRes(R.string.dash_busy)
        AgentStatus.ONLINE -> LabelRes(R.string.chat_filter_online)
        AgentStatus.OFFLINE -> LabelRes(R.string.chat_filter_offline)
    }

    /**
     * Zusammenfassungs-Zeile über der Agenten-Liste: „3 Agenten · 2 online ·
     * 1 beschäftigt“ — W15c als LabelRes-Liste: die UI löst je Teil auf und
     * verbindet mit „ · “. Nur EHRLICHE Teile (0 online → Teil entfällt,
     * 0 Agenten → leere Liste, die UI zeigt dann ihren Leer-Hinweis).
     */
    fun summaryLine(total: Int, online: Int, busy: Int): List<LabelRes> {
        if (total <= 0) return emptyList()
        val parts = mutableListOf(agentCountLabel(total))
        if (online > 0) parts += LabelRes(R.string.dash_online_count, args = listOf(online))
        if (busy > 0) parts += LabelRes(R.string.dash_busy_count, args = listOf(busy))
        return parts
    }

    /**
     * Singular-Rettung bleibt: „1 Agent“ statt „1 Agenten“ — W15c über die
     * Plural-Ressource dash_agent_count (one/other, DE wie EN korrekt),
     * nicht mehr als deutsches Literal im Rule-Code.
     */
    fun agentCountLabel(total: Int): LabelRes =
        LabelRes(R.plurals.dash_agent_count, count = total, args = listOf(total))
}
