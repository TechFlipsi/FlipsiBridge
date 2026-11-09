package com.hermesandroid.bridge.ui.chat

/**
 * W4-B (Zitieren): Reine Regeln des Zitier-Verbands — JVM-getestet
 * (ChatQuoteRulesTest); die Composables (ChatScreen/ChatWidgets) rendern nur.
 *
 * ## Ratchet-Vertrag (W4)
 * Zitieren reist NUR über die bestehenden Chat-Körper — kein neuer Frame-Typ,
 * kein neues Protokollfeld. Der Agent liest ohnehin nur das `text`-Feld von
 * `chat.send` / `chat.send.enc` (Adapter entschlüsselt und reicht den Text);
 * das Zitat wird deshalb klartext-sicher IN den Text gefaltet:
 *
 * - Klartext-Modus: gleicher Text im POST-/chat-Körper (Feld `text`).
 * - E2E-Modus (chat.send.enc): Text NUR im AES-GCM-ct der bestehenden inneren
 *   Nutzdaten — der Relay transportiert ihn weiter opak, sieht nie das Zitat.
 *
 * ## Draht-Format (SSOT für Persistenz UND Restore)
 * ```
 * Antwort auf [KI ]»<Zitat, Newlines zu " / " gefaltet, ≤ MAX_QUOTE_CHARS>«: <Antwort>
 * ```
 * Der Relay persistiert eigene Bubbles ohnehin mit dem GESAMTEN `text`-Arg
 * (RelayClient.persistChatBubble, success + failure) — der Draht-Text ist damit
 * die Platte-Wahrheit. Der Restore (ChatScreen, historyRestored-Gate) faltet
 * ihn mit [splitWireText] zurück in Antwort-Text + [ChatQuote]-Snapshot:
 * Live-Bubble und restaurierte Bubble rendern identisch strukturiert.
 *
 * ## Degradations-Vertrag
 * - Enthält das Original selbst „»…«:" erkennt [splitWireText] den Termina-
 *   tor eher → Zitat fällt kürzer aus, Antwort-Text geht NIE verloren
 *   (der Rest bleibt im Antwort-Körper sichtbar).
 * - Blank/leeres Zitat (nach Falten) → kein Draht-Präfix, reine Antwort.
 * - Ein Agent-Text, der zufällig mit dem Präfix beginnt, wird erst beim
 *   RESTORE eigener Bubbles (fromAgent = false) gefaltet — Agenten-Bubbles
 *   bleiben unverändert Echo.
 */
private val NEWLINE_RUNS = Regex("[\\r\\n]+")
private val WHITESPACE_RUNS = Regex("\\s+")

object ChatQuoteRules {

    /** Draht-Präfix, wenn eine EIGENE Nachricht zitiert wird. */
    const val WIRE_PREFIX = "Antwort auf »"

    /** Draht-Präfix, wenn eine AGENTEN-Antwort zitiert wird („KI“-Marke). */
    const val WIRE_AGENT_PREFIX = "Antwort auf KI »"

    /** Terminator zwischen Zitat und Antwort im Draht-Text. */
    const val WIRE_SUFFIX = "«: "

    /**
     * Länge-Cap des Zitats im Draht-Text (Relay-Cap ist 32 KiB je Text — das
     * Bubble-lesbare Zitat deckt die Alltagsspanne und hält 400-Zeilen-Originale
     * beim Zitieren klein; das VOLLSTÄNDIGE Original bleibt in der Timeline).
     */
    const val MAX_QUOTE_CHARS = 400

    const val LABEL_SELF = "Du"
    const val LABEL_KI = "KI"

    /**
     * Falten: Newline-Runs zu „ / “ ([flatten] von „a\nb“ = „a / b“), sonstige
     * Whitespace-Runs zu einem Leerzeichen („ \n\t “ faltet leer — Ränder
     * getrimmt). Der Draht bleibt einzeilig: eindeutige Grenze zwischen Zitat
     * und Antwort.
     */
    fun flatten(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val newlineFolded = NEWLINE_RUNS.replace(trimmed, " / ")
        return WHITESPACE_RUNS.replace(newlineFolded, " ")
    }

    /** Zitat-Span: gefaltet + gekappt (max [MAX_QUOTE_CHARS], „…“ als Endmarke). */
    fun quoteSpan(quotedText: String): String {
        val flat = flatten(quotedText)
        if (flat.isEmpty()) return ""
        return if (flat.length <= MAX_QUOTE_CHARS) flat else flat.take(MAX_QUOTE_CHARS - 1) + "…"
    }

    /**
     * Draht-Text der Antwort: ohne Zitat die reine Antwort, mit Zitat das
     * Präfix je Ursprung ([WIRE_PREFIX] / [WIRE_AGENT_PREFIX]) + Zitat-Span +
     * Terminator + Antwort (Antwort bleibt UNVERÄNDERT mehrzeilig).
     */
    fun wireText(reply: String, quote: ChatQuote?): String {
        val span = quoteSpan(quote?.text ?: "")
        if (span.isEmpty()) return reply
        val prefix = if (quote?.fromAgent == true) WIRE_AGENT_PREFIX else WIRE_PREFIX
        return "$prefix$span$WIRE_SUFFIX$reply"
    }

    /**
     * Draht-Rückfold (Restore eigener Bubbles): null, wenn [text] kein
     * Zitat-Draht ist (kein Präfix / leerer Zitat-Span); sonst Ursprung,
     * Zitat-Text und Antwort.
     */
    fun splitWireText(text: String): ParsedQuoteWire? {
        val fromAgent = text.startsWith(WIRE_AGENT_PREFIX)
        val prefix = if (fromAgent) WIRE_AGENT_PREFIX else if (text.startsWith(WIRE_PREFIX)) WIRE_PREFIX else return null
        val suffixIndex = text.indexOf(WIRE_SUFFIX, prefix.length)
        if (suffixIndex <= prefix.length) return null
        return ParsedQuoteWire(
            fromAgent = fromAgent,
            quoteText = text.substring(prefix.length, suffixIndex),
            reply = text.substring(suffixIndex + WIRE_SUFFIX.length),
        )
    }

    /** Resultat der Draht-Rückfold (ursprung + Zitat-Text + reine Antwort). */
    data class ParsedQuoteWire(
        val fromAgent: Boolean,
        val quoteText: String,
        val reply: String,
    )

    /**
     * Absender-Label im Zitier-Verband: eigene Nachricht → „Du“, Agenten-
     * Antwort mit Backend → Anzeigename, Demo-Eintrag (blank agentId, senderName
     * null) → neutrale „KI“-Marke (nie ein erfundener Name).
     */
    fun quoteLabel(fromAgent: Boolean, senderName: String?): String = when {
        !fromAgent -> LABEL_SELF
        !senderName.isNullOrBlank() -> senderName
        else -> LABEL_KI
    }
}
