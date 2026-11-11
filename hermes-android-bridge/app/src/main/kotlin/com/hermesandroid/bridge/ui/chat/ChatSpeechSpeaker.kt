package com.hermesandroid.bridge.ui.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * WELLE-13 P9 (Sir-Wunsch: das HANDY spricht): Vorlesen von Agenten-Antworten
 * über die Android-System-TextToSpeech — bewusst GERÄTE-seitig (kein Server-
 * Zwang, funktioniert offline, Sprachqualität = Systemstimme des Geräts;
 * Entscheidung im Fixplan Welle 13).
 *
 * [ChatSpeechPrefs] = je-Agent-Schalter („vorlesen_<agentId>“, SharedPreferences)
 * — angeschaltet (ChatModelSheet-Regler) liest das Gerät jede NEUE Agenten-
 * Antwort dieser Conversation vor.
 *
 * [ChatSpeechSpeaker] = App-weites SINGLETON um EINE TextToSpeech-Instanz:
 * - Queue statt Überlappung: [enqueue] mit QUEUE_ADD — die nächste Antwort
 *   wartet, bis die laufende fertig ist (kein Durcheinander-Sprechen).
 * - Stopp: [stopAll] bricht ALLES ab (Speaking-Chip-Tap je Bubble); der
 *   Chip verschwindet, weil die Utterance-Callbacks die IDs räumen.
 * - [speakingIds] = StateFlow der gerade/nächst vorgelesenen Bubble-IDs →
 *   die Bubbles zeigen den Speaking-Chip zustandsgesteuert.
 * - Ehrlicher Engine-Fail-Hinweis: Init- oder Sprachdaten-Fehler landen in
 *   [state] (ChatSpeechState.Failed) — das ChatModelSheet zeigt den Text,
 *   statt still nichts vorzulesen (Checkliste K: kein stiller Fehler).
 *
 * Keine privaten Daten: gesprochen wird nur der bereits empfangene Antwort-
 * Text des Agenten; es fließt nichts an Dritte.
 */
object ChatSpeechPrefs {
    private const val FILE = "chat_speech"

    /**
     * Vorlesen-Schalter je Agent (vorlesen_<agentId>). Demo-Einträge (blank
     * agentId) bleiben grundsätzlich aus — nie ein Fake-Zustand ohne Backend.
     */
    fun enabled(context: Context, agentId: String): Boolean {
        if (agentId.isBlank()) return false
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean("vorlesen_$agentId", false)
    }

    /** Schalter persistieren (pro Agent); [enabled] = false räumt den Schlüssel. */
    fun setEnabled(context: Context, agentId: String, enabled: Boolean) {
        if (agentId.isBlank()) return
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (enabled) putBoolean("vorlesen_$agentId", true)
            else remove("vorlesen_$agentId")
        }.apply()
    }
}

/** Zustand der TTS-Engine (ehrlich statt still: Failed trägt den Grund). */
sealed interface ChatSpeechState {
    /** Noch nie angefordert — Engine bewusst nicht gestartet (kein unnötiger Dienst). */
    data object Idle : ChatSpeechState

    /** Engine-Initialisierung läuft (erster Vorlesen-Wunsch oder Regler-An). */
    data object Initializing : ChatSpeechState

    /** Engine bereit — enqueue() spricht. */
    data object Ready : ChatSpeechState

    /** Engine fehlgeschlagen: [reason] wird der UI-Text (Checkliste K). */
    data class Failed(val reason: String) : ChatSpeechState
}

/**
 * WELLE-13 P9: TTS-Singleton — genau EINE TextToSpeech-Instanz für die
 * ganze App (App-Context, kein Activity-Leak), Queue-Verwaltung und
 * Utterance-Callbacks. Thread-sicher: die Engine-Callbacks kommen vom
 * Binder-Thread, [enqueue]/[stopAll] vom Main-Thread.
 */
object ChatSpeechSpeaker {

    @Volatile
    private var tts: TextToSpeech? = null

    private val lock = Any()

    /** Zustand der Engine — Idle bis zur ersten Anforderung (sparsam). */
    private val stateFlow = MutableStateFlow<ChatSpeechState>(ChatSpeechState.Idle)
    val state: StateFlow<ChatSpeechState> get() = stateFlow

    /** Bubble-IDs, die gerade/nächst vorgelesen werden (Speaking-Chips). */
    private val speakingFlow = MutableStateFlow<Set<Long>>(emptySet())
    val speakingIds: StateFlow<Set<Long>> get() = speakingFlow

    /**
     * Engine lazyl anlegen (einmal je Prozess). Der App-Context verhindert
     * Activity-Leaks; ein zweiter Aufruf ist ein No-op.
     */
    fun ensure(context: Context) {
        if (tts != null) return
        synchronized(lock) {
            if (tts != null) return
            stateFlow.value = ChatSpeechState.Initializing
            val engine = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    // Deutsch als Zielsprache; fehlende Sprachdaten = ehrlicher
                    // Fail statt Kauderwelsch-Rate.
                    val result = runCatching {
                        tts?.setLanguage(Locale.GERMANY)
                    }.getOrNull()
                    stateFlow.value = when (result) {
                        TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED ->
                            ChatSpeechState.Failed("Keine deutschen Sprachdaten installiert — Vorlesen nicht möglich")
                        else -> ChatSpeechState.Ready
                    }
                } else {
                    stateFlow.value = ChatSpeechState.Failed("Keine Sprachausgabe-Engine gefunden — Vorlesen nicht verfügbar")
                }
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // Speaking-Chip zeigen, sobald die Engine wirklich spricht
                    // (nicht schon beim Einreihen — kein Phantom-Chip).
                    utteranceId?.toLongOrNull()?.let { id ->
                        speakingFlow.value = speakingFlow.value + id
                    }
                }

                override fun onDone(utteranceId: String?) {
                    utteranceId?.toLongOrNull()?.let { id ->
                        speakingFlow.value = speakingFlow.value - id
                    }
                }

                @Deprecated("Deprecated in Android-API — neuer Pfad onError(id, errorCode)")
                override fun onError(utteranceId: String?) {
                    // Auch Fehler räumen den Chip — nie ein hängender Chip.
                    utteranceId?.toLongOrNull()?.let { id ->
                        speakingFlow.value = speakingFlow.value - id
                    }
                }
            })
            tts = engine
        }
    }

    /**
     * Eine Agenten-Antwort einreihen: QUEUE_ADD = sie wartet hinter einer
     * laufenden Vorlese-Ausgabe (Queue statt Überlappung). Blank-Text und
     * Failed-Engine werden ehrlich STILL verworfen — der Grund steht im
     * ChatModelSheet (state-Flow), nicht als Toast-Geplapper.
     * [messageId] = Bubble-ID → Speaking-Chip-Bindung über [speakingIds].
     */
    fun enqueue(context: Context, messageId: Long, text: String) {
        if (text.isBlank()) return
        ensure(context)
        val engine = tts ?: return
        if (stateFlow.value is ChatSpeechState.Failed) return
        // Reihenfolge bewusst: erst Engine prüfen, dann sprechen — ein
        // enqueue während Initializing verwirft der Engine intern sauber
        // (speak vor OnInit liefert ERROR an den Listener → Chip nie falsch).
        runCatching {
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, messageId.toString())
        }
    }

    /**
     * Alles stoppen (Speaking-Chip-Tap): laufende Ausgabe bricht ab, die
     * Queue wird geleert, alle Chips verschwinden über die Callbacks.
     */
    fun stopAll() {
        runCatching { tts?.stop() }
        speakingFlow.value = emptySet()
    }

    /** Aufräumen (App-Ende/Tests) — danach startet ensure() neu. */
    fun shutdown() {
        synchronized(lock) {
            runCatching {
                tts?.stop()
                tts?.shutdown()
            }
            tts = null
            stateFlow.value = ChatSpeechState.Idle
            speakingFlow.value = emptySet()
        }
    }
}
