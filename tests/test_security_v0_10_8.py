"""Security regression tests for v0.10.8 (upstream review raulvidis#113 fixes).

Covers the three review requirements that had no tests:
- CapabilityGate deny-unknown (Blocking-2)
- DeviceFiles.sanitize root-escape (Blocking-5)
- UpdateInstaller without caller-supplied URL (Blocking-1, source-level)
"""
import json
import re
from pathlib import Path

import pytest

REPO = __import__("pathlib").Path(__file__).resolve().parent.parent
GATE_KT = REPO / "hermes-android-bridge/app/src/main/kotlin/com/hermesandroid/bridge/security/CapabilityGate.kt"
INSTALLER_KT = REPO / "hermes-android-bridge/app/src/main/kotlin/com/hermesandroid/bridge/files/UpdateInstaller.kt"
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
    """Blocking-1: pairing token must never reach caller-supplied URLs."""

    def test_installer_has_no_url_parameter(self):
        src = INSTALLER_KT.read_text(encoding="utf-8")
        sig = re.search(r"fun start\(([^)]*)\)", src)
        assert sig, "UpdateInstaller.start signature not found"
        assert "url" not in sig.group(1), (
            "UpdateInstaller.start still takes a caller-supplied URL"
        )
        assert "/apk/latest" in src, "installer must use the fixed relay path"

    def test_installer_has_no_bearer_to_arbitrary_url(self):
        src = INSTALLER_KT.read_text(encoding="utf-8")
        assert 'header("Authorization"' in src  # token an Relay ok
        # kein Request.Builder().url(variable) mit caller-URL:
        assert 'url(trimmed)' not in src, "caller-supplied URL still in use"

    def test_relay_sends_sha_header(self):
        src = RELAY_PY.read_text(encoding="utf-8")
        assert "X-APK-SHA256" in src, "relay must serve X-APK-SHA256 header"

    def test_tool_has_no_url_parameter(self):
        src = TOOL_PY.read_text(encoding="utf-8")
        fn = re.search(r"def android_apk_install\(([^)]*)\)", src)
        assert fn and fn.group(1).strip() == "", "android_apk_install must take no args"


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
        assert "compare_digest" in block
        assert "_auth_record_failure" in block


class TestMigration:
    """Blocking-3: existing installs keep working (migration, not all-OFF)."""

    def test_migration_exists(self):
        src = GATE_KT.read_text(encoding="utf-8")
        assert "migrateLegacyInstall" in src
        assert "legacy_had_relay" in src
        # Flag wird beim connect gesetzt:
        relay_src = RELAY_KT.read_text(encoding="utf-8")
        assert 'putBoolean("legacy_had_relay", true)' in relay_src
