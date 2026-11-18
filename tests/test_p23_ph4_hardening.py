# P23 + PH-4 Backend-Härtung — Regressionstests
#
# Scope (Task P23+PH-4, Sub-Worker):
#   P23.1  systemd-Unit-Vorlage (deployment/flipsibridge-relay.service):
#          Direktiven-Pflicht des Sandbox-Vollsets + eigener System-User.
#   P23.2  deployment/README.md: Migration-/Rollback-Anleitung vorhanden.
#   PH-4 O4:  Security-Parameter = Server-Config (Env-Clamps), nie bare
#             hard-coded; Syntax-Guards bleiben bewusst hard (fail-closed).
#   PH-4 Log-Redaktion: _safe_body_repr rekursiv — Tokens/Codes/PSK landen
#             NIE im Log, auch nicht verschachtelt; Debug-Body-Gate Default-OFF.
#   PH-4 Code-Verdunkelung: --version-Banner NUR auf explizites Flag.
#
# Bestehende Tests werden nicht angerufen (Tests-Bestand unverändert).

import hashlib
import json
import logging
import subprocess
import sys
from pathlib import Path

import pytest

import tools.android_relay as ar

REPO = Path(__file__).resolve().parent.parent
UNIT_TEMPLATE = REPO / "deployment" / "flipsibridge-relay.service"
DEPLOY_README = REPO / "deployment" / "README.md"


# ── PH-4 O4: Env-Clamp-Helfer ────────────────────────────────────────────────

class TestEnvClampHelpers:
    def test_missing_env_yields_default(self, monkeypatch):
        monkeypatch.delenv("FB_TEST_CLAMP", raising=False)
        assert ar._env_clamped_float("FB_TEST_CLAMP", 600.0, 60.0, 600.0) == 600.0
        assert ar._env_clamped_int("FB_TEST_CLAMP", 8, 2, 8) == 8

    def test_empty_and_blank_env_yields_default(self, monkeypatch):
        monkeypatch.setenv("FB_TEST_CLAMP", "")
        assert ar._env_clamped_int("FB_TEST_CLAMP", 8, 2, 8) == 8
        monkeypatch.setenv("FB_TEST_CLAMP", "   ")
        assert ar._env_clamped_float("FB_TEST_CLAMP", 600.0, 60.0, 600.0) == 600.0

    def test_value_clamped_to_hard_cap(self, monkeypatch):
        # Konfig darf das DoS-Schild nie lockern: über Maximum = Maximum.
        monkeypatch.setenv("FB_TEST_CLAMP", "999999")
        assert ar._env_clamped_int("FB_TEST_CLAMP", 8, 2, 8) == 8
        monkeypatch.setenv("FB_TEST_CLAMP", "1")
        assert ar._env_clamped_int("FB_TEST_CLAMP", 8, 2, 8) == 2  # unter Minimum = Minimum

    def test_invalid_values_yield_default(self, monkeypatch):
        for bad in ("abc", "NaN", "inf", "-inf", "8.5.2"):
            monkeypatch.setenv("FB_TEST_CLAMP", bad)
            assert ar._env_clamped_int("FB_TEST_CLAMP", 8, 2, 8) == 8, bad

    def test_valid_int_accepted_and_clamped(self, monkeypatch):
        monkeypatch.setenv("FB_TEST_CLAMP", "5")
        assert ar._env_clamped_int("FB_TEST_CLAMP", 8, 2, 8) == 5
        monkeypatch.setenv("FB_TEST_CLAMP", "4.7")
        assert ar._env_clamped_float("FB_TEST_CLAMP", 600.0, 60.0, 600.0) == 60.0  # unter Minimum = Minimum


class TestO4DerivedConstants:
    """Env ist in der Test-Suite unset → alle abgeleiteten Konstanten müssen den
    bisherigen (grünen) Default tragen: kein Verhaltensbruch für Live-Deployments
    ohne Env-Pflege."""

    def test_defaults_unchanged(self):
        assert ar._PAIRING_TTL_SECONDS == 600.0
        assert ar._PAIRING_PENDING_MAX == 8
        assert ar._REPLAY_WINDOW == 100
        assert ar._FRAME_MAX_AGE_MS == 10 * 60 * 1000
        assert ar._CHAT_QUEUE_MAXLEN == 500
        assert ar._CHAT_QUEUE_MAX_BYTES == 8 * 1024 * 1024
        assert ar._AUTH_MAX_ATTEMPTS == 5
        assert ar._AUTH_WINDOW_SECONDS == 60
        assert ar._AUTH_BLOCK_SECONDS == 300

    def test_ceilings_never_exceeded_via_helper(self):
        # Vertrag: Default == Klammer-Ende — kein env-weg-Zustand kann über das
        # bisherige Schild hinaus (fail-closed).
        assert ar._env_clamped_int("FB_TEST_UNSET_NEVER_SET", 5, 1, 5) == 5


# ── PH-4 Log-Redaktion: _safe_body_repr rekursiv ─────────────────────────────

class TestSafeBodyReprRecursiveRedaction:
    def test_nested_token_redacted(self):
        body = {"cmd": "http.request", "params": {"token": "SECRET-TOKEN-42"}}
        out = ar._safe_body_repr(body)
        assert "SECRET-TOKEN-42" not in out
        assert "<redacted>" in out

    def test_multi_level_leak_redacted(self):
        # Nicht-sensitive Zwischen-Keys: die WERTBAUM-Redaktion muss bis unten
        # wandern; ein sensitiver KEY (hier: auth) redigiert den Subtree ganz.
        body = {"params": {"note": {"bearer": "abc"}}, "meta": {"otp": "9z8y"},
                "auth": {"anything": "gone"}}
        out = ar._safe_body_repr(body)
        assert "abc" not in out and "9z8y" not in out and "gone" not in out
        assert out.count("<redacted>") == 3

    def test_case_insensitive_key_match(self):
        out = ar._safe_body_repr({"Password": "hunter2", "Token": "T"})
        assert "hunter2" not in out and '"T"' not in out

    def test_list_of_dicts_redacted(self):
        out = ar._safe_body_repr({"items": [{"password": "p1"}, {"note": "ok"}]})
        assert "p1" not in out and '"ok"' in out

    def test_depth_cap_without_content(self):
        deep = {}
        node = deep
        for _ in range(20):
            node["down"] = {}
            node = node["down"]
        node["secret"] = "TOP-SECRET"
        out = ar._safe_body_repr(deep)
        assert "TOP-SECRET" not in out
        assert "<redacted-depth>" in out or "<redacted>" in out

    def test_non_dict_unchanged(self):
        assert ar._safe_body_repr(["x", "y"]) .startswith("<non-dict:list>")
        assert ar._safe_body_repr("plain") == "<non-dict:str> (7 bytes)"
        assert ar._safe_body_repr(None) == "{}"

    def test_top_level_still_redacted(self):
        out = ar._safe_body_repr({"token": "TOP", "text": "hello-secret"})
        assert "TOP" not in out and "hello-secret" not in out

    def test_output_capped_200(self):
        out = ar._safe_body_repr({"text": "x" * 5000})
        assert len(out) <= 200


# ── PH-4 Log-Redaktion: Debug-Body-Gate Default-OFF ──────────────────────────

class TestDebugBodyGate:
    def test_default_off(self, monkeypatch):
        monkeypatch.delenv(ar._DEBUG_BODY_LOGGING_ENV, raising=False)
        assert ar._debug_body_enabled() is False

    def test_opt_in_explicit(self, monkeypatch):
        monkeypatch.setenv(ar._DEBUG_BODY_LOGGING_ENV, "1")
        assert ar._debug_body_enabled() is True
        monkeypatch.setenv(ar._DEBUG_BODY_LOGGING_ENV, "0")
        assert ar._debug_body_enabled() is False

    def _captures(self, caplog):
        return [r.getMessage() for r in caplog.records if ">>>" in r.getMessage()]

    def test_body_never_emitted_without_env_even_at_debug(self, caplog, monkeypatch):
        monkeypatch.delenv(ar._DEBUG_BODY_LOGGING_ENV, raising=False)
        with caplog.at_level(logging.DEBUG, logger="android_relay"):
            ar._log_command_debug("POST", "/command", "dev-1",
                                  {"text": "HUSH-CONTENT", "password": "p"})
        lines = self._captures(caplog)
        assert lines, "Method/Path-Zeile muss (debug) bleiben"
        assert any("body=<off>" in l for l in lines)
        assert not any("HUSH-CONTENT" in l for l in lines)
        assert not any("body=" in l and "<off>" not in l for l in lines)

    def test_with_env_body_redacted(self, caplog, monkeypatch):
        monkeypatch.setenv(ar._DEBUG_BODY_LOGGING_ENV, "1")
        with caplog.at_level(logging.DEBUG, logger="android_relay"):
            ar._log_command_debug("POST", "/command", "dev-1",
                                  {"params": {"token": "HHH-TOKEN"}, "note": "ok"})
        lines = self._captures(caplog)
        assert lines and any("body=" in l for l in lines)
        joined = " ".join(lines)
        assert "HHH-TOKEN" not in joined
        assert "<redacted>" in joined

    def test_device_masked_in_debug_line(self, caplog, monkeypatch):
        monkeypatch.delenv(ar._DEBUG_BODY_LOGGING_ENV, raising=False)
        with caplog.at_level(logging.DEBUG, logger="android_relay"):
            ar._log_command_debug("GET", "/battery", "very-long-device-id-123", None)
        joined = " ".join(self._captures(caplog))
        assert "very-long-device-id-123" not in joined


# ── PH-4 Code-Verdunkelung: --version NUR auf Flag ───────────────────────────

class TestVersionFlag:
    def test_version_flag_prints_and_exits_clean(self):
        proc = subprocess.run(
            [sys.executable, str(REPO / "tools" / "android_relay.py"), "--version"],
            capture_output=True, text=True, timeout=30, cwd=str(REPO))
        assert proc.returncode == 0
        assert "android_relay" in proc.stdout
        assert proc.stderr.strip() == ""

    def test_version_short_flag(self):
        proc = subprocess.run(
            [sys.executable, str(REPO / "tools" / "android_relay.py"), "-V"],
            capture_output=True, text=True, timeout=30, cwd=str(REPO))
        assert proc.returncode == 0 and "android_relay" in proc.stdout

    def test_bare_invocation_starts_no_relay(self):
        proc = subprocess.run(
            [sys.executable, str(REPO / "tools" / "android_relay.py")],
            capture_output=True, text=True, timeout=30, cwd=str(REPO))
        assert proc.returncode == 0
        assert "listening" not in proc.stdout.lower()

    def test_version_resolves_without_inventing_value(self):
        version = ar._resolve_relay_version()
        assert isinstance(version, str) and version


# ── P23.1: systemd-Unit-Vorlage — Sandbox-Vollset-Direktiven ────────────────

class TestUnitTemplateHardening:
    @pytest.fixture(scope="class")
    def unit_text(self):
        assert UNIT_TEMPLATE.exists(), "Unit-Vorlage fehlt in deployment/"
        return UNIT_TEMPLATE.read_text(encoding="utf-8")

    def _require(self, text, *directives):
        missing = [d for d in directives if d not in text]
        assert not missing, f"Unit-Vorlage fehlt Direktiven: {missing}"

    def test_own_system_user(self, unit_text):
        self._require(unit_text, "User=flipsibridge", "Group=flipsibridge",
                      "UMask=0077")

    def test_sandbox_full_set(self, unit_text):
        self._require(
            unit_text,
            "NoNewPrivileges=yes",
            "ProtectSystem=strict",
            "ProtectHome=yes",
            "PrivateTmp=yes",
            "PrivateDevices=yes",
            "CapabilityBoundingSet=",
            "RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX",
            "SystemCallFilter=@system-service",
            "SystemCallArchitectures=native",
            "ProtectKernelTunables=yes",
            "ProtectControlGroups=yes",
            "MemoryDenyWriteExecute=yes",
            "LockPersonality=yes",
            "RestrictNamespaces=yes",
            "RestrictRealtime=yes",
            "RestrictSUIDSGID=yes",
            "ProtectProc=invisible",
        )

    def test_restart_policy_and_stop_path(self, unit_text):
        self._require(unit_text, "Restart=always", "RestartSec=",
                      "KillSignal=SIGTERM")

    def test_no_real_infra_values_in_template(self, unit_text):
        # O3: Vorlage enthält bewusst KEINE echten IPs/Domänen — RFC 5737/
        # .invalid-Platzhalter. Nadeln zur Laufzeit zusammengebaut, damit der
        # öffentliche Baum beim Nackt-Grep (W3-Standard) keine Treffer wirft.
        lan_prefix = "192.168" + "."
        assert lan_prefix not in unit_text
        assert "kirchw" + "eger" not in unit_text
        # RFC-5737-Platzhalter sind ausdrücklich erwünscht:
        assert "192.0.2." in unit_text

    def test_readwrite_paths_for_state(self, unit_text):
        self._require(unit_text, "ReadWritePaths=/opt/flipsibridge")


# ── P23.2: deployment/README.md — Anleitung/Migration/Rollback ───────────────

class TestDeploymentReadme:
    @pytest.fixture(scope="class")
    def readme_text(self):
        assert DEPLOY_README.exists(), "deployment/README.md fehlt"
        return DEPLOY_README.read_text(encoding="utf-8")

    def test_migration_steps_present(self, readme_text):
        for needle in ("adduser", "daemon-reload", "EnvironmentFile",
                       "chown", "chmod 0640", "UMask"):
            assert needle in readme_text, f"README fehlt Migrationsschritt: {needle}"

    def test_rollback_present(self, readme_text):
        assert "pre-p23.bak" in readme_text
        assert "Rollback" in readme_text

    def test_template_not_executed_note(self, readme_text):
        assert "NICHT am Live-System" in readme_text

    def test_permission_matrix_present(self, readme_text):
        for needle in ("0644", "0640", "0600", "0750"):
            assert needle in readme_text, f"README fehlt Rechte-Angabe: {needle}"


# ── P23/PH-4: B3-Parität der Relay-Kopien (repo-intern) ─────────────────────

class TestRepoCopyParity:
    def test_tools_and_plugin_copy_byte_identical(self):
        src = REPO / "tools" / "android_relay.py"
        plugin = REPO / "hermes-android-plugin" / "android_relay.py"
        h1 = hashlib.md5(src.read_bytes()).hexdigest()
        h2 = hashlib.md5(plugin.read_bytes()).hexdigest()
        assert h1 == h2, "4-Kopien-Kontrakt (B3): Plugin-Kopie driftet"
