package com.hermesandroid.bridge.ui.components

import com.hermesandroid.bridge.security.CapabilityGate

/**
 * Master-Schalter-Logik (v0.12.0) — NUR öffentliche CapabilityGate-API
 * (isEnabled/setEnabled). Der Bestätigungsdialog kommt im Screen; hier die
 * reinen Write-/Snapshot-Helfer (Robolectric-testbar).
 */
object CapabilityMaster {

    /** Schaltet alle ids an; invasive nur wenn includeInvasive (UI hat vorher bestätigt). */
    fun enableAll(ids: Collection<String>, includeInvasive: Boolean, isInvasive: (String) -> Boolean) {
        ids.filter { includeInvasive || !isInvasive(it) }
            .forEach { CapabilityGate.setEnabled(it, true) }
    }

    /** Schaltet alles aus und liefert den Snapshot für Undo. */
    fun disableAll(ids: Collection<String>): Map<String, Boolean> =
        ids.associateWith { CapabilityGate.isEnabled(it) }
            .also { ids.forEach { id -> CapabilityGate.setEnabled(id, false) } }

    /** Undo: stellt den Zustand vor dem Master-OFF wieder her (mit Frische-Check —
      * bewusste Änderungen im Snackbar-Fenster werden NICHT clobbered, SK-04). */
    fun restore(snapshot: Map<String, Boolean>) =
        snapshot.forEach { (id, on) ->
            if (!CapabilityGate.isEnabled(id)) CapabilityGate.setEnabled(id, on)
        }
}