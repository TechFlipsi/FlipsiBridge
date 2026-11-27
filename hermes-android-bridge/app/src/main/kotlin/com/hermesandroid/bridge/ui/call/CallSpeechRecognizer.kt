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
 * W22 A1 (P4-Rest, Anruf-Konzept Variante A): On-Device-Sprach-
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
 * - Fail-soft: RETRY_SILENT-Fehler (Stille/OEM-BUSY) starten neu — mit
 *   Restart-Treppe + Deckel (W22 A2 Fix 2: 300→600→1200→2400 ms, nach 5
 *   folgenlosen Restarts ehrlich [Callbacks.onFatal] → sichtbarer
 *   Push-to-Talk-Fallback, keine unbegrenzte Create/Destroy-Schleife);
 *   ein pendierendes Diktat-Partial geht beim Silent-Fehler NICHT lautlos
 *   verloren, sondern raus als Turn (Fix 4), und der Turn je Äußerung ist
 *   durch ein delivered-Flag doppelfest gegen Stille-Guard-vs-onResults
 *   (Fix 3). FATAL-Fehler (Netz/Audio/Server/Permission —
 *   [CallRules.sttErrorClass]) melden [Callbacks.onFatal] → die UI schaltet
 *   SICHTBAR auf Push-to-Talk (kein stiller Tod). Ein fehlender Provider ist
 *   kein Crash: gleicher Weg.
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

    /**
     * W22 A2 Fix 2: aufeinanderfolgende RETRY_SILENT-Restarts seit dem letzten
     * Erfolg (nur Main-Thread) — Erfolg (onBeginningOfSpeech/onResults) oder
     * [stop] setzen zurück; [CallRules.shouldEscalateStt] entscheidet die
     * ehrliche FATAL-Eskalation.
     */
    private var silentRetryAttempts = 0

    /**
     * W22 A2 Fix 3: wurde die AKTUELLE Äußerung schon als Turn geliefert? Der
     * Stille-Guard setzt sie beim Versand — ein später eintreffendes onResults
     * desselben Aussprache-Zyklus darf nicht noch einmal senden. Reset je
     * neuem Lausch-Zyklus ([startListeningOnMain]) und bei [stop].
     */
    private var turnDelivered = false

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
            // W22 A2 Fix 2/3: frisches Blatt — Retry-Kap-Schuld UND delivered-
            // Flag sind an eine Lebenszeit gebunden, nicht an den Prozess.
            silentRetryAttempts = 0
            turnDelivered = false
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
        // W22 A2 Fix 3: neuer Äußerungs-Zyklus → der Stille-Guard-vs-onResults-
        // Doppel-Turn-Schutz startet neu je Äußerung.
        turnDelivered = false
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
                // W22 A2 Fix 3: dieser Turn IST die Äußerung — ein danach noch
                // eintreffendes onResults desselben Zyklus darf keinen zweiten
                // Turn (Doppel-Send) erzeugen.
                turnDelivered = true
                callbacks.onTurn(turn)
            }
            if (active.get()) scheduleRestartOnMain()
        }, SILENCE_GUARD_MS)
    }

    // ── Listener (alle Events Main) ─────────────────────────────────────────

    private inner class CallListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {
            // W22 A2 Fix 2: echtes Sprechen = die Engine lebt — die Restart-
            // Treppen-Schuld (OEM-BUSY-Sturm VOR diesem Beginn) ist getilgt.
            silentRetryAttempts = 0
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
                    // W22 A2 Fix 4: ein diktiertes Partial wird NICHT lautlos
                    // verworfen — falls der OEM onResults nie liefert (Quirk),
                    // geht der letzte Partial als Turn raus (Stille-Guard-
                    // Semantik); das delivered-Flag verhindert den Doppel-Turn,
                    // falls onResults später doch noch kommt.
                    val turn = CallRules.sttTurnText(lastPartial)
                    lastPartial = ""
                    if (turn != null) {
                        turnDelivered = true
                        callbacks.onTurn(turn)
                    }
                    // W22 A2 Fix 2: Silent-Restarts MIT Treppen-Backoff und
                    // Kap — sonst OEM-BUSY = unbegrenzte ~3 Hz Create/Destroy-
                    // Schleife, in der die UI behauptet, weiter zu lauschen.
                    silentRetryAttempts++
                    if (CallRules.shouldEscalateStt(silentRetryAttempts)) {
                        // Ehrlich aufgeben → sichtbarer Push-to-Talk-Fallback.
                        active.set(false)
                        teardownOnMain()
                        callbacks.onListeningChanged(false)
                        callbacks.onFatal(error)
                    } else if (active.get()) {
                        scheduleRestartOnMain(
                            CallRules.nextRestartDelayMs(silentRetryAttempts - 1),
                        )
                    }
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
            // W22 A2 Fix 2: Erfolg — die Restart-Kap-Schuld ist getilgt.
            silentRetryAttempts = 0
            val text = textsFrom(results).firstOrNull()
            val turn = CallRules.sttTurnText(text)
            lastPartial = ""
            if (turn != null && !turnDelivered) {
                // W22 A2 Fix 3: der NEUE Turn gehört dieser onResults — senden
                // + frischen Lausch-Zyklus selbst übernehmen ...
                turnDelivered = true
                callbacks.onTurn(turn)
            }
            if (turnDelivered && turn == null) {
                // ... ABER ein FRÜHERER Weg in diesem Zyklus (Stille-Guard /
                // Silent-Fehler-Partial) hat Turn + Restart bereits erledigt:
                // dieses späte onResults darf weder doppelt senden noch einen
                // zweiten Lausch-Zyklus anstoßen (W22 A2 Fix 3).
                return
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
