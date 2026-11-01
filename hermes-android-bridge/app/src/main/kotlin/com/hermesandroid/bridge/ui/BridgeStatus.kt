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
 */
object BridgeStatus {

    private const val TAG = "BridgeStatus"

    data class ConnectionState(
        val connected: Boolean = RelayClient.isConnected,
        val message: String = RelayClient.currentStatusMessage,
        val authBroken: Boolean = RelayClient.isAuthBroken(),
        val serverUrl: String? = RelayClient.serverUrl,
    )

    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Version-Counter für Capability-/Berechtigungslisten (nach Aktionen bump). */
    private val _configVersion = MutableStateFlow(0)
    val configVersion: StateFlow<Int> = _configVersion.asStateFlow()

    fun init() {
        try {
            RelayClient.addStatusListener { _connected, message ->
                _state.value = ConnectionState(
                    connected = _connected,
                    message = message,
                    authBroken = RelayClient.isAuthBroken(),
                    serverUrl = RelayClient.serverUrl,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Status-Anmeldung fehlgeschlagen: ${e.message}")
        }
    }

    /** Capability-/Prefs-Änderungen → Listen neu bewerten. */
    fun refreshConfig() {
        _configVersion.value = _configVersion.value + 1
        _state.value = _state.value.copy(
            connected = RelayClient.isConnected,
            message = RelayClient.currentStatusMessage,
            authBroken = RelayClient.isAuthBroken(),
            serverUrl = RelayClient.serverUrl,
        )
    }

    /**
     * K5-Fix (Kobold): lokal, nur-UI-Hinweis (z. B. "Einstellung auf diesem
     * Gerät nicht verfügbar") OHNE RelayClient/Notification zu berühren —
     * erscheint in der Statuszeile des Screens bis zum nächsten Status-Event.
     */
    fun showText(text: String) {
        _state.value = _state.value.copy(message = text)
    }

    fun appVersion(): String = "v${BuildConfig.VERSION_NAME}"
}