package com.hermesandroid.bridge.ui.assistant

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.TextUtils
import android.text.TextWatcher
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button as PlatformButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView as PlatformTextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.MainActivity
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.ui.FlipsiColors
import kotlin.math.min

/**
 * W22 LANE B — G11 „Seite fragen“ (docs/roadmap-anruf.md §7 Z.118 + §7.1):
 * die Overlay-/Empfangs-Bausteine des Wisch-Access.
 *
 * 1) [AssistantShareBus] — der Zustell-Bus in den aktiven Chat (Kontext-Chip).
 *    HOOK-BEDARF (Lane A/C, NICHT hier angebaut — ChatScreen/Composer sind
 *    fremde Zone): ConversationBody konsumiert [AssistantShareBus.consume]
 *    beim Kompositionsaufbau nach dem AssistLaunch.pendingVoice-Muster
 *    (ChatScreen:1125): prompt → inputText-Vorlage (autoSend=false) oder
 *    direkte Sendung (autoSend=true), chipLabel als Kontext-Chip an der
 *    Nachricht. Bis zur Verdrahtung bleibt eine Zustellung IM Bus stehen —
 *    sichtbar, verlustfrei, kein stilles Wegwerfen.
 *
 * 2) [MiniPanelController] — das Mini-Panel über JEDEM Fenster (Overlay-Weg,
 *    Plattform-Views aus demselben Grund wie die G2-Blase: ein ComposeView
 *    bräuchte außerhalb einer Activity einen eigenen Recomposer-/Lifecycle-
 *    Verband, fragiler Eigenbau).
 *    Inhalt: Profil-Avatar (Agenten-Farb-Hash) + Textfeld + Mikro-Turn-
 *    Knopf (bestehender AssistLaunch.pendingVoice-Weg — der Call-Turn/STT-
 *    Upgrade-Pfad ist Lane-A-Anschluss, HOOK-BEDARF) + Kontext-Chip.
 *
 *    BEWUSSTE GRENZE (Mid-Screen-Edgewis): ein Wisch aus der Bildschirm-
 *    MITTE ist Dritt-Apps ohne System-Rechte nicht zuzuordnen (System-
 *    Gestenzuordnung) — hier gibt es bewusst KEINEN Gesten-Code. Die zwei
 *    legalen Trigger bleiben: Ecken-Wisch des Standard-Assistenten (G1,
 *    AssistEntryActivity → AssistantBubbleService.showPanel-Hook) und die
 *    Overlay-Blase (G2). Universeller Fallback: Share-Target (ACTION_SEND →
 *    [AssistantShareDialog] in MainActivity).
 *
 * 3) [AssistantShareDialog] — der Empfangs-Dialog des Share-Targets mit
 *    den 3 Aktionen (An Agenten senden / Seite fragen / Zusammenfassen),
 *    jede mit sichtbarem Ergebnis; Seiten-Aktionen ohne Link bleiben
 *    ehrlich gesperrt (Grund-Zeile statt stiller Knopf).
 */

/**
 * G11-Bus: Overlay-/Share-Zustellung in den aktiven Chat. Der Verbraucher
 * ist der Komposer (HOOK — siehe Datei-KDoc); die Zustellung überlebt den
 * Overlay-Weg und den Dialog-Dismiss atomar (Volatile, ein Objekt).
 */
object AssistantShareBus {

    /** Zustell-Auftrag aus Panel oder Share-Dialog (Prompt + Kontext-Chip). */
    @Volatile
    var pendingDelivery: DeliveryPrompt? = null
        private set

    /** Einreihen (Panel/Share-Dialog) — überschreibt eine offene Zustellung. */
    fun deliver(prompt: DeliveryPrompt) {
        pendingDelivery = prompt
    }

    /** Einmaliger Verbrauch (Komposer-Hook): nimmt die Zustellung ab und räumt sie. */
    fun consume(): DeliveryPrompt? {
        val taken = pendingDelivery
        pendingDelivery = null
        return taken
    }
}

/**
 * G11: Mini-Panel-Controller — baut/hält das Overlay-Panel über dem
 * aktuellen Fenster. Plattform-Views (siehe Datei-KDoc), Fokus-Fenster
 * (Textfeld tippt direkt), Außen-Tap schließt (FLAG_WATCH_OUTSIDE_TOUCH).
 */
class MiniPanelController(private val service: android.app.Service) {

    private var panelView: View? = null
    private var contextUrl: String? = null
    private var chipRow: LinearLayout? = null
    private var chipLabel: PlatformTextView? = null
    private var inputField: EditText? = null
    private var sendButton: PlatformButton? = null

    /** Panel hängt gerade am Fenster. */
    val isAttached: Boolean get() = panelView != null

    // Spiegel der FlipsiColors-Werte (BridgeTheme) — Plattform-Views brauchen
    // Ints, die Compose-Color-Objekte sind dort nicht nutzbar.
    private val colorPanel = 0xFF1A1D23.toInt()
    private val colorPanelElev = 0xFF21262E.toInt()
    private val colorEmber = 0xFFED7931.toInt()
    private val colorOnEmber = 0xFF1A1208.toInt()
    private val colorTextPrimary = 0xFFE8EAED.toInt()
    private val colorTextSecondary = 0xFF9BA3AE.toInt()
    private val colorLine = 0xFF2A2F38.toInt()

    /** Hängt das Panel ans Fenster (Kontext-URL für den Chip, null = ohne). */
    fun show(url: String?) {
        if (isAttached) {
            updateContext(url)
            return
        }
        contextUrl = url
        val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val dm = service.resources.displayMetrics
        val density = dm.density
        val widthPx = min((dm.widthPixels * 0.92f).toInt(), dp(340, density))

        val panel = buildPanel(density)
        // Initialer Chip-Stand (buildPanel baut die Zeile leer — hier fließt
        // die Kontext-URL zum ersten Mal hinein, inkl. Senden-Knopf-Zustand).
        updateContext(url)

        val params = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Fokussierbar (Textfeld!), Außen-Tap schließt — kein NOT_FOCUSABLE.
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(96, density)
        }

        // Außen-Tap = schließen (der einzige Gesten-Weg, bewusst simpel).
        panel.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                hide()
                true
            } else {
                false
            }
        }

        runCatching { wm.addView(panel, params) }.onFailure {
            panelView = null
            return
        }
        panelView = panel
    }

    /** Panel vom Fenster (Außen-Tap, Schließen-Knopf, Senden). */
    fun hide() {
        panelView?.let { view ->
            runCatching {
                (service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager)
                    .removeView(view)
            }
        }
        panelView = null
    }

    /** Kontext-URL austauschen (Panel bleibt offen, Chip passt sich an). */
    fun updateContext(url: String?) {
        contextUrl = url
        chipRow?.visibility = if (url == null) View.GONE else View.VISIBLE
        chipLabel?.text = PanelActionRules.chipLabel(url) ?: ""
        refreshSendState()
    }

    // ── Aufbau ─────────────────────────────────────────────────────────────

    private fun buildPanel(density: Float): View {
        val pad = dp(16, density)
        val agentName = activeAgentName()

        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 24 * density
                setColor(colorPanel)
                setStroke(dp(1, density), colorLine)
            }
            setPadding(pad, pad, pad, pad)
        }

        // ── Kopfzeile: Avatar (Agenten-Farb-Hash) + Name + Schließen. ──
        root.addView(buildHeaderRow(density, agentName))

        // ── Kontext-Chip (nur mit URL): Host + Entfernen. ──
        val chip = buildChipRow(density)
        chipRow = chip
        root.addView(chip)
        chip.visibility = if (contextUrl == null) View.GONE else View.VISIBLE

        // ── Eingabezeile: Textfeld (Fokus) + Mikro-Turn. ──
        val inputRow = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        val field = EditText(service).apply {
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(colorPanelElev)
            }
            setTextColor(colorTextPrimary)
            setHintTextColor(colorTextSecondary)
            hint = service.getString(R.string.assistant_panel_input_hint)
            textSize = 15f
            setSingleLine(false)
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(2000))
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        inputField = field
        inputRow.addView(
            field,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        val micParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        ).apply { leftMargin = dp(8, density) }
        inputRow.addView(buildMicButton(density), micParams)
        root.addView(inputRow)

        // ── Senden-Knopf: Aktion mit Ergebnis (ohne Text UND ohne Chip gesperrt). ──
        val send = buildSendButton(density)
        sendButton = send
        root.addView(send)
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = refreshSendState()
        })
        refreshSendState()
        return root
    }

    private fun buildHeaderRow(density: Float, agentName: String?): LinearLayout {
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Avatar: Kreis in Agenten-Farb-Hash + Initiale (G2-gleicher Verband).
        val avatarSize = dp(36, density)
        val avatar = PlatformTextView(service).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AgentColorRules.bubbleColor(agentName))
            }
            text = AgentColorRules.avatarInitial(agentName, fallback = "F")
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        row.addView(avatar, LinearLayout.LayoutParams(avatarSize, avatarSize))
        val nameParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        nameParams.leftMargin = dp(12, density)
        row.addView(
            PlatformTextView(service).apply {
                text = agentName
                    ?: service.getString(R.string.assistant_panel_default_name)
                setTextColor(colorTextPrimary)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            },
            nameParams,
        )
        // Schließen: 48-dp-Touch-Ziel (HIG).
        val close = PlatformTextView(service).apply {
            text = "×" // Schließen-Glyph, universell
            setTextColor(colorTextSecondary)
            gravity = Gravity.CENTER
            textSize = 22f
            contentDescription = service.getString(R.string.assistant_panel_close_a11y)
            minWidth = dp(48, density)
            minHeight = dp(48, density)
        }
        close.setOnClickListener { hide() }
        row.addView(close)
        return row
    }

    private fun buildChipRow(density: Float): LinearLayout {
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 999 * density
                setColor(colorPanelElev)
            }
            setPadding(dp(12, density), dp(6, density), dp(4, density), dp(6, density))
        }
        val label = PlatformTextView(service).apply {
            setTextColor(colorTextSecondary)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        chipLabel = label
        row.addView(
            label,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        // Chip entfernen: Kontext bewusst abwählbar (kein Zwangs-Kontext).
        val remove = PlatformTextView(service).apply {
            text = "×"
            setTextColor(colorTextSecondary)
            gravity = Gravity.CENTER
            textSize = 16f
            contentDescription = service.getString(R.string.assistant_panel_chip_remove_a11y)
            minWidth = dp(48, density)
            minHeight = dp(48, density)
        }
        remove.setOnClickListener { updateContext(null) }
        row.addView(remove)
        return row
    }

    private fun buildMicButton(density: Float): View {
        val mic = PlatformButton(service).apply {
            text = service.getString(R.string.assistant_panel_mic)
            textSize = 14f
            isAllCaps = false
            contentDescription = service.getString(R.string.assistant_panel_mic_a11y)
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(colorPanelElev)
            }
            setTextColor(colorTextPrimary)
            minHeight = dp(48, density)
        }
        mic.setOnClickListener { startVoiceTurn() }
        return mic
    }

    private fun buildSendButton(density: Float): PlatformButton =
        PlatformButton(service).apply {
            text = service.getString(R.string.assistant_panel_send)
            textSize = 15f
            isAllCaps = false
            contentDescription = service.getString(R.string.assistant_panel_send_a11y)
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(colorEmber)
            }
            setTextColor(colorOnEmber)
            minHeight = dp(48, density)
            setPadding(0, dp(12, density), 0, dp(12, density))
            setOnClickListener { submitPanelText() }
        }

    // ── Aktionen ───────────────────────────────────────────────────────────

    /**
     * Senden aus dem Panel: Feld-Text als Prompt; leeres Feld + Seiten-
     * Kontext = Zusammenfass-Anfrage (ehrlicher Nutzen des Chips); leeres
     * Feld ohne Kontext = Knopf bleibt gesperrt (refreshSendState).
     */
    private fun submitPanelText() {
        val typed = inputField?.text?.toString()?.trim().orEmpty()
        val url = contextUrl
        val prompt: DeliveryPrompt? = when {
            typed.isNotEmpty() -> DeliveryPrompt(
                prompt = typed,
                contextUrl = url,
                chipLabel = PanelActionRules.chipLabel(url),
                autoSend = true,
            )
            url != null -> PanelActionRules.buildPrompt(
                ShareAction.SUMMARIZE_PAGE,
                SharePayload(subject = null, text = url, contextUrl = url),
            )
            else -> null
        }
        if (prompt == null) return
        AssistantShareBus.deliver(prompt)
        openChat()
        hide()
    }

    /**
     * Mikro-Turn: über die JVM-gepinnte Regel (MINI_PANEL → Voice-Turn, nie
     * ein Anruf aus dem eigenen Fenster) auf den BESTEHENDEN Sprach-Weg
     * (AssistLaunch.pendingVoice + Chat-Route), identisch zur G2-Blase und
     * zum AssistEntry-Trampoline.
     * HOOK-BEDARF (Lane A): das Call-Turn/STT-Upgrade (Anruf-Modus aus dem
     * Panel) gehört der Call-Lane — ChatScreen/CallScreen sind hier nicht
     * angefasst.
     */
    private fun startVoiceTurn() {
        val entry = AssistantEntryRules.resolve(
            source = AssistantEntryRules.Source.MINI_PANEL,
            roleHeld = false, // Eigenes Fenster — die Rolle ist irrelevant (Regel liest sie nicht).
            callAssistantMode = false,
        )
        if (entry != AssistantEntryRules.Entry.CHAT_VOICE_TURN) return
        com.hermesandroid.bridge.AssistLaunch.pendingVoice = true
        openChat()
        hide()
    }

    /** In die App (Chat-Route) wechseln — die Zustellung liegt im Bus. */
    private fun openChat() {
        val intent = Intent(service, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { service.startActivity(intent) }
    }

    /** Senden-Knopf-Zustand: nur mit Text ODER Seiten-Kontext wählbar. */
    private fun refreshSendState() {
        sendButton?.isEnabled = hasTypedText() || contextUrl != null
    }

    private fun hasTypedText(): Boolean =
        !inputField?.text?.toString().isNullOrBlank()

    /** Aktiver Agent (Anzeige-Name) für Avatar/Farb-Hash; null = App-Fallback. */
    private fun activeAgentName(): String? {
        val agents = runCatching { RelayClient.agents.value }.getOrDefault(emptyList())
        return agents.firstOrNull { it.online }?.displayName
            ?: agents.firstOrNull()?.displayName
    }

    private fun dp(value: Int, density: Float): Int = (value * density + 0.5f).toInt()
}

/**
 * G11: Share-Target-Empfangs-Dialog (ACTION_SEND, MainActivity). Drei
 * Aktionen, jede mit Ergebnis; Seiten-Aktionen ohne Link sind sichtbar
 * gesperrt mit Grund-Zeile (AKTIONEN-MIT-ERGEBNIS, kein toter Knopf).
 */
@Composable
fun AssistantShareDialog(
    payload: SharePayload?,
    onAction: (ShareAction) -> Unit,
    onDismiss: () -> Unit,
) {
    if (payload == null) return
    val url = payload.contextUrl

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.assistant_share_dialog_title))
        },
        text = {
            Column {
                // Vorschau des Geteilten (gekappt — der Dialog bleibt lesbar).
                val preview = if (payload.text.length > 400) {
                    payload.text.take(400) + " …"
                } else {
                    payload.text
                }
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlipsiColors.TextPrimary,
                )
                Spacer(Modifier.height(8.dp))
                if (url != null) {
                    // Kontext-Chip: Host der Seite als kurze, sichtbare Adresse.
                    Text(
                        stringResource(
                            R.string.assistant_share_chip_prefix,
                            PanelActionRules.chipLabel(url) ?: url,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.TextSecondary,
                    )
                } else {
                    Text(
                        stringResource(R.string.assistant_share_no_url),
                        style = MaterialTheme.typography.bodySmall,
                        color = FlipsiColors.SignalAmber,
                    )
                }
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ShareActionButton(
                    label = stringResource(R.string.assistant_share_action_send),
                    a11y = stringResource(R.string.assistant_share_action_send_a11y),
                    enabled = true,
                    onClick = { onAction(ShareAction.SEND_TO_AGENT) },
                )
                Spacer(Modifier.height(8.dp))
                ShareActionButton(
                    label = stringResource(R.string.assistant_share_action_ask),
                    a11y = stringResource(R.string.assistant_share_action_ask_a11y),
                    enabled = url != null,
                    onClick = { onAction(ShareAction.ASK_PAGE) },
                )
                Spacer(Modifier.height(8.dp))
                ShareActionButton(
                    label = stringResource(R.string.assistant_share_action_summarize),
                    a11y = stringResource(R.string.assistant_share_action_summarize_a11y),
                    enabled = url != null,
                    onClick = { onAction(ShareAction.SUMMARIZE_PAGE) },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        containerColor = FlipsiColors.PanelElev,
    )
}

/** Dialog-Aktions-Knopf: 48 dp hoch, Ember wählbar, gedimmt gesperrt. */
@Composable
private fun ShareActionButton(
    label: String,
    a11y: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            // A11y: Bedeutung nie nur über Farbe — die Zeile trägt die
            // Beschreibung mit (stringResource lebt nicht im semantics-Lambda,
            // deshalb als Parameter herein gereicht).
            .semantics { contentDescription = a11y },
        colors = ButtonDefaults.buttonColors(
            containerColor = FlipsiColors.Ember,
            contentColor = FlipsiColors.OnEmber,
            disabledContainerColor = FlipsiColors.PanelElev,
            disabledContentColor = FlipsiColors.TextSecondary,
        ),
    ) {
        Text(label)
    }
}
