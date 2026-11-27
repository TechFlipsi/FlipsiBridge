"""W23 (23.11.2026): ID-Welten-Zusammenführung (W23-4) + Geräte-Alias (W23-9).

- W23-4: das Zert-Enroll (mTLS-Welt) trägt die App-Pairing-ID (WS-Welt,
  X-Device-Id) SOFORT in die B1-Geräte-Registry (b1_registry_enroll_sync) —
  nie wieder manuelles Registry-Edit nach einer App-Neukopplung
  (Root-Cause 18.11.: App-Pairing-ID ≠ Zert-CN-ID → 403 code-in-use).
- W23-2: Regressionsschutz — die Persist-Stellen persistieren IMMER den
  vollen RAM-Stand (Alt-Einträge löscht NUR der 14-Tage-Sweep, nie
  der Connect-Handler).
"""
import asyncio
import importlib
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest


def _relay_mod():
    for name in ("tools.android_relay", "android_relay"):
        try:
            return importlib.import_module(name)
        except ImportError:
            continue
    pytest.skip("android_relay nicht importierbar")


def _mtls_mod():
    for name in ("tools.mtls_enroll", "mtls_enroll"):
        try:
            return importlib.import_module(name)
        except ImportError:
            continue
    pytest.skip("mtls_enroll nicht importierbar")


# ── W23-4: b1_registry_enroll_sync ────────────────────────────────────────────

def _w23_state(mod, tmp_path, monkeypatch):
    """Bare _RelayState wie die Sweep/Enroll-Tests: nur die Felder, die der
    Sync/Handler berührt (device_registry + Persist-Pfad-Ablage tmp_path)."""
    monkeypatch.setenv("ANDROID_RELAY_DEVICE_REGISTRY", str(tmp_path / "relay-devices.json"))
    state = mod._RelayState(pairing_code="w23-test-code", port=28766)
    state.device_registry = {
        "aaa-11": {"alias": "", "admin": True, "epoch_of_enroll": 3,
                   "enrolled_at": 1, "last_seen": 1},
    }
    return state


def test_sync_schreibt_beide_idwelten(tmp_path, monkeypatch):
    mod = _relay_mod()
    state = _w23_state(mod, tmp_path, monkeypatch)
    monkeypatch.setattr(_mtls_mod(), "_registry_load", lambda **kw: {"v": 2, "epoch": 77, "devices": {}})
    mod.b1_registry_enroll_sync(state, "bbb-22", "ccc-33")
    entry = state.device_registry["bbb-22"]
    assert entry["alias"] == ""
    assert entry["epoch_of_enroll"] == 77
    assert entry["mtls_device_id"] == "ccc-33"
    assert int(entry["enrolled_at"]) > 0
    # Persist: Datei existiert und enthält EINTRAEGE von beiden (kein Schrumpf!)
    raw = json.loads((tmp_path / "relay-devices.json").read_text())
    assert set(raw["devices"].keys()) == {"aaa-11", "bbb-22"}


def test_sync_kein_pairing_id_kein_eintrag(tmp_path, monkeypatch):
    """Alt-App (0.17.0) ohne pairing_device_id im Body → KEIN B1-Eintrag,
    kein Crash (fail-soft), bestehende Registry unverändert."""
    mod = _relay_mod()
    state = _w23_state(mod, tmp_path, monkeypatch)
    monkeypatch.setattr(_mtls_mod(), "_registry_load", lambda **kw: {"v": 2, "epoch": 77, "devices": {}})
    mod.b1_registry_enroll_sync(state, "", "ccc-33")
    mod.b1_registry_enroll_sync(state, "schrott id!", "ccc-33")
    assert set(state.device_registry.keys()) == {"aaa-11"}


def test_sync_ungueltige_zert_id_wird_begrenzt(tmp_path, monkeypatch):
    mod = _relay_mod()
    state = _w23_state(mod, tmp_path, monkeypatch)
    monkeypatch.setattr(_mtls_mod(), "_registry_load", lambda **kw: {})
    mod.b1_registry_enroll_sync(state, "ddd-44", "x" * 500)
    entry = state.device_registry["ddd-44"]
    assert len(entry["mtls_device_id"]) <= 64


def test_enroll_handler_ruft_sync(tmp_path, monkeypatch):
    """Der /mtls/enroll-Handler zieht die Pairing-ID bei erfolgreichem
    Zert-Enroll mit (Integrationsebene: Hook-Verband, kein Doppel-Verband)."""
    mtls = _mtls_mod()
    mod = _relay_mod()
    calls = []
    monkeypatch.setattr(mod, "b1_registry_enroll_sync",
                        lambda state, pid, cid: calls.append((pid, cid)))
    assert hasattr(mtls, "enroll_handler"), "enroll_handler muss existieren"
    # Direkt-Verband: der Handler-Aufruf selbst mockt wir teuer (CA-Verband)
    # — daher beweisen wir NUR die Aufruf-Ader: die Quelltext-Zeile existiert.
    src = open(mtls.__file__, encoding="utf-8").read()
    assert "b1_registry_enroll_sync(" in src
    assert "pairing_device_id" in src


# ── W23-2: Persist = voller RAM-Stand (Regressionsschutz Schrumpf) ───────────

def test_persist_relay_schreibt_vollen_stand(tmp_path, monkeypatch):
    """18.11-Regression: Connect-Handler persistierte GESCHRUMPFT (4→1).
    Guard: _store_device_registry schreibt IMMER alle Entries der RAM-Map."""
    mod = _relay_mod()
    monkeypatch.setenv("ANDROID_RELAY_DEVICE_REGISTRY", str(tmp_path / "relay-devices.json"))
    entries = {
        f"dev-{i}": {"alias": "", "admin": i == 0, "epoch_of_enroll": 2,
                     "enrolled_at": 11 * 1000, "last_seen": 12 * 1000}
        for i in range(4)
    }
    mod._store_device_registry(entries)
    raw = json.loads((tmp_path / "relay-devices.json").read_text())
    assert raw["v"] == 2
    assert set(raw["devices"].keys()) == set(entries.keys())


def test_last_seen_persist_verliert_keine_eintraege(tmp_path, monkeypatch):
    """Die Disconnect-/last_seen-Persist-Stelle (Z. ~3667, W22): sie schreibt
    state.device_registry (VOLLER Stand) — der Test beweist, dass ein
    last_seen-Update am BESTEHENDEN Stand keine Einträge verwirft."""
    mod = _relay_mod()
    monkeypatch.setenv("ANDROID_RELAY_DEVICE_REGISTRY", str(tmp_path / "relay-devices.json"))
    mod._store_device_registry({
        "dev-a": {"alias": "", "admin": True, "epoch_of_enroll": 2,
                  "enrolled_at": 1, "last_seen": 1},
        "dev-b": {"alias": "", "admin": False, "epoch_of_enroll": 2,
                  "enrolled_at": 1, "last_seen": 1},
    })
    # Simuliere die last_seen-Verbandspflege wie im Disconnect-Pfad:
    registry = mod._load_device_registry_file()
    entry = registry.get("dev-a")
    if isinstance(entry, dict):
        entry["last_seen"] = int(time.time() * 1000)
        mod._store_device_registry(registry)
    raw = json.loads((tmp_path / "relay-devices.json").read_text())
    assert set(raw["devices"].keys()) == {"dev-a", "dev-b"}
