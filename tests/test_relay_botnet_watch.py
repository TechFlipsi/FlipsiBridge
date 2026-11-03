# W3-04 — Relay-Log-Wächter (Botnet-/Brute-Force-Alarm).
#
# Vertrag: still wenn OK (kein Notify, Exit 0); Alarm via Notify-Skript wenn
# DISTINCT-IP-Zahl ODER Fehler-Gesamtzahl im Fenster die Schwelle reißt;
# Cooldown verhindert Alarm-Spam; Block-/429-Zeilen zählen zur IP-Verteilung,
# aber NICHT als neue Fehlversuche.

import importlib
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))), "tools"))


@pytest.fixture()
def watch(monkeypatch, tmp_path):
    """Modul mit testbaren Umgebungs-Werten laden (ENV wird beim Import
    gelesen → nachsetzen + reload)."""
    for key, val in {
        "RELAY_BOTNET_WINDOW_MIN": "60",
        "RELAY_BOTNET_IPS": "10",
        "RELAY_BOTNET_FAILS": "120",
        "RELAY_BOTNET_COOLDOWN_MIN": "30",
        "RELAY_BOTNET_UNIT": "flipsibridge-relay",
        "RELAY_BOTNET_NOTIFY": str(tmp_path / "notify.sh"),
        "RELAY_BOTNET_STATE": str(tmp_path / "state.json"),
    }.items():
        monkeypatch.setenv(key, val)
    if "relay_botnet_watch" in sys.modules:
        del sys.modules["relay_botnet_watch"]
    import relay_botnet_watch
    return importlib.reload(relay_botnet_watch)


def _rejected_lines(n_ips: int, per_ip: int = 1) -> list:
    lines = []
    for i in range(n_ips):
        ip = f"203.0.113.{i + 1}"
        for _ in range(per_ip):
            lines.append(
                f"Nov 03 07:26:36 kibot python3[1]: 2026-11-03 07:26:36,130 "
                f"[android_relay] WARNING: Phone WS rejected — bad token "
                f"(got ****) from {ip}")
    return lines


class TestAnalyzeLines:
    def test_counts_rejects_and_distinct_ips(self, watch):
        total, ip_count, ips = watch.analyze_lines(_rejected_lines(3, 2))
        assert total == 6, "je 'rejected from <ip>'-Zeile = 1 Fehler"
        assert ip_count == 3
        assert ips == {"203.0.113.1", "203.0.113.2", "203.0.113.3"}

    def test_blocked_lines_add_ip_but_not_total(self, watch):
        lines = _rejected_lines(1, 5) + [
            "Nov 03 07:26:43 kibot python3[1]: WARNING: IP 203.0.113.1 "
            "blocked for 300s after 5 failed auth attempts",
            "Nov 03 07:27:10 kibot python3[1]: WARNING: Auth attempt from "
            "blocked IP 203.0.113.1 — returning 429",
        ]
        total, ip_count, _ips = watch.analyze_lines(lines)
        assert total == 5, "Block-/429-Zeilen sind Folgen, keine neuen Versuche"
        assert ip_count == 1

    def test_all_reject_variants_counted(self, watch):
        lines = [
            "WARNING: HTTP /chat rejected — bad auth from 198.51.100.7",
            "WARNING: Agent WS rejected — bad agent token from 198.51.100.8",
            "WARNING: APK latest rejected — bad auth from 198.51.100.9",
            "WARNING: HTTP /key_publish rejected — bad auth from 198.51.100.7",
        ]
        total, ip_count, _ips = watch.analyze_lines(lines)
        assert total == 4
        assert ip_count == 3

    def test_normal_traffic_not_counted(self, watch):
        lines = [
            "INFO: Phone connected (ip=192.0.2.1)",
            'INFO: 10.0.0.5 "POST /chat HTTP/1.1" 200 -',
            "WARNING: HTTP /chat: enc Replay ... verworfen",
        ]
        assert watch.analyze_lines(lines) == (0, 0, set())


class TestMainBehavior:
    def _run(self, watch, monkeypatch, lines, notified):
        monkeypatch.setattr(watch, "read_journal",
                            lambda unit, window: lines)
        monkeypatch.setattr(watch, "send_alarm",
                            lambda path, title, msg: notified.append(
                                (title, msg)) or True)
        return watch.main()

    def test_silent_when_ok(self, watch, monkeypatch):
        notified = []
        rc = self._run(watch, monkeypatch, _rejected_lines(3), notified)
        assert rc == 0
        assert notified == [], "OK = still (kein Notify, kein Output)"

    def test_alarm_on_botnet_ips(self, watch, monkeypatch):
        notified = []
        rc = self._run(watch, monkeypatch, _rejected_lines(11), notified)
        assert rc == 0
        assert len(notified) == 1
        title, msg = notified[0]
        assert "Botnet" in title
        assert "11 DISTINCT IPs" in msg
        assert os.path.exists(watch.DEFAULT_STATE), \
            "Alarm muss den Cooldown-Zeitstempel schreiben"

    def test_alarm_on_single_ip_storm(self, watch, monkeypatch):
        notified = []
        rc = self._run(watch, monkeypatch, _rejected_lines(2, 61), notified)
        assert rc == 0
        assert len(notified) == 1
        assert "122 Auth-Fehler" in notified[0][1], \
            "Gesamtzahl über Schwelle löst aus, obwohl nur 2 IPs"

    def test_cooldown_suppresses_repeat_alarm(self, watch, monkeypatch):
        notified = []
        # 1. Lauf: Alarm + State-Write.
        self._run(watch, monkeypatch, _rejected_lines(20), notified)
        assert len(notified) == 1
        # 2. Lauf (gleicher Angriff läuft weiter): Cooldown = still.
        self._run(watch, monkeypatch, _rejected_lines(20), notified)
        assert len(notified) == 1, "Cooldown darf keinen zweiten Alarm senden"


class TestCooldownState:
    def test_corrupt_state_means_no_cooldown(self, watch, tmp_path):
        state = tmp_path / "state.json"
        state.write_text("kein json", encoding="utf-8")
        assert watch.cooldown_active(str(state)) is False, \
            "kaputte State-Datei darf den Wächter nie stilllegen"

    def test_fresh_state_means_cooldown(self, watch, tmp_path):
        state = tmp_path / "state.json"
        state.write_text(json.dumps({"last_alarm_ts": __import__("time").time()}),
                          encoding="utf-8")
        assert watch.cooldown_active(str(state)) is True


def test_w3_04_notify_script_wiring(watch, tmp_path, monkeypatch):
    """send_alarm ruft das Notify-Skript mit (Titel, Meldung, Stufe) —
    fehlendes Skript = best-effort False, nie eine Exception."""
    assert watch.send_alarm(str(tmp_path / "fehlt.sh"), "t", "m") is False