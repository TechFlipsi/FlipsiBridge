import json

import pytest


@pytest.fixture(autouse=True)
def relay_state_isolation(tmp_path_factory, monkeypatch):
    """W26-Test-Isolation (REPO-WEIT — gilt auch für contrib/-Testläufe).

    Der Relay liest/schreibt Start-State über die Env-gelenkten Pfade; ohne
    Isolation fiel je Lauf die Live-Ablage (/opt/flipsibridge bzw. das Plugin-
    Datenverzeichnis) an. Bewiesener Prod-Verstoß 24.11. 17:31: ein Testlauf
    unter contrib/ stieß den last_seen-Persist der PROD-Geräte-Registry an
    (relay-devices.json mtime 17:31:44, Feld last_seen neu) — Relay-devices.
    json ist im Betrieb NUR-LESEN-Verband. Diese Fixture kapselt JE Test:
      - ANDROID_RELAY_DEVICE_REGISTRY → leere v2-Registry (Erst-Annahme-Welt,
        kein v1→v2-Migrations-Seed aus mTLS-/Key-Live-Ablage);
      - ANDROID_RELAY_CONFIG → leere Agent-Config (Agent-Zugang fail-closed;
        Tests, die Agenten brauchen, schreiben ihre eigene Config — ein
        späteres monkeypatch.setenv gewinnt);
      - ANDROID_RELAY_KEYS / PASSWORD_FILE / INVITES → leere 0600-Welten;
      - IDENTITY_DIR / TLS_DIR / APK_DIR → tmp-Ablagen (keine Write-Probes
        in der Prod-Identity).
    NIEMALS /opt — Prod bleibt unverändert (read-only)."""
    relay_state = tmp_path_factory.mktemp("relay-state")
    monkeypatch.setenv("ANDROID_RELAY_CONFIG",
                       str(relay_state / "relay-config.json"))
    (relay_state / "relay-config.json").write_text("{}", encoding="utf-8")
    monkeypatch.setenv("ANDROID_RELAY_DEVICE_REGISTRY",
                       str(relay_state / "relay-devices.json"))
    (relay_state / "relay-devices.json").write_text(
        json.dumps({"v": 2, "ts": 0, "devices": {}}), encoding="utf-8")
    monkeypatch.setenv("ANDROID_RELAY_KEYS", str(relay_state / "relay-keys.json"))
    monkeypatch.setenv("ANDROID_RELAY_PASSWORD_FILE",
                       str(relay_state / "pairing_password.json"))
    monkeypatch.setenv("ANDROID_RELAY_INVITES", str(relay_state / "relay-invites.json"))
    monkeypatch.setenv("ANDROID_RELAY_IDENTITY_DIR", str(relay_state / "identity"))
    monkeypatch.setenv("ANDROID_RELAY_TLS_DIR", str(relay_state / "tls"))
    monkeypatch.setenv("ANDROID_RELAY_APK_DIR", str(relay_state / "apk"))
    monkeypatch.setenv("ANDROID_MTLS_EPOCH", "1")
    yield
