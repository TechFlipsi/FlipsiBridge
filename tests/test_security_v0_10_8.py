"""Security regression tests for v0.10.8 (upstream review raulvidis#113 fixes).

Covers the three review requirements that had no tests:
- CapabilityGate deny-unknown (Blocking-2)
- DeviceFiles.sanitize root-escape (Blocking-5)
- UpdateInstaller without caller-supplied URL (Blocking-1, source-level)

W16 (Fixliste-Punkt 26): der In-App-Install-Pfad (UpdateInstaller +
android_apk_install-Tool + /apk_install-Route + Capability selfupdate) ist
RAUS — die ehemals harten Assertions sind jetzt Absenz-Assertions.
"""
from pathlib import Path

import pytest

REPO = __import__("pathlib").Path(__file__).resolve().parent.parent
GATE_KT = REPO / "hermes-android-bridge/app/src/main/kotlin/com/hermesandroid/bridge/security/CapabilityGate.kt"
INSTALLER_KT = REPO / "hermes-android-bridge/app/src/main/kotlin/com/hermesandroid/bridge/files/UpdateInstaller.kt"
MAIN_MANIFEST = REPO / "hermes-android-bridge/app/src/main/AndroidManifest.xml"
PLAY_MANIFEST = REPO / "hermes-android-bridge/app/src/play/AndroidManifest.xml"
DISPATCHER_KT = REPO / "hermes-android-bridge/app/src/main/kotlin/com/hermesandroid/bridge/server/CommandDispatcher.kt"
ROUTER_KT = REPO / "hermes-android-bridge/app/src/main/kotlin/com/hermesandroid/bridge/server/BridgeRouter.kt"
RELAY_KT = REPO / "hermes-android-bridge/app/src/main/kotlin/com/hermesandroid/bridge/client/RelayClient.kt"
FILES_KT = REPO / "hermes-android-bridge/app/src/main/kotlin/com/hermesandroid/bridge/files/DeviceFiles.kt"
TOOL_PY = REPO / "tools" / "android_tool.py"
RELAY_PY = REPO / "tools" / "android_relay.py"


class TestDenyUnknownGate:
    """Blocking-2: checkEndpoint must DENY unknown routes, never return null."""

    def test_gate_is_deny_unknown(self):
        src = GATE_KT.read_text(encoding="utf-8")
        assert "?: return null" not in src.split("checkEndpoint")[1].split("}")[0], (
            "checkEndpoint still falls back to null (allowed) for unknown routes"
        )
        assert "deny-unknown" in src, "deny-unknown marker missing"

    def test_mic_file_gate_on_relay_path(self):
        src = RELAY_KT.read_text(encoding="utf-8")
        mic_block = src.split('"/mic_file"')[1][:1200]
        assert "CapabilityGate.checkEndpoint" in mic_block, (
            "/mic_file relay branch must call the capability gate"
        )

    def test_mic_file_gate_on_local_http_path(self):
        src = ROUTER_KT.read_text(encoding="utf-8")
        mic_block = src.split('"/mic_file"')[1][:900]
        assert "CapabilityGate.checkEndpoint" in mic_block, (
            "/mic_file local HTTP route must call the capability gate"
        )


class TestApkInstallHardening:
    """Blocking-1 (damals) + W16/Punkt-26 (jetzt): der Install-Pfad ist weg.

    Der Pairing-Token durfte nie an caller-supplied URLs; seit W16 existiert
    der In-App-Install-Weg GAR NICHT mehr — Updates laufen je Edition über
    Play Store/GitHub, in-app nur der Update-Check (kein APK-Download, keine
    Installer-Fähigkeit)."""


    def test_installer_file_is_gone(self):
        assert not INSTALLER_KT.exists(), (
            "UpdateInstaller.kt wurde in W16 (Punkt 26) entfernt — der "
            "In-App-Install-Code darf nicht im Tree zurückbleiben"
        )

    def test_no_in_app_install_left_in_app(self):
        # Dispatcher: kein /apk_install-Handler mehr (deny-unknown 404).
        dispatcher = DISPATCHER_KT.read_text(encoding="utf-8")
        assert 'path == "/apk_install"' not in dispatcher
        assert "UpdateInstaller" not in dispatcher
        # Gate: Capability „selfupdate“ entfällt (Route-Map + UI-Liste).
        # Absenz-Assert am CODE-BINDung-Niveau — der Verband-Kommentar darf
        # die Geschichte erwähnen, der Code darf sie nicht mehr laden.
        gate = GATE_KT.read_text(encoding="utf-8")
        assert 'to "selfupdate"' not in gate and '"selfupdate" to' not in gate, (
            "Capability-Rest von selfupdate in Route-/UI-Liste"
        )
        # Manifest: keine Install-Permission mehr (Basis ohne, play-Overlay
        # braucht den remove-Node nicht mehr). Assert auf die volle
        # Permission-Bindung — der Entfall-Kommentar darf den Namen nennen.
        assert "android.permission.REQUEST_INSTALL_PACKAGES" not in MAIN_MANIFEST.read_text(encoding="utf-8")
        assert "android.permission.REQUEST_INSTALL_PACKAGES" not in PLAY_MANIFEST.read_text(encoding="utf-8")

    def test_tool_has_no_install_function(self):
        src = TOOL_PY.read_text(encoding="utf-8")
        assert "android_apk_install" not in src, (
            "Agent-Tool android_apk_install ist in W16 (Punkt 26) entfernt"
        )
        assert '"/apk_install"' not in src

    def test_relay_apk_install_route_removed(self):
        src = RELAY_PY.read_text(encoding="utf-8")
        assert '"/apk_install"' not in src, (
            "Relay-Route /apk_install ist in W16 (Punkt 26) entfernt"
        )
        # /apk/latest bleibt (Download-Hinweis + HEAD-Update-Check):
        assert "/apk/latest" in src
        # HEAD-Update-Check: Versions-Header für die App (fail-closed ohne).
        assert "X-APK-Version" in src

    def test_relay_sends_sha_header(self):
        src = RELAY_PY.read_text(encoding="utf-8")
        assert "X-APK-SHA256" in src, "relay must serve X-APK-SHA256 header"


class TestSanitize:
    """Blocking-5: unknown top-level segment must NOT fall back to root."""

    def test_no_root_fallback(self):
        src = FILES_KT.read_text(encoding="utf-8")
        sanitize_src = src.split("fun sanitize")[1][:2600]
        assert 'rootMap[first] ?: return null' in sanitize_src, (
            "sanitize must reject unknown top-level segments (no root fallback)"
        )


class TestCapabilitySeparation:
    """Blocking-5b/6: destructive ops + outbound actions need their own switches."""

    def test_files_write_is_separate_capability(self):
        src = GATE_KT.read_text(encoding="utf-8")
        assert 'Pair("POST", "/files_push") to "files_write"' in src
        assert 'Pair("POST", "/files_delete") to "files_write"' in src

    def test_notify_reply_has_own_capability(self):
        src = GATE_KT.read_text(encoding="utf-8")
        assert 'Pair("POST", "/notify_reply") to "notify_reply"' in src

    def test_notify_reply_uses_add_results_to_intent(self):
        src = (REPO / "hermes-android-bridge/app/src/main/kotlin/com/hermesandroid/bridge/service/NotificationReplier.kt").read_text(encoding="utf-8")
        assert "RemoteInput.addResultsToIntent" in src
        assert 'putExtra(RemoteInput.RESULTS_CLIP_LABEL' not in src, (
            "manual ClipData assembly must be gone"
        )


class TestRelayApkLatestAuth:
    """apk/latest must use pairing_code + compare_digest + rate limiting."""

    def test_uses_pairing_code(self):
        src = RELAY_PY.read_text(encoding="utf-8")
        block = src.split("async def apk_latest")[1][:1200]
        assert 'ANDROID_BRIDGE_TOKEN' not in block
        # v0.11.0: der konstante Zeit-Compare lebt im Helper _constant_time_eq
        # (Bytes-Vergleich, TypeError-sicher); apk_latest muss ihn nutzen und
        # die Fehlversuchen-Zaehlung (_auth_record_failure) behalten.
        assert "_constant_time_eq" in block
        assert "_auth_record_failure" in block
        helper = src.split("def _constant_time_eq")[1][:400]
        assert "compare_digest" in helper


class TestMigration:
    """Blocking-3: existing installs keep working (migration, not all-OFF)."""

    def test_migration_exists(self):
        src = GATE_KT.read_text(encoding="utf-8")
        assert "migrateLegacyInstall" in src
        assert "legacy_had_relay" in src
        # Flag wird beim connect gesetzt:
        relay_src = RELAY_KT.read_text(encoding="utf-8")
        assert 'putBoolean("legacy_had_relay", true)' in relay_src
