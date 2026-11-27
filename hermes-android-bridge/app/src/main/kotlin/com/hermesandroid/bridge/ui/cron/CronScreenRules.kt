package com.hermesandroid.bridge.ui.cron

import com.hermesandroid.bridge.ui.dashboard.DashboardRules
import java.util.Locale

/**
 * W23-11-App (Arbeits-Brief): Gruppier-Regeln
 * des 5. Tabs „Geplante Aufgaben“ — rein, JVM-testbar, KEINE Android-Imports
 * (gleiche Faser wie DashboardRules/AgentStatusRules). Die UI hängt die
 * Sichtwerte an diese Objekt-Methoden.
 *
 * FRAME-VERTRAG (cron.status / cron.status.enc — Adapter-Lane,
 * Lane-Brief TEIL 1 + Bestand emit_cron_status):
 *   {"type": "cron.status", "agent_id": <Träger-Agent>, "jobs": [
 *       {"name": <Pflicht>, "cron_id"?: <Verbriefung für cron.remove>,
 *        "status": "running|planned|paused", "next_at"?: <unix-ms>}]}
 * je Frame = REPLACE des GEsaMT-Stands DES Agents (Klartext- UND enc-Pfad
 * landen in EINEM Verband — RelayClient handleCronStatusFrame/Enc).
 *
 * GENERIK-Regel (Brief: „Agenten-Presence-Liste = die Frame-Verband“): der
 * Agenten-NAME kommt NIE hartkodiert — Anzeigename = Presence-lookup
 * (relay.state → [RelayClient.agents]), Fehl im Presence-Stand = der
 * agent_id selbst (einträglich — auch der ist Frame-Daten), ohne beides =
 * generischer Fallback-Gruppe (die UI löst die Ressource cron_group_unknown).
 */
object CronScreenRules {

    /** Anzeige-Einheit des Presence-Stands (Spiegel RelayClient.AgentEntry). */
    data class AgentRef(
        val agentId: String,
        val displayName: String,
        val online: Boolean,
    )

    /**
     * EINE PRO-AGENT-Gruppe (Dropdown je Agent): [agentKey] = agent_id aus dem
     * cron.status-Frame (leer = unbekannter Klartext-Stand → Fallback-Gruppe),
     * [displayName] der aufgelöste Presence-Name (leer = Fallback-Ressource
     * der UI), [known] = aus der Presence-Liste aufgelöst (false =
     * Frame-Zweig ohne Presence-Eintrag), [online] = Presence-Netzustand
     * (false = unbekannt/offline — der Dot zeigt grau, W23-8-Regel: SignalRed
     * NIE bei offline), [rows] die sortierten Sichtzeilen
     * (DashboardRules.cronSortedRows — EINE Faser).
     */
    data class AgentCronGroup(
        val agentKey: String,
        val displayName: String,
        val known: Boolean,
        val online: Boolean,
        val rows: List<DashboardRules.CronJobView>,
    )

    /**
     * PER-AGENT-REPLACE-Faltung (client-seitig, RelayClient bindet sie an die
     * cron.status(-enc)-Frames — pure für AtomicStateFlow-Updates): je Frame
     * ersetzt der Stand des AGENTS ([agentKey], leer = „unbekannt“-Eimer "")
     * den Gesamt-Stand — REPLACE (nicht Append, nicht Merge); Einfüge-
     * Reihenfolge folgt dem Presence-Fluss-Rhythmus: der ersetzte Stand reiht
     * FRISCH ein (remove + put — der zuletzt gesehene Frame hinten, KEIN
     * Alt-Slot am Ersatzstand; LinkedHashMap).
     */
    fun replaceAgentJobs(
        current: Map<String, List<DashboardRules.CronJobItem>>,
        agentKey: String,
        jobs: List<DashboardRules.CronJobItem>,
    ): Map<String, List<DashboardRules.CronJobItem>> {
        val next = LinkedHashMap(current)
        // REPLACE reiht FRISCH ein (remove + put): die Faltung spiegelt den
        // Fluss, nicht den Erst-Kontakt (ein Alt-Slot wäre eine Leiche eines
        // Frames, der längst überrollt ist).
        next.remove(agentKey)
        if (jobs.isNotEmpty()) {
            next[agentKey] = jobs
        }
        // Leerer Stand = der Agent hat keine Crons mehr — kein leeres
        // Dropdown-Gerüst (Gruppe verschwindet ganz, Parität cronCardVisible).
        return next
    }

    /**
     * Abflachung für die EINZIGE Legacy-Union ([RelayClient.cronJobs] — die
     * Dashboard-Karte zeigt WEITERHIN eine flache Liste; Einfüge-Reihenfolge
     * = Gruppen-Reihenfolge; die Karte sortiert ohnehin via cronSortedRows).
     */
    fun flattenJobs(
        merged: Map<String, List<DashboardRules.CronJobItem>>,
    ): List<DashboardRules.CronJobItem> = merged.values.flatten()

    /**
     * PRO-AGENT-Faltung der Anzeige:
     * - presence-Agents MIT Jobs zuerst (Fluss-Reihenfolge der relay.state-
     *   Liste — die App erfindet KEINE eigene Reihen-Fibel),
     * - dann Frame-Zweige ohne Presence-Eintrag (alphabetisch, Locale.ROOT —
     *   deterministisch, keine länderspezifische Ordnungs-Sünde),
     * - unbekannter Stand ohne agent_id (""-Eimer) GANZ ZULETZT.
     * Agenten OHNE Jobs bekommen KEINE Gruppe (leeres Dropdown = Geraffel);
     * je leere Gruppe bleibt die Regeln leer → die UI zeigt ihren Leerhut.
     *
     * W26d (F5, UX-Report): AUSNAHME — der PENDente Sprung-Fokus
     * ([pendingFocusAgentId], der Uhr-Sprung der Agenten-Zentrale auf einen
     * Agenten MIT cron-Cap aber OHNE Jobs) bekommt GENAU EINE (leere)
     * Gruppe: die Adresse ist nie tot, die UI zeigt den Agent-Kopf mit
     * ihrem Leerzustand, bis der erste cron.status sie füllt. Ohne pending
     * (null/blank; je Konsum der Wahl) bleibt der Bestand exakt (dann gilt
     * presence-Auflösung — der Name lebt je [AgentRef], sonst die agent_id).
     */
    fun groups(
        jobsByAgent: Map<String, List<DashboardRules.CronJobItem>>,
        agents: List<AgentRef>,
        nowMs: Long,
        pendingFocusAgentId: String? = null,
    ): List<AgentCronGroup> {
        val focus = pendingFocusAgentId?.trim().orEmpty()
        if (jobsByAgent.isEmpty()) {
            // W26d (F5): der Pendente Sprung-Fokus ist auch je GESAMT-leerem
            // Stand nie tot — GENAU eine (leere)-Gruppe je Adresse.
            return if (focus.isEmpty()) emptyList() else listOf(focusGroup(focus, agents))
        }
        val covered = HashSet<String>()
        // ── 1) Presence-Gruppen in der Fluss-Reihenfolge der relay.state-
        // Liste — DORT steht die Reihen-Fibel (die App erfindet KEINE eigene,
        // auch nicht die Frame-Einfüge-Reihenfolge des Stand-Verbands):
        val out = ArrayList<AgentCronGroup>()
        agents.forEach { agent ->
            if (agent.agentId.isEmpty()) return@forEach // "" bleibt der Fallback-Eimer
            if (!covered.add(agent.agentId)) return@forEach // doppelte Presence-Zeile rattert doppelt
            val jobs = jobsByAgent[agent.agentId].orEmpty()
            if (jobs.isEmpty()) return@forEach // Agent ohne Crons = kein Dropdown-Geraffel
            out += AgentCronGroup(
                agentKey = agent.agentId,
                displayName = agent.displayName,
                known = true,
                online = agent.online,
                rows = DashboardRules.cronSortedRows(jobs, nowMs),
            )
        }
        // ── 2) Frame-Zweige ohne Presence-Eintrag + 3) „unbekannt“-Eimer:
        val leftovers = ArrayList<Pair<String, List<DashboardRules.CronJobItem>>>()
        var unknownBucket: List<DashboardRules.CronJobItem>? = null
        jobsByAgent.forEach { (agentKey, jobs) ->
            if (jobs.isEmpty()) return@forEach  // leere Gruppen fliegen (kein Dropdown-Geraffel)
            when {
                agentKey.isEmpty() -> unknownBucket = jobs
                agentKey in covered -> Unit // folgt ohnehin via Presence-Zeile
                else -> leftovers += agentKey to jobs
            }
        }
        leftovers
            // Presence-lose Frame-Zweige sortiert: die agent_id als Name
            // (Frame-Daten, generisch — KEIN hartkodierter Name).
            .sortedBy { (agentKey, _) -> agentKey.lowercase(Locale.ROOT) }
            .forEach { (agentKey, jobs) ->
                out += AgentCronGroup(
                    agentKey = agentKey,
                    displayName = agentKey,
                    known = false,
                    online = false,
                    rows = DashboardRules.cronSortedRows(jobs, nowMs),
                )
            }
        unknownBucket?.let { jobs ->
            out += AgentCronGroup(
                agentKey = "",
                displayName = "",
                known = false,
                online = false,
                rows = DashboardRules.cronSortedRows(jobs, nowMs),
            )
        }
        // ── 4) W26d (F5): die pendente Sprung-Adresse ist NIE tot — bekommt
        // der Fokus (Agent MIT cron-Cap, NULL Aufgaben) noch keine Gruppe,
        // erzeugt die Faser GENAU eine leere je ihm (Kopf + Leerzustand der
        // UI). Presence löst den Anzeige-Namen, sonst die agent_id.
        if (focus.isNotEmpty() && out.none { it.agentKey == focus }) {
            out += focusGroup(focus, agents)
        }
        return out
    }

    /**
     * W26d (F5): die (leere)-Gruppe des Pendente Sprung-Fokus — Anzeige-Name
     * je Presence-Auflösung (der GENERIK-Verband: presence displayName,
     * sonst die agent_id; die UI löst eine leere Zeile je Fallback-Res).
     */
    private fun focusGroup(agentKey: String, agents: List<AgentRef>): AgentCronGroup {
        val presence = agents.firstOrNull { it.agentId == agentKey }
        // ZF2 (W27, Suite-Nachziehen; W26d-Report-Verband „presence löst den
        // Namen, SONST die agent_id“): ohne Presence-Zeile trägt die (leere)-
        // Sprung-Gruppe die agent_id als ehrlichen Anzeige-Namen — der
        // Erststand lieferte leere Zeichenkette und der Namens-Fallback-Test
        // lief rot.
        val displayName = presence?.displayName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: agentKey
        return AgentCronGroup(
            agentKey = agentKey,
            displayName = displayName,
            known = presence != null,
            online = presence?.online == true,
            rows = emptyList(),
        )
    }
}
