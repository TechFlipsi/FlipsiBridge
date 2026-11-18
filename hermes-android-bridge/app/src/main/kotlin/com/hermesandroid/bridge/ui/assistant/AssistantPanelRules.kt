package com.hermesandroid.bridge.ui.assistant

/**
 * W22 LANE B (G2+G11, docs/roadmap-anruf.md §7 Z.109/118 + §7.1) — die
 * Overlay-/Panel-Regeln als reine JVM-Logik: KEIN android.*-Import (gleicher
 * Verband wie [AssistantRules] im selben Paket, damit der JVM-Test ohne
 * Robolectric läuft).
 *
 * Inhalt (ehrlich abgegrenzt, nichts Erfundenes):
 *  - [OverlayToggleRules]: G2-Schalter — Toggle-Tap ↔ Service-Start ↔ Overlay-
 *    Freigabe inkl. RÜCKWEG-Zustand (ON_RESUME nach ACTION_MANAGE_OVERLAY_
 *    PERMISSION): die Blase startet sichtbar, sobald die Freigabe steht.
 *  - [ShareIntake]: G11 Share-Target-Parsing (ACTION_SEND, Text-Mime) mit
 *    http/https-Kontext-Whitelist — file:, javascript:, ftp:, www ohne
 *    Schema usw. werden ehrlich verworfen (kein Chip aus Müll).
 *  - [PanelActionRules]: die 3 Share-Aktionen (An Agenten senden / Seite
 *    fragen / Zusammenfassen) → Zustell-Prompt + Kontext-Chip-Label.
 *  - [AssistContentRules]: strukturiertes AssistContent-JSON — dieselbe
 *    Schiene, über die Browser die Seiten-URL an den Assistenten liefern
 *    (onProvideAssistContent in MainActivity).
 *  - [AgentColorRules]: stabiler Farb-Hash des aktiven Agenten für Blase
 *    (G2) und Mini-Panel-Avatar (G11) — App-Icon-/Farb-Verband, generisch.
 *
 * Mid-Screen-Edgewis (G11, bewusste Grenze): ein Wisch aus der Bildschirm-
 * MITTE ist ohne System-Rechte Dritt-Apps nicht zuverlässig zuzuordnen
 * (System-Gestenzuordnung) — NUR der Ecken-Wisch des Standard-Assistenten
 * (G1) und die Overlay-Blase (G2) sind unsere Trigger; hier gibt es bewusst
 * KEINE Regel dafür.
 */

/** G2: Zustandsmaschine des Overlay-Bubble-Schalters (Permission-Flow). */
object OverlayToggleRules {

    /** Sichtbarer Zustand der G2-Zeile in den Assistenten-Einstellungen. */
    enum class UiState {
        /** Aus — keine Blase, kein Dienst. */
        OFF,

        /** Nutzer wollte AN, Overlay-Freigabe fehlt — Setup-Weg mit sichtbarem Ergebnis. */
        WAITING_FOR_PERMISSION,

        /** Gewünscht + Freigabe da — der Dienst hält die Blase über allen Apps. */
        ON,
    }

    /** Entscheidung nach einem Toggle-Tap — jede hat eine sichtbare Folge. */
    enum class TapDecision {
        /** Blase jetzt starten (Freigabe steht). */
        START_SERVICE,

        /** Overlay-Freigabe öffnen (ACTION_MANAGE_OVERLAY_PERMISSION) — Freigabe fehlt. */
        OPEN_PERMISSION_SETTINGS,

        /** Blase stoppen (Toggle auf AUS). */
        STOP_SERVICE,
    }

    /**
     * Der Tap entscheidet über den Weg — kein stiller runCatching: ohne
     * Freigabe führt JEDER AN-Tap in die Overlay-Freigabe (mit Ergebnis-
     * Zeile), mit Freigabe startet der Dienst sofort.
     */
    fun onToggleTap(wantOn: Boolean, overlayGranted: Boolean): TapDecision = when {
        !wantOn -> TapDecision.STOP_SERVICE
        overlayGranted -> TapDecision.START_SERVICE
        else -> TapDecision.OPEN_PERMISSION_SETTINGS
    }

    /** Zustand aus Wunsch + Freigabe (der Dienst-Start ist die sichtbare Folge von ON). */
    fun resolveUiState(desired: Boolean, overlayGranted: Boolean): UiState = when {
        !desired -> UiState.OFF
        overlayGranted -> UiState.ON
        else -> UiState.WAITING_FOR_PERMISSION
    }

    /**
     * RÜCKWEG (ON_RESUME nach den System-Einstellungen): true = die Ver-
     * drahtung startet den Dienst JETZT — Wunsch + Freigabe stehen, der
     * Dienst läuft noch nicht. Genau dieser Übergang macht das Ergebnis des
     * Freigabe-Wegs sichtbar (Blase erscheint), statt dass der Schalter
     * still im „warten“ klebt.
     */
    fun shouldAutoStartOnResume(
        desired: Boolean,
        overlayGranted: Boolean,
        serviceRunning: Boolean,
    ): Boolean = desired && overlayGranted && !serviceRunning
}

/** G11: geparste Share-Zustellung (null = unbrauchbar → kein Dialog). */
data class SharePayload(
    /** EXTRA_SUBJECT — nur Anzeige im Dialog, keine Logik. */
    val subject: String?,
    /** Geteilter Text komplett (kann URL + Begleittext mischen). */
    val text: String,
    /** Erste Whitelist-URL (http/https) im Text für den Kontext-Chip, sonst null. */
    val contextUrl: String?,
)

/** G11: Share-Target-Verband — ACTION_SEND (Text-Mime) sauber aufnehmen. */
object ShareIntake {

    /** android.content.Intent.ACTION_SEND als Literal (JVM-pure, kein Android-Import). */
    const val ACTION_SEND: String = "android.intent.action.SEND"

    /** Kap gegen absurde Chips — längere Kandidaten werden verworfen. */
    private const val MAX_URL_LENGTH = 2048

    /** Öffnende Klammern/Zitate vor einer URL (Markdown, Klammern, Gänsefüßchen). */
    private val URL_LEADING_TRIM = "<([{«„\"'".toList()

    /** Schließende Satzzeichen hinter einer URL (Punkt, Komma, Klammer, Zitate …). */
    private val URL_TRAILING_TRIM = ">)]}»\"'“.,;:!?".toList()

    /**
     * Parst eine eingehende ACTION_SEND-Zustellung. null = nicht für uns
     * (falsche Action, kein Text-Mime, komplett leer) — der Aufrufer zeigt
     * dann bewusst NICHTS statt eines Dialogs fürs Leere.
     */
    fun parse(
        action: String?,
        mimeType: String?,
        subject: String?,
        text: String?,
    ): SharePayload? {
        if (action != ACTION_SEND) return null
        val mime = mimeType?.trim()?.lowercase() ?: return null
        if (!mime.startsWith("text/")) return null
        val cleanSubject = subject?.trim().orEmpty().ifEmpty { null }
        val cleanText = text.orEmpty().trim()
        if (cleanText.isEmpty() && cleanSubject == null) return null
        // Subjekt als Fallback-Quelle, wenn Apps nur den Titel teilen.
        val source = if (cleanText.isEmpty()) cleanSubject.orEmpty() else cleanText
        return SharePayload(
            subject = cleanSubject,
            text = source,
            contextUrl = extractContextUrl(source),
        )
    }

    /**
     * Kontext-Whitelist: die ERSTE http/https-URL im Text (Schema klein-
     * geschrieben normalisiert, umschließende Satzzeichen/Klammern entfernt).
     * Alles andere — ftp:, file:, javascript:, intent:, www ohne Schema —
     * bleibt ehrlich draußen (kein Chip, kein Kontext-Hack).
     */
    fun extractContextUrl(source: String?): String? {
        if (source.isNullOrBlank()) return null
        for (rawToken in source.split(Regex("\\s+"))) {
            val trimmedToken = trimUrlDelimiters(rawToken) ?: continue
            val normalized = normalizeScheme(trimmedToken) ?: continue
            if (isWhitelistedHttpUrl(normalized)) return normalized
        }
        return null
    }

    /** Wirft umschließende Klammern/Satzzeichen ab: „(https://x.org/a).“ → https://x.org/a. */
    private fun trimUrlDelimiters(raw: String): String? {
        var candidate = raw
        while (candidate.isNotEmpty() && candidate.first() in URL_LEADING_TRIM) {
            candidate = candidate.substring(1)
        }
        while (candidate.isNotEmpty() && candidate.last() in URL_TRAILING_TRIM) {
            candidate = candidate.dropLast(1)
        }
        return candidate.ifEmpty { null }
    }

    /** Schema-Klein-Normalisierung (HTTPS://X.ORG → https://X.ORG); kein http(s)-Schema → null. */
    private fun normalizeScheme(candidate: String): String? {
        val lower = candidate.lowercase()
        return when {
            lower.startsWith("https://") -> "https://" + candidate.substring(8)
            lower.startsWith("http://") -> "http://" + candidate.substring(7)
            else -> null
        }
    }

    /** Länge + Host-Plausibilität (nicht leer, mind. ein Buchstabe/eine Ziffer). */
    private fun isWhitelistedHttpUrl(url: String): Boolean {
        if (url.length > MAX_URL_LENGTH) return false
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return false
        val authority = url
            .substring(schemeEnd + 3)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfter('@')
        val host = if (authority.startsWith("[")) {
            // IPv6-Literal in Klammern — Port fällt hinter der Klammer ab.
            authority.substringBefore(']').removePrefix("[")
        } else {
            authority.substringBefore(':')
        }
        return host.isNotBlank() && host.any { it.isLetterOrDigit() }
    }
}

/** G11: die 3 Aktionen des Share-Dialogs (Reihenfolge = Dialog-Optik). */
enum class ShareAction {
    /** Roh an den aktiven Chat weitergeben (Begleittext/Link, wie geteilt). */
    SEND_TO_AGENT,

    /** Seite fragen — Vorlage im Komposer, Nutzer vervollständigt die Frage. */
    ASK_PAGE,

    /** Zusammenfassen — direkte Zusammenfass-Anfrage über den Link. */
    SUMMARIZE_PAGE,
}

/** G11: Zustell-Prompt an den aktiven Chat (Verbrauch = Komposer-Hook, [AssistantShareBus]). */
data class DeliveryPrompt(
    /** Text für den Komposer (direkt senden oder als Vorlage). */
    val prompt: String,
    /** Kontext-URL für den Chip (null = kein Chip). */
    val contextUrl: String?,
    /** Kurzes Chip-Label (Host der URL) — null ohne URL. */
    val chipLabel: String?,
    /** true = direkt senden; false = Vorlage in den Komposer (Nutzer ergänzt seine Frage). */
    val autoSend: Boolean,
)

/** G11: Prompt-Bau je Share-Aktion — Link-Pflichten ehrlich erzwungen. */
object PanelActionRules {

    /** Seiten-Aktionen brauchen einen Link (kein stilles Raten über reinen Text). */
    fun requiresUrl(action: ShareAction): Boolean = action != ShareAction.SEND_TO_AGENT

    /** true = die Aktion ist im Dialog wählbar (Link-Pflicht erfüllt). */
    fun isAvailable(action: ShareAction, payload: SharePayload?): Boolean =
        payload != null && (!requiresUrl(action) || payload.contextUrl != null)

    /**
     * Baut den Zustell-Prompt je Aktion. null = Aktion nicht verfügbar
     * (Link-Pflicht verletzt) — der Dialog zeigt den Grund statt still.
     */
    fun buildPrompt(action: ShareAction, payload: SharePayload): DeliveryPrompt? {
        val url = payload.contextUrl
        return when (action) {
            ShareAction.SEND_TO_AGENT ->
                // Roh weitergeben: Der Nutzer hat bewusst geteilt — kein Umschmücken.
                DeliveryPrompt(
                    prompt = payload.text,
                    contextUrl = url,
                    chipLabel = chipLabel(url),
                    autoSend = true,
                )
            ShareAction.ASK_PAGE -> {
                if (url == null) return null
                // STRING: assistant_share_ask_template — de "Frage zur Seite {host}: ",
                // en "Question about this page ({host}): "
                val template = "Frage zur Seite ${hostLabel(url)}: "
                DeliveryPrompt(
                    prompt = template,
                    contextUrl = url,
                    chipLabel = chipLabel(url),
                    autoSend = false,
                )
            }
            ShareAction.SUMMARIZE_PAGE -> {
                if (url == null) return null
                // STRING: assistant_share_summarize_template — de "Fasse den Inhalt dieser
                // Seite kurz zusammen: {url}", en "Briefly summarize the content of this
                // page: {url}"
                val template = "Fasse den Inhalt dieser Seite kurz zusammen: $url"
                DeliveryPrompt(
                    prompt = template,
                    contextUrl = url,
                    chipLabel = chipLabel(url),
                    autoSend = true,
                )
            }
        }
    }

    /** Chip-Label: Host der Kontext-URL (kurz, app-generisch); ohne URL null. */
    fun chipLabel(url: String?): String? = url?.let { hostLabel(it).ifEmpty { null } }

    /** Host-Label (für Chip + „Seite fragen“-Vorlage): Authority ohne Port/Userinfo. */
    fun hostLabel(url: String): String {
        val schemeEnd = url.indexOf("://")
        val rest = if (schemeEnd > 0) url.substring(schemeEnd + 3) else url
        val authority = rest
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfter('@')
        val host = if (authority.startsWith("[")) {
            authority.substringBefore(']').removePrefix("[")
        } else {
            authority.substringBeforeLast(':')
        }
        return host.ifEmpty { url }
    }
}

/** G11: AssistContent-Verband — strukturierte Daten für onProvideAssistContent. */
object AssistContentRules {

    /** Minimal-JSON-Escape (Anführungszeichen, Backslash, Steuerzeichen). */
    fun escapeJson(raw: String): String = buildString {
        for (ch in raw) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append(String.format("\\u%04x", ch.code)) else append(ch)
            }
        }
    }

    /**
     * Strukturiertes AssistContent (JSON-LD, SoftwareApplication) für unsere
     * eigenen Inhalte: FlipsiBridge zeigt keinen öffentlichen Web-Inhalt,
     * deshalb ehrlich App-Metadaten OHNE Web-URI. Der Seiten-Kontext fürs
     * Mini-Panel kommt über den Share-Target-Weg (ACTION_SEND) bzw. den
     * AssistEntry-Hook — genau wie Browser ihre Seiten-URL über dieselbe
     * onProvideAssistContent-Schiene an den Assistenten liefern.
     * schema.org ist der Standard-Kontext des Formats (keine private Domain).
     */
    fun structuredDataJson(appName: String, description: String): String =
        "{\"@context\":\"https://schema.org\",\"@type\":\"SoftwareApplication\"," +
            "\"name\":\"${escapeJson(appName)}\"," +
            "\"applicationCategory\":\"CommunicationApplication\"," +
            "\"operatingSystem\":\"Android\"," +
            "\"description\":\"${escapeJson(description)}\"}"

    /** Whitelist für Web-URIs im Assist-Verband: nur http/https überleben. */
    fun webUriOrNull(candidate: String?): String? {
        if (candidate.isNullOrBlank()) return null
        val trimmed = candidate.trim()
        val lower = trimmed.lowercase()
        return if (lower.startsWith("https://") || lower.startsWith("http://")) trimmed else null
    }
}

/** G2/G11: stabiler Farb-Hash + Initiale für Blase und Mini-Panel-Avatar. */
object AgentColorRules {

    /** App-Fallback (Ember) — trägt kein Agentenname, bleibt die Marke selbst. */
    val FALLBACK_COLOR: Int = 0xFFED7931.toInt()

    /**
     * Farb-Hash des (aktiven) Agenten: gleicher Name → gleiche Farbe, stabil
     * über App-Neustarts (String.hashCode ist in der JVM spezifiziert). Ohne
     * Namen der App-Fallback. Voll deckend (Alpha FF) — Overlay-Kontrast.
     */
    fun bubbleColor(name: String?): Int {
        val key = name?.trim().orEmpty()
        if (key.isEmpty()) return FALLBACK_COLOR
        // Int-Min-Falle: als Long maskieren, dann in den Farbkreis falten.
        val hue = ((key.lowercase().hashCode().toLong() and 0xFFFFFFFFL) % 360L).toFloat()
        return hsvToArgb(hue, saturation = 0.62f, value = 0.86f)
    }

    /** HSV→ARGB (reine Mathe, kein android.graphics — JVM-testbar). */
    private fun hsvToArgb(h: Float, saturation: Float, value: Float): Int {
        val c = value * saturation
        val hp = (h / 60f) % 6f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
        val rgb = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = value - c
        fun channel(v: Float): Int = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(rgb.first) shl 16) or
            (channel(rgb.second) shl 8) or channel(rgb.third)
    }

    /** Initiale für Blase/Avatar: erstes alphanumerisches Zeichen, sonst App-Fallback. */
    fun avatarInitial(name: String?, fallback: String = "F"): String {
        val key = name?.trim().orEmpty()
        val letter = key.firstOrNull { it.isLetterOrDigit() }
        val initial = letter?.uppercaseChar()?.toString()
            ?: fallback.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString()
        return initial ?: "F"
    }
}
