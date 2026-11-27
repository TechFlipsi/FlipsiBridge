package com.hermesandroid.bridge.ui.dashboard

import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.ui.chat.WorkingStatusRules
import java.util.Locale

/**
 * W15 (Fixliste 9): Regeln des Status-Dashboards — rein, JVM-testbar,
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
     * Plural-Ressource dash_agent_count (one/other, DE wie EN korrekt), nicht
     * mehr als deutsches Literal im Rule-Code.
     */
    fun agentCountLabel(total: Int): LabelRes =
        LabelRes(R.plurals.dash_agent_count, count = total, args = listOf(total))

    // ══════ F1 (18.11.2026, Vollgas-Batch): cron.status-Verband ═════════
    // Karte „Geplante Aufgaben“ (APP-GENERISCH — der Frame lebt je AGENT, die
    // App kennt keinen Agent-Namen in der Karte). Quelle: cron.status(-enc)-
    // Frames des Adapters mit {jobs: [{name, status, next_at?}]}. KEIN-DOPPEL
    // mit den Sub-Slots: die Cron-Karte ist EIN app-globales Gebilde (nicht je
    // Agent, keine Sub-Slot-Elemente) — sie steht SOLO nach den Agenten-Slots.

    /** Sichtbarer Job-Zustand (Protokoll-Werte des cron.status-Frames). */
    enum class CronJobState { RUNNING, PLANNED, PAUSED }

    /**
     * EIN geplanter/laufender Gateway-Cron-Job (aus dem cron.status-Payload,
     * das RelayClient parst — Wiring-Bedarf notiert). nextAtMs NUR bei
     * [CronJobState.PLANNED] gesetzt (running/paused tragen keinen Termin —
     * kein erfundener „demnächst“-Fake).
     *
     * W23-11-App (TEIL 2, Frame-Verbriefung): cronId = das cron_id-Feld der
     * Jobs-Zeile aus dem cron.status-Frame (Adapter-Lane,
     * Lane-Brief TEIL 2) — PRIMARY-Zielkennung des
     * cron.remove-Rückkanals. null = Frame ohne Verbriefung (Alt-Stand) —
     * die Zeile bleibt Sicht, wird aber NICHT löschbar (fail-soft: die UI
     * verbirgt das Lösch-Element statt still zu senden). Dashboard-Karte
     * bleibt unverändert (Default null, KEINE Verhaltens-Änderung).
     */
    data class CronJobItem(
        val name: String,
        val state: CronJobState,
        val nextAtMs: Long? = null,
        val cronId: String? = null,
    )

    /**
     * Sichtzeile der Karte: [minutesFromNow] = rel. Minuten bis zur nächsten
     * Ausführung (nur PLANNED); [overdue] = fälliger Termin ist VERPASST
     * (Scheduler-Tot/Catch-up — ehrlich rot markiert statt „demnächst“-Lüge),
     * nur bei PLANNED möglich.
     */
    data class CronJobView(
        val item: CronJobItem,
        val minutesFromNow: Long?,
        val hoursFromNow: Long?,
        val overdue: Boolean,
    )

    /** Protokoll-String → Zustand (unbekannt/leer = geplant, kein Throw). */
    fun cronJobState(raw: String?): CronJobState =
        when ((raw ?: "").trim().lowercase(Locale.ROOT)) {
            "running" -> CronJobState.RUNNING
            "paused" -> CronJobState.PAUSED
            else -> CronJobState.PLANNED
        }

    /**
     * next_at (Unix-ms) → Sichtwerte: Verpasst-Flag + Minuten/Std.-Eimer.
     * W23-11-App: cronId wandert unverändert durch (die CronScreen-Zeile
     * braucht die Verbriefung für cron.remove; alte 4-Arg-Aufrufe des
     * Dashboards bleiben unverändert gültig, Default = null).
     */
    fun cronJobView(
        name: String,
        state: CronJobState,
        nextAtMs: Long?,
        nowMs: Long,
        cronId: String? = null,
    ): CronJobView {
        if (state != CronJobState.PLANNED || nextAtMs == null || nextAtMs <= 0L) {
            return CronJobView(
                CronJobItem(name.ifBlank { "?" }, state, cronId = cronId),
                minutesFromNow = null,
                hoursFromNow = null,
                overdue = false,
            )
        }
        // Ceil + Floor-Grenze: JEDE sichtbare Zeile trägt ≥1 Minute — „in 0 min“
        // existiert nicht (sofort veraltete Anzeige) und fällig-jetzt liest als
        // „überfällig · vor 1 min“ (ehrlich knapp statt gerundetes 0-Fake).
        val deltaMs = nextAtMs - nowMs
        val minutes = ((kotlin.math.abs(deltaMs) + 59_999L) / 60_000L).coerceAtLeast(1L)
        return if (deltaMs <= 0L) {
            // next_at liegt hinter/bei der Device-Uhr — Termine ohne Scheduler
            // bleiben nicht „demnächst“ stehen (Parität Adapter-Verband).
            CronJobView(
                CronJobItem(name.ifBlank { "?" }, state, nextAtMs, cronId),
                minutesFromNow = minutes,
                hoursFromNow = null,
                overdue = true,
            )
        } else {
            CronJobView(
                CronJobItem(name.ifBlank { "?" }, state, nextAtMs, cronId),
                minutesFromNow = minutes,
                hoursFromNow = minutes / 60L,
                overdue = false,
            )
        }
    }

    /**
     * Sortierung der Karten-Zeilen: laufende zuerst, dann geplante nach
     * nächster Ausführung (verpasste ganz vorn — drängen sich nach vorn, weil
     * sie Aufmerksamkeit wollen), pausiert zuletzt; Zeilen-Cap 16 (Parität zum
     * Adapter-Frame-Cap _CRON_STATUS_JOBS_MAX). W23-11-App: die CronScreen-
     * Gruppen rechen dieselbe Sortier-Faser (EINE Zeit-Eimer-Regel, kein
     * zweites Zeit-Mechanik-Doppel).
     */
    fun cronSortedRows(jobs: List<CronJobItem>, nowMs: Long): List<CronJobView> {
        val views = jobs.take(16).map { cronJobView(it.name, it.state, it.nextAtMs, nowMs, it.cronId) }
        return views.sortedWith(
            compareBy(
                { row -> when {
                    row.item.state == CronJobState.RUNNING -> 0
                    row.item.state == CronJobState.PLANNED && row.overdue -> 1
                    row.item.state == CronJobState.PLANNED -> 2
                    else -> 3
                } },
                { row -> when {
                    row.item.state == CronJobState.PLANNED && row.overdue -> -(row.minutesFromNow ?: 0L)
                    row.item.state == CronJobState.PLANNED -> row.minutesFromNow ?: Long.MAX_VALUE
                    else -> 0L
                } },
            ),
        )
    }

    /**
     * Karte sichtbar? NUR mit mindestens einem Job (Leerzustand: die Sektion
     * bleibt KOMPLETT weg — W19 Leerzustand-Muster der Sub-Slots, kein
     * „keine geplanten“-Geraffel, keine Titelzeile ohne Inhalt).
     */
    fun cronCardVisible(jobs: List<CronJobItem>): Boolean = jobs.isNotEmpty()
}
