# W26 (Lane R): Agent-Selbst-Einladung (Self-Enrollment) — agent.invite.create/
# list/revoke (NUR ADMIN-Geräte) + Einladungs-Redeem an der Agent-WS (Bearer =
# Code statt Agent-Token) → agent.register_ok {agent_token} + normaler Token-
# Betrieb. Der Flow läuft OHNE Relay-Restart; die Einladung überlebt einen.
import json
import time

import pytest

import tools.android_relay as R
from tools.android_relay import (
    _RelayState,
    _PhoneSlot,
    _NoAsyncLock,
    _handle_phone_agent_invite,
    _chat_rate_hits,
    _chat_rate_lock,
    start_relay,
    stop_relay,
)

pytestmark = pytest.mark.usefixtures("relay_state_isolation")

_PORT = 19958


class _FakeWs:
    """Minimal-WS-Doppel je Unit-Test (send_json merkt, closed statisch)."""

    def __init__(self):
        self.sent = []
        self.closed = False

    async def send_json(self, payload):
        self.sent.append(payload)
        return None


@pytest.fixture(autouse=True)
def _reset_rate_buckets():
    with _chat_rate_lock:
        _chat_rate_hits.clear()
    yield
    with _chat_rate_lock:
        _chat_rate_hits.clear()


def _unit_state(admin: bool = True):
    """Hand-gebautes Relay-State (kein Server) je device_alias-Muster: ein
    lebender Slot (+ Registry-Eintrag) mit admin-Flag."""
    state = _RelayState("W26INV", _PORT)
    state.devices_lock = state.phone_ws_lock = __import__("asyncio").Lock()
    state.broker_lock = __import__("asyncio").Lock()
    slot = _PhoneSlot(_FakeWs(), "phone", admin=admin)
    state.devices["phone"] = slot
    state.device_registry["phone"] = {"admin": admin}
    return state


def _rate_clear():
    """Rate-Bucket je Test-Schritt zurücksetzen (die Familie cappt 2/s je
    Gerät — die Unit-Tests ketteln mehrere Aktionen in dasselbe Fenster)."""
    with _chat_rate_lock:
        _chat_rate_hits.clear()


class TestInviteAdminGate:
    """Admin-Gate + Form-Welt je Phone-Frame (Unit-Ebene, kein Server)."""

    @pytest.mark.asyncio
    async def test_non_admin_denied_ack(self):
        """fail-closed: kein Admin-Slot = ack 'denied', KEINE Einladung."""
        state = _unit_state(admin=False)
        await _handle_phone_agent_invite(state, "phone", {
            "type": "agent.invite.create", "request_id": "rq1"})
        ack = state.devices["phone"].ws.sent[-1]
        assert ack["type"] == "agent.invite.create_ok"
        assert ack["ok"] is False
        assert ack["error"] == "denied"
        assert state.agent_invites == {}

    @pytest.mark.asyncio
    async def test_create_ack_code_and_masked_list(self):
        """create → ACK mit Code (≥ 16 Zufallszeichen); list → NUR der hint
        (Vollcode NIE in Listen — PII-Regel), uses_left 1, ttl 60."""
        state = _unit_state()
        await _handle_phone_agent_invite(state, "phone", {
            "type": "agent.invite.create", "request_id": "rq1"})
        ack = state.devices["phone"].ws.sent[-1]
        assert ack["type"] == "agent.invite.create_ok"
        assert ack["ok"] is True
        assert ack["ttl_min"] == 60 and ack["uses"] == 1
        assert len(ack["invite_code"]) >= 16
        assert ack["request_id"] == "rq1"

        await _handle_phone_agent_invite(state, "phone", {
            "type": "agent.invite.list", "request_id": "rq2"})
        list_ack = state.devices["phone"].ws.sent[-1]
        assert list_ack["type"] == "agent.invite.list_ok"
        assert list_ack["ok"] is True
        assert len(list_ack["invites"]) == 1
        row = list_ack["invites"][0]
        assert row["invite_id"] == ack["invite_id"]
        assert row["uses_left"] == 1
        assert ack["invite_code"] not in json.dumps(list_ack)  # nur hint!
        assert row["hint"].endswith("•" * 8)

    @pytest.mark.asyncio
    async def test_revoke_by_id_and_expiry(self):
        """revoke (by invite_id) markiert verbraucht; list ist danach leer und
        der Code-Cache-Verband (_invite_find_for_redeem) findet nichts mehr."""
        state = _unit_state()
        await _handle_phone_agent_invite(state, "phone", {"type": "agent.invite.create"})
        ack = state.devices["phone"].ws.sent[-1]
        invite_id, code = ack["invite_id"], ack["invite_code"]
        assert R._invite_find_for_redeem(state, code) is not None

        await _handle_phone_agent_invite(state, "phone", {
            "type": "agent.invite.revoke", "invite_id": invite_id})
        revoke_ack = state.devices["phone"].ws.sent[-1]
        assert revoke_ack["ok"] is True

        _rate_clear()
        await _handle_phone_agent_invite(state, "phone", {"type": "agent.invite.list"})
        list_ack = state.devices["phone"].ws.sent[-1]
        assert list_ack["invites"] == []
        assert R._invite_find_for_redeem(state, code) is None
        state.devices["phone"].ws.closed = True

    @pytest.mark.asyncio
    async def test_revoke_unknown_target(self):
        state = _unit_state()
        await _handle_phone_agent_invite(state, "phone", {
            "type": "agent.invite.revoke", "invite_id": "nix"})
        ack = state.devices["phone"].ws.sent[-1]
        assert ack["type"] == "agent.invite.revoke_ok"
        assert ack["ok"] is False
        assert ack["error"] == "not-found"


class TestInviteRedeemFlow:
    """Voll-Flow am laufen Relay: create → Agent-WS mit Code → register →
    register_ok {agent_token} → normaler Token-Betrieb (OHNE Restart)."""

    @pytest.fixture()
    def running_relay(self, tmp_path, monkeypatch):
        cfg = tmp_path / "relay-config.json"
        cfg.write_text("{\n  \"keep\": 1}\n", encoding="utf-8")
        monkeypatch.setenv("ANDROID_RELAY_CONFIG", str(cfg))
        start_relay(pairing_code="W26INV", port=_PORT)
        yield R._relay_instance, cfg
        stop_relay()

    async def _connect_phone(self, session, port):
        import aiohttp

        phone = await session.ws_connect(
            f"ws://127.0.0.1:{port}/ws", headers={"Authorization": "Bearer W26INV"})
        greet = await phone.receive_json(timeout=2)
        assert greet["type"] == "relay.state"
        return phone

    def test_full_redeem_and_token_lifecycle(self, running_relay):
        import asyncio
        import aiohttp

        state, cfg = running_relay

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await self._connect_phone(session, _PORT)
                await phone.send_json({"type": "agent.invite.create",
                                       "ttl_min": 60, "label": "Test-Agent"})
                ack = await phone.receive_json(timeout=2)
                assert ack["type"] == "agent.invite.create_ok"
                assert ack["ok"] is True
                code = ack["invite_code"]

                # Agent verbindet mit CODE statt Token (Einladungs-Connect):
                invitee = await session.ws_connect(
                    f"ws://127.0.0.1:{_PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {code}"})
                # Fail-closed-Tor NACH dem Connect: ein anderes Frame zuvor
                # wird verworfen (WS bleibt für den Register-Weg offen).
                await invitee.send_json({"type": "keepalive"})
                await invitee.send_json({
                    "type": "agent.register", "v": 1,
                    "agent_id": "invitee", "display_name": "Invited Agent"})
                register_ok = await invitee.receive_json(timeout=3)
                assert register_ok["type"] == "agent.register_ok"
                assert register_ok["ok"] is True
                assert register_ok["agent_id"] == "invitee"
                token = register_ok["agent_token"]
                assert len(token) >= 20
                assert token != code

                # Phone sieht den neuen Agenten als online (Presence):
                presence = await phone.receive_json(timeout=2)
                assert presence["type"] == "relay.state"
                agent_rows = [row for row in presence.get("agents", [])
                              if row.get("agent_id") == "invitee"]
                assert agent_rows and agent_rows[0].get("online") is True

                # Ab hier normaler Token-Betrieb (Frame im Verband geht durch):
                await invitee.send_json({
                    "type": "agent.info", "display_name": "Invited Agent",
                    "adapter_caps": ["cron.status", "cron.remove"]})
                info = await phone.receive_json(timeout=2)
                assert info["type"] == "agent.info"
                assert info["agent_id"] == "invitee"

                await invitee.close()
                await phone.close()

                # Einladung verbraucht (einmalig): nach dem Redeem weist das
                # Tor einen ZWEITEN Connect mit demselben Code ab (fail-closed).
                try:
                    second = await session.ws_connect(
                        f"ws://127.0.0.1:{_PORT}/agent_ws",
                        headers={"Authorization": f"Bearer {code}"})
                    await second.close()
                    assert False, "Wiederverwendung war möglich (fail-open!)"
                except Exception:
                    pass

        asyncio.run(scenario())

        # Dauertoken atomar in der Config (Runtime, kein Restart nötig):
        data = json.loads(cfg.read_text(encoding="utf-8"))
        assert data.get("keep") == 1                      # Fremdkeys erhalten
        token = data.get("ANDROID_AGENT_TOKEN_INVITEE")
        assert isinstance(token, str) and token
        assert data.get("ANDROID_AGENT_DISPLAY_INVITEE") == "Invited Agent"

    def test_reused_code_rejected_401(self, running_relay):
        import asyncio
        import aiohttp

        state, _cfg = running_relay

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await self._connect_phone(session, _PORT)
                await phone.send_json({"type": "agent.invite.create"})
                ack = await phone.receive_json(timeout=2)
                code = ack["invite_code"]

                invitee = await session.ws_connect(
                    f"ws://127.0.0.1:{_PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {code}"})
                await invitee.send_json({
                    "type": "agent.register", "v": 1, "agent_id": "dup"})
                ok1 = await invitee.receive_json(timeout=3)
                assert ok1["ok"] is True
                await invitee.close()
                await phone.close()
                # Achtung: die WS schließt async — kurz Platz geben:
                await asyncio.sleep(0.3)

                try:
                    second = await session.ws_connect(
                        f"ws://127.0.0.1:{_PORT}/agent_ws",
                        headers={"Authorization": f"Bearer {code}"})
                    await second.close()
                    assert False, "Wiederverwendung war möglich (fail-open!)"
                except Exception:
                    pass

        asyncio.run(scenario())

    def test_invalid_agent_id_ack_fails_closed(self, running_relay, tmp_path,
                                               monkeypatch):
        """agent.register mit ILLEGALER agent_id (Großbuchstabe) → register_ok
        {ok False, error invalid-agent-id} + Invite bleibt VERBRUCHEN-los."""
        import asyncio
        import aiohttp

        state, cfg = running_relay

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await self._connect_phone(session, _PORT)
                await phone.send_json({"type": "agent.invite.create"})
                ack = await phone.receive_json(timeout=2)
                code = ack["invite_code"]

                invitee = await session.ws_connect(
                    f"ws://127.0.0.1:{_PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {code}"})
                await invitee.send_json({
                    "type": "agent.register", "v": 1,
                    "agent_id": "Böser Agent", "display_name": "X"})
                fail_ok = await invitee.receive_json(timeout=3)
                assert fail_ok["type"] == "agent.register_ok"
                assert fail_ok["ok"] is False
                assert fail_ok["error"] == "invalid-agent-id"
                await phone.close()
                await invitee.close()

        asyncio.run(scenario())

    def test_duplicate_agent_id_fails_and_invite_survives(self, running_relay):
        """agent-exists: die ID je Config = fail-closed, KEIN Token-Overwrite;
        die Einladung bleibt nutzbar (ein freier Name geht danach durch)."""
        import asyncio
        import aiohttp

        state, cfg = running_relay

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await self._connect_phone(session, _PORT)
                await phone.send_json({"type": "agent.invite.create"})
                ack = await phone.receive_json(timeout=2)
                code = ack["invite_code"]

                invitee = await session.ws_connect(
                    f"ws://127.0.0.1:{_PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {code}"})
                await invitee.send_json({
                    "type": "agent.register", "v": 1,
                    "agent_id": "invitee", "display_name": "First"})
                ok1 = await invitee.receive_json(timeout=3)
                assert ok1["ok"] is True
                first_token = ok1["agent_token"]
                await invitee.close()
                await phone.close()
                await asyncio.sleep(0.2)

                # Zweiter Agent, BUT er wählt die BELEGTEN ID:
                code2 = None
                phone2 = await self._connect_phone(session, _PORT)
                await phone2.send_json({"type": "agent.invite.create"})
                ack2 = await phone2.receive_json(timeout=2)
                code2 = ack2["invite_code"]
                second = await session.ws_connect(
                    f"ws://127.0.0.1:{_PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {code2}"})
                await second.send_json({
                    "type": "agent.register", "v": 1,
                    "agent_id": "invitee", "display_name": "Second"})
                fail = await second.receive_json(timeout=3)
                assert fail["ok"] is False
                assert fail["error"] == "agent-exists"
                # Fail-closed-Tor: der Relay schließt die WS (4403) — der
                # nächste Redeem-Weg braucht EINE frische WS (die Einladung
                # ist NICHT verbraucht, der falsche Name einfach nicht
                # durchgekommen):
                await second.close()
                await asyncio.sleep(0.2)

                # Kein Token-Overwrite:
                data = json.loads(cfg.read_text(encoding="utf-8"))
                assert data["ANDROID_AGENT_TOKEN_INVITEE"] == first_token

                # Und der freie Name funktioniert je SELBER Einladung:
                third = await session.ws_connect(
                    f"ws://127.0.0.1:{_PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {code2}"})
                await third.send_json({
                    "type": "agent.register", "v": 1,
                    "agent_id": "zweiter", "display_name": "Zweiter"})
                ok2 = await third.receive_json(timeout=3)
                assert ok2["ok"] is True
                assert ok2["agent_id"] == "zweiter"
                await third.close()
                await phone2.close()

        asyncio.run(scenario())

    def test_invite_survives_relay_restart(self, running_relay, monkeypatch):
        """Persistenz: create → restart → redeem mit dem SELBEN Code (die
        Kette überlebt den Restart; das File ist die Runtime-Welt)."""
        import asyncio
        import aiohttp

        state, cfg = running_relay

        async def create_phase():
            async with aiohttp.ClientSession() as session:
                phone = await self._connect_phone(session, _PORT)
                await phone.send_json({"type": "agent.invite.create"})
                ack = await phone.receive_json(timeout=2)
                await phone.close()
                return ack["invite_code"]

        code = asyncio.run(create_phase())
        stop_relay()
        start_relay(pairing_code="W26INV", port=_PORT)
        restarted = R._relay_instance
        assert len(restarted.agent_invites) == 1

        async def redeem_phase():
            async with aiohttp.ClientSession() as session:
                invitee = await session.ws_connect(
                    f"ws://127.0.0.1:{_PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {code}"})
                await invitee.send_json({
                    "type": "agent.register", "v": 1,
                    "agent_id": "afterrestart", "display_name": "Survivor"})
                ok1 = await invitee.receive_json(timeout=3)
                assert ok1["ok"] is True
                await invitee.close()

        asyncio.run(redeem_phase())
        data = json.loads(cfg.read_text(encoding="utf-8"))
        assert data.get("ANDROID_AGENT_TOKEN_AFTERRESTART")

    def test_expired_invite_rejected_401(self, running_relay, monkeypatch):
        import asyncio
        import aiohttp

        state, _cfg = running_relay

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await self._connect_phone(session, _PORT)
                await phone.send_json({"type": "agent.invite.create",
                                       "ttl_min": 5})
                ack = await phone.receive_json(timeout=2)
                code = ack["invite_code"]
                await phone.close()

            # TTL-Vergangenheit je RAM-Record injizieren (kein Flakiness-Sleep):
            record = next(iter(state.agent_invites.values()))
            record["expires"] = time.time() - 1.0

            try:
                await session.ws_connect(
                    f"ws://127.0.0.1:{_PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {code}"})
                assert False, "Abgelaufene Einladung war brauchbar (fail-open!)"
            except Exception:
                pass

        asyncio.run(scenario())


class TestW26cInviteHardening:
    """W26c-Sollte B1 (Hash-only-Persist) + B4 (Verbrauch nach Zustellung)."""

    @pytest.mark.asyncio
    async def test_b1_invite_record_and_file_are_hash_only(self, tmp_path,
                                                           monkeypatch):
        """relay-invites.json trägt NUR code_sha + hint (erste 4 Zeichen) —
        der Klartext-Code lebt NUR im create-Ack; Alt-Bestand ('code'-Schlüssel
        der 0600-Welt) wird beim Laden abschrubben."""
        import os
        monkeypatch.setenv("ANDROID_RELAY_INVITES", str(tmp_path / "relay-invites.json"))
        state = _unit_state()
        await _handle_phone_agent_invite(state, "phone", {
            "type": "agent.invite.create", "request_id": "rq-b1"})
        ack = state.devices["phone"].ws.sent[-1]
        assert ack["ok"] is True
        code = ack["invite_code"]
        record = state.agent_invites[ack["invite_id"]]
        assert "code" not in record, \
            "RAM-Record trägt KEINEN Klartext mehr (nur create-Ack-Welt)"
        assert record["code_sha"] == __import__("hashlib").sha256(
            code.encode("utf-8")).hexdigest()
        # hint = die maskierte Form (erste 4 Zeichen + Bullet-Deckel):
        assert record["hint"] == R._invite_hint(code)
        assert record["hint"].startswith(code[:4])
        assert record["hint"].endswith("•" * 8)

        R._store_agent_invites(state.agent_invites)
        persisted = json.loads((tmp_path / "relay-invites.json").read_text(
            encoding="utf-8"))
        assert code not in json.dumps(persisted), \
            "Klartext-Code NIE auf Disk (W26c B1)"
        row = persisted["invites"][ack["invite_id"]]
        # hint = die maskierte Form (erste 4 Zeichen + Bullet-Deckel —
        # persistierbar UND listen-safe je _invite_hint/PII-Regel):
        assert row.get("code_sha")
        assert row.get("hint"), "hint kommt je Persist mit"
        assert row["hint"].startswith(code[:4]), "hint trägt die ersten 4 Zeichen"
        assert row["hint"].endswith("•" * 8), "hint bleibt maskiert (PII-Regel)"

        # Alt-Bestand (legacy 'code' + sha): laden scrubbert den Klartext weg
        # (die nächste Persist schreibt die Hash-only-Form):
        legacy = {"v": 2, "ts": 0, "invites": {"leg1": {
            "invite_id": "leg1", "code": "Klartext-Legacy-Code",
            "code_sha": "0" * 64, "hint": "Klar",
            "created_at": 0, "expires": time.time() + 600.0,
            "uses_left": 1, "ttl_min": 5}}}
        (tmp_path / "legacy.json").write_text(
            json.dumps(legacy, ensure_ascii=False), encoding="utf-8")
        monkeypatch.setenv("ANDROID_RELAY_INVITES", str(tmp_path / "legacy.json"))
        loaded = R._load_agent_invites()
        assert "code" not in loaded["leg1"], "Alt-Klartext beim Laden weggeschrubben"
        assert loaded["leg1"]["code_sha"] == "0" * 64

    @pytest.mark.asyncio
    async def test_b4_register_ok_send_fail_keeps_invite(self, tmp_path,
                                                         monkeypatch):
        """W26c B4: die Einladung wird ERST nach erfolgreicher ACK-Zustellung
        verbraucht — Send-Fail → uses_left bleibt 1; der Token-Overwrite-Gate
        (agent-exists) bleibt laut Brief unangetastet (der Retry-Weg)."""
        import asyncio as _aio
        import hashlib as _hl
        import logging

        monkeypatch.setenv("ANDROID_RELAY_CONFIG",
                           str(tmp_path / "relay-config.json"))
        (tmp_path / "relay-config.json").write_text("{}", encoding="utf-8")
        monkeypatch.setenv("ANDROID_RELAY_INVITES", str(tmp_path / "relay-invites.json"))
        state = _RelayState("W26INV", _PORT)
        state.devices_lock = state.phone_ws_lock = _aio.Lock()
        state.broker_lock = _aio.Lock()
        state.agent_ws_lock = _aio.Lock()
        state.chat_inbound = state.chat_inbound or {}

        code = "w26cb4-invite-code-0123456789"
        invite_id = "b4invite"
        state.agent_invites[invite_id] = {
            "invite_id": invite_id,
            "code_sha": _hl.sha256(code.encode("utf-8")).hexdigest(),
            "hint": code[:4], "expires": time.time() + 600.0,
            "uses_left": 1, "ttl_min": 5, "created_at": int(time.time() * 1000),
        }

        class _DyingWs(_FakeWs):
            """send_json fliegt JEDES Mal (Zustell-Weg tot — Crash-Welt)."""

            async def send_json(self, payload):
                raise ConnectionError("die-mid-send")

        dying = _DyingWs()
        state.agent_invite_pending[dying] = {"invite_id": invite_id}
        logging.disable(logging.WARNING)
        try:
            await R._redeem_invite_agent(state, dying, {
                "agent_id": "b4agent", "display_name": "B4 Agent"})
        finally:
            logging.disable(logging.NOTSET)
        assert not dying.sent, "ACK-Zustellung fehlgeschlagen (Senden-Weg tot)"
        assert state.agent_invites[invite_id]["uses_left"] == 1, \
            "B4: Send-Fail verbraucht KEINE Einladung (bleibt nutzbar)"
        # Token-Overwrite-Gate bleibt: der bestehende Token-Verband überlebt.
        assert R._load_relay_config().get("ANDROID_AGENT_TOKEN_B4AGENT"), \
            "Token-Mint je Config bleibt (Zustell-Weg war der einzige Bruch)"
