package com.hermesandroid.bridge.ui

import android.util.Log
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.client.RelayClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Verbindungs-Zustand als einzige UI-Quelle (MVVM-lite für die Utility-App):
 * RelayClient meldet; dieses Objekt hält StateFlows, die Compose sammelt.
 *
 * In BridgeApplication.onCreate einmal via init() angemeldet — die Activity
 * (Compose) hält KEINE Referenz mehr auf sich selbst im RelayClient-Rückruf
 * (Review-Fix R11: der frühere onStatusChanged-Einzel-Slot hielt die
 * Activity-Referenz und wurde nie aufgeräumt).
 *
 * PH1-T3 (v0.12.5): ConnectionState ist jetzt ein sealed interface — die UI
 * matcht auf dem ZUSTAND (exhaustive when), nicht mehr auf Status-Texten.
 * Die frühere String-Grep-Route (message.contains("Verbinde") …) ist komplett
 * raus; RelayClient meldet die Phase strukturiert (RelayClient.Phase), und
 * RECONNECTING/FAILED tragen die Ursache als Anzeigetext mit.
 */
object BridgeStatus {

    private const val TAG = "BridgeStatus"

    /**
     * Strukturierte Verbindungs-Zustände der UI. Server-Adresse und Anzeige-
     * Text leben in eigenen Flows (kein State-Bestandteil).
     */
    sealed interface ConnectionState {
        /** Keine Verbindung, keine laufende Verbindungs-Schleife. */
        data object IDLE : ConnectionState

        /** Verbindungsaufbau läuft (erster Versuch). */
        data object CONNECTING : ConnectionState

        /** WebSocket-Sitzung steht. */
        data object CONNECTED : ConnectionState

        /** Wiederverbindungs-Schleife läuft; [ursache] = letzter Anlass-Text. */
        data class RECONNECTING(val ursache: String) : ConnectionState

        /** Kopplungscode wurde abgelehnt (403-Stopp) — Neu-Kopplung nötig. */
        data object PAIRING_BROKEN : ConnectionState

        /** Verbindungsaufbau endgültig gescheitert; [ursache] = Fehler-Text. */
        data class FAILED(val ursache: String) : ConnectionState
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.IDLE)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Server-Adresse (füllt das Eingabefeld der Verbindungskarte). */
    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    /** Anzeige-Text zur Verbindung: letzte Relay-Meldung ODER lokaler Hinweis. */
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    /** Version-Counter für Capability-/Berechtigungslisten (nach Aktionen bump). */
    private val _configVersion = MutableStateFlow(0)
    val configVersion: StateFlow<Int> = _configVersion.asStateFlow()

    /** Phase-Enum des RelayClient → sealed State (exhaustive, ohne else). */
    private fun stateOf(phase: RelayClient.Phase, message: String): ConnectionState = when (phase) {
        RelayClient.Phase.IDLE -> ConnectionState.IDLE
        RelayClient.Phase.CONNECTING -> ConnectionState.CONNECTING
        RelayClient.Phase.CONNECTED -> ConnectionState.CONNECTED
        RelayClient.Phase.RECONNECTING -> ConnectionState.RECONNECTING(message)
        RelayClient.Phase.PAIRING_BROKEN -> ConnectionState.PAIRING_BROKEN
        RelayClient.Phase.FAILED -> ConnectionState.FAILED(message)
    }

    fun init() {
        try {
            RelayClient.addStatusListener { _connected, message ->
                // notifyStatus setzt currentPhase VOR dem Hörer-Post — hier
                // ist die Phase also bereits die gemeldete.
                _state.value = stateOf(RelayClient.currentPhase, message)
                _message.value = message
                _serverUrl.value = RelayClient.serverUrl
            }
        } catch (e: Exception) {
            Log.w(TAG, "Status-Anmeldung fehlgeschlagen: ${e.message}")
        }
    }

    /** Capability-/Prefs-Änderungen → Listen neu bewerten. */
    fun refreshConfig() {
        _configVersion.value = _configVersion.value + 1
        _state.value = stateOf(RelayClient.currentPhase, RelayClient.currentStatusMessage)
        _message.value = RelayClient.currentStatusMessage
        _serverUrl.value = RelayClient.serverUrl
    }

    /**
     * K5-Fix (Kobold): lokal, nur-UI-Hinweis (z. B. "Einstellung auf diesem
     * Gerät nicht verfügbar") OHNE RelayClient/Notification zu berühren —
     * erscheint in der Statuszeile des Screens bis zum nächsten Status-Event.
     * (PH1-T3: schreibt nur den Anzeige-Text, der Zustand bleibt wahr.)
     */
    fun showText(text: String) {
        _message.value = text
    }

    /**
     * Versionsanzeige = NUR „vX.Y.Z“ (Welle 13 / P2b + QA A): der Editions-
     * Suffix (-play/-github) aus versionNameSuffix fällt in der UI weg —
     * gleiche Reinigung wie im About-Sheet (AboutInfo.versionLabel).
     */
    fun appVersion(): String =
        "v${BuildConfig.VERSION_NAME.substringBefore("-play").substringBefore("-github")}"
}