package com.hermesandroid.bridge

import android.app.Application
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.model.DeviceCapabilities
import com.hermesandroid.bridge.power.WakeLockManager
import com.hermesandroid.bridge.server.BridgeServer
import com.hermesandroid.bridge.server.ServerProfiles

class BridgeApplication : Application() {
    companion object {
        lateinit var instance: BridgeApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        PairingManager.init(applicationContext)
        DeviceCapabilities.init(applicationContext)
        WakeLockManager.init(applicationContext)
        // FlipsiBridge-Fix (v0.9.2): CapabilityGate initialisieren — ohne init() ist prefs null,
        // alle Schalter lesen/kein Speichern (Alles nach App-Neustart wieder AUS, Gate lahmgelegt).
        com.hermesandroid.bridge.security.MtlsSupport.init(applicationContext)
        com.hermesandroid.bridge.security.CapabilityGate.init(applicationContext)
        BridgeServer.start(port = 8765)

        // Initialize relay client and auto-connect if previously configured
        RelayClient.init(applicationContext)
        // W4-D (Lane D): Profil-Store NACH RelayClient.init (Migration liest
        // serverUrl) und VOR autoConnect (Letzt-Verbunden-Tracker braucht die
        // Anmeldung vor dem ersten onOpen) — A6 legt ihn an, A7 verwaltet.
        ServerProfiles.init(applicationContext)
        // v0.11.0: UI-Status-Quelle (StateFlows) nach RelayClient-Init anmelden;
        // hält KEINE Activity-Referenz (Leak-Fix R11).
        com.hermesandroid.bridge.ui.BridgeStatus.init()
        RelayClient.autoConnect()
    }
}
