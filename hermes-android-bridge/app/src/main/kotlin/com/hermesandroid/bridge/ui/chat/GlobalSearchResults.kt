package com.hermesandroid.bridge.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.chat.ChatHistoryStore
import com.hermesandroid.bridge.chat.GlobalSearchRules
import com.hermesandroid.bridge.chat.SearchIndex
import com.hermesandroid.bridge.ui.FlipsiColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * W22 (PH-6): Globale Volltextsuch-UI — Abschnitt „Weitere Treffer in
 * Verläufen" UNTER der lokalen Trefferliste des Chat-Suchmodus. Regeln in
 * [GlobalSearchRules] (JVM-getestet); hier rendert + sucht nur.
 *
 * ## Verband (Brief)
 * - EIN Suchfeld: die bestehende Chat-Suchleiste bleibt die EINZIGE Eingabe
 *   (KEIN-DOPPEL — hier kein zusätzliches Textfeld, kein eigener Magnifier).
 * - Treffer über ALLE Agenten: Quelle = [ChatHistoryStore.searchAll] (RAM-
 *   Index, PH-6-REST), Debounce [GlobalSearchRules.DEBOUNCE_MS] koppelt den
 *   Tipp-Rhythmus; Suchlauf im IO-Dispatcher (UI-Thread bleibt frei).
 * - Je Treffer: Agent (Avatar-Initial + Name), Snippet mit Highlight-Pille auf
 *   der Treffer-Stelle, Anzeigezeit. Gruppenkopf je Agent (Avatar + Name +
 *   Trefferzahl, „(+n)" wenn gekappt).
 * - Klick = Sprung in den Chat des Agenten + Treffer-Bubble-Highlight über den
 *   BESTEHENDEN Sprung-/Ember-Verband des Suchmodus ([onJump]; der Search-
 *   Screen-Mapping-Verband macht (agentId, seq) → ChatMessage.id — Hook-Report).
 * - AKTIONEN-MIT-ERGEBNIS: Zeilen sind NUR klickbar, wenn der Sprung WIRKLICH
 *   landen kann ([GlobalSearchRules.jumpResolvable] gegen den Bildschirm-
 *   Katalog) — sonst ehrliche, nicht-klickbare Lese-Zeile.
 * - Leere Zustände ehrlich: Index unprimt/leer = „Noch kein Verlauf indiziert"
 *   (bzw. Aufbau-Hinweis bei [indexBuilding]), 0 Treffer = „Keine weiteren
 *   Treffer" — NIE Fake.
 *
 * ## Strings (W22 MERGE: i18n ×3 Locales)
 * Texte leben als <string>-Ressourcen (`chat_global_search_*` in strings.xml)
 * — hier nur noch stringResource(R.string.…)-Zugriffe an den Nutzungsstellen.
 */

/** Interner Suchzustand des Abschnitts (null = inaktiv / Debounce-Erstfassung). */
private data class GlobalSearchSectionState(
    val searching: Boolean,
    val indexEmpty: Boolean,
    val result: GlobalSearchRules.Result?,
)

/**
 * Abschnitt „Weitere Treffer in Verläufen": Kopf (Titel + Zähler-Pille) +
 * Gruppen je Agent. Lebt UNTER der lokalen Trefferliste im selben Such-Slot;
 * der Wrapper-Modifier kommt vom Aufrufer (Gewichts-Verteilung beim Hook).
 */
@Composable
fun GlobalSearchResultsSection(
    query: String,
    agentNames: Map<String, String>,
    indexEntryCount: Int,
    onJump: (SearchIndex.Hit) -> Unit,
    indexBuilding: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val activeQuery = GlobalSearchRules.effectiveQuery(query)
    // Je gültiger Query frischer Zustand (kein Alt-Ergebnis-Blitz beim
    // Agenten-Wechsel/Query-Rücksetzen — remember-Key zieht das State-Slot weg).
    var state by remember(activeQuery) { mutableStateOf<GlobalSearchSectionState?>(null) }

    // Suche-Verband (W4-FIX-JAG3-Parität): erst SCHREIBEN (Debounce-Maske),
    // DANN suspendieren — delay ist die erste Suspension dieses Effekts; ein
    // Key-Diff (weitere Tipp-Schläge) killt den schlafenden Job sauber und
    // der neue Effekt maskiert neu. Nie Zustand-Rest VOR der Suspension.
    LaunchedEffect(activeQuery) {
        if (activeQuery == null) {
            state = null
            return@LaunchedEffect
        }
        state = GlobalSearchSectionState(searching = true, indexEmpty = false, result = null)
        delay(GlobalSearchRules.DEBOUNCE_MS)
        val hits = withContext(Dispatchers.IO) {
            ChatHistoryStore.searchAll(activeQuery)
        }
        val indexEmpty = withContext(Dispatchers.IO) {
            ChatHistoryStore.searchIndexEntryCount() == 0
        }
        state = GlobalSearchSectionState(
            searching = false,
            indexEmpty = indexEmpty,
            result = GlobalSearchRules.shapeResults(hits),
        )
    }

    // Abschnitt nur MIT aktivem Suchvorgang (Gate in den Regeln: Mindest-
    // Länge + Normalisierung) — ohne gültige Query rendert hier NICHTS.
    if (activeQuery == null) return

    // AND-Terme einmal je Suchvorgang (Highlight-Quelle für alle Snippets).
    val terms = remember(activeQuery) { GlobalSearchRules.queryTerms(activeQuery) }
    val knownAgentIds = agentNames.keys

    Column(modifier = modifier) {
        // Abschnitts-Kopf: Titel + Zähler-Pille — FESTER Spacer vor der
        // Pille (Abstands-Klasse a: Titel klebt nie an der Ziffer).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.chat_global_search_title),
                style = MaterialTheme.typography.labelMedium,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            // Zähler-Pille: „…" während Suchlauf, sonst ehrliche Gesamtzahl
            // (ANGEZEIGTE Treffer nach Cap — keine erfundene Vollzahl).
            Text(
                pillText(state),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = FlipsiColors.TextSecondary,
                modifier = Modifier
                    .background(FlipsiColors.PanelElev, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        // Ehrliche Zustands-Zeilen (Priorität: Index-Verband vor Suchlauf vor
        // Ergebnis) — die Abschnitt-Kopf-Zeile bleibt immer sichtbar.
        when {
            // Unprimter Index mit laufendem Aufbau (Bildschirm-Lane meldet)
            // → Aufbau-Hinweis; sonst ehrlich: kein Verlauf im Index.
            indexEntryCount == 0 && indexBuilding -> SectionNote(
                stringResource(R.string.chat_global_search_index_building),
            )
            indexEntryCount == 0 || state?.indexEmpty == true -> SectionNote(
                stringResource(R.string.chat_global_search_no_index),
            )
            state == null || state?.searching == true -> SectionNote(
                stringResource(R.string.chat_global_search_searching),
            )
            (state?.result?.total ?: 0) == 0 -> SectionNote(
                stringResource(R.string.chat_global_search_empty),
            )
            else -> {
                val result = state!!.result!!
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                ) {
                    items(result.groups, key = { it.agentId }) { group ->
                        AgentGroupBlock(
                            group = group,
                            agentNames = agentNames,
                            knownAgentIds = knownAgentIds,
                            terms = terms,
                            onJump = onJump,
                        )
                    }
                }
                if (result.capped) {
                    SectionNote(stringResource(R.string.chat_global_search_capped))
                }
                // 10-s-Fenster (Index-Aufbau läuft): Treffer können aus einem
                // TEIL-Index stammen — dezente Ein-Zeilen-Erklärung, kein Fake.
                if (indexBuilding) {
                    SectionNote(stringResource(R.string.chat_global_search_building_partial))
                }
            }
        }
    }
}

/** Pille-Text: „…" solange der Suchlauf läuft, sonst Anzeige-Zahl. */
private fun pillText(state: GlobalSearchSectionState?): String {
    if (state == null || state.searching) return "…"
    return state.result?.total?.toString() ?: "0"
}

/** Dezente Zustands-Zeile (Lade-/Leer-Zustände — ehrlich, kein Fake). */
@Composable
private fun SectionNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = FlipsiColors.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Agent-Gruppe: Gruppenkopf (Avatar-Initial + Name + Trefferzahl) + Zeilen. */
@Composable
private fun AgentGroupBlock(
    group: GlobalSearchRules.AgentGroup,
    agentNames: Map<String, String>,
    knownAgentIds: Set<String>,
    terms: List<String>,
    onJump: (SearchIndex.Hit) -> Unit,
) {
    // Ehrliche Anzeige ohne Katalog-Eintrag: agentId-gekürzt (Regeln), blank =
    // Unbekannt (Avatar fällt auf den „?“-Initial).
    val displayLabel = GlobalSearchRules.displayAgentLabel(group.agentId, agentNames)
    val unknownAgent = stringResource(R.string.chat_global_search_unknown_agent)
    val label = displayLabel.ifBlank { unknownAgent }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentAvatar(
                agentId = group.agentId,
                name = label,
                diameter = 32.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = FlipsiColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            // Zähler-Ehrlichkeit: am Engine-Suchlauf-Cap (Liste beschnitten)
            // ist totalForAgent nur Untergrenze → offene Anzeige „n+“, keine
            // falsch-exakte „(+n)“-Differenz; unterm Cap bleibt es exakt.
            val countText = when {
                group.totalGecappt -> "${group.totalForAgent}+"
                group.moreInAgent > 0 -> "${group.totalForAgent} (+${group.moreInAgent})"
                else -> "${group.totalForAgent}"
            }
            Text(
                countText,
                style = MaterialTheme.typography.labelMedium,
                color = FlipsiColors.TextSecondary,
            )
        }
        for (hit in group.hits) {
            GlobalHitRow(
                hit = hit,
                terms = terms,
                resolvable = GlobalSearchRules.jumpResolvable(hit, knownAgentIds),
                onJump = onJump,
            )
        }
    }
}

/** Treffer-Zeile: Zeit + Snippet (Highlight-Pille auf der Treffer-Stelle). */
@Composable
private fun GlobalHitRow(
    hit: SearchIndex.Hit,
    terms: List<String>,
    resolvable: Boolean,
    onJump: (SearchIndex.Hit) -> Unit,
) {
    val snippet = remember(hit.snippet) { GlobalSearchRules.truncateSnippet(hit.snippet) }
    // Sprung-A11y-Verb hoisten (stringResource lebt nicht im Modifier-Lambda).
    val jumpLabel = stringResource(R.string.chat_global_search_jump_label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .let { base ->
                // AKTIONEN-MIT-ERGEBNIS: klickbar NUR bei wirksamem Sprung;
                // onClickLabel = eigenes A11y-Verb im TalkBack-Handlunghinweis.
                if (resolvable) {
                    base.clickable(onClickLabel = jumpLabel) {
                        onJump(hit)
                    }
                } else {
                    base
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            hit.time,
            style = MaterialTheme.typography.labelSmall,
            color = FlipsiColors.TextSecondary,
        )
        Spacer(Modifier.width(10.dp))
        HighlightedSnippet(
            snippet = snippet,
            terms = terms,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Snippet-Zeile: Treffer-Stellen als Pille (Ember-Dim-Fläche + halbfett —
 *  Zustandsinformation nie nur über Farbe, A11y liest den vollen Text). */
@Composable
private fun HighlightedSnippet(
    snippet: String,
    terms: List<String>,
    modifier: Modifier = Modifier,
) {
    val spans = remember(snippet, terms) {
        GlobalSearchRules.highlightSpans(snippet, terms)
    }
    val annotated = remember(snippet, spans) {
        buildAnnotatedString {
            append(snippet)
            for (span in spans) {
                addStyle(
                    SpanStyle(
                        background = FlipsiColors.EmberDim,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    span.first,
                    span.last + 1,
                )
            }
        }
    }
    Text(
        annotated,
        style = MaterialTheme.typography.bodyMedium,
        color = FlipsiColors.TextPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
