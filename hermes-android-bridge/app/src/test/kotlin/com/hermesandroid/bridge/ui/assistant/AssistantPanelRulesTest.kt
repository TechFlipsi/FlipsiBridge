package com.hermesandroid.bridge.ui.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W22 LANE B (G2+G11) — JVM-Verträge der Overlay-/Panel-Regeln
 * (AssistantPanelRules.kt): Permission-Flow-Zustände des Bubble-Schalters,
 * Share-Intent-Parsing (ACTION_SEND text/plain), Kontext-Whitelist http/https,
 * Prompt-Bau der 3 Share-Aktionen, AssistContent-JSON und Agenten-Farb-Hash.
 *
 * Reine JVM-Logik (kein android.*-Import in den Regeln — gleicher Verband
 * wie AssistantRulesTest, läuft ohne Robolectric).
 */
class AssistantPanelRulesTest {

    // ── G2: OverlayToggleRules — Permission-Flow-Zustände ──────────────────

    @Test
    fun toggleOnWithoutOverlayPermissionOpensPermissionSettings() {
        assertEquals(
            OverlayToggleRules.TapDecision.OPEN_PERMISSION_SETTINGS,
            OverlayToggleRules.onToggleTap(wantOn = true, overlayGranted = false),
        )
    }

    @Test
    fun toggleOnWithOverlayPermissionStartsService() {
        assertEquals(
            OverlayToggleRules.TapDecision.START_SERVICE,
            OverlayToggleRules.onToggleTap(wantOn = true, overlayGranted = true),
        )
    }

    @Test
    fun toggleOffAlwaysStopsService() {
        assertEquals(
            OverlayToggleRules.TapDecision.STOP_SERVICE,
            OverlayToggleRules.onToggleTap(wantOn = false, overlayGranted = true),
        )
        assertEquals(
            OverlayToggleRules.TapDecision.STOP_SERVICE,
            OverlayToggleRules.onToggleTap(wantOn = false, overlayGranted = false),
        )
    }

    @Test
    fun uiStateMatrixFollowsDesireAndGrant() {
        // Ohne Wunsch immer AUS — auch wenn die Freigabe steht.
        assertEquals(
            OverlayToggleRules.UiState.OFF,
            OverlayToggleRules.resolveUiState(desired = false, overlayGranted = true),
        )
        assertEquals(
            OverlayToggleRules.UiState.OFF,
            OverlayToggleRules.resolveUiState(desired = false, overlayGranted = false),
        )
        // Wunsch + Freigabe = AN.
        assertEquals(
            OverlayToggleRules.UiState.ON,
            OverlayToggleRules.resolveUiState(desired = true, overlayGranted = true),
        )
        // Wunsch ohne Freigabe = wartet (Setup-Weg, kein stiller Zustand).
        assertEquals(
            OverlayToggleRules.UiState.WAITING_FOR_PERMISSION,
            OverlayToggleRules.resolveUiState(desired = true, overlayGranted = false),
        )
    }

    @Test
    fun resumeAutoStartsOnlyWhenDesireGrantAndNotRunning() {
        assertTrue(
            OverlayToggleRules.shouldAutoStartOnResume(
                desired = true, overlayGranted = true, serviceRunning = false,
            ),
        )
        // Freigabe fehlt → kein Auto-Start (weiterhin Setup-Weg sichtbar).
        assertFalse(
            OverlayToggleRules.shouldAutoStartOnResume(
                desired = true, overlayGranted = false, serviceRunning = false,
            ),
        )
        // Ohne Wunsch → nie.
        assertFalse(
            OverlayToggleRules.shouldAutoStartOnResume(
                desired = false, overlayGranted = true, serviceRunning = false,
            ),
        )
        // Läuft schon → kein Doppelstart.
        assertFalse(
            OverlayToggleRules.shouldAutoStartOnResume(
                desired = true, overlayGranted = true, serviceRunning = true,
            ),
        )
    }

    // ── G11: AssistantEntryRules-Mini-Panel-Pin (Routing wie G2-Bubble) ─────

    @Test
    fun miniPanelMicTurnNeverRoutesToCall() {
        // G11 (W22 Lane B): das Mini-Panel ist ein eigenes Fenster — sein
        // Mikro-Turn ist IMMER ein Voice-Turn, unabhängig von Rolle/Modus
        // (gleicher Pin wie der G2-Bubble-Tap, AssistantRulesTest).
        assertEquals(
            AssistantEntryRules.Entry.CHAT_VOICE_TURN,
            AssistantEntryRules.resolve(
                AssistantEntryRules.Source.MINI_PANEL,
                roleHeld = false,
                callAssistantMode = false,
            ),
        )
        assertEquals(
            AssistantEntryRules.Entry.CHAT_VOICE_TURN,
            AssistantEntryRules.resolve(
                AssistantEntryRules.Source.MINI_PANEL,
                roleHeld = true,
                callAssistantMode = true,
            ),
        )
    }

    // ── G11: ShareIntake.parse — Share-Intent-Parsing ───────────────────────

    @Test
    fun parseRejectsForeignActionAndNonTextMime() {
        assertNull(
            ShareIntake.parse(
                action = "android.intent.action.VIEW",
                mimeType = "text/plain",
                subject = null,
                text = "https://example.com",
            ),
        )
        assertNull(
            ShareIntake.parse(
                action = ShareIntake.ACTION_SEND,
                mimeType = "image/png",
                subject = null,
                text = "https://example.com",
            ),
        )
        assertNull(
            ShareIntake.parse(
                action = ShareIntake.ACTION_SEND,
                mimeType = null,
                subject = null,
                text = "irgendwas",
            ),
        )
    }

    @Test
    fun parseRejectsCompletelyEmptyDelivery() {
        assertNull(
            ShareIntake.parse(
                action = ShareIntake.ACTION_SEND,
                mimeType = "text/plain",
                subject = "   ",
                text = "   ",
            ),
        )
    }

    @Test
    fun parseKeepsSubjectAndExtractsContextUrl() {
        val payload = ShareIntake.parse(
            action = ShareIntake.ACTION_SEND,
            mimeType = "TEXT/PLAIN",
            subject = " Interessanter Artikel ",
            text = "Schau mal: https://example.com/artikel?x=1 — find ich gut.",
        )
        assertNotNull(payload)
        assertEquals("Interessanter Artikel", payload?.subject)
        assertEquals(
            "Schau mal: https://example.com/artikel?x=1 — find ich gut.",
            payload?.text,
        )
        assertEquals("https://example.com/artikel?x=1", payload?.contextUrl)
    }

    @Test
    fun parseFallsBackToSubjectWhenTextIsEmpty() {
        // Manche Apps teilen nur den Titel — der Titel ist dann die Quelle.
        val payload = ShareIntake.parse(
            action = ShareIntake.ACTION_SEND,
            mimeType = "text/plain",
            subject = "https://example.com/nur-titel",
            text = "",
        )
        assertNotNull(payload)
        assertEquals("https://example.com/nur-titel", payload?.text)
        assertEquals("https://example.com/nur-titel", payload?.contextUrl)
    }

    // ── G11: Kontext-Whitelist http/https ───────────────────────────────────

    @Test
    fun contextUrlWhitelistAcceptsHttpAndHttps() {
        assertEquals(
            "https://example.com/a",
            ShareIntake.extractContextUrl("lies https://example.com/a danke"),
        )
        assertEquals(
            "http://example.com/b",
            ShareIntake.extractContextUrl("http://example.com/b"),
        )
    }

    @Test
    fun contextUrlTrimsSurroundingPunctuationAndBrackets() {
        assertEquals(
            "https://example.com/page",
            ShareIntake.extractContextUrl("Artikel (https://example.com/page). Ende"),
        )
        assertEquals(
            "https://example.com/x",
            ShareIntake.extractContextUrl("<https://example.com/x>,"),
        )
    }

    @Test
    fun contextUrlNormalizesUppercaseScheme() {
        assertEquals(
            "https://example.com/Path",
            ShareIntake.extractContextUrl("HTTPS://example.com/Path"),
        )
        assertEquals(
            "http://example.com",
            ShareIntake.extractContextUrl("HTTP://example.com"),
        )
    }

    @Test
    fun contextUrlRejectsNonWhitelistedSchemes() {
        assertNull(ShareIntake.extractContextUrl("ftp://example.com/datei"))
        assertNull(ShareIntake.extractContextUrl("file:///etc/passwd"))
        assertNull(ShareIntake.extractContextUrl("javascript:alert(1)"))
        assertNull(ShareIntake.extractContextUrl("intent://example.com/#Intent"))
        // www ohne Schema ist KEIN Whitelist-Treffer (kein Kontext-Hack).
        assertNull(ShareIntake.extractContextUrl("lies www.example.com/seite"))
        assertNull(ShareIntake.extractContextUrl("   "))
    }

    @Test
    fun contextUrlTakesTheFirstWhitelistedCandidate() {
        assertEquals(
            "https://erste.example.org/x",
            ShareIntake.extractContextUrl(
                "https://erste.example.org/x und ftp://boese.de sowie https://zweite.example.org/y",
            ),
        )
    }

    @Test
    fun contextUrlRejectsAbsurdlyLongCandidates() {
        val longUrl = "https://example.com/" + "a".repeat(2100)
        assertNull(ShareIntake.extractContextUrl(longUrl))
    }

    // ── G11: PanelActionRules — die 3 Share-Aktionen ────────────────────────

    private val urlPayload = SharePayload(
        subject = null,
        text = "https://example.com/artikel",
        contextUrl = "https://example.com/artikel",
    )

    private val textOnlyPayload = SharePayload(
        subject = null,
        text = "Nur ein Text ohne Link.",
        contextUrl = null,
    )

    // W22 MERGE-2 (i18n): Test-Vorlagen statt de-Defaults — die Tests
    // parametrisieren buildPrompt wie die Nutzungsstellen (Roh-Vorlage mit
    // %1$s übergeben, die Regel füllt Host/URL), gleiche Beweiskraft.
    private val askTemplate = "Frage zur Seite %1\$s: "
    private val summarizeTemplate = "Fasse den Inhalt dieser Seite kurz zusammen: %1\$s"

    @Test
    fun pageActionsRequireUrlSendDoesNot() {
        assertFalse(PanelActionRules.requiresUrl(ShareAction.SEND_TO_AGENT))
        assertTrue(PanelActionRules.requiresUrl(ShareAction.ASK_PAGE))
        assertTrue(PanelActionRules.requiresUrl(ShareAction.SUMMARIZE_PAGE))
    }

    @Test
    fun availabilityMatrixHonestWithoutUrl() {
        assertTrue(PanelActionRules.isAvailable(ShareAction.SEND_TO_AGENT, textOnlyPayload))
        assertFalse(PanelActionRules.isAvailable(ShareAction.ASK_PAGE, textOnlyPayload))
        assertFalse(PanelActionRules.isAvailable(ShareAction.SUMMARIZE_PAGE, textOnlyPayload))
        assertTrue(PanelActionRules.isAvailable(ShareAction.ASK_PAGE, urlPayload))
        assertTrue(PanelActionRules.isAvailable(ShareAction.SUMMARIZE_PAGE, urlPayload))
        // Ohne Zustellung ist NICHTS verfügbar (kein Dialog fürs Leere).
        assertFalse(PanelActionRules.isAvailable(ShareAction.SEND_TO_AGENT, null))
    }

    @Test
    fun sendToAgentPassesTextVerbatimAndAutoSends() {
        val prompt = PanelActionRules.buildPrompt(
            ShareAction.SEND_TO_AGENT, urlPayload, askTemplate, summarizeTemplate,
        )
        assertNotNull(prompt)
        assertEquals("https://example.com/artikel", prompt?.prompt)
        assertEquals("example.com", prompt?.chipLabel)
        assertTrue(prompt?.autoSend == true)
    }

    @Test
    fun askPagePreparesTemplateForTheComposerWithoutAutoSend() {
        val prompt = PanelActionRules.buildPrompt(
            ShareAction.ASK_PAGE, urlPayload, askTemplate, summarizeTemplate,
        )
        assertNotNull(prompt)
        assertEquals("Frage zur Seite example.com: ", prompt?.prompt)
        // Vorlage — der Nutzer ergänzt seine Frage, KEIN Auto-Send.
        assertTrue(prompt?.autoSend == false)
        assertEquals("example.com", prompt?.chipLabel)
    }

    @Test
    fun summarizePageSendsDirectlyWithFullUrl() {
        val prompt = PanelActionRules.buildPrompt(
            ShareAction.SUMMARIZE_PAGE, urlPayload, askTemplate, summarizeTemplate,
        )
        assertNotNull(prompt)
        assertEquals(
            "Fasse den Inhalt dieser Seite kurz zusammen: https://example.com/artikel",
            prompt?.prompt,
        )
        assertTrue(prompt?.autoSend == true)
    }

    @Test
    fun pageActionsWithoutUrlRefuseInsteadOfGuessing() {
        assertNull(
            PanelActionRules.buildPrompt(
                ShareAction.ASK_PAGE, textOnlyPayload, askTemplate, summarizeTemplate,
            ),
        )
        assertNull(
            PanelActionRules.buildPrompt(
                ShareAction.SUMMARIZE_PAGE, textOnlyPayload, askTemplate, summarizeTemplate,
            ),
        )
    }

    @Test
    fun chipLabelStripsPortUserinfoPathAndQuery() {
        assertEquals(
            "example.com",
            PanelActionRules.chipLabel("https://user@example.com:8443/pfad?x=1#top"),
        )
        assertNull(PanelActionRules.chipLabel(null))
        assertEquals("localhost", PanelActionRules.hostLabel("http://localhost:8080/app"))
    }

    // ── G11: AssistContentRules — strukturierte AssistContent-Daten ─────────

    @Test
    fun jsonEscapeCoversQuotesBackslashAndControls() {
        assertEquals("leer", AssistContentRules.escapeJson("leer"))
        assertEquals("sagt \\\"hi\\\"", AssistContentRules.escapeJson("sagt \"hi\""))
        assertEquals("a\\\\b", AssistContentRules.escapeJson("a\\b"))
        assertTrue(AssistContentRules.escapeJson("zeile\numbruch").contains("\\n"))
    }

    @Test
    fun structuredDataCarriesNameAndEscapedDescription() {
        val json = AssistContentRules.structuredDataJson(
            appName = "FlipsiBridge",
            description = "Brücke mit \"Anführungs\"",
        )
        assertTrue(json.contains("\"@type\":\"SoftwareApplication\""))
        assertTrue(json.contains("\"name\":\"FlipsiBridge\""))
        assertTrue(json.contains("\\\"Anführungs\\\""))
        assertFalse(json.contains("\"description\":\"Brücke mit \"Anführungs\"\""))
    }

    @Test
    fun assistWebUriWhitelistTakesOnlyHttpAndHttps() {
        assertEquals(
            "https://example.com",
            AssistContentRules.webUriOrNull(" https://example.com "),
        )
        assertEquals("http://example.com", AssistContentRules.webUriOrNull("http://example.com"))
        assertNull(AssistContentRules.webUriOrNull("javascript:alert(1)"))
        assertNull(AssistContentRules.webUriOrNull("   "))
        assertNull(AssistContentRules.webUriOrNull(null))
    }

    // ── G2/G11: AgentColorRules — Farb-Hash + Initiale ─────────────────────

    @Test
    fun bubbleColorIsStablePerName() {
        assertEquals(
            AgentColorRules.bubbleColor("Alpha"),
            AgentColorRules.bubbleColor("Alpha"),
        )
        assertEquals(
            AgentColorRules.bubbleColor("  alpha  "), // trim + Klein-Schreibung
            AgentColorRules.bubbleColor("Alpha"),
        )
    }

    @Test
    fun bubbleColorDistinguishesNamesAndFallsBackWhenBlank() {
        // Hues vorab berechnet: alpha=38°, beta=232° — garantiert verschieden.
        assertNotEquals(
            AgentColorRules.bubbleColor("alpha"),
            AgentColorRules.bubbleColor("beta"),
        )
        assertEquals(AgentColorRules.FALLBACK_COLOR, AgentColorRules.bubbleColor(null))
        assertEquals(AgentColorRules.FALLBACK_COLOR, AgentColorRules.bubbleColor("   "))
    }

    @Test
    fun bubbleColorIsAlwaysOpaque() {
        for (name in listOf("alpha", "beta", "gamma", "delta")) {
            assertEquals(
                "Alpha-Kanal muss voll deckend sein: $name",
                0xFF,
                (AgentColorRules.bubbleColor(name) ushr 24) and 0xFF,
            )
        }
    }

    @Test
    fun avatarInitialUsesFirstAlnumAndFallsBack() {
        assertEquals("A", AgentColorRules.avatarInitial("Ada Lovelace"))
        assertEquals("4", AgentColorRules.avatarInitial("42 Notifier"))
        assertEquals("F", AgentColorRules.avatarInitial(null))
        assertEquals("F", AgentColorRules.avatarInitial("   "))
        assertEquals("B", AgentColorRules.avatarInitial("", fallback = "Bridge"))
    }
}
