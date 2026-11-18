package com.hermesandroid.bridge.ui.call

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.hermesandroid.bridge.client.CallRules
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * W22 A1 (P4-Rest, roadmap-anruf.md §3.2 Variante A): On-Device-Sprach-
 * erkennung im AKTIVEN Anruf. Kapselt android.speech.SpeechRecognizer
 * komplett — die Call-Bindung in ChatScreen startet/stoppt und konsumiert
 * nur die Rückwege:
 *
 * - Freihand-Modus: Erkennung läuft CONTINUERLICH (nach jedem Turn neu
 *   gestartet), endet bei Stille von selbst (onEndOfSpeech/onResults) →
 *   finaler Text geht als Chat-TEXT-Turn im Call-Kontext raus (§3.2 A:
 *   „Ton verlässt das Handy NICHT“ — NICHT die Audio-Chunk-E2E-Pipeline,
 *   die bleibt als Push-to-Talk-Fallback im CallScreen).
 * - Partial-Results-Overlay: onPartialResults fließen live in die Status-
 *   Zeile des Call-Strips (Live-Transkript, WhatsApp-Diktier-Gedanke).
 * - Stille-Timer: onEndOfSpeech ohne nachfolgende onResults (OEM-Quirk)
 *   liefert nach [SILENCE_GUARD_MS] den letzten Partial als Turn und
 *   startet neu — kein hängender Freihand-Zustand.
 * - Lifecycle-Cancel bei Call-Ende: [stop] räumt die Instanz; die Call-
 *   Bindung ruft es bei JEDEM Phasenwechsel weg von ACTIVE.
 * - Fail-soft: RETRY_SILENT-Fehler (Stille/OEM-BUSY) starten neu; FATAL-
 *   Fehler (Netz/Audio/Server/Permission — [CallRules.sttErrorClass])
 *   melden [Callbacks.onFatal] → die UI schaltet SICHTBAR auf Push-to-Talk
 *   (kein stiller Tod). Ein fehlender Provider ist kein Crash: gleicher Weg.
 *
 * Privatsphäre-Gate (Plan Z.38): Freihand läuft NUR, wenn
 * [handsFreeSupported] gilt — ab API 31 VERIFIZIERBAR on-device
 * (createOnDeviceSpeechRecognizer); darunter bleibt Freihand bewusst AUS
 * („on-device“ wäre nur behauptet, der Ton könnte zum Cloud-Provider gehen).
 * Emulator ohne Provider = handsFreeSupported false → Push-to-Talk-Welt.
 *
 * Threading: SpeechRecognizer verlangt den Main-Looper — start/stop posten
 * auf Main, ALLE Callbacks kommen auf Main an (Compose-State-Schreibungen
 * sind damit frei von Thread-Wechseln). Der Listener hängt in einer
 * [AtomicReference] (COMPILE-KLASSE (a): Callback-Holder, austauschbar ohne
 * Race gegen teardown).
 */
class CallSpeechRecognizer(
    context: Context,
    private val callbacks: Callbacks,
) {

    /** UI-Rückwege (alle auf dem Main-Thread — siehe Klassendoku). */
    interface Callbacks {
        /** Live-Transkript-Zwischenstand (Partial-Results-Overlay). */
        fun onPartial(text: String) {}

        /** Finaler Turn (End-of-Speech erkannt) — senden obliegt der Bindung. */
        fun onTurn(text: String) {}

        /** Lauscht die Erkennung gerade? (Pillen-Status + Partial-Overlay). */
        fun onListeningChanged(listening: Boolean) {}

        /**
         * Endgültiges Scheitern (errorCode = SpeechRecognizer.ERROR_* oder
         * [FATAL_NO_ENGINE]) — die Bindung schaltet sichtbar auf Push-to-Talk.
         */
        fun onFatal(errorCode: Int) {}
    }

    companion object {
        /** Kein Provider/Engine konstruierbar (Beweis-Wert für onFatal). */
        const val FATAL_NO_ENGINE = -1

        /**
         * Stille-Beweis-Fenster nach onEndOfSpeech: manche OEM-Ketten feuern
         * onResults NIE (Quirk) — nach dieser Frist gilt der letzte Partial
         * als Turn (oder Stille → Neustart), der Freihand-Zustand hängt nie.
         */
        const val SILENCE_GUARD_MS = 1_500L

        /** Neulausch-Verzögerung nach NO_MATCH/BUSY (kein Buzz-Loop). */
        const val RESTART_DELAY_MS = 300L

        /**
         * A1-Verfügbarkeits-Gate (33er-Default „Ton verlässt Handy NICHT“):
         * ab API 31 prüfbar und NUR dann echt on-device. Xiaomi/HyperOS mit
         * Google-Speech-Provider erfüllt das i. d. R.; Emulatoren ohne
         * Provider ehrlich false → Push-to-Talk-Welt mit Hinweis.
         */
        fun handsFreeSupported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            }.getOrDefault(false)
    }

    private val appContext = context.applicationContext
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val active = AtomicBoolean(false)

    /** Listener-Holder (COMPILE-KLASSE (a)) — teardown tauscht frei von Race. */
    private val listenerRef = AtomicReference<RecognitionListener?>(null)

    /** Instanz-Holder — gleiche Race-Freiheit für die Erkennungs-Räumung. */
    private val recognizerRef = AtomicReference<SpeechRecognizer?>(null)

    /** Letzter Partial-Stand (nur Main-Thread). */
    private var lastPartial: String = ""

    /** onEndOfSpeech-Zeitpunkt (0 = keine offene Stille-Phase; nur Main). */
    private var endOfSpeechAtMs: Long = 0L

    /** Läuft die Freihand-Erkennung gerade (Lifecycle-Spiegel für die Bindung)? */
    val isRunning: Boolean get() = active.get()

    /** Freihand-Erkennung starten (idempotent; effect auf dem Main-Looper). */
    fun start() {
        if (!active.compareAndSet(false, true)) return
        mainHandler.post { startListeningOnMain() }
    }

    /**
     * Erkennung stoppen + Instanz räumen — Lifecycle-Cancel bei Call-Ende,
     * Modus-Wechsel oder Fehler. Idempotent von jedem Thread rufbar.
     */
    fun stop() {
        active.set(false)
        mainHandler.post {
            teardownOnMain()
            endOfSpeechAtMs = 0L
            lastPartial = ""
            callbacks.onListeningChanged(false)
        }
    }

    // ── Interner Bau/Start (alles Main) ─────────────────────────────────────

    private fun listeningIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Geräte-Locale als Sprache (wie der Nutzer tippt/spricht) — NICHT
            // die App-Sprache: Diktieren folgt der Sprechsprache.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
        }

    /** Frische Instanz + StartListening (Alte vorher räumen — BUSY-Quirk). */
    private fun startListeningOnMain() {
        if (!active.get()) return
        teardownOnMain()
        lastPartial = ""
        endOfSpeechAtMs = 0L
        val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // On-Device-PFLICHT-Weg (Z.38); Fallback createSpeechRecognizer nur
            // wenn der on-device-Konstruktor auf dem Gerät fehlt (Hersteller-
            // Split) — handsFreeSupported hat die on-device-Fähigkeit VORHER
            // geprüft, ein Netz-Rückfall meldet sich fail-soft über onError.
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) }
                .getOrNull()
                ?: runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrNull()
        } else {
            runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrNull()
        }
        if (recognizer == null) {
            active.set(false)
            callbacks.onListeningChanged(false)
            callbacks.onFatal(FATAL_NO_ENGINE)
            return
        }
        val listener = CallListener()
        listenerRef.set(listener)
        runCatching { recognizer.setRecognitionListener(listener) }
        recognizerRef.set(recognizer)
        val started = runCatching { recognizer.startListening(listeningIntent()) }.isSuccess
        if (!started) {
            teardownOnMain()
            active.set(false)
            callbacks.onListeningChanged(false)
            callbacks.onFatal(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        callbacks.onListeningChanged(true)
    }

    private fun teardownOnMain() {
        val recognizer = recognizerRef.getAndSet(null)
        listenerRef.set(null)
        if (recognizer != null) {
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }

    /** Neulausch nach Turn/Fehler — frische Instanz (BUSY-Quirk umgangen). */
    private fun scheduleRestartOnMain(delayMs: Long = RESTART_DELAY_MS) {
        mainHandler.postDelayed({
            if (active.get()) startListeningOnMain()
        }, delayMs)
    }

    /** Stille-Timer: nach onEndOfSpeech ohne onResults rettet der Partial. */
    private fun scheduleSilenceGuardOnMain() {
        mainHandler.postDelayed({
            if (!active.get() || endOfSpeechAtMs == 0L) return@postDelayed
            val partial = lastPartial
            lastPartial = ""
            endOfSpeechAtMs = 0L
            val turn = CallRules.sttTurnText(partial)
            if (turn != null) {
                callbacks.onTurn(turn)
            }
            if (active.get()) scheduleRestartOnMain()
        }, SILENCE_GUARD_MS)
    }

    // ── Listener (alle Events Main) ─────────────────────────────────────────

    private inner class CallListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {
            // Barge-in-Signal §3.1: der Agenten-TTS wird in der Bindung über
            // onPartial gestoppt (nicht-leerer Partial = Nutzer redet) — hier
            // bewusst KEIN weiterer Hook (ein leeres Räuspern soll die Antwort
            // nicht kappen).
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            endOfSpeechAtMs = System.currentTimeMillis()
            scheduleSilenceGuardOnMain()
        }

        override fun onError(error: Int) {
            when (CallRules.sttErrorClass(error)) {
                // Stille/OEM-Quirk — kein Modus-Wechsel, nur neu lauschen.
                CallRules.SttErrorClass.RETRY_SILENT -> {
                    endOfSpeechAtMs = 0L
                    lastPartial = ""
                    if (active.get()) scheduleRestartOnMain()
                }
                // Ehrlich aufgeben → sichtbarer Push-to-Talk-Fallback.
                CallRules.SttErrorClass.FATAL -> {
                    active.set(false)
                    teardownOnMain()
                    callbacks.onListeningChanged(false)
                    callbacks.onFatal(error)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            endOfSpeechAtMs = 0L
            val text = textsFrom(results).firstOrNull()
            val turn = CallRules.sttTurnText(text)
            lastPartial = ""
            if (turn != null) {
                callbacks.onTurn(turn)
            }
            if (active.get()) scheduleRestartOnMain()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = textsFrom(partialResults).firstOrNull() ?: ""
            lastPartial = text
            if (text.isNotBlank()) {
                callbacks.onPartial(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun textsFrom(bundle: Bundle?): List<String> {
        if (bundle == null) return emptyList()
        return runCatching {
            bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        }.getOrDefault(emptyList())
    }
}
