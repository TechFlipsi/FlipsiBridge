package com.hermesandroid.bridge.ui.chat

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    private val synthLock = Any()

    /** Utterance-Präfix der Datei-Synthese (nie mit den Chip-IDs verwechseln). */
    private const val SYNTH_PREFIX = "ttsfile-"
    /** App-privater Cache je msgId (W16 B2: Agenten-Antwort als Audio-Datei). */
    private const val SYNTH_DIR = "voice_tts"

    /** Callbacks der laufenden/erwarteten Synthesen je msgId + Ziel-Dateien. */
    private val synthCallbacks = HashMap<Long, MutableList<(String?) -> Unit>>()
    private val synthTargets = HashMap<Long, File>()
    private val mainHandler = Handler(Looper.getMainLooper())

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
            // W16-Fix (Compile 05:15): der Init-Listener darf sein eigenes Val
            // nicht direkt schließen (Kotlin: Unresolved) — Holder-Ref, gesetzt
            // nach dem Konstruktor; im Callback NUR über das Handle.
            val engineRef = java.util.concurrent.atomic.AtomicReference<TextToSpeech?>(
                null,
            )
            val engine = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    // Deutsch als Zielsprache; fehlende Sprachdaten = ehrlicher
                    // Fail statt Kauderwelsch-Rate. W16 B2: SPEECH-Attribute auch
                    // für die Datei-Synthese (Agenten-Antwort als Audio-Datei).
                    val handle = engineRef.get()
                    val result = if (handle != null) runCatching {
                        handle.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                        // Handle statt tts?: der Init-Callback kann NACH dem Assign
                        // an tts= laufen — das lokale Engine-Handle ist immer da.
                        handle.setLanguage(Locale.GERMANY)
                    }.getOrNull() else null
                    stateFlow.value = when (result) {
                        TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED ->
                            ChatSpeechState.Failed("Keine deutschen Sprachdaten installiert — Vorlesen nicht möglich")
                        else -> ChatSpeechState.Ready
                    }
                } else {
                    stateFlow.value = ChatSpeechState.Failed("Keine Sprachausgabe-Engine gefunden — Vorlesen nicht verfügbar")
                }
            }
            engineRef.set(engine)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // Datei-Synthese hat keine Chip-Wirkung — nur Vorlese-IDs.
                    if (utteranceId?.startsWith(SYNTH_PREFIX) == true) return
                    // Speaking-Chip zeigen, sobald die Engine wirklich spricht
                    // (nicht schon beim Einreihen — kein Phantom-Chip).
                    utteranceId?.toLongOrNull()?.let { id ->
                        speakingFlow.value = speakingFlow.value + id
                    }
                }

                override fun onDone(utteranceId: String?) {
                    // W16 B2: Datei-Synthese → Callbacks mit dem Dateipfad füttern.
                    if (utteranceId?.startsWith(SYNTH_PREFIX) == true) {
                        val messageId = utteranceId.removePrefix(SYNTH_PREFIX).toLongOrNull()
                        if (messageId != null) {
                            val file = synchronized(synthLock) { synthTargets[messageId] }
                            val produced = file
                                ?.takeIf { it.exists() && it.length() > 0 }
                                ?.absolutePath
                            deliverSynthResult(messageId, produced)
                        }
                        return
                    }
                    utteranceId?.toLongOrNull()?.let { id ->
                        speakingFlow.value = speakingFlow.value - id
                    }
                }

                @Deprecated("Deprecated in Android-API — neuer Pfad onError(id, errorCode)")
                override fun onError(utteranceId: String?) {
                    // Auch Fehler räumen den Chip — nie ein hängender Chip.
                    if (utteranceId?.startsWith(SYNTH_PREFIX) == true) {
                        utteranceId.removePrefix(SYNTH_PREFIX).toLongOrNull()?.let { messageId ->
                            deliverSynthResult(messageId, null)
                        }
                        return
                    }
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
        // W16 B2 (Fixliste 31): EIN Audio-Pfad je App — Bubble-Wiedergabe
        // räumen, bevor das Vorlesen ansetzt (keine zwei parallelen Spuren).
        ChatVoicePlayback.stop()
        // Reihenfolge bewusst: erst Engine prüfen, dann sprechen — ein
        // enqueue während Initializing verwirft der Engine intern sauber
        // (speak vor OnInit liefert ERROR an den Listener → Chip nie falsch).
        runCatching {
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, messageId.toString())
        }
    }

    /**
     * W16 B2 (Fixliste 31, Agenten-Richtung): die Agenten-Antwort als
     * AUDIO-DATEI erzeugen (TextToSpeech.synthesizeToFile) — die WA-Voice-
     * Bubble ([VoiceBubbleAgentTts] in VoiceBubbleWidget.kt) spielt sie danach
     * wie eine echte Sprachnachricht (Wellenform + Dauer aus dem Audio, keine
     * Deko-Fakes). Ergebnis je msgId app-privat gecachet (cache/voice_tts/,
     * Galerie-Verbot unberührt) — erneutes Play ist sofort bereit.
     *
     * [onReady] = Main-Thread-Callback; null = Engine fehl/Datei nicht
     * erzeugt (die Bubble zeigt den ehrlichen Nicht-verfügbar-Text).
     * Mehrere Anfragen je msgId sind erlaubt: alle Callbacks feuern einmal.
     */
    fun synthesizeMessageFile(
        context: Context,
        messageId: Long,
        text: String,
        onReady: (String?) -> Unit,
    ) {
        if (text.isBlank()) {
            onReady(null)
            return
        }
        val dir = File(context.cacheDir, SYNTH_DIR)
        val target = File(dir, "tts-$messageId.wav")
        if (target.exists() && target.length() > 0) {
            onReady(target.absolutePath)
            return
        }
        ensure(context)
        if (stateFlow.value is ChatSpeechState.Failed) {
            onReady(null)
            return
        }
        val engine = tts
        if (engine == null) {
            onReady(null)
            return
        }
        synchronized(synthLock) {
            synthTargets[messageId] = target
            synthCallbacks.getOrPut(messageId) { mutableListOf() }.add(onReady)
        }
        val started = runCatching {
            dir.mkdirs()
            // Alte Teildatei löschen (Neu-Erzeugung gewinnt, kein gemischter Stand).
            target.delete()
            engine.synthesizeToFile(
                text,
                Bundle(),
                target,
                "$SYNTH_PREFIX$messageId",
            )
        }.getOrDefault(TextToSpeech.ERROR)
        // Sofortiger Start-Fail (z. B. Synthese abgewiesen): Callbacks ehrlich
        // beenden statt sie an der Registrierung zu binden.
        if (started != TextToSpeech.SUCCESS) {
            deliverSynthResult(messageId, null)
        }
    }

    /** Synthese-Ergebnis je msgId ausliefern (Callbacks räumen, Main-Thread). */
    private fun deliverSynthResult(messageId: Long, path: String?) {
        val callbacks: List<(String?) -> Unit> = synchronized(synthLock) {
            synthTargets.remove(messageId)
            synthCallbacks.remove(messageId).orEmpty()
        }
        if (callbacks.isEmpty()) return
        mainHandler.post {
            callbacks.forEach { callback ->
                runCatching { callback(path) }
            }
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
        // W16 B2: wartende Synthese-Callbacks ehrlich beenden (kein Hängen).
        val pending: List<Pair<Long, List<(String?) -> Unit>>> = synchronized(synthLock) {
            val drained = synthCallbacks.map { it.key to it.value.toList() }
            synthCallbacks.clear()
            synthTargets.clear()
            drained
        }
        pending.forEach { (messageId, callbacks) ->
            mainHandler.post {
                callbacks.forEach { callback ->
                    runCatching { callback(null) }
                }
            }
        }
    }
}
