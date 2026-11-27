# W26 (Lane R): Cron-Steuer komplett — Router-Tests je cron.remove-Muster.
# Pflicht-Muster (Brief w26-brief-relay.md): die remove-Kette Z. ~1207 ist die
# Vorlage — Phone→Relay (Rate-Cap, fail-closed je E2E-Session, device_id-Stempel)
# → Agent-WS; ACKs (cron.pause_ok/cron.resume_ok/cron.add_ok) kommen je der
# cron.remove_ok-Verband-Welt (agentnotify-Bucket, Downgrade-Schutz) ans Phone.
import json
import time

import pytest

import tools.android_relay as R
from tools.android_relay import (
    _chat_rate_hits,
    _chat_rate_lock,
    start_relay,
    stop_relay,
    _phone_cron_add_job_ok,
)

pytestmark = pytest.mark.usefixtures("relay_state_isolation")


@pytest.fixture(autouse=True)
def _reset_rate_buckets():
    """Je Test saubere Rate-Buckets (Parität reset_chat_rate_state im
    Haupt-Testfile — hier eigener Fixture, damit die Datei autark bleibt)."""
    with _chat_rate_lock:
        _chat_rate_hits.clear()
    yield
    with _chat_rate_lock:
        _chat_rate_hits.clear()


@pytest.fixture()
def running_relay(tmp_path, monkeypatch):
    """Relay mit EINEM konfigurierten Agenten (token-Welt; die Router-Frames
    brauchen die Agent-WS als Empfangsseite)."""
    cfg = tmp_path / "relay-config.json"
    cfg.write_text(json.dumps({
        "ANDROID_AGENT_TOKEN_ALPHA": "w26-agent-token",
        "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha W26",
    }), encoding="utf-8")
    monkeypatch.setenv("ANDROID_RELAY_CONFIG", str(cfg))
    start_relay(pairing_code="W26CRON", port=19955)
    yield R._relay_instance
    stop_relay()


async def _connect_phone_agent(session, port, token="w26-agent-token"):
    """Phone-WS + Agent-WS verbinden, Presence/Hallo/Ident-Frames wegkonsumieren
    (die Router-Tests erwarten danach NUR ihre eigenen Steuer-Frames)."""
    import aiohttp

    phone = await session.ws_connect(
        f"ws://127.0.0.1:{port}/ws", headers={"Authorization": "Bearer W26CRON"})
    # 1 relay.state (Begrüßung je Connect):
    greet = await phone.receive_json(timeout=2)
    assert greet["type"] == "relay.state"

    agent = await session.ws_connect(
        f"ws://127.0.0.1:{port}/agent_ws",
        headers={"Authorization": f"Bearer {token}"})
    await agent.send_json({"type": "agent.register", "v": 1,
                           "agent_id": "alpha", "display_name": "Alpha W26"})
    # Ident-Frame je Register (PH-4-T2) + 1 relay.state (Agent-Presence):
    ident = await agent.receive_json(timeout=2)
    assert ident["type"] == "agent.ident"
    push = await phone.receive_json(timeout=2)
    assert push["type"] == "relay.state"
    return phone, agent


async def _inject_device_session(state, device_id="phone", agent_id="alpha"):
    """Session-Broker-Eintrag je remove-Muster (die Router-Welt routet JE
    E2E-Session je Gerät — die Tests bauen sie direkt am State; Beweis, dass
    die Bindung wirklich fail-closed ist, liefert der No-Session-Test)."""
    session_id = "w26-sess-1"
    state.crypto_sessions[session_id] = {
        "agent_id": agent_id, "device_id": device_id,
        "ts": time.time() * 1000, "acked": True,
    }
    return session_id


class TestCronCtrlRouter:
    """Phone→Agent-Router je remove-Muster (pause/resume/add)."""

    def test_pause_forwarded_and_ack_roundtrip(self, running_relay):
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await _connect_phone_agent(session, 19955)
                await _inject_device_session(running_relay)

                await phone.send_json({"type": "cron.pause", "cron_id": "job-1"})
                frame = await agent.receive_json(timeout=2)
                assert frame["type"] == "cron.pause"
                assert frame["v"] == 1
                assert frame["cron_id"] == "job-1"
                assert frame["device_id"] == "phone"

                # ACK-Rückkanal je cron.remove_ok-Verband. W26c (REALWELT): der
                # Adapter baut das ACK je AKTIVER Session ALS *.enc (Klartext
                # wird je Downgrade-Schutz verworfen — Beweis oben), der Relay
                # fächert es OPAK ans Phone; der Klar-ACK gilt NUR ohne Session
                # (deshalb beendet der Test die Session VOR der Klar-Leg — die
                # enc-Leg mit aktiver Session beweist test_pause_ack_enc_realwelt).
                running_relay.crypto_sessions.pop("w26-sess-1", None)
                await agent.send_json({
                    "type": "cron.pause_ok", "agent_id": "alpha",
                    "cron_id": "job-1", "ok": True, "device_id": "phone"})
                ack = await phone.receive_json(timeout=2)
                assert ack["type"] == "cron.pause_ok"
                assert ack["ok"] is True
                assert ack["cron_id"] == "job-1"
                assert ack["agent_id"] == "alpha"
                await phone.close()
                await agent.close()

        asyncio.run(scenario())

    def test_resume_forwarded(self, running_relay):
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await _connect_phone_agent(session, 19955)
                await _inject_device_session(running_relay)
                await phone.send_json({"type": "cron.resume", "cron_id": "job-2"})
                frame = await agent.receive_json(timeout=2)
                assert frame["type"] == "cron.resume"
                assert frame["cron_id"] == "job-2"
                assert frame["device_id"] == "phone"
                await phone.close()
                await agent.close()

        asyncio.run(scenario())

    def test_add_forwarded_with_valid_job(self, running_relay):
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await _connect_phone_agent(session, 19955)
                await _inject_device_session(running_relay)
                job = {"name": "Backup", "schedule": "*/5 * * * *",
                       "prompt": "Lauf das Backup", "deliver": "origin"}
                await phone.send_json({"type": "cron.add", "job": job})
                frame = await agent.receive_json(timeout=2)
                assert frame["type"] == "cron.add"
                assert frame["job"] == job  # unverändert durchgereicht
                assert frame["device_id"] == "phone"

                # add_ok-Rückkanal (Klar-Leg OHNE aktive Session — Parität
                # oben je Downgrade-Schutz-Welt):
                running_relay.crypto_sessions.pop("w26-sess-1", None)
                await agent.send_json({
                    "type": "cron.add_ok", "agent_id": "alpha",
                    "cron_id": "newid1", "name": "Backup", "ok": True})
                ack = await phone.receive_json(timeout=2)
                assert ack["type"] == "cron.add_ok"
                assert ack["ok"] is True
                assert ack["cron_id"] == "newid1"
                assert ack["name"] == "Backup"
                await phone.close()
                await agent.close()

        asyncio.run(scenario())

    def test_add_without_name_ack_fails_closed_on_relay_gate(self, running_relay):
        """add_ok OHNE gültiges name = Verworb am Relay-Verband (nichts am
        Phone an) — der ACK-Aufbau (ok/error/cron_id) bleibt Adapter-Lane."""
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await _connect_phone_agent(session, 19955)
                await _inject_device_session(running_relay)
                running_relay.crypto_sessions.pop("w26-sess-1", None)
                await agent.send_json({
                    "type": "cron.add_ok", "agent_id": "alpha",
                    "ok": False, "error": "error"})
                with pytest.raises(Exception):
                    await phone.receive_json(timeout=0.6)
                await phone.close()
                await agent.close()

        asyncio.run(scenario())

    def test_pause_ack_downgrade_blocked(self, running_relay):
        """Downgrade-Schutz je cron.remove_ok-Verband: bei AKTIVER E2E-Session
        wird der Klartext-ACK verworfen (der Adapter muss ein *.enc bauen —
        Beweis je Log-Zeile 'Klartext-… verworfen')."""
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await _connect_phone_agent(session, 19955)
                await _inject_device_session(running_relay)
                await agent.send_json({
                    "type": "cron.pause_ok", "agent_id": "alpha",
                    "cron_id": "job-1", "ok": True})
                with pytest.raises(Exception):
                    await phone.receive_json(timeout=0.6)
                await phone.close()
                await agent.close()

        asyncio.run(scenario())

    def test_pause_without_session_fail_closed(self, running_relay):
        """KEINE E2E-Session je Gerät = verworfen (fail-closed, remove-Muster)."""
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await _connect_phone_agent(session, 19955)
                await phone.send_json({"type": "cron.pause", "cron_id": "job-x"})
                with pytest.raises(Exception):
                    await agent.receive_json(timeout=0.6)
                await phone.close()
                await agent.close()

        asyncio.run(scenario())

    def test_add_invalid_job_discarded(self, running_relay):
        """Job-Form fail-closed: unbekanntes Feld/NICHT Strings = nichts beim
        Agenten (die Caps spiegeln die Adapter-Lane)."""
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await _connect_phone_agent(session, 19955)
                await _inject_device_session(running_relay)
                await phone.send_json({
                    "type": "cron.add",
                    "job": {"name": "X", "schedule": "@daily", "evil": 1}})
                with pytest.raises(Exception):
                    await agent.receive_json(timeout=0.6)
                await phone.send_json({
                    "type": "cron.add", "job": {"name": "X"}})  # schedule fehlt
                with pytest.raises(Exception):
                    await agent.receive_json(timeout=0.6)
                await phone.close()
                await agent.close()

        asyncio.run(scenario())

    def test_pause_rate_cap_2_per_second(self, running_relay):
        """EIGENER Rate-Bucket (2/s je Gerät) — der 3. Frame je Fenster geht
        NICHT an den Agenten (Parität cron.remove)."""
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await _connect_phone_agent(session, 19955)
                await _inject_device_session(running_relay)
                for i in range(2):
                    await phone.send_json({
                        "type": "cron.pause", "cron_id": f"job-{i}"})
                frame1 = await agent.receive_json(timeout=2)
                frame2 = await agent.receive_json(timeout=2)
                assert (frame1["cron_id"], frame2["cron_id"]) == ("job-0", "job-1")
                await phone.send_json({"type": "cron.pause", "cron_id": "job-2"})
                with pytest.raises(Exception):
                    await agent.receive_json(timeout=0.6)
                await phone.close()
                await agent.close()

        asyncio.run(scenario())


class TestCronCtrlUnitGates:
    """Unit-Gates: Job-Form + atomarer Config-Write (Einladungs-Welt)."""

    def test_job_gate_accepts_and_caps(self):
        job = _phone_cron_add_job_ok({
            "name": "N" * 200, "schedule": "  @daily ", "prompt": "P" * 5000,
            "deliver": "D" * 100})
        assert job is not None
        assert len(job["name"]) == 128
        assert job["schedule"] == "@daily"       # gestrippt
        assert len(job["prompt"]) == 4096
        assert len(job["deliver"]) == 64

    def test_job_gate_rejects(self):
        assert _phone_cron_add_job_ok(None) is None
        assert _phone_cron_add_job_ok("job") is None
        assert _phone_cron_add_job_ok({"schedule": "@daily"}) is None      # name fehlt
        assert _phone_cron_add_job_ok({"name": "n", "schedule": ""}) is None
        assert _phone_cron_add_job_ok({"name": "n", "schedule": 5}) is None
        assert _phone_cron_add_job_ok({"name": "n", "schedule": "s",
                                       "extra": 1}) is None                # deny-unknown
        # Brief-Cap: das INKOMMENDE Job-JSON ≤ 8 KB (fail-closed, kein Clip)…
        big = {"name": "n", "schedule": "s", "prompt": "x" * (8 * 1024)}
        assert _phone_cron_add_job_ok(big) is None
        # …während Feld-Clippping im Rahmen des Caps gültig bleibt (ein
        # ~5-KB-prompt-Feld kommt an und wird je Adapter-Cap auf 4096 gekappt).
        long_ok = _phone_cron_add_job_ok(
            {"name": "n", "schedule": "s", "prompt": "y" * 5000})
        assert long_ok is not None and len(long_ok["prompt"]) == 4096


# ── W26c (Jäger-Blocker 2): die *.enc-ACK-Familie ─────────────────────────────
# JE aktiver E2E-Session baut der Adapter das ACK ALS *.enc (Körper NUR im ct);
# der Relay ist opaker Pass-Through (agentinfo.enc-Verband: Rate agentnotify,
# Form-Gate, Session fail-closed, Replay-Fenster) und fächert ans Phone —
# er entschlüsselt NIE. Der REALWELT-Test: Session AKTIV → ACK kommt an.


import base64 as _b64          # noqa: E402 (Realwelt-Sektion hinter den Fixtures)


def _enc_ack_frame(frame_type, session_id="w26-sess-1", n=1, chat_id=""):
    """Form-gültiges cron.*_ok.enc (ct/nonce Fake-Opak — der Relay bleibt
    blind; ts = Pflichtfeld des ±10-min-Freshness-Fensters)."""
    return {"type": frame_type, "v": 1, "session_id": session_id,
            "hdr": {"rk": "a" * 64, "n": n, "pn": 0},
            "nonce": _b64.b64encode(b"\x01" * 12).decode("ascii"),
            "ct": _b64.b64encode(b"\x00" * 48).decode("ascii"),
            "chat_id": chat_id, "ts": int(time.time() * 1000)}


class TestCronAckEncRealwelt:
    """Session aktiv → das ACK kommt ans Phone (die Tötungs-Lücke von
    test_pause_ack_downgrade_blocked ist GESCHLOSSEN)."""

    def test_pause_ack_enc_realwelt_with_active_session(self, running_relay):
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await _connect_phone_agent(session, 19955)
                await _inject_device_session(running_relay)
                # Session bleibt AKTIV (exakt die Realwelt des Adapters, die
                # bis W26b das ACK im Klartext tötete):
                frame = _enc_ack_frame("cron.pause_ok.enc", n=1)
                await agent.send_json(frame)
                ack = await phone.receive_json(timeout=2)
                assert ack["type"] == "cron.pause_ok.enc"
                assert ack["session_id"] == "w26-sess-1"
                assert ack["agent_id"] == "alpha"
                assert ack["ct"] == frame["ct"] and ack["nonce"] == frame["nonce"] \
                    and ack["hdr"] == frame["hdr"], \
                    "body bleibt OPAK (ct unverändert — der Relay entschlüsselt nie)"
                await phone.close()
                await agent.close()

        asyncio.run(scenario())

    def test_remove_ack_enc_round_trip(self, running_relay):
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await _connect_phone_agent(session, 19955)
                await _inject_device_session(running_relay)
                await agent.send_json(_enc_ack_frame("cron.remove_ok.enc", n=2))
                ack = await phone.receive_json(timeout=2)
                assert ack["type"] == "cron.remove_ok.enc"
                await agent.send_json(_enc_ack_frame("cron.add_ok.enc", n=3))
                ack = await phone.receive_json(timeout=2)
                assert ack["type"] == "cron.add_ok.enc"
                await phone.close()
                await agent.close()

        asyncio.run(scenario())

    def test_ack_enc_fail_closed_without_session(self, running_relay):
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await _connect_phone_agent(session, 19955)
                running_relay.crypto_sessions.pop("w26-sess-1", None)
                await agent.send_json(_enc_ack_frame("cron.pause_ok.enc", n=1))
                with pytest.raises(Exception):
                    await phone.receive_json(timeout=0.6)
                # Ill-Form (kein ct) mit Session → ebenfalls nichts am Phone:
                await _inject_device_session(running_relay)
                bad = _enc_ack_frame("cron.resume_ok.enc", n=1)
                bad.pop("ct")
                await agent.send_json(bad)
                with pytest.raises(Exception):
                    await phone.receive_json(timeout=0.6)
                await phone.close()
                await agent.close()

        asyncio.run(scenario())


# ── W26c (Jäger-Blocker 3): Agent-Adresse am Steuer-Frame ─────────────────────
# Der Router wählte bis W26b die FRISCHESTE Geräte-Session (max-ts) — die
# Aktion an Agent A flog bei aktiverem Agent B zum FALSCHEN Besitzer. NUN
# routet der Relay je GENANNTEM Agenten (fail-closed: ehrlicher NACK statt
# Fallback) und stempelt agent_id in den weitergeleiteten Frame (die
# Adapter-Namespace-Grenze bekommt Zähne); ohne agent_id (Alt-App) bleibt
# der max-ts-Fallback mit LOUD-Log.


class TestAgentAddressRouting:
    """2-Agenten-Routing: die Aktion an A landet bei A — NICHT am frischesten B."""

    @staticmethod
    def _inject_two_sessions(running_relay):
        """alpha (älter) + beta (frischer) am SELBEN Gerät — der Alt-Fallback
        würde IMMER beta wählen (max-ts); der Genannte gewinnt dagegen."""
        running_relay.crypto_sessions["w26-sess-a"] = {
            "agent_id": "alpha", "device_id": "phone",
            "ts": time.time() * 1000 - 5_000, "acked": True,
        }
        running_relay.crypto_sessions["w26-sess-b"] = {
            "agent_id": "beta", "device_id": "phone",
            "ts": time.time() * 1000, "acked": True,
        }

    def test_two_agent_routing_named_agent_wins(self, running_relay):
        """Die Aktion an alpha landet bei alpha, OBWOHL beta die FRISCHERE
        Session trägt (max-ts wäre der Falsche); der weitergeleitete Frame
        trägt den agent_id-Stempel — und die GLEICHE job-Verbriefung bleibt
        je Adresse getrennt (gleichnamige Jobs kollabieren nicht)."""
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    "ws://127.0.0.1:19955/ws",
                    headers={"Authorization": "Bearer W26CRON"})
                greet = await phone.receive_json(timeout=2)
                assert greet["type"] == "relay.state"
                agent_a = await session.ws_connect(
                    "ws://127.0.0.1:19955/agent_ws",
                    headers={"Authorization": "Bearer w26-agent-token"})
                await agent_a.send_json({"type": "agent.register", "v": 1,
                                         "agent_id": "alpha",
                                         "display_name": "Alpha W26"})
                await agent_a.receive_json(timeout=2)  # agent.ident
                await phone.receive_json(timeout=2)    # relay.state (alpha)
                # Beta-Agent (zweiter Token je Config):
                R._store_agent_token_config("beta", "w26-beta-token", "Beta W26")
                agent_b = await session.ws_connect(
                    "ws://127.0.0.1:19955/agent_ws",
                    headers={"Authorization": "Bearer w26-beta-token"})
                await agent_b.send_json({"type": "agent.register", "v": 1,
                                         "agent_id": "beta",
                                         "display_name": "Beta W26"})
                await agent_b.receive_json(timeout=2)
                await phone.receive_json(timeout=2)
                TestAgentAddressRouting._inject_two_sessions(running_relay)
                # FRISCHE Session ist beta — der NAMED ist trotzdem alpha:
                running_relay.crypto_sessions["w26-sess-a"]["ts"] = \
                    time.time() * 1000 - 5_000
                running_relay.crypto_sessions["w26-sess-b"]["ts"] = \
                    time.time() * 1000
                await phone.send_json({"type": "cron.pause",
                                       "cron_id": "job-shared",
                                       "agent_id": "alpha"})
                frame = await agent_a.receive_json(timeout=2)
                assert frame["type"] == "cron.pause"
                assert frame["cron_id"] == "job-shared"
                assert frame["agent_id"] == "alpha", \
                    "agent_id-Stempel am weitergeleiteten Frame (Namespace-Grenze)"
                with pytest.raises(Exception):
                    await agent_b.receive_json(timeout=0.6)
                # Und umgekehrt — GLEICHE cron_id an beta bleibt beta (die
                # Verbriefungen zweier gleichnamiger Jobs bleiben getrennt):
                await phone.send_json({"type": "cron.resume",
                                       "cron_id": "job-shared",
                                       "agent_id": "beta"})
                frame = await agent_b.receive_json(timeout=2)
                assert frame["agent_id"] == "beta"
                assert frame["cron_id"] == "job-shared"
                with pytest.raises(Exception):
                    await agent_a.receive_json(timeout=0.6)
                await phone.close()
                await agent_a.close()
                await agent_b.close()

        asyncio.run(scenario())

    def test_named_unknown_agent_honest_nack(self, running_relay):
        """Fail-closed je GENANNTEM Agenten: unverortbar (keine Session an
        diesem Gerät) → ehrlicher NACK am Phone (ok=False error='agent-
        unknown' + Echo), NICHT der max-ts-Fallback zum falschen Agenten."""
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await _connect_phone_agent(session, 19955)
                await _inject_device_session(running_relay)   # alpha only
                await phone.send_json({"type": "cron.remove",
                                       "cron_id": "job-9",
                                       "agent_id": "ghost"})
                nack = await phone.receive_json(timeout=2)
                assert nack["type"] == "cron.remove_ok"
                assert nack["ok"] is False and nack["error"] == "agent-unknown"
                assert nack["cron_id"] == "job-9"
                with pytest.raises(Exception):
                    await agent.receive_json(timeout=0.6)
                await phone.close()
                await agent.close()

        asyncio.run(scenario())

    def test_alt_app_fallback_keeps_max_ts(self, running_relay):
        """Abwärtskompat: OHNE agent_id (Alt-App) routet der Relay auf die
        FRISCHESTE Session (LOUD-Log) — bewusst dokumentierter Bestand."""
        import asyncio
        import aiohttp

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    "ws://127.0.0.1:19955/ws",
                    headers={"Authorization": "Bearer W26CRON"})
                await phone.receive_json(timeout=2)
                agent_a = await session.ws_connect(
                    "ws://127.0.0.1:19955/agent_ws",
                    headers={"Authorization": "Bearer w26-agent-token"})
                await agent_a.send_json({"type": "agent.register", "v": 1,
                                         "agent_id": "alpha",
                                         "display_name": "Alpha W26"})
                await agent_a.receive_json(timeout=2)
                await phone.receive_json(timeout=2)
                R._store_agent_token_config("beta", "w26-beta-token", "Beta W26")
                agent_b = await session.ws_connect(
                    "ws://127.0.0.1:19955/agent_ws",
                    headers={"Authorization": "Bearer w26-beta-token"})
                await agent_b.send_json({"type": "agent.register", "v": 1,
                                         "agent_id": "beta",
                                         "display_name": "Beta W26"})
                await agent_b.receive_json(timeout=2)
                await phone.receive_json(timeout=2)
                TestAgentAddressRouting._inject_two_sessions(running_relay)
                running_relay.crypto_sessions["w26-sess-a"]["ts"] = \
                    time.time() * 1000 - 9_000
                running_relay.crypto_sessions["w26-sess-b"]["ts"] = \
                    time.time() * 1000
                await phone.send_json({"type": "cron.add",
                                       "job": {"name": "Alt-Anlage",
                                               "schedule": "@daily",
                                               "prompt": "Alt-App-Prompt"}})
                frame = await agent_b.receive_json(timeout=2)
                assert frame["type"] == "cron.add"
                assert frame["agent_id"] == "beta", \
                    "Alt-Fallback = frischeste Session (beta), LOUD-Log"
                with pytest.raises(Exception):
                    await agent_a.receive_json(timeout=0.6)
                await phone.close()
                await agent_a.close()
                await agent_b.close()

        asyncio.run(scenario())
