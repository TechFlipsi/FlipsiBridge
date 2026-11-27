"""W28 Lane E: Slot-Replay je Geräte-WS-Connect (Soll-Check-Fund 8).

Verbindet sich ein GERÄT (Handy/Tablet) NACH agent-/subagent-Start mit dem
Relay, sah es offene Sub-Slots und arbeitende Agenten NICHT: der Adapter
emittiert seinen W20-Status-Snapshot (_emit_status_snapshot) nur bei
ADAPTER-(Re)Connect, das Relay meldete am Geräte-Connect nur
agents/devices/session_active (_send_phone_state). Lane E füllt die Lücke
minimal: das Replay sendet je Geräte-(Re)Connect den letzten bekannten
Stand je Agent an DIESE Geräte-WS — EIN agent.status-Haupt-Frame + je
offenem Sub-Slot EINEN Upsert-Frame.

Frame-Vertrag aus dem App-Code gelesen (nicht geraten):
- RelayClient.kt: 'agent.status' → handleAgentStatusFrame → AgentStatusRules
  (sub == null → Haupt-Status last-write-wins; sub mit end != true → Slot-
  Upsert je sub.id; end == true → Entfernung; started_at fällt auf now).
- AgentStatusRules.kt: working (striktes bool) + agent_id Pflicht,
  label/id/name ≤ 64, MAX_SUBS_PER_AGENT = 16 (Relay-Cap-Parität).
- RelayClient räumt agentStatus/agentSubs an allen 3 Verbindungsverlust-
  Punkten (disconnect()/onClosed/onFailure) — das Replay ERSETZT, es
  addiert nie.
- adapter.py _emit_status_snapshot (W20): Haupt-Frame + je offenem Slot
  EIN Upsert-Frame, started_at = jetzt — das Relay-Replay spiegelt exakt
  diese Welt auf der Geräte-Seite (KEIN neuer Frame-Typ).
"""

import asyncio
import json
import os
import sys
import time

import aiohttp
import pytest

from tools.android_relay import start_relay, stop_relay

PORT = 19981
CODE = "W28LANEE1"
AGENT_TOKEN = "laneE-agent-token"
DEVICE_A = "device-e-a"
DEVICE_B = "device-e-b"
DEVICE_C = "device-e-c"

MAIN_BODY = {"agent_id": "alpha", "working": True,
             "started_at": 1730515200000, "label": "Recherche"}
SLOT_BODY = {"agent_id": "alpha", "working": True,
             "started_at": 1730515200000,
             "sub": {"id": "sub-1", "name": "Unteragent-A"}}


@pytest.fixture(autouse=True)
def _lane_e_relay_reset():
    yield
    stop_relay()


def _write_registry(tmp_path) -> str:
    """Registry v2 vorgeseedet: A = Admin, B/C = bekannte Zweitgeräte."""
    path = tmp_path / "relay-devices.json"
    path.write_text(json.dumps({
        "v": 2, "ts": 0,
        "devices": {
            DEVICE_A: {"alias": "Admin", "admin": True, "epoch_of_enroll": 1},
            DEVICE_B: {"alias": "", "admin": False, "epoch_of_enroll": 1},
            DEVICE_C: {"alias": "", "admin": False, "epoch_of_enroll": 1},
        },
    }), encoding="utf-8")
    return str(path)


def _write_config(tmp_path) -> str:
    path = tmp_path / "relay-config.json"
    path.write_text(json.dumps({
        "ANDROID_AGENT_TOKEN_ALPHA": AGENT_TOKEN,
        "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha",
        "CHAT_RATE_PER_SECOND": 50,
    }), encoding="utf-8")
    return str(path)


def _start(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("ANDROID_RELAY_CONFIG", _write_config(tmp_path))
    monkeypatch.setenv("ANDROID_RELAY_DEVICE_REGISTRY",
                       _write_registry(tmp_path))
    start_relay(pairing_code=CODE, port=PORT)


async def _agent_connect(http) -> aiohttp.ClientWebSocketResponse:
    return await http.ws_connect(
        f"ws://127.0.0.1:{PORT}/agent_ws",
        headers={"Authorization": f"Bearer {AGENT_TOKEN}"})


async def _phone_connect(http, device_id: str) -> aiohttp.ClientWebSocketResponse:
    """Geräte-WS + Hallo-Frame (relay.state) konsumieren."""
    ws = await http.ws_connect(
        f"ws://127.0.0.1:{PORT}/ws",
        headers={"Authorization": f"Bearer {CODE}", "X-Device-Id": device_id})
    hallo = await ws.receive_json(timeout=6)
    assert hallo["type"] == "relay.state"
    return ws


async def _collect(ws, timeout: float = 0.8) -> list:
    """Alle Frames im Fenster einsammeln (leer = nichts kam)."""
    frames = []
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return frames
        try:
            frame = await ws.receive_json(timeout=remaining)
        except asyncio.TimeoutError:
            return frames
        frames.append(frame)


def _status_frames(frames) -> list:
    return [f for f in frames if f.get("type") == "agent.status"]


async def _wait_cache(pred, timeout: float = 5.0) -> None:
    """Auf einen Relay-Cache-Stand warten (Server-Thread verarbeitet die
    Agent-Frames asynchron — deterministisch statt blind senden+connecten)."""
    from tools.android_relay import _relay_instance
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = _relay_instance
        if state is not None and pred(state):
            return
        await asyncio.sleep(0.05)
    raise AssertionError("Relay-Cache-Stand kam nicht (Timeout)")


async def _send_agent_frames(agent, *bodies) -> None:
    for body in bodies:
        await agent.send_json({"type": "agent.status", "v": 1, **body})


# ── Relay-E2E: Cache füllen, DANN Gerät verbinden ────────────────────────────


class TestW28LaneESlotReplayE2E:
    """Kern-Fund 8: frisch verbundene Geräte sehen den Live-Stand sofort."""

    def test_geraet_nach_agentstart_sieht_working_label_und_slot(self,
                                                                 tmp_path,
                                                                 monkeypatch):
        asyncio.run(self._test_replay(tmp_path, monkeypatch))

    async def _test_replay(self, tmp_path, monkeypatch) -> None:
        _start(tmp_path, monkeypatch)
        async with aiohttp.ClientSession() as http:
            # 1) Agent verbindet OHNE Gerät — Status-Frames laufen ins
            #    Leere (Live-Zustand, kein Spool), füllen aber den Cache.
            agent = await _agent_connect(http)
            await _send_agent_frames(agent, MAIN_BODY, SLOT_BODY)
            await _wait_cache(lambda s: (
                s.agent_status == {"alpha": (True, "Recherche")}
                and s.agent_sub_slots == {
                    "alpha": {"sub-1": ("Unteragent-A", True)}}))

            # 2) DAS Gerät verbindet ERST JETZT → Replay NACH dem Hallo-Frame.
            phone = await _phone_connect(http, DEVICE_A)
            frames = await _collect(phone, timeout=3)
            replay = _status_frames(frames)
            assert len(replay) == 2, f"Replay-Frames: {frames}"

            main, slot = replay
            # Haupt-Frame: Working-Stand + Label, KEIN sub (App faltet
            # last-write-wins), Feldsatz = App-Parser-Vertrag.
            assert main["agent_id"] == "alpha"
            assert main["working"] is True
            assert main["label"] == "Recherche"
            assert "sub" not in main
            assert isinstance(main["started_at"], int) and main["started_at"] > 0
            assert main["v"] == 1 and main["ts"] > 0
            # Slot-Frame: Upsert-Verband (end=False, sub.id/name ≤ 64) —
            # exakt die Adapter-Snapshot-Form (W20).
            assert slot["agent_id"] == "alpha"
            assert slot["sub"] == {"id": "sub-1", "name": "Unteragent-A"}
            assert slot["end"] is False
            assert slot["working"] is True
            assert isinstance(slot["started_at"], int) and slot["started_at"] > 0
            # Reihenfolge: Haupt-Stand VOR den Slots (Snapshot-Parität).
            assert frames.index(main) < frames.index(slot)

            # 3) Kein weiterer Frame — das Replay gilt NUR diesem Connect.
            assert _status_frames(await _collect(phone)) == []

            # 4) Reconnect desselben Geräts: identisches Replay (ERSETZT,
            #    addiert nicht — die App räumt ihren Stand je Verlust).
            await phone.close()
            again = await _phone_connect(http, DEVICE_A)
            replay2 = _status_frames(await _collect(again, timeout=3))
            assert len(replay2) == 2
            assert replay2[0]["working"] is True
            assert replay2[0]["label"] == "Recherche"
            assert replay2[1]["sub"]["id"] == "sub-1"
            assert _status_frames(await _collect(again)) == []
            await again.close()
            await agent.close()

    def test_leerer_cache_kein_frame_keine_exception(self, tmp_path,
                                                     monkeypatch):
        """Fail-soft-Kern: ohne Agent-Stand bleibt der Connect still und
        gesund — kein Slot-Frame, kein Fehler, Hallo kam regulär."""
        asyncio.run(self._test_empty(tmp_path, monkeypatch))

    async def _test_empty(self, tmp_path, monkeypatch) -> None:
        _start(tmp_path, monkeypatch)
        async with aiohttp.ClientSession() as http:
            phone = await _phone_connect(http, DEVICE_A)
            assert _status_frames(await _collect(phone)) == []
            # Verbindung bleibt benutzbar (kein Crash im Connect-Pfad):
            # ein Agent-Status kommt jetzt LIVE durch.
            agent = await _agent_connect(http)
            await _send_agent_frames(agent, MAIN_BODY)
            live = []
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline and not live:
                live = _status_frames(await _collect(phone, timeout=0.4))
            assert live and live[0]["working"] is True, \
                "Live-Frame nach leerem Replay kam nicht"
            await agent.close()
            await phone.close()

    def test_end_raeumt_slot_aus_dem_replay(self, tmp_path, monkeypatch):
        """Leichen-Regel: geräumte Slots sind aus dem Cache WEG — das
        Replay zeigt NUR noch den Haupt-Stand."""
        asyncio.run(self._test_corpses(tmp_path, monkeypatch))

    async def _test_corpses(self, tmp_path, monkeypatch) -> None:
        _start(tmp_path, monkeypatch)
        async with aiohttp.ClientSession() as http:
            agent = await _agent_connect(http)
            await _send_agent_frames(agent, MAIN_BODY, SLOT_BODY,
                                     {**SLOT_BODY,
                                      "started_at": 1730515200001,
                                      "end": True})
            await _wait_cache(lambda s: (
                s.agent_status == {"alpha": (True, "Recherche")}
                and s.agent_sub_slots == {}))

            phone = await _phone_connect(http, DEVICE_A)
            replay = _status_frames(await _collect(phone, timeout=3))
            assert len(replay) == 1, f"Leichen im Replay: {replay}"
            assert replay[0]["working"] is True
            assert replay[0]["label"] == "Recherche"
            assert "sub" not in replay[0]
            await phone.close()
            await agent.close()

    def test_zweitgeraet_und_agent_offline_raeumung(self, tmp_path,
                                                    monkeypatch):
        """B4-Welt: JEDES Gerät bekommt das Replay am EIGENEN Connect; stirbt
        der Agent, räumt der Relay den Live-Stand (frisch verbundene Geräte
        sehen ehrlich NICHTS — kein toter 'arbeitet…'-Stand)."""
        asyncio.run(self._test_multi(tmp_path, monkeypatch))

    async def _test_multi(self, tmp_path, monkeypatch) -> None:
        _start(tmp_path, monkeypatch)
        async with aiohttp.ClientSession() as http:
            agent = await _agent_connect(http)
            await _send_agent_frames(agent, MAIN_BODY, SLOT_BODY)
            await _wait_cache(lambda s: s.agent_sub_slots.get("alpha", {}).

                               get("sub-1") == ("Unteragent-A", True))

            phone_a = await _phone_connect(http, DEVICE_A)
            assert len(_status_frames(await _collect(phone_a, timeout=3))) == 2
            # Zweitgerät: SEIN Connect bringt SEIN Replay (kein Broadcast —
            # Gerät A bekommt davon nichts).
            assert _status_frames(await _collect(phone_a)) == []
            phone_b = await _phone_connect(http, DEVICE_B)
            replay_b = _status_frames(await _collect(phone_b, timeout=3))
            assert len(replay_b) == 2
            assert replay_b[1]["sub"]["id"] == "sub-1"
            assert _status_frames(await _collect(phone_a)) == []

            # Agent offline → Band + Slots geräumt (Presence-Guard: nur der
            # aktuelle Socket räumt — WS-Replace wischt nie frischen Stand);
            # danach ist das Replay ehrlich leer.
            await agent.close()
            await _wait_cache(lambda s: (not s.agent_status
                                         and not s.agent_sub_slots))
            phone_c = await _phone_connect(http, DEVICE_C)
            assert _status_frames(await _collect(phone_c, timeout=3)) == []
            await phone_c.close()
            await phone_b.close()
            await phone_a.close()


# ── Regel-Unit: Sub-Slot-Cache (Direkthandler wie W19) ───────────────────────


class TestW28EncSlotCacheNachtrag:
    """Reviewer-E-Auflage (P3): der enc-Pfad füttert den Sub-Slot-Cache über
    das GETEILTE _agent_status_dedup_allow — Regressionsschutz für den Fall,
    dass jemand das _agent_sub_slot_track je Handler hängt statt ins Sieb."""

    def test_enc_sub_frame_fuellt_den_slot_cache(self, monkeypatch):
        import asyncio
        import base64
        import time
        from tools import android_relay as relay

        state = relay._RelayState("code", PORT)
        state.broker_lock = asyncio.Lock()

        async def fake_session(state_, agent_id_, data_):
            return {"agent_id": agent_id_, "acked": True}

        monkeypatch.setattr(relay, "_enc_session_active", fake_session)

        body = {
            "agent_id": "alpha", "working": True,
            "started_at": 1730515200000, "label": "Recherche",
            "sub": {"id": "sub-1", "name": "Bauer"},
            "session_id": "sess-w28-enc-01",
            "hdr": {"rk": "a" * 64, "n": 0, "pn": 1},
            "nonce": base64.b64encode(bytes(12)).decode(),
            "ct": base64.b64encode(b"opak").decode(),
            "ts": int(time.time() * 1000),
        }

        async def run():
            await relay._handle_agent_status_enc(state, "alpha", dict(body))

        asyncio.run(run())
        # Cache-Track im enc-Pfad über das geteilte Sieg (Delivery ist ohne
        # Phone fail-soft — der Cache-Beweis zählt).
        assert state.agent_sub_slots == {"alpha": {"sub-1": ("Bauer", True)}}
        # Band bleibt Haupt-Aggregat (W19: Slot-Frames überschreiben nie).
        assert state.agent_status == {}


class TestW28LaneESubSlotCacheUnit:
    """_agent_sub_slot_track: Upsert/end-Räumung/Cap — der Band bleibt W19."""

    def _state(self):
        from tools import android_relay as relay
        state = relay._RelayState("code", PORT)
        state.broker_lock = asyncio.Lock()
        return state

    def _status(self, state, body, agent_id="alpha"):
        from tools import android_relay as relay

        async def run():
            await relay._handle_agent_status(state, agent_id, dict(body))

        asyncio.run(run())

    def test_upsert_end_und_band_unberuehrt(self):
        from tools import android_relay as relay
        state = self._state()
        body = {"agent_id": "alpha", "working": True, "started_at": 1}
        # Haupt-Frame → Band; Slot-Frames → Cache (Band UNBERÜHRT, W19);
        # end → Räumung; letzter Slot weg → Agent-Eintrag ganz weg.
        self._status(state, {**body, "label": "Recherche"})
        self._status(state, {**body, "sub": {"id": "s1", "name": "A"}})
        self._status(state, {**body, "sub": {"id": "s2", "name": "B"}})
        assert state.agent_status == {"alpha": (True, "Recherche")}
        assert state.agent_sub_slots == {
            "alpha": {"s1": ("A", True), "s2": ("B", True)}}
        self._status(state, {**body, "end": True,
                             "sub": {"id": "s1", "name": "A"}})
        assert state.agent_sub_slots == {"alpha": {"s2": ("B", True)}}
        self._status(state, {**body, "end": True,
                             "sub": {"id": "s2", "name": "B"}})
        assert state.agent_sub_slots == {}
        # end ohne sub = kein Slot-Bezug → Cache-No-op (die App faltet ihn
        # als Haupt-Frame; der Band bleibt von Slot-Frames unberührt).
        self._status(state, {**body, "sub": {"id": "s3", "name": "C"}})
        self._status(state, {**body, "end": True})
        assert state.agent_sub_slots == {"alpha": {"s3": ("C", True)}}
        # Cap-Parität zum Code-Bestand (Adapter _STATUS_SUB_SLOTS_CAP /
        # App AgentStatusRules.MAX_SUBS_PER_AGENT):
        assert relay._AGENT_SUB_SLOTS_PER_AGENT_CAP == 16

    def test_fifo_cap_16_paritaet_adapter_und_app(self):
        """17 offene Slots → der älteste fliegt (FIFO), der frischeste
        bleibt; Cap-Parität zum Adapter-Bestand (Beleg aus dem Original)."""
        state = self._state()
        body = {"agent_id": "alpha", "working": True, "started_at": 1}
        for i in range(17):
            self._status(state, {**body,
                                 "sub": {"id": f"s{i:02d}", "name": f"N{i}"}})
        assert len(state.agent_sub_slots["alpha"]) == 16
        assert "s00" not in state.agent_sub_slots["alpha"]
        assert "s16" in state.agent_sub_slots["alpha"]

        _PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                             "..", "contrib", "hermes-chat-plugin")
        if _PATH not in sys.path:
            sys.path.insert(0, _PATH)
        sys.modules.setdefault("gateway", None)
        import adapter as _adapter  # noqa: E402
        assert _adapter._STATUS_SUB_SLOTS_CAP == 16

    def test_band_eviction_räumt_slots_mit(self, monkeypatch):
        """_cap_agent_status hält beide Caches im Takt — das Replay sendet
        nie Slots ohne Haupt-Stand (Band-Eviction = Slot-Eviction)."""
        from tools import android_relay as relay
        state = self._state()
        monkeypatch.setattr(relay, "_AGENT_STATUS_MAX", 1)
        body = {"agent_id": "alpha", "working": True, "started_at": 1}
        # Erster Agent füllt Band (Cap 1) + Slot-Cache.
        self._status(state, {**body, "sub": {"id": "s1", "name": "A"}})
        self._status(state, {**body, "label": "L"})
        assert state.agent_status == {"alpha": (True, "L")}
        assert "alpha" in state.agent_sub_slots
        # Zweiter Agent verdrängt den ersten aus dem Band → SEINE Slots
        # räumen MIT (kein Slot-Waisen-Frame im Replay).
        self._status(state, {"agent_id": "beta", "working": False,
                             "started_at": 2}, agent_id="beta")
        assert state.agent_status == {"beta": (False, None)}
        assert "alpha" not in state.agent_sub_slots
        assert state.agent_sub_slots == {}

    def test_replay_rahmen_idempotent_app_parser_welt(self):
        """Frame-Welt des Replays = Live-Welt: die App-Parser-Pflichten
        (working strikt bool, agent_id str, sub.id/name ≤ 64, end bool)
        erfüllt jedes Replay-Frame — gebaut aus dem GATE-gesiebten Cache
        (dasselbe _agent_status_ok wie der Live-Weg, kein zweites Sieb)."""
        from tools import android_relay as relay
        state = self._state()

        async def run():
            # Mock-Geräte-WS wie die W19-Tests (AsyncMock, kein nackter Mock).
            from unittest import mock
            ws = mock.Mock()
            ws.closed = False
            ws.send_json = mock.AsyncMock()
            state.phone_ws = ws
            # Cache füllen: Haupt alpha (Band), Slot alpha (Slot-Cache, Band
            # UNBERÜHRT — W19-Regel), Haupt beta (Band, working ehrlich).
            await relay._handle_agent_status(state, "alpha", {
                "agent_id": "alpha", "working": True, "started_at": 5,
                "label": "x" * 64})
            await relay._handle_agent_status(state, "alpha", {
                "agent_id": "alpha", "working": True, "started_at": 5,
                "sub": {"id": "sub-langer-kennung", "name": "y" * 64}})
            await relay._handle_agent_status(state, "beta", {
                "agent_id": "beta", "working": False, "started_at": 6})
            ws.send_json.reset_mock()
            await relay._replay_agent_status_to_device(state, ws, "d-e")

            # 3 Frames: Haupt alpha, Slot alpha, Haupt beta (sortierte
            # Agent-Reihenfolge, Haupt vor Slots).
            frames = [c[0][0] for c in ws.send_json.call_args_list]
            assert [f["agent_id"] for f in frames] == ["alpha", "alpha", "beta"]
            assert frames[0]["label"] == "x" * 64
            assert "sub" not in frames[0] and "end" not in frames[0]
            assert frames[1]["sub"] == {"id": "sub-langer-kennung",
                                        "name": "y" * 64}
            assert frames[1]["end"] is False
            assert frames[2]["working"] is False
            for frame in frames:
                assert frame["type"] == "agent.status" and frame["v"] == 1
                assert isinstance(frame["working"], bool)  # App: streng bool
                assert isinstance(frame["started_at"], int)
                assert frame["ts"] > 0
            # Zweiter Lauf: identisches Frame-Set (Replay ERSETZT — kein
            # Verdoppeln, keine Kumulation im Cache).
            ws.send_json.reset_mock()
            await relay._replay_agent_status_to_device(state, ws, "d-e")
            frames2 = [c[0][0] for c in ws.send_json.call_args_list]
            assert len(frames2) == 3
            assert [f["agent_id"] for f in frames2] == ["alpha", "alpha",
                                                         "beta"]

        asyncio.run(run())

    def test_replay_leerer_cache_sendet_nichts(self):
        """Fail-soft-Regel: leerer Cache → kein Frame, keine Exception."""
        from unittest import mock
        from tools import android_relay as relay
        state = self._state()

        async def run():
            ws = mock.Mock()
            ws.closed = False
            ws.send_json = mock.AsyncMock()
            await relay._replay_agent_status_to_device(state, ws, "d-e")
            assert ws.send_json.call_count == 0

        asyncio.run(run())

    def test_replay_sendefehler_bleibt_fail_soft(self):
        """Toter Socket (send_json wirft) → Log statt Exception; der
        Cache bleibt unangetastet (kein Verlust — der nächste Connect
        holt denselben Stand)."""
        from unittest import mock
        from tools import android_relay as relay
        state = self._state()
        state.agent_status["alpha"] = (True, None)

        async def run():
            ws = mock.Mock()
            ws.closed = False
            ws.send_json = mock.AsyncMock(side_effect=RuntimeError("boom"))
            await relay._replay_agent_status_to_device(state, ws, "d-e")
            assert state.agent_status == {"alpha": (True, None)}

        asyncio.run(run())
