"""SIRS-Fix-Wellen-Tests (Volltest 03.11.2026): Relay-Seite.

SIRS-01 (P0) — E2E-Session nach Agent-Restart: Der Agent stößt beim Empfang
eines enc-Frames mit unbekannter session_id SOFORT einen crypto.session_reset
an (statt still zu verwerfen). Damit das Phone zwischen einem BENIGNEN Reset
(Agent-Neustart: neutraler Hinweis + sofortiger Neu-Handshake) und einem
SICHERHEITSEREIGNIS (Fingerprint-Abweichung: MitM-Warnung) unterscheiden kann,
leitet der Relay den Reason 1:1 weiter — NUR für Allowlist-Reasons
(fail-closed: unbekannt/fehlt = fingerprint-mismatch, der alte hart-codierte
Wert). Diese Tests pinnen den Relay-Vertrag des SIRS-01-Fixes.
"""

import json
import os as _sirs_os
import sys as _sirs_sys
import time

import pytest

from tools.android_relay import start_relay, stop_relay

_SIRS_SYS_PATH = _sirs_os.path.join(
    _sirs_os.path.dirname(_sirs_os.path.abspath(__file__)),
    "..", "contrib", "hermes-chat-plugin")
if _SIRS_SYS_PATH not in _sirs_sys.path:
    _sirs_sys.path.insert(0, _SIRS_SYS_PATH)
_sirs_sys.modules.setdefault("gateway", None)
import adapter as _ph3_adapter  # noqa: E402  (contrib/hermes-chat-plugin/adapter.py)


@pytest.fixture(autouse=True)
def _reset_relay():
    yield
    stop_relay()


class TestSirsSessionResetReasonForwarding:
    """SIRS-01: session_reset-Reason wird nur allowlisted ans Phone gereicht."""

    PORT = 19940
    CODE = "SIRSCD1"
    TOKEN = "sirs-agent-token"
    SESSION_ID = "sirs-e2e-session-0001"

    # Fixe Agent-Identity (RFC-7748-Bob als Identity, Fixture-PreKey —
    # identische Werte wie TestPh3CryptoE2E im Relay-Testmodul).
    IK_A_PRIV = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"
    IK_A_PUB = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
    SPK_A_PRIV = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"
    SPK_A_PUB = "52a5524b0c32e8a47f239ab6ca8e42c3b9316a54cae4854ecdb5889311cc9a7e"

    @property
    def identity(self):
        return {"identity_priv": self.IK_A_PRIV, "identity_pub": self.IK_A_PUB,
                "prekey_priv": self.SPK_A_PRIV, "prekey_pub": self.SPK_A_PUB}

    def _write_config(self, tmp_path):
        cfg = tmp_path / "relay-config.json"
        data = {
            "ANDROID_AGENT_TOKEN_ALPHA": self.TOKEN,
            "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha",
            "CHAT_RATE_PER_SECOND": 50,
            "ANDROID_AGENT_IDENTITY_ALPHA": self.IK_A_PUB,
            "ANDROID_AGENT_PREKEY_ALPHA": self.SPK_A_PUB,
            "ANDROID_AGENT_FINGERPRINT_ALPHA":
                _ph3_adapter.fingerprint_of_pub(bytes.fromhex(self.IK_A_PUB)),
        }
        cfg.write_text(json.dumps(data), encoding="utf-8")
        return str(cfg)

    def _run_reason_scenario(self, tmp_path, monkeypatch, port_offset,
                            agent_reason, expected_phone_reason):
        """Handshake etabliert → Agent-Reset mit `agent_reason` → das
        Phone-Frame MUSS `expected_phone_reason` tragen."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT + port_offset)
        base = f"http://127.0.0.1:{self.PORT + port_offset}"
        headers = {"Authorization": f"Bearer {self.CODE}"}

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + port_offset}/ws", headers=headers)
                await _drain_phone_state_frame(phone, expected=1)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + port_offset}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6)

                # E2E-Handshake: hello → Agent → ack → Phone.
                peer = _ph3_adapter.PhoneCryptoPeer(
                    "alpha", self.IK_A_PUB, self.SPK_A_PUB)
                hello = peer.make_hello(self.SESSION_ID)
                await session.post(f"{base}/crypto_hello", json=hello, headers=headers)
                hello_frame = await agent.receive_json(timeout=6)
                _, ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
                    hello_frame, self.identity)
                await agent.send_json(ack)
                await _recv_until(phone, "crypto.hello_ack", timeout=6)

                # SIRS-01: Agent-Reset — Reason entscheidet über die Phone-Reaktion
                # (benigner Neu-Handshake vs. MitM-Warnung).
                await agent.send_json({
                    "type": "crypto.session_reset", "v": 1,
                    "session_id": self.SESSION_ID,
                    "agent_id": "alpha",
                    "reason": agent_reason})
                reset = await _recv_until(phone, "crypto.session_reset", timeout=6)
                assert reset["reason"] == expected_phone_reason, (
                    f"Phone-Reason {reset['reason']!r} statt "
                    f"{expected_phone_reason!r}")
                assert reset["session_id"] == self.SESSION_ID
                assert reset["agent_id"] == "alpha"

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_sirs01_benign_reason_agent_session_lost_forwarded(
            self, tmp_path, monkeypatch):
        """SIRS-01 Vertrag: reason='agent-session-lost' (Agent-Neustart) kommt
        1:1 am Phone an — die App stößt daraufhin SOFORT einen Neu-Handshake an
        statt MitM zu rufen."""
        self._run_reason_scenario(
            tmp_path, monkeypatch, port_offset=0,
            agent_reason="agent-session-lost",
            expected_phone_reason="agent-session-lost")

    def test_sirs01_unknown_reason_fails_closed_to_fingerprint_mismatch(
            self, tmp_path, monkeypatch):
        """Fail-closed: unbekannter/fehlender Reason wird NICHT durchgereicht,
        sondern als fingerprint-mismatch (Sicherheitsereignis) gemeldet."""
        self._run_reason_scenario(
            tmp_path, monkeypatch, port_offset=1,
            agent_reason="kaputter-muell-reason",
            expected_phone_reason="fingerprint-mismatch")


# ── Helfer (Parität zum Relay-Testmodul) ────────────────────────────────────

async def _drain_phone_state_frame(ws, expected=1):
    frames = []
    for _ in range(expected):
        frame = await ws.receive_json(timeout=2)
        assert frame.get("type") == "relay.state", (
            f"erster Phone-Frame sollte relay.state sein, kam aber: {frame}")
        frames.append(frame)
    return frames[0]


async def _recv_until(ws, frame_type, timeout=6.0):
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        assert remaining > 0, f"Frame {frame_type!r} kam nicht"
        frame = await ws.receive_json(timeout=max(0.1, remaining))
        if frame.get("type") == frame_type:
            return frame

# ── PH-FIX-BACKEND-B: Zusatz-Order „EIN Code für alles" (02.11.2026) ────────

class TestPskCodepathRemoved:
    """Zusatz-Order 02.11.2026 (PIPELINE-BUGS): Der zweite Code
    (FLIPSI_PAIRING_PSK) ist TOD — der einzige Ort, der ihn je las, war der
    FlipsiBridge-Chat-Adapter (Bug-1-Wurzel: agent-seitig psk in die KDF,
    app-seitig nie übergeben). Der Adapter ignoriert ihn jetzt laut-warnend;
    der KDF-Live-Vertrag ist psk=b"" (PH3-KDF-FIXTURES-v1.md kanonisch).
    SCOPE-GUARD: FlipsiBridge-spezifisch — KEIN anderer Plattform-Adapter
    (Discord/Telegram/Signal/WhatsApp/…) darf pairing_psk je referenzieren.
    """

    def test_pairing_psk_referenced_only_by_flipsibridge_chat_adapter(self):
        import pathlib
        repo = pathlib.Path(__file__).resolve().parents[1]
        offenders = []
        for path in sorted(repo.rglob("*.py")):
            if any(part in {"build", "__pycache__", "scratch"} for part in path.parts):
                continue
            if path.name.startswith("test_") or path.name == "conftest.py":
                continue  # Tests pinnen den PSK-Tod absichtlich (Text-Treffer ok)
            try:
                text = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            if "pairing_psk" in text or "FLIPSI_PAIRING_PSK" in text:
                offenders.append(str(path))
        expected = str(repo / "contrib" / "hermes-chat-plugin" / "adapter.py")
        assert offenders == [expected], (
            "pairing_psk/FLIPSI_PAIRING_PSK außerhalb des FlipsiBridge-Chat-"
            f"Adapters entdeckt (SCOPE-GUARD-Verstoß): {offenders}")

    def test_adapter_handshake_uses_hard_empty_psk(self):
        """Adapter-responder_to_hello-Pfad im Quelltext: hartes psk=b"" statt
        Config-Lookup — der alte 'psk=self.pairing_psk...'-Ausdruck ist weg."""
        import inspect
        source = inspect.getsource(_ph3_adapter.FlipsiBridgeChatAdapter)
        assert "self.pairing_psk" not in source, (
            "FlipsiBridgeChatAdapter darf pairing_psk als Config-Attribut nicht "
            "mehr kennen (Zusatz-Order: EIN Code = Relay-Pairing-Code)")
        assert 'psk=b""' in source, (
            "_handle_crypto_hello muss hart psk=b\"\" übergeben (Zusatz-Order)")
