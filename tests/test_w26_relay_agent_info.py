# W26 (Lane R): agent.info-Durchleitung — je dem cron.status-Fanout-Muster.
# Klar NUR ohne E2E-Session (Downgrade-Schutz), enc opak je bestätigter
# Session (Replay-Fenster), EIGENER Rate-Bucket 'agentinfo:*', WS-Identität
# gewinnt, Struktur-Gate fail-closed (display_name/adapter_caps).
import base64
import json
import time

import pytest

import tools.android_relay as R
from tools.android_relay import (
    _chat_rate_hits,
    _chat_rate_lock,
    start_relay,
    stop_relay,
)

pytestmark = pytest.mark.usefixtures("relay_state_isolation")

_PORT = 19957


@pytest.fixture(autouse=True)
def _reset_rate_buckets():
    """Je Test saubere Rate-Buckets (Parität haupt-Testfile-Fixture)."""
    with _chat_rate_lock:
        _chat_rate_hits.clear()
    yield
    with _chat_rate_lock:
        _chat_rate_hits.clear()


@pytest.fixture()
def running_relay(tmp_path, monkeypatch):
    cfg = tmp_path / "relay-config.json"
    cfg.write_text(json.dumps({
        "ANDROID_AGENT_TOKEN_ALPHA": "w26-agent-token",
        "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha W26",
    }), encoding="utf-8")
    monkeypatch.setenv("ANDROID_RELAY_CONFIG", str(cfg))
    start_relay(pairing_code="W26INFO", port=_PORT)
    yield R._relay_instance
    stop_relay()


async def _connect_phone_agent(session, port):
    import aiohttp

    phone = await session.ws_connect(
        f"ws://127.0.0.1:{port}/ws", headers={"Authorization": "Bearer W26INFO"})
    greet = await phone.receive_json(timeout=2)
    assert greet["type"] == "relay.state"
    agent = await session.ws_connect(
        f"ws://127.0.0.1:{port}/agent_ws",
        headers={"Authorization": "Bearer w26-agent-token"})
    await agent.send_json({"type": "agent.register", "v": 1,
                           "agent_id": "alpha", "display_name": "Alpha W26"})
    ident = await agent.receive_json(timeout=2)
    assert ident["type"] == "agent.ident"
    push = await phone.receive_json(timeout=2)
    assert push["type"] == "relay.state"
    return phone, agent


def _enc_frame(session_id, n=1, chat_id=""):
    """Form-gültiger *.enc-Frame (ct/nonce Fake-Opak — der Relay bleibt blind;
    ts = Pflichtfeld des ±10-min-Freshness-Fensters)."""
    rk = "a" * 64
    nonce = base64.b64encode(b"\x01" * 12).decode("ascii")
    ct = base64.b64encode(b"\x00" * 40).decode("ascii")
    return {"type": "agent.info.enc", "v": 1, "session_id": session_id,
            "hdr": {"rk": rk, "n": n, "pn": 0},
            "nonce": nonce, "ct": ct, "chat_id": chat_id,
            "ts": int(time.time() * 1000)}


class TestAgentInfoFanout:
    """agent.info → Phone (Klar + enc, je cron.status-Fanout-Muster)."""

    def test_agent_info_clear_forwarded(self, running_relay):
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await _connect_phone_agent(session, _PORT)
                await agent.send_json({
                    "type": "agent.info", "agent_id": "alpha",
                    "display_name": "Alpha W26",
                    "adapter_caps": ["cron.status", "cron.remove",
                                     "cron.pause", "cron.add", "models"]})
                frame = await phone.receive_json(timeout=2)
                assert frame["type"] == "agent.info"
                assert frame["v"] == 1
                assert frame["agent_id"] == "alpha"          # WS-Identität gewinnt
                assert frame["display_name"] == "Alpha W26"
                assert frame["adapter_caps"] == ["cron.status", "cron.remove",
                                                 "cron.pause", "cron.add", "models"]
                await phone.close()
                await agent.close()

        asyncio.run(scenario())

    def test_agent_info_struct_gate_fail_closed(self, running_relay):
        """display_name/adapter_caps fehlen/ill-Form = nichts am Phone."""
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await _connect_phone_agent(session, _PORT)
                await agent.send_json({"type": "agent.info", "agent_id": "alpha"})
                with pytest.raises(Exception):
                    await phone.receive_json(timeout=0.6)
                await agent.send_json({
                    "type": "agent.info", "display_name": "Kappa",
                    "adapter_caps": ["c" * 100]})               # Cap-Zeichen > 48
                with pytest.raises(Exception):
                    await phone.receive_json(timeout=0.6)
                await agent.send_json({
                    "type": "agent.info", "display_name": "Kappa",
                    "adapter_caps": [f"cap{i}" for i in range(20)]})  # > 16 Caps
                with pytest.raises(Exception):
                    await phone.receive_json(timeout=0.6)
                await phone.close()
                await agent.close()

        asyncio.run(scenario())

    def test_agent_info_clear_downgrade_blocked_with_session(self, running_relay):
        """Je E2E-Session ist der Klartext gesperrt (Adapter muss .enc senden)."""
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await _connect_phone_agent(session, _PORT)
                running_relay.crypto_sessions["w26-info-sess"] = {
                    "agent_id": "alpha", "device_id": "phone",
                    "ts": time.time() * 1000, "acked": True}
                await agent.send_json({
                    "type": "agent.info", "display_name": "Alpha W26",
                    "adapter_caps": ["models"]})
                with pytest.raises(Exception):
                    await phone.receive_json(timeout=0.6)
                await phone.close()
                await agent.close()

        asyncio.run(scenario())

    def test_agent_info_enc_opaque_passthrough(self, running_relay):
        """enc je bestätigter Session = opaker Pass-Through (Typ bleibt,
        ct bleibt, Replay-Fenster akzeptiert einen frischen Zähler)."""
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await _connect_phone_agent(session, _PORT)
                running_relay.crypto_sessions["w26-info-sess"] = {
                    "agent_id": "alpha", "device_id": "phone",
                    "ts": time.time() * 1000, "acked": True}
                await agent.send_json(
                    _enc_frame("w26-info-sess", n=1, chat_id="chat-1"))
                frame = await phone.receive_json(timeout=2)
                assert frame["type"] == "agent.info.enc"
                assert frame["session_id"] == "w26-info-sess"
                assert frame["chat_id"] == "chat-1"       # AAD-Kette (J4-Fund 1)
                assert "ct" in frame and "hdr" in frame and "nonce" in frame
                await phone.close()
                await agent.close()

        asyncio.run(scenario())
