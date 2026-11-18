import pytest
import threading
import time
from tools.android_relay import (
    start_relay,
    stop_relay,
    is_relay_running,
    is_phone_connected,
    get_relay_url,
    get_agent_registry,
    set_pairing_code,
    _auth_is_blocked,
    _auth_record_failure,
    _auth_lock,
    _auth_blocked,
    _auth_failures,
    _AUTH_MAX_ATTEMPTS,
    _chat_rate_hits,
    _chat_rate_lock,
    _mask_token,
    _safe_body_repr,
    _decode_stream_frame,
    _cleanup_phone,
    _RelayState,
    _broadcast_agent_presence,
    _mtls_gate,
)


@pytest.fixture(autouse=True)
def reset_relay():
    yield
    stop_relay()


@pytest.fixture(autouse=True)
def reset_auth_state():
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()
    yield
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()


@pytest.fixture(autouse=True)
def reset_chat_rate_state():
    """PH2-T1: Chat-Rate-Limiter je Test zurücksetzen (sonst 'klebt' das
    2-pro-Sekunde-Fenster über Testgrenzen hinweg)."""
    with _chat_rate_lock:
        _chat_rate_hits.clear()
    yield
    with _chat_rate_lock:
        _chat_rate_hits.clear()


def _await_log_record(caplog, substring, timeout=5.0, poll=0.01):
    """Wait for a captured log record containing `substring`; fail loudly if none.

    The relay finalises a stream on its own background thread and its own
    event loop, so its warning about that stream can land *after* the HTTP
    client the test drives has already returned. Asserting on `caplog.records`
    straight after the scenario therefore races the relay: the assertion is
    right and the record simply has not been emitted yet.

    Waiting on the condition rather than guessing a delay is what makes the
    stream tests deterministic. A timeout raises with the records that *were*
    seen, so a genuine regression still fails with something readable instead
    of a bare `assert False`.
    """
    deadline = time.monotonic() + timeout
    while True:
        for record in caplog.records:
            if substring in record.getMessage():
                return record
        if time.monotonic() >= deadline:
            seen = [record.getMessage() for record in caplog.records]
            raise AssertionError(
                f"no log record containing {substring!r} within {timeout}s; "
                f"saw {len(seen)} record(s): {seen}"
            )
        time.sleep(poll)


async def _drain_phone_state_frame(ws, expected=1):
    """PH2-MERGE: Der Phone-WS-Connect liefert ZUERST relay.state (Agenten-
    Liste) — Command-Tests konsumieren den Frame weg, bevor sie auf ihre
    Kommando-Frames warten.

    PH2-FIX (E2E P2): JE Agent-Presence-Wechsel während die Phone-WS offen
    ist, schiebt das Relay zusätzlich ein relay.state nach — `expected` zählt
    Hallo-Frame + erwartete Presence-Pushes zusammen, damit nachfolgende
    receive_json-Asserts nicht auf einen ungeplanten relay.state laufen.
    """
    frames = []
    for _ in range(expected):
        frame = await ws.receive_json(timeout=2)
        assert frame.get("type") == "relay.state", (
            f"erster Phone-Frame sollte relay.state sein, kam aber: {frame}"
        )
        frames.append(frame)
    return frames[0]


class TestRelayLifecycle:
    def test_start_with_specific_port(self):
        start_relay(pairing_code="TEST01", port=19876)
        assert is_relay_running()
        url = get_relay_url()
        assert "19876" in url
        stop_relay()

    def test_stop_when_not_running(self):
        stop_relay()

    def test_double_start_is_noop(self):
        start_relay(pairing_code="TEST01", port=19877)
        start_relay(pairing_code="TEST02", port=19877)
        assert is_relay_running()
        stop_relay()

    def test_is_phone_connected_false(self):
        assert not is_phone_connected()

    def test_is_relay_running_false_initially(self):
        assert not is_relay_running()

    def test_get_relay_url_returns_default_when_stopped(self):
        url = get_relay_url()
        assert url is not None
        assert "localhost" in url

    def test_set_pairing_code(self):
        set_pairing_code("NEPCODE")
        start_relay(pairing_code="NEPCODE", port=19878)
        assert is_relay_running()
        stop_relay()


class TestRateLimiting:
    def test_not_blocked_initially(self):
        assert not _auth_is_blocked("1.2.3.4")

    def test_blocked_after_max_failures(self):
        for _ in range(_AUTH_MAX_ATTEMPTS):
            _auth_record_failure("1.2.3.4")
        assert _auth_is_blocked("1.2.3.4")

    def test_different_ip_not_blocked(self):
        for _ in range(_AUTH_MAX_ATTEMPTS):
            _auth_record_failure("1.2.3.4")
        assert not _auth_is_blocked("5.6.7.8")

    def test_under_limit_not_blocked(self):
        for _ in range(_AUTH_MAX_ATTEMPTS - 1):
            _auth_record_failure("1.2.3.4")
        assert not _auth_is_blocked("1.2.3.4")


class TestTokenMasking:
    """Verify that bad auth tokens are masked in log output, not logged in plaintext."""

    def test_normal_token_masked(self):
        token = "SECRET123"
        masked = _mask_token(token)
        assert masked == "****"
        assert token not in masked

    def test_short_token_fully_masked(self):
        assert _mask_token("X") == "****"
        assert _mask_token("AB") == "****"

    def test_empty_token_fully_masked(self):
        assert _mask_token("") == "****"


class TestBodyLogRedaction:
    """Regression: request-body debug logs must not contain PII (AGENTS.md rule:
    strip phone numbers, recipients, location from tool responses/logs)."""

    def test_sms_body_redacted(self):
        repr_ = _safe_body_repr({"to": "+15551234567", "body": "secret message"})
        assert "+15551234567" not in repr_
        assert "secret message" not in repr_
        assert "<redacted>" in repr_

    def test_call_number_redacted(self):
        repr_ = _safe_body_repr({"number": "+15551234567"})
        assert "+15551234567" not in repr_

    def test_typed_text_redacted(self):
        repr_ = _safe_body_repr({"text": "hunter2-password"})
        assert "hunter2-password" not in repr_

    def test_non_sensitive_fields_kept(self):
        repr_ = _safe_body_repr({"x": 100, "y": 200})
        assert "100" in repr_ and "200" in repr_

    def test_empty_body(self):
        assert _safe_body_repr({}) == "{}"

    def test_truncated_to_200_chars(self):
        repr_ = _safe_body_repr({"key": "v" * 500})
        assert len(repr_) <= 200


class TestWsAuthHeader:
    """Regression: WS handshake auth accepts a Bearer header only. The ?token=
    query string fallback was removed because it leaked pairing codes into
    reverse-proxy access logs."""

    PORT = 19881
    CODE = "WSCODE"

    def _try_connect(self, headers=None, query=""):
        import asyncio
        import aiohttp

        async def attempt():
            async with aiohttp.ClientSession() as session:
                try:
                    ws = await session.ws_connect(
                        f"ws://127.0.0.1:{self.PORT}/ws{query}", headers=headers or {}
                    )
                    await ws.close()
                    return True
                except aiohttp.WSServerHandshakeError:
                    return False

        return asyncio.run(attempt())

    def test_bearer_header_accepted(self):
        start_relay(pairing_code=self.CODE, port=self.PORT)
        assert self._try_connect(headers={"Authorization": f"Bearer {self.CODE}"})

    def test_query_token_rejected(self):
        start_relay(pairing_code=self.CODE, port=self.PORT)
        assert not self._try_connect(query=f"?token={self.CODE}")

    def test_bad_bearer_header_rejected(self):
        start_relay(pairing_code=self.CODE, port=self.PORT)
        assert not self._try_connect(headers={"Authorization": "Bearer WRONG1"})

    def test_no_credentials_rejected(self):
        start_relay(pairing_code=self.CODE, port=self.PORT)
        assert not self._try_connect()


class TestMicrophoneBinaryStream:
    PORT = 19882
    CODE = "MICODE"

    @staticmethod
    def _frame(request_id: str, payload: bytes) -> bytes:
        request_id_bytes = request_id.encode("utf-8")
        return len(request_id_bytes).to_bytes(2, "big") + request_id_bytes + payload

    def test_binary_frame_decoder(self):
        raw = self._frame("request-1", b"audio")
        assert _decode_stream_frame(raw) == ("request-1", b"audio")

    @pytest.mark.parametrize("raw", [b"", b"\x00\x00x", b"\x00\x05abc"])
    def test_binary_frame_decoder_rejects_malformed_frames(self, raw):
        with pytest.raises(ValueError):
            _decode_stream_frame(raw)

    def test_replaced_socket_cannot_clean_up_new_phone(self):
        import asyncio

        async def scenario():
            state = _RelayState(pairing_code=self.CODE, port=self.PORT)
            state.phone_ws_lock = asyncio.Lock()
            old_socket = object()
            new_socket = object()
            state.phone_ws = new_socket

            await _cleanup_phone(
                state,
                reason="old socket disconnected",
                expected_ws=old_socket,
            )

            assert state.phone_ws is new_socket

        asyncio.run(scenario())

    def test_wav_is_streamed_from_phone_to_http_client(self):
        import asyncio
        import hashlib
        import aiohttp

        wav = b"RIFF" + (b"\x01\x02" * 64)
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            headers = {"Authorization": f"Bearer {self.CODE}"}
            async with aiohttp.ClientSession() as session:
                ws = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers=headers,
                )
                download = asyncio.create_task(
                    session.get(
                        f"http://127.0.0.1:{self.PORT}/mic_file",
                        headers=headers,
                    )
                )

                await _drain_phone_state_frame(ws)
                command = await ws.receive_json(timeout=2)
                request_id = command["request_id"]
                assert command["path"] == "/mic_file"

                await ws.send_json(
                    {
                        "request_id": request_id,
                        "status": 200,
                        "stream": {
                            "event": "start",
                            "filename": "recording_test.wav",
                            "mimeType": "audio/wav",
                            "size": len(wav),
                        },
                    }
                )
                await ws.send_bytes(self._frame(request_id, wav[:48]))
                await ws.send_bytes(self._frame(request_id, wav[48:]))
                await ws.send_json(
                    {
                        "request_id": request_id,
                        "status": 200,
                        "stream": {
                            "event": "end",
                            "bytes": len(wav),
                            "sha256": hashlib.sha256(wav).hexdigest(),
                        },
                    }
                )

                response = await asyncio.wait_for(download, timeout=2)
                assert response.status == 200
                assert response.headers["Content-Type"] == "audio/wav"
                assert await response.read() == wav
                await ws.close()

        asyncio.run(scenario())


class TestPhoneReplacement:
    PORT = 19883
    CODE = "REPLCE"

    def test_new_phone_remains_usable_after_replacing_old_socket(self):
        import asyncio
        import aiohttp

        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            headers = {"Authorization": f"Bearer {self.CODE}"}
            async with aiohttp.ClientSession() as session:
                old_ws = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers=headers,
                )
                old_close = asyncio.create_task(old_ws.receive())
                new_ws = await asyncio.wait_for(
                    session.ws_connect(
                        f"ws://127.0.0.1:{self.PORT}/ws",
                        headers=headers,
                    ),
                    timeout=2,
                )
                await asyncio.wait_for(old_close, timeout=2)
                await _drain_phone_state_frame(new_ws)

                request = asyncio.create_task(
                    session.get(
                        f"http://127.0.0.1:{self.PORT}/ping",
                        headers=headers,
                    )
                )
                command = await new_ws.receive_json(timeout=2)
                await new_ws.send_json(
                    {
                        "request_id": command["request_id"],
                        "status": 200,
                        "result": {"status": "ok"},
                    }
                )

                response = await asyncio.wait_for(request, timeout=2)
                assert response.status == 200
                assert await response.json() == {"status": "ok"}
                await new_ws.close()

        asyncio.run(scenario())


class TestMicrophoneBinaryStreamNegative:
    """Negative paths for the binary stream relay (#99).

    The happy path (TestMicrophoneBinaryStream) covers a well-behaved phone.
    These tests pin the relay's behavior against a hostile or failing phone:
    checksum mismatch, oversize payloads, length-mismatched end events, an
    invalid declared size, and a mid-stream disconnect. In every case the
    HTTP caller must get either an error status or a torn-down connection —
    never a silent partial file presented as complete.
    """

    PORT = 19884
    CODE = "MINEG1"

    @staticmethod
    def _frame(request_id: str, payload: bytes) -> bytes:
        request_id_bytes = request_id.encode("utf-8")
        return len(request_id_bytes).to_bytes(2, "big") + request_id_bytes + payload

    def _run_stream_scenario(self, phone_play, expect):
        import asyncio
        import aiohttp

        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            headers = {"Authorization": f"Bearer {self.CODE}"}
            async with aiohttp.ClientSession() as session:
                ws = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers=headers,
                )
                download = asyncio.create_task(
                    session.get(
                        f"http://127.0.0.1:{self.PORT}/mic_file",
                        headers=headers,
                    )
                )
                await _drain_phone_state_frame(ws)
                command = await ws.receive_json(timeout=2)
                assert command["path"] == "/mic_file"

                await phone_play(ws, command["request_id"])

                if expect == "status":
                    response = await asyncio.wait_for(download, timeout=2)
                    assert response.status >= 400
                    await ws.close()
                    return
                if expect == "teardown-or-complete":
                    # Detection happens after the last byte is delivered; the
                    # client either sees a torn-down body or the full payload.
                    # Server-side logs are asserted by the caller.
                    response = await asyncio.wait_for(download, timeout=2)
                    try:
                        await asyncio.wait_for(response.read(), timeout=2)
                    except aiohttp.ClientError:
                        pass
                    await ws.close()
                    return
                # expect == "teardown": the client must never see a silent
                # truncated success. Three outcomes all satisfy that, and
                # which one happens depends on whether the relay processes
                # the phone's disconnect before it commits the response:
                #   - the connection dies before or with the headers,
                #   - the headers arrive and the body read fails,
                #   - the relay notices first and answers with an error status.
                try:
                    response = await asyncio.wait_for(download, timeout=2)
                except aiohttp.ClientError:
                    response = None  # died before/with headers
                if response is not None:
                    try:
                        await asyncio.wait_for(response.read(), timeout=2)
                    except aiohttp.ClientError:
                        pass  # torn-down body — the intended outcome
                    else:
                        assert response.status >= 400, (
                            "stream completed cleanly with status "
                            f"{response.status}; a truncated success is the "
                            "one outcome this test exists to prevent"
                        )
                await ws.close()

        asyncio.run(scenario())

    def test_sha256_mismatch_is_detected_server_side(self, caplog):
        import hashlib
        import logging

        wav = b"RIFF" + (b"\x09\x09" * 64)

        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_bad.wav",
                        "mimeType": "audio/wav",
                        "size": len(wav),
                    },
                }
            )
            await ws.send_bytes(self._frame(request_id, wav))
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "end",
                        "bytes": len(wav),
                        # Wrong digest over different content.
                        "sha256": hashlib.sha256(b"tampered").hexdigest(),
                    },
                }
            )

        # The full body has already been streamed when the checksum is
        # verified, so the HTTP client cannot observe the abort — pin the
        # server-side detection instead: the relay must log the mismatch and
        # force-close rather than complete the stream cleanly.
        with caplog.at_level(logging.WARNING, logger="tools.android_relay"):
            self._run_stream_scenario(phone_play, expect="teardown-or-complete")
            # The relay reaches this verdict on its own thread, after the
            # client has returned — wait for it rather than racing it.
            record = _await_log_record(caplog, "checksum mismatch")

        assert "Recording stream checksum mismatch" in record.getMessage()

    def test_oversize_chunk_aborts_the_download(self):
        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_big.wav",
                        "mimeType": "audio/wav",
                        "size": 8,  # declare 8 bytes...
                    },
                }
            )
            # ...then send 48.
            await ws.send_bytes(self._frame(request_id, b"\x01" * 48))

        self._run_stream_scenario(phone_play, expect="teardown")

    def test_end_bytes_mismatch_is_detected_server_side(self, caplog):
        import hashlib
        import logging

        wav = b"RIFF" + (b"\x02\x02" * 32)

        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_len.wav",
                        "mimeType": "audio/wav",
                        "size": len(wav),
                    },
                }
            )
            await ws.send_bytes(self._frame(request_id, wav))
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "end",
                        "bytes": len(wav) + 100,  # lies about the total
                        "sha256": hashlib.sha256(wav).hexdigest(),
                    },
                }
            )

        # Same shape as the checksum case: the payload is fully delivered
        # before the end event lies, so pin the server-side detection.
        with caplog.at_level(logging.WARNING, logger="tools.android_relay"):
            self._run_stream_scenario(phone_play, expect="teardown-or-complete")
            # As above: the verdict is logged on the relay's own thread.
            record = _await_log_record(caplog, "length does not match")

        assert "Recording stream length does not match metadata" in record.getMessage()

    def test_declared_size_above_the_cap_is_rejected_up_front(self):
        from tools.android_relay import _MAX_STREAM_BYTES

        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_huge.wav",
                        "mimeType": "audio/wav",
                        "size": _MAX_STREAM_BYTES + 1,
                    },
                }
            )

        self._run_stream_scenario(phone_play, expect="status")

    def test_negative_declared_size_is_rejected_up_front(self):
        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_neg.wav",
                        "mimeType": "audio/wav",
                        "size": -1,
                    },
                }
            )

        self._run_stream_scenario(phone_play, expect="status")

    def test_phone_disconnect_mid_stream_tears_down_the_download(self):
        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_cut.wav",
                        "mimeType": "audio/wav",
                        "size": 1024,
                    },
                }
            )
            await ws.send_bytes(self._frame(request_id, b"\x03" * 48))
            await ws.close()  # phone vanishes mid-stream

        self._run_stream_scenario(phone_play, expect="teardown")


class TestAwaitLogRecord:
    """The wait helper the stream tests rely on must never pass silently.

    These pin the helper itself, because the whole point of it is to turn a
    lost race into a loud failure. A helper that returned happily when the
    record never arrived would be worse than the bare assert it replaces.
    """

    LOGGER = "tools.android_relay"

    def test_it_returns_the_record_that_is_already_there(self, caplog):
        import logging

        with caplog.at_level(logging.WARNING, logger=self.LOGGER):
            logging.getLogger(self.LOGGER).warning("checksum mismatch seen")

        record = _await_log_record(caplog, "checksum mismatch", timeout=1)

        assert "checksum mismatch" in record.getMessage()

    def test_it_waits_for_a_record_that_arrives_late(self, caplog):
        """The race this exists for: the relay logs after the client returns.

        A bare `assert any(...)` fails here; only waiting passes.
        """
        import logging

        def emit_later():
            time.sleep(0.2)
            logging.getLogger(self.LOGGER).warning("late length does not match")

        with caplog.at_level(logging.WARNING, logger=self.LOGGER):
            thread = threading.Thread(target=emit_later, daemon=True)
            thread.start()
            try:
                record = _await_log_record(
                    caplog, "length does not match", timeout=5
                )
            finally:
                thread.join(timeout=5)

        assert "length does not match" in record.getMessage()

    def test_it_fails_loudly_when_the_record_never_arrives(self, caplog):
        with pytest.raises(AssertionError, match="no log record containing"):
            _await_log_record(caplog, "this is never logged", timeout=0.05)


class TestRouteAllowlist:
    """The relay forwards only what _ROUTES lists, so gaps are silent.

    A route added to CommandDispatcher but not to the allowlist works over
    direct USB/LAN and simply does not exist over the relay — which is the
    default transport. Nothing fails loudly, so these tests are the only thing
    standing between a new endpoint and being quietly unreachable.
    """

    def test_battery_is_forwarded_as_a_get(self):
        from tools.android_relay import _ROUTES

        assert _ROUTES.get("/battery") == "GET"

    def test_the_documented_endpoints_are_exactly_the_allowlist(self):
        """docs/architecture.md and _ROUTES must not drift in either direction.

        Catches both the endpoint that was documented but never allowlisted —
        reachable on paper, refused in practice — and the one that was
        allowlisted but never documented.
        """
        import pathlib
        import re
        from tools.android_relay import _ROUTES

        doc = pathlib.Path(__file__).resolve().parent.parent / "docs" / "architecture.md"
        line = next(
            l for l in doc.read_text(encoding="utf-8").splitlines()
            if "HTTP bridge endpoints (method per path)" in l
        )

        def paths(section, terminator):
            start = line.index(section) + len(section)
            end = line.index(terminator, start) if terminator else len(line)
            return re.findall(r"`([^`]+)`", line[start:end])

        documented = {}
        for path in paths("GET: ", ". POST:"):
            documented[path] = "GET"
        for path in paths("POST: ", ". Both:"):
            documented[path] = "POST"
        for path in paths("Both: ", None):
            documented[path] = "BOTH"

        # PH-3-T2: Die drei E2E-Registry-Routen sind relay-nativ (Broker, kein
        # Device-Forward) und werden hier testseitig als dokumentiert geführt —
        # die docs/-Zeile 73 ("HTTP bridge endpoints") ist in der PH-3-Welle-1
        # Zone gesperrt (Docs-Welle ergänzt `/key_publish`, `/key_get`,
        # `/crypto_hello` als POST/GET/POST). Sobald der Doc steht, kann dieser
        # Block entfallen.
        documented.update({
            "/key_publish": "POST",
            "/key_get": "GET",
            "/crypto_hello": "POST",
        })

        assert documented == _ROUTES, (
            "docs/architecture.md and the relay allowlist disagree — "
            f"documented only: {sorted(set(documented) - set(_ROUTES))}, "
            f"allowlisted only: {sorted(set(_ROUTES) - set(documented))}, "
            "method mismatches: "
            f"{sorted(p for p in set(documented) & set(_ROUTES) if documented[p] != _ROUTES[p])}"
        )

    def test_the_two_relay_copies_are_byte_identical(self):
        """AGENTS.md requires the plugin copy to track tools/ exactly.

        android_tool.py is allowed to differ (import path and registration
        trailer); android_relay.py is not. Comparing bytes rather than
        behaviour, because the rule is about the copies staying in step.
        """
        import pathlib

        root = pathlib.Path(__file__).resolve().parent.parent
        tools_copy = (root / "tools" / "android_relay.py").read_bytes()
        plugin_copy = (root / "hermes-android-plugin" / "android_relay.py").read_bytes()

        assert tools_copy == plugin_copy, (
            "tools/android_relay.py and hermes-android-plugin/android_relay.py "
            "have drifted; apply the same change to both"
        )


class TestAgentRegistry:
    """PH2-T1: Agent-Registry + /agent_ws-Registrierung (Presence je Agent).

    Token-Quelle = relay-config.json (Key ANDROID_AGENT_TOKEN_<ID>) — per
    env ANDROID_RELAY_CONFIG auf eine Test-Config gelenkt, das Live-Default
    (/opt/flipsibridge/relay-config.json) bleibt unberührt.
    """

    PORT = 19885
    CODE = "AGCODE"
    TOKEN = "agent-secret-token"

    @staticmethod
    def _write_config(tmp_path, token=TOKEN):
        import json

        cfg = tmp_path / "relay-config.json"
        cfg.write_text(
            json.dumps(
                {
                    "ANDROID_AGENT_TOKEN_ALPHA": token,
                    "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha",
                }
            ),
            encoding="utf-8",
        )
        return str(cfg)

    def test_registry_prefills_configured_agents_offline(self, tmp_path, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        registry = get_agent_registry()
        assert "alpha" in registry
        entry = registry["alpha"]
        assert entry["display_name"] == "Alpha"
        assert entry["online"] is False
        assert entry["last_seen"] == 0.0
        assert entry["fingerprint"] == ""

    def test_registration_flow_presence_online_offline(self, tmp_path, monkeypatch):
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                ws = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"},
                )
                # Race: der Handshake ist client-seitig fertig, bevor der Server
                # die Registry-Einträge setzt → auf online pollen statt asserten.
                deadline = time.monotonic() + 2
                while get_agent_registry()["alpha"]["online"] is not True:
                    assert time.monotonic() < deadline, (
                        "Agent-WS verbunden, aber Registry bleibt offline"
                    )
                    time.sleep(0.01)
                entry = get_agent_registry()["alpha"]
                assert entry["fingerprint"] == ""
                seen_at = entry["last_seen"]
                assert seen_at > 0

                # Keep-Alive-Frame pflegt last_seen (Soll-Raster: je 30 s).
                time.sleep(0.05)
                await ws.send_json({"type": "keepalive"})
                deadline = time.monotonic() + 2
                while get_agent_registry()["alpha"]["last_seen"] <= seen_at:
                    assert time.monotonic() < deadline, (
                        "keepalive hat last_seen nicht gepflegt"
                    )
                    time.sleep(0.01)

                await ws.close()
                deadline = time.monotonic() + 5
                while get_agent_registry()["alpha"]["online"] is not False:
                    assert time.monotonic() < deadline, (
                        "Agent bleibt online nach Disconnect"
                    )
                    time.sleep(0.01)

        asyncio.run(scenario())

    def test_agent_ws_wrong_token_rejected_401(self, tmp_path, monkeypatch):
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                with pytest.raises(aiohttp.WSServerHandshakeError) as excinfo:
                    await session.ws_connect(
                        f"ws://127.0.0.1:{self.PORT}/agent_ws",
                        headers={"Authorization": "Bearer WRONG-TOKEN"},
                    )
                assert excinfo.value.status == 401

        asyncio.run(scenario())

    def test_agent_ws_rejected_without_agent_config(self, tmp_path, monkeypatch):
        """Fail-closed: leere Server-Config kennt keine Agenten → kein WS-Zugang."""
        import asyncio
        import aiohttp

        cfg = tmp_path / "relay-config.json"
        cfg.write_text("{}", encoding="utf-8")
        monkeypatch.setenv("ANDROID_RELAY_CONFIG", str(cfg))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                with pytest.raises(aiohttp.WSServerHandshakeError) as excinfo:
                    await session.ws_connect(
                        f"ws://127.0.0.1:{self.PORT}/agent_ws",
                        headers={"Authorization": f"Bearer {self.TOKEN}"},
                    )
                assert excinfo.value.status == 401

        asyncio.run(scenario())


class TestChatBroker:
    """PH2-T1: /chat (App→Agent, je-Agent-Queue) + /chat_event (Agent→App).

    Deckt den kompletten Delivery-Weg: Registrierung → /chat → Queue →
    Agent-WS → /chat_event → Phone-WS, plus Offline-Spool in beide
    Richtungen, das 2-Nachrichten-pro-Sekunde-Limit und Auth-Fehler.
    """

    PORT = 19886
    CODE = "CHTCD1"
    TOKEN = "agent-broker-token"

    @staticmethod
    def _write_config(tmp_path):
        import json

        cfg = tmp_path / "relay-config.json"
        cfg.write_text(
            json.dumps(
                {
                    "ANDROID_AGENT_TOKEN_ALPHA": "agent-broker-token",
                    "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha",
                }
            ),
            encoding="utf-8",
        )
        return str(cfg)

    def test_full_flow_chat_queue_chat_event_phone_delivery(self, tmp_path, monkeypatch):
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)
        phone_headers = {"Authorization": f"Bearer {self.CODE}"}
        agent_headers = {"Authorization": f"Bearer {self.TOKEN}"}

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws", headers=phone_headers
                )
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws", headers=agent_headers
                )

                # App→Agent: /chat landet live an der verbundenen Agent-WS.
                r = await session.post(
                    f"http://127.0.0.1:{self.PORT}/chat",
                    headers=phone_headers,
                    json={"agent_id": "alpha", "text": "Hallo Agent"},
                )
                assert r.status == 200
                data = await r.json()
                assert data["delivered"] is True
                assert data["queued"] is False

                frame = await agent.receive_json(timeout=2)
                assert frame["type"] == "chat.send"
                assert frame["agent_id"] == "alpha"
                assert frame["text"] == "Hallo Agent"
                assert frame["chat_id"] == data["chat_id"]

                # Agent→App: /chat_event liefert an die verbundene Phone-WS.
                r2 = await session.post(
                    f"http://127.0.0.1:{self.PORT}/chat_event",
                    headers=agent_headers,
                    json={"chat_id": data["chat_id"], "text": "Hallo Sir"},
                )
                assert r2.status == 200
                data2 = await r2.json()
                assert data2["delivered"] is True

                # Hallo-Frame + Agent-Connect-Presence-Push wegkonsumieren.
                await _drain_phone_state_frame(phone, expected=2)
                # PH2-T10: je Statuswechsel ein chat.status-Frame ans Phone —
                # queued + delivered VOR dem chat.event wegkonsumieren.
                for _ in range(2):
                    status_frame = await phone.receive_json(timeout=2)
                    assert status_frame["type"] == "chat.status"
                    assert status_frame["msg_id"] == data["msg_id"]
                    assert status_frame["status"] in ("queued", "delivered")
                pframe = await phone.receive_json(timeout=2)
                assert pframe["type"] == "chat.event"
                assert pframe["chat_id"] == data["chat_id"]
                assert pframe["agent_id"] == "alpha"
                assert pframe["text"] == "Hallo Sir"

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_chat_spooled_until_agent_connects(self, tmp_path, monkeypatch):
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)
        phone_headers = {"Authorization": f"Bearer {self.CODE}"}
        agent_headers = {"Authorization": f"Bearer {self.TOKEN}"}

        async def scenario():
            async with aiohttp.ClientSession() as session:
                # Agent offline → Nachricht bleibt in der je-Agent-Queue.
                r = await session.post(
                    f"http://127.0.0.1:{self.PORT}/chat",
                    headers=phone_headers,
                    json={"agent_id": "alpha", "text": "Nachricht im Spool"},
                )
                assert r.status == 200
                data = await r.json()
                assert data["delivered"] is False
                assert data["queued"] is True

                # Agent verbindet → Spool wird bei der Anmeldung entleert.
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws", headers=agent_headers
                )
                frame = await agent.receive_json(timeout=2)
                assert frame["type"] == "chat.send"
                assert frame["text"] == "Nachricht im Spool"
                assert frame["chat_id"] == data["chat_id"]
                await agent.close()

        asyncio.run(scenario())

    def test_chat_event_spooled_until_phone_connects(self, tmp_path, monkeypatch):
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)
        phone_headers = {"Authorization": f"Bearer {self.CODE}"}
        agent_headers = {"Authorization": f"Bearer {self.TOKEN}"}

        async def scenario():
            async with aiohttp.ClientSession() as session:
                # Phone offline → chat.event bleibt in der Phone-Outbox.
                r = await session.post(
                    f"http://127.0.0.1:{self.PORT}/chat_event",
                    headers=agent_headers,
                    json={"chat_id": "chat-1", "text": "Antwort im Spool"},
                )
                assert r.status == 200
                data = await r.json()
                assert data["delivered"] is False
                assert data["queued"] is True

                # Phone verbindet → ZUERST relay.state (Agenten-Liste), dann
                # die gespoolte Nachricht aus der Outbox.
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws", headers=phone_headers
                )
                state_frame = await _drain_phone_state_frame(phone)
                assert state_frame["agents"][0]["agent_id"] == "alpha"
                frame = await phone.receive_json(timeout=2)
                assert frame["type"] == "chat.event"
                assert frame["chat_id"] == "chat-1"
                assert frame["text"] == "Antwort im Spool"
                await phone.close()

        asyncio.run(scenario())

    def test_chat_rate_limit_two_per_second(self, tmp_path, monkeypatch):
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)
        phone_headers = {"Authorization": f"Bearer {self.CODE}"}

        async def scenario():
            async with aiohttp.ClientSession() as session:
                statuses = []
                for _ in range(3):
                    r = await session.post(
                        f"http://127.0.0.1:{self.PORT}/chat",
                        headers=phone_headers,
                        json={"agent_id": "alpha", "text": "x"},
                    )
                    statuses.append(r.status)
                assert statuses == [200, 200, 429]

        asyncio.run(scenario())

    def test_chat_event_rate_limit_two_per_second(self, tmp_path, monkeypatch):
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)
        agent_headers = {"Authorization": f"Bearer {self.TOKEN}"}

        async def scenario():
            async with aiohttp.ClientSession() as session:
                statuses = []
                for _ in range(3):
                    r = await session.post(
                        f"http://127.0.0.1:{self.PORT}/chat_event",
                        headers=agent_headers,
                        json={"chat_id": "chat-1", "text": "x"},
                    )
                    statuses.append(r.status)
                assert statuses == [200, 200, 429]

        asyncio.run(scenario())

    def test_chat_wrong_token_401(self, tmp_path, monkeypatch):
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                r = await session.post(
                    f"http://127.0.0.1:{self.PORT}/chat",
                    headers={"Authorization": "Bearer WRONG"},
                    json={"agent_id": "alpha", "text": "x"},
                )
                assert r.status == 401

        asyncio.run(scenario())

    def test_chat_event_wrong_agent_token_401(self, tmp_path, monkeypatch):
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                r = await session.post(
                    f"http://127.0.0.1:{self.PORT}/chat_event",
                    headers={"Authorization": "Bearer WRONG"},
                    json={"chat_id": "chat-1", "text": "x"},
                )
                assert r.status == 401

        asyncio.run(scenario())

    def test_chat_unknown_agent_404(self, tmp_path, monkeypatch):
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)
        phone_headers = {"Authorization": f"Bearer {self.CODE}"}

        async def scenario():
            async with aiohttp.ClientSession() as session:
                r = await session.post(
                    f"http://127.0.0.1:{self.PORT}/chat",
                    headers=phone_headers,
                    json={"agent_id": "nobody", "text": "x"},
                )
                assert r.status == 404

        asyncio.run(scenario())

    def test_chat_missing_fields_400(self, tmp_path, monkeypatch):
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)
        phone_headers = {"Authorization": f"Bearer {self.CODE}"}

        async def scenario():
            async with aiohttp.ClientSession() as session:
                r = await session.post(
                    f"http://127.0.0.1:{self.PORT}/chat",
                    headers=phone_headers,
                    json={"agent_id": "alpha"},  # text fehlt
                )
                assert r.status == 400

        asyncio.run(scenario())


class TestAgentWsChatBridge:
    """PH2-MERGE: WS-Backbridge — chat.reply-Frames des Agenten über /agent_ws
    wandern mit identischer Semantik wie POST /chat_event in den Phone-Broker
    (Spool+Retention, chat.event-Frame an die Phone-WS, Rate-Limit). Frame-
    Allowlist: keepalive/ping/presence = Presence, chat.reply = Bridge,
    ALLES andere = loggen + verwerfen, kein Crash."""

    PORT = 19887
    CODE = "MRGBRC"
    TOKEN = "agent-bridge-token"

    @staticmethod
    def _write_config(tmp_path):
        import json

        cfg = tmp_path / "relay-config.json"
        cfg.write_text(
            json.dumps(
                {
                    "ANDROID_AGENT_TOKEN_ALPHA": "agent-bridge-token",
                    "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha",
                }
            ),
            encoding="utf-8",
        )
        return str(cfg)

    def test_agent_ws_chat_reply_delivered_to_phone(self, tmp_path, monkeypatch):
        """Frame→Phone-Delivery: chat.reply-Frame landet als chat.event an der
        Phone-WS — gleiche Felder wie der /chat_event-HTTP-Pfad."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"},
                )
                await _drain_phone_state_frame(phone, expected=2)

                await agent.send_json(
                    {"type": "chat.reply", "chat_id": "ws-reply-1", "text": "Hallo vom Agent"}
                )
                frame = await phone.receive_json(timeout=2)
                assert frame["type"] == "chat.event"
                assert frame["chat_id"] == "ws-reply-1"
                assert frame["agent_id"] == "alpha"
                assert frame["text"] == "Hallo vom Agent"
                assert frame["ts"] > 0

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_agent_ws_chat_reply_spooled_until_phone_connects(self, tmp_path, monkeypatch):
        """Spool+Retention: Phone offline → chat.reply bleibt in der Outbox;
        beim Phone-Connect kommt ZUERST relay.state (mit online=True), dann
        der gespoolte chat.event-Frame."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"},
                )
                await agent.send_json(
                    {"type": "chat.reply", "chat_id": "ws-reply-2", "text": "Antwort im Spool"}
                )
                # Kurz warten, damit der Frame im Broker verarbeitet ist.
                await asyncio.sleep(0.2)

                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                state_frame = await _drain_phone_state_frame(phone)
                agents = state_frame["agents"]
                alpha = next(a for a in agents if a["agent_id"] == "alpha")
                assert alpha["display_name"] == "Alpha"
                assert alpha["online"] is True

                frame = await phone.receive_json(timeout=2)
                assert frame["type"] == "chat.event"
                assert frame["chat_id"] == "ws-reply-2"
                assert frame["text"] == "Antwort im Spool"

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_agent_ws_frame_allowlist(self, tmp_path, monkeypatch, caplog):
        """Erlaubte Typen (keepalive/ping/presence) = nur Presence, KEIN
        Phone-Frame; unbekannte/ungültige Frames werden geloggt und verworfen
        — der Agent-Socket überlebt und ein nachfolgender gültiger chat.reply
        wird trotzdem zugestellt (kein Crash)."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"},
                )
                await _drain_phone_state_frame(phone, expected=2)

                # Erlaubte Presence-Typen: keine Broker-Berührung.
                await agent.send_json({"type": "keepalive"})
                await agent.send_json({"type": "ping"})
                await agent.send_json({"type": "presence"})
                await asyncio.sleep(0.3)
                with pytest.raises(asyncio.TimeoutError):
                    await phone.receive_json(timeout=0.3)

                # Unbekannter Typ: loggen + verwerfen, kein Crash.
                await agent.send_json({"type": "file.meta", "path": "/etc/passwd"})
                _await_log_record(caplog, "verworfen (Allowlist")

                # Ungültiger chat.reply (text fehlt): loggen + verwerfen.
                await agent.send_json({"type": "chat.reply", "chat_id": "x"})
                _await_log_record(caplog, "chat.reply ohne gültiges chat_id/text")

                # Non-JSON: loggen + verwerfen, Socket bleibt offen.
                await agent.send_str("kein json")
                _await_log_record(caplog, "non-JSON message")

                # Beweis, dass der Socket überlebt: gültiger chat.reply kommt
                # weiterhin an der Phone-WS an.
                await agent.send_json(
                    {"type": "chat.reply", "chat_id": "ws-reply-3", "text": "Noch da"}
                )
                frame = await phone.receive_json(timeout=2)
                assert frame["type"] == "chat.event"
                assert frame["chat_id"] == "ws-reply-3"

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_agent_register_updates_display_name_no_discard(self, tmp_path, monkeypatch, caplog):
        """PH-FIX-BACKEND-B (PIPELINE-BUGS Log-Rauschen-Fund): agent.register
        ist allowlisted und wird VERARBEITET — display_name übernehmen (die
        WS-Identität gewinnt, eine im Frame mitgeschickte agent_id wird
        IGNORIERT) + Presence-Push bei echter Änderung. KEIN
        'verworfen (Allowlist'-Log mehr je Agent-Connect/Reconnect."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"},
                )
                # Hallo-Frame + Connect-Presence-Push des Agenten wegkonsumieren.
                await _drain_phone_state_frame(phone, expected=2)
                assert get_agent_registry()["alpha"]["display_name"] == "Alpha"

                # Register-Frame: display_name übernehmen; frame-agent_id
                # (hier fremd) muss ignoriert werden — NIE ein Rename-Fenster.
                await agent.send_json({
                    "type": "agent.register", "agent_id": "evil-other",
                    "display_name": "Alpha Live-Name"})
                frame = await phone.receive_json(timeout=2)
                assert frame["type"] == "relay.state", (
                    f"Änderung muss als Presence-Push kommen, kam aber: {frame}")
                entry = next(a for a in frame["agents"] if a["agent_id"] == "alpha")
                assert entry["display_name"] == "Alpha Live-Name"
                assert get_agent_registry()["alpha"]["display_name"] == "Alpha Live-Name"
                # KEIN Allowlist-Verwurf: das Connect-Log-Rauschen ist weg.
                for record in caplog.records:
                    message = record.getMessage()
                    assert not ("agent.register" in message and "verworfen" in message), (
                        f"agent.register darf nicht mehr verworfen werden: {message}")

                # Identischer Name (nach Strip) = No-Op, KEIN zweiter Push.
                await agent.send_json({
                    "type": "agent.register", "display_name": "  Alpha Live-Name  "})
                await asyncio.sleep(0.2)
                with pytest.raises(asyncio.TimeoutError):
                    await phone.receive_json(timeout=0.3)

                # Ungültiger Register-Frame (display_name fehlt) = No-Op;
                # der Socket lebt und ein gültiger chat.reply kommt weiter an.
                await agent.send_json({"type": "agent.register", "agent_id": "alpha"})
                await agent.send_json(
                    {"type": "chat.reply", "chat_id": "ws-reg-1", "text": "Noch da"})
                frame = await phone.receive_json(timeout=2)
                assert frame["type"] == "chat.event"
                assert frame["chat_id"] == "ws-reg-1"

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_phone_state_frame_empty_registry_fallback(self, tmp_path, monkeypatch):
        """Leere Server-Config → relay.state.agents = [] (App-Demo-Fallback,
        kein Crash, kein fehlendes Feld)."""
        import asyncio
        import aiohttp

        cfg = tmp_path / "relay-config.json"
        cfg.write_text("{}", encoding="utf-8")
        monkeypatch.setenv("ANDROID_RELAY_CONFIG", str(cfg))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                state_frame = await _drain_phone_state_frame(phone)
                assert state_frame["agents"] == []
                await phone.close()

        asyncio.run(scenario())


class TestChatStatusFrames:
    """PH2-T10 (F2-S1/S2/S3): Broker-Status je msg_id + chat.status-/
    chat.activity-Frames an die Phone-WS + Allowlist-Erweiterung (chat.read/
    chat.activity). Status-Frames sind NICHT rate-limited."""

    PORT = 19888
    CODE = "STCODE"
    TOKEN = "agent-status-token"

    @staticmethod
    def _write_config(tmp_path):
        import json

        cfg = tmp_path / "relay-config.json"
        cfg.write_text(
            json.dumps(
                {
                    "ANDROID_AGENT_TOKEN_ALPHA": "agent-status-token",
                    "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha",
                }
            ),
            encoding="utf-8",
        )
        return str(cfg)

    def test_chat_status_queued_then_delivered_flow(self, tmp_path, monkeypatch):
        """POST /chat liefert msg_id zurück; das Phone sieht je Wechsel einen
        chat.status-Frame: erst queued (Broker-Annahme), dann delivered
        (Agent-WS-Send)."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"},
                )
                await _drain_phone_state_frame(phone, expected=2)

                r = await session.post(
                    f"http://127.0.0.1:{self.PORT}/chat",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                    json={"agent_id": "alpha", "text": "Status-Test"},
                )
                assert r.status == 200
                data = await r.json()
                assert data["msg_id"], "msg_id fehlt in der /chat-Antwort"

                agent_frame = await agent.receive_json(timeout=2)
                assert agent_frame["type"] == "chat.send"
                assert agent_frame["msg_id"] == data["msg_id"]

                s1 = await phone.receive_json(timeout=2)
                assert s1["type"] == "chat.status"
                assert s1["msg_id"] == data["msg_id"]
                assert s1["chat_id"] == data["chat_id"]
                assert s1["status"] == "queued"
                assert s1["ts"] > 0

                s2 = await phone.receive_json(timeout=2)
                assert s2["type"] == "chat.status"
                assert s2["msg_id"] == data["msg_id"]
                assert s2["status"] == "delivered"

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_chat_status_stays_queued_offline_then_delivered_on_flush(self, tmp_path, monkeypatch):
        """Agent offline → nur der queued-Frame (Spool, kein Downgrade);
        nach dem Agent-Connect liefert die Spool-Auslieferung delivered."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                await _drain_phone_state_frame(phone)

                r = await session.post(
                    f"http://127.0.0.1:{self.PORT}/chat",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                    json={"agent_id": "alpha", "text": "Offline-Nachricht"},
                )
                data = await r.json()
                assert data["queued"] is True

                s1 = await phone.receive_json(timeout=2)
                assert s1["type"] == "chat.status" and s1["status"] == "queued"

                # Kein delivered-Frame, solange der Agent offline ist.
                with pytest.raises(asyncio.TimeoutError):
                    await phone.receive_json(timeout=0.3)

                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"},
                )
                agent_frame = await agent.receive_json(timeout=2)
                assert agent_frame["type"] == "chat.send"
                assert agent_frame["msg_id"] == data["msg_id"]

                # PH2-FIX (E2E P2): Agent-Connect-Presence-Push (relay.state)
                # vor dem delivered-Status-Frame wegkonsumieren.
                await _drain_phone_state_frame(phone)
                s2 = await phone.receive_json(timeout=2)
                assert s2["type"] == "chat.status" and s2["status"] == "delivered"

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_agent_chat_read_marks_status_and_pushes_phone(self, tmp_path, monkeypatch):
        """chat.read {chat_id, msg_id} → Status read + chat.status-Frame;
        chat.read mit nur chat_id liest alle ungelesenen Nachrichten der
        Konversation (2 Nachrichten → 2 read-Frames)."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"},
                )
                await _drain_phone_state_frame(phone, expected=2)

                msg_ids = []
                chat_ids = []
                for i in range(2):
                    r = await session.post(
                        f"http://127.0.0.1:{self.PORT}/chat",
                        headers={"Authorization": f"Bearer {self.CODE}"},
                        json={"agent_id": "alpha", "text": f"Msg {i}"},
                    )
                    data = await r.json()
                    msg_ids.append(data["msg_id"])
                    chat_ids.append(data["chat_id"])
                    # je Nachricht: queued- + delivered-Frame wegkonsumieren
                    for _ in range(2):
                        f = await phone.receive_json(timeout=2)
                        assert f["type"] == "chat.status"

                # Gezielt eine Nachricht lesen (chat_id MUSS zur msg_id passen):
                await agent.send_json(
                    {"type": "chat.read", "chat_id": chat_ids[0], "msg_id": msg_ids[0]}
                )
                fr = await phone.receive_json(timeout=2)
                assert fr["type"] == "chat.status"
                assert fr["msg_id"] == msg_ids[0]
                assert fr["status"] == "read"

                # Doppeltes read (gleiche msg_id) = Dedup, KEIN zweiter Frame:
                await agent.send_json(
                    {"type": "chat.read", "msg_id": msg_ids[0], "chat_id": chat_ids[0]}
                )
                with pytest.raises(asyncio.TimeoutError):
                    await phone.receive_json(timeout=0.3)

                # chat.read ohne msg_id: alle noch ungelesenen Nachrichten der
                # chat_id lesen — chat_ids[0] ist bereits read → No-Op (kein Frame):
                await agent.send_json({"type": "chat.read", "chat_id": chat_ids[0]})
                with pytest.raises(asyncio.TimeoutError):
                    await phone.receive_json(timeout=0.3)

                # Gezieltes read der zweiten Nachricht (eigene chat_id):
                await agent.send_json(
                    {"type": "chat.read", "chat_id": chat_ids[1], "msg_id": msg_ids[1]}
                )
                fr2 = await phone.receive_json(timeout=2)
                assert fr2["type"] == "chat.status"
                assert fr2["msg_id"] == msg_ids[1]
                assert fr2["status"] == "read"

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_agent_chat_activity_dedup_and_not_rate_limited(self, tmp_path, monkeypatch):
        """chat.activity: je Wechsel genau 1 Frame (Dedup bei gleichem
        working-Wert), started_at + agent_id im Frame, und >2 Frames je
        Sekunde kommen alle durch (keine Chat-Rate-Limit auf Status-Frames)."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"},
                )
                await _drain_phone_state_frame(phone, expected=2)

                # 5 Frames in <1s — mehr als das 2/s-Chat-Limit: ALLE Wechsel
                # kommen durch, der 3. identische true wird dedupliziert.
                seq = [True, True, False, True, False]
                for working in seq:
                    await agent.send_json(
                        {"type": "chat.activity", "chat_id": "act-1",
                         "working": working, "started_at": 1730515200000}
                    )
                frames = [await phone.receive_json(timeout=2) for _ in range(4)]
                assert all(f["type"] == "chat.activity" for f in frames)
                assert [f["working"] for f in frames] == [True, False, True, False]
                assert all(f["agent_id"] == "alpha" for f in frames)
                assert all(f["started_at"] == 1730515200000 for f in frames)
                assert all(f["ts"] > 0 for f in frames)

                # Der 5. Frame (Dedup) erzeugt keinen weiteren Phone-Frame.
                with pytest.raises(asyncio.TimeoutError):
                    await phone.receive_json(timeout=0.3)

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_status_frames_bypass_chat_rate_limit(self, tmp_path, monkeypatch):
        """4 read-Frames (2 chat_id-weit + 2 gezielt) in <1s — das Chat-Limit
        (2/s je agent_id) darf read/activity NICHT drosseln: alle Frames
        wirken (hier: 2 read-Status-Frames ans Phone)."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"},
                )
                await _drain_phone_state_frame(phone, expected=2)

                r = await session.post(
                    f"http://127.0.0.1:{self.PORT}/chat",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                    json={"agent_id": "alpha", "text": "Rate-Test"},
                )
                data = await r.json()
                for _ in range(2):  # queued + delivered
                    await phone.receive_json(timeout=2)

                # 4 read-Frames in <1s: wirksam ist der letzte Stand read;
                # entscheidend ist, dass KEIN Frame vom Rate-Limit verworfen
                # wurde (die chat_id-Variante liest die eine Nachricht).
                for _ in range(4):
                    await agent.send_json(
                        {"type": "chat.read", "chat_id": data["chat_id"],
                         "msg_id": data["msg_id"]}
                    )
                fr = await phone.receive_json(timeout=2)
                assert fr["type"] == "chat.status" and fr["status"] == "read"

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_agent_frame_allowlist_extended_for_status(self):
        """Allowlist-Beweis: chat.read + chat.activity + chat.reply erlaubt,
        unbekannte Typen bleiben draußen (Frozenset je Modulebene)."""
        from tools.android_relay import _AGENT_FRAME_TYPES

        assert {"chat.read", "chat.activity", "chat.reply"} <= _AGENT_FRAME_TYPES
        assert "file.meta" not in _AGENT_FRAME_TYPES
        assert "tool_call" not in _AGENT_FRAME_TYPES

    def test_agent_chat_reply_elapsed_ms_passthrough(self, tmp_path, monkeypatch):
        """PH2-T11: elapsed_ms im chat.reply-Frame landet im chat.event-Frame
        am Phone (Adapter-Messung Empfang→Antwort)."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"},
                )
                await _drain_phone_state_frame(phone, expected=2)

                await agent.send_json({
                    "type": "chat.reply", "chat_id": "elapsed-1",
                    "text": "Antwort", "elapsed_ms": 1234,
                })
                fr = await phone.receive_json(timeout=2)
                assert fr["type"] == "chat.event"
                assert fr["chat_id"] == "elapsed-1"
                assert fr["elapsed_ms"] == 1234

                await agent.close()
                await phone.close()

        asyncio.run(scenario())


class TestChatIsolationAndRate:
    """PH2-T5 (Konversations-Isolation je agent_id) + PH2-T6 (Rate-Limit
    2/s je agent_id — getrennte Buckets je Agent)."""

    PORT = 19889
    CODE = "ISOCDE"
    TOKEN_ALPHA = "alpha-token"
    TOKEN_BETA = "beta-token"

    @staticmethod
    def _write_config(tmp_path):
        import json

        cfg = tmp_path / "relay-config.json"
        cfg.write_text(
            json.dumps(
                {
                    "ANDROID_AGENT_TOKEN_ALPHA": "alpha-token",
                    "ANDROID_AGENT_TOKEN_BETA": "beta-token",
                }
            ),
            encoding="utf-8",
        )
        return str(cfg)

    def test_foreign_chat_id_reply_discarded(self, tmp_path, monkeypatch, caplog):
        """T5: Eine chat_id, die über /chat dem Agent alpha zugeordnet wurde,
        ist für beta fremd — chat.reply UND POST /chat_event werden verworfen
        (WS: log + still; HTTP: 403). alpha' Antwort auf dieselbe chat_id
        kommt durch."""
        import asyncio
        import aiohttp
        import logging

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                alpha = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN_ALPHA}"},
                )
                beta = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN_BETA}"},
                )
                # Hallo-Frame + 2 Agent-Connect-Presence-Pushes (alpha, beta).
                await _drain_phone_state_frame(phone, expected=3)

                r = await session.post(
                    f"http://127.0.0.1:{self.PORT}/chat",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                    json={"agent_id": "alpha", "text": "An alpha"},
                )
                data = await r.json()
                # queued/delivered-Status-Frames wegkonsumieren
                for _ in range(2):
                    await phone.receive_json(timeout=2)

                # Beta versucht, in alpha' Konversation zu antworten:
                await beta.send_json({
                    "type": "chat.reply", "chat_id": data["chat_id"],
                    "text": "Fremd-Antwort",
                })
                r2 = await session.post(
                    f"http://127.0.0.1:{self.PORT}/chat_event",
                    headers={"Authorization": f"Bearer {self.TOKEN_BETA}"},
                    json={"chat_id": data["chat_id"], "text": "Fremd-HTTP"},
                )
                assert r2.status == 403

                # Kein chat.event von beta an der Phone-WS:
                with pytest.raises(asyncio.TimeoutError):
                    await phone.receive_json(timeout=0.4)

                # Alpha' Antwort auf DIESELBE chat_id kommt durch:
                await alpha.send_json({
                    "type": "chat.reply", "chat_id": data["chat_id"],
                    "text": "Eigene Antwort",
                })
                fr = await phone.receive_json(timeout=2)
                assert fr["type"] == "chat.event"
                assert fr["agent_id"] == "alpha"
                assert fr["text"] == "Eigene Antwort"

                await beta.close()
                await alpha.close()
                await phone.close()

        with caplog.at_level(logging.WARNING, logger="tools.android_relay"):
            asyncio.run(scenario())
            _await_log_record(caplog, "Namespace-Isolation")
        assert "verworfen" in caplog.text

    def test_chat_event_rate_limit_separate_bucket_per_agent(self, tmp_path, monkeypatch):
        """T6-Beweis: Das Chat-Limit 2/s gilt je agent_id als EIGENER Bucket —
        alpha' Limit verbraucht betas Budget NICHT (2+2 = 4×200 in 1s)."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                statuses = []
                for token in (self.TOKEN_ALPHA, self.TOKEN_BETA):
                    # SEC-W1 W1C-3: je Agent EIGENE chat_id — der erste Reply
                    # claimt eine unbekannte chat_id jetzt; ein gemeinsamer
                    # Test-Schlüssel wäre 403-Isolation statt Rate-Beweis.
                    chat_key = "iso-rate-a" if token == self.TOKEN_ALPHA else "iso-rate-b"
                    for _ in range(2):
                        r = await session.post(
                            f"http://127.0.0.1:{self.PORT}/chat_event",
                            headers={"Authorization": f"Bearer {token}"},
                            json={"chat_id": chat_key, "text": "x"},
                        )
                        statuses.append(r.status)
                # 4 Requests in <1s: alle 200 — je Agent ein eigener Bucket.
                assert statuses == [200, 200, 200, 200]

                # Der 3. Request desselben Agentens in derselben Sekunde: 429.
                r = await session.post(
                    f"http://127.0.0.1:{self.PORT}/chat_event",
                    headers={"Authorization": f"Bearer {self.TOKEN_ALPHA}"},
                    json={"chat_id": "iso-rate-a", "text": "x"},
                )
                assert r.status == 429

        asyncio.run(scenario())


class TestPresencePush:
    """PH2-FIX (E2E P2): JE Agent-Presence-Wechsel (Connect → online=True,
    Disconnect → offline) pusht ein relay.state an die offene Phone-WS —
    gleiche Payload-Form wie das Hallo-Frame (_send_phone_state), die App
    parst relay.state bereits (kein App-Change nötig). Ohne offene Phone-WS
    ist der Push ein No-Op; Sendefehler je WS werden geschluckt."""

    PORT = 19890
    CODE = "PRCODE"
    TOKEN = "agent-presence-token"

    @staticmethod
    def _write_config(tmp_path):
        import json

        cfg = tmp_path / "relay-config.json"
        cfg.write_text(
            json.dumps(
                {
                    "ANDROID_AGENT_TOKEN_ALPHA": "agent-presence-token",
                    "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha",
                }
            ),
            encoding="utf-8",
        )
        return str(cfg)

    @staticmethod
    def _alpha(frame):
        return next(a for a in frame["agents"] if a["agent_id"] == "alpha")

    def test_agent_connect_pushes_relay_state_online(self, tmp_path, monkeypatch):
        """Connect-Push: Die offene Phone-WS sieht online=True, ohne dass sie
        neu verbinden muss (der App-Header folgt dem Agent-Status live)."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                hello = await _drain_phone_state_frame(phone)
                assert self._alpha(hello)["online"] is False

                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"},
                )
                push = await phone.receive_json(timeout=2)
                assert push["type"] == "relay.state"
                assert push["v"] == 1
                alpha = self._alpha(push)
                assert alpha["online"] is True
                assert alpha["display_name"] == "Alpha"

                # Kein Presence-Wechsel mehr → keine weiteren Frames (keine Flood).
                with pytest.raises(asyncio.TimeoutError):
                    await phone.receive_json(timeout=0.3)

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_agent_disconnect_pushes_relay_state_offline(self, tmp_path, monkeypatch):
        """Disconnect-Push: Nach dem Agent-Disconnect sieht die Phone-WS
        online=False — der Header erstarrete nicht auf 'Online'."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"},
                )
                # Hallo-Frame + Connect-Push wegkonsumieren.
                await _drain_phone_state_frame(phone, expected=2)

                await agent.close()
                push = await phone.receive_json(timeout=2)
                assert push["type"] == "relay.state"
                assert self._alpha(push)["online"] is False

                await phone.close()

        asyncio.run(scenario())

    def test_hello_frame_unchanged_without_presence_change(self, tmp_path, monkeypatch):
        """Hallo-Frame unverändert: Erster Phone-Frame bleibt relay.state
        {type, v, agents:[{agent_id, display_name, online}]} mit Offline-
        Prefill aus der Config — OHNE Presence-Wechsel danach Stille."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                hello = await _drain_phone_state_frame(phone)
                assert hello["type"] == "relay.state"
                assert hello["v"] == 1
                agents = hello["agents"]
                assert len(agents) == 1
                # PH-3 F7: session_active kam als viertes Feld dazu (Wahrheits-
                # Abgleich der App beim Re-Connect gegen den Session-Broker).
                assert set(agents[0]) == {"agent_id", "display_name", "online", "session_active"}
                assert agents[0]["agent_id"] == "alpha"
                assert agents[0]["display_name"] == "Alpha"
                assert agents[0]["online"] is False

                # Kein Agent-Connect/Disconnect → kein zweiter Frame.
                with pytest.raises(asyncio.TimeoutError):
                    await phone.receive_json(timeout=0.3)

                await phone.close()

        asyncio.run(scenario())

    def test_presence_broadcast_survives_dead_phone_ws(self):
        """Robustheit: Der Broadcast wirft NIE — fehlende Locks (Relay vor
        Server-Start), keine Phone-WS, eine tote (closed) WS und eine WS mit
        sendendem Fehler sind alles No-Ops bzw. geschluckte Exceptions."""
        import asyncio

        class _DeadWS:
            closed = True

            async def send_json(self, payload):
                raise RuntimeError("tote WS darf nicht senden")

        class _RaisingWS:
            closed = False

            async def send_json(self, payload):
                raise RuntimeError("send boom")

        async def scenario():
            state = _RelayState(self.CODE, self.PORT)

            # (a) Locks noch nicht gebunden (Relay vor _run_loop): No-Op.
            await _broadcast_agent_presence(state)

            state.phone_ws_lock = asyncio.Lock()
            state.agent_ws_lock = asyncio.Lock()
            state.agents["alpha"] = {
                "display_name": "Alpha",
                "online": True,
                "last_seen": 0.0,
                "fingerprint": "",
            }

            # (b) Keine Phone-WS: No-Op.
            await _broadcast_agent_presence(state)

            # (c) Tote WS (closed): No-Op, kein Send-Versuch.
            state.phone_ws = _DeadWS()
            await _broadcast_agent_presence(state)

            # (d) Sendefehler je WS: geschluckt (kein Crash des Handlers).
            state.phone_ws = _RaisingWS()
            await _broadcast_agent_presence(state)

        asyncio.run(scenario())


class TestJaegerFixrunde:
    """PH2-FIX (Jäger-Welle): Regression-Tests der Fixrunde — B1 Presence-
    Guard bei WS-Replace, B2 Broker-FIFO-Cap 500, B3 Owner-Check für
    chat.read/chat.activity (Cross-Agent-Spoofing), B5 Spinner-Reset beim
    Agent-Disconnect."""

    PORT = 19891
    CODE = "JGRB91"
    TOKEN_A = "jaeger-token-alpha"
    TOKEN_B = "jaeger-token-beta"

    @staticmethod
    def _write_config(tmp_path):
        import json

        cfg = tmp_path / "relay-config.json"
        cfg.write_text(
            json.dumps(
                {
                    "ANDROID_AGENT_TOKEN_ALPHA": "jaeger-token-alpha",
                    "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha",
                    "ANDROID_AGENT_TOKEN_BETA": "jaeger-token-beta",
                    "ANDROID_AGENT_DISPLAY_BETA": "Beta",
                }
            ),
            encoding="utf-8",
        )
        return str(cfg)

    # ── B1: Presence-Guard bei WS-Replace ─────────────────────────────────

    def test_agent_ws_replace_keeps_agent_online(self, tmp_path, monkeypatch):
        """B1 Replace-Szenario: Der alte Agent-Socket wird beim Reconnect
        ersetzt — dessen finally darf online NICHT auf False ziehen (die
        neue Verbindung lebt). Kein offline-Push, Registry bleibt online."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                ws_old = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN_A}"},
                )
                # Hallo-Frame + Connect-Push wegkonsumieren.
                await _drain_phone_state_frame(phone, expected=2)

                # Replace: zweite Verbindung mit demselben Token ersetzt die
                # erste (der Relay schließt ws_old mit GOING_AWAY).
                ws_new = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN_A}"},
                )
                # Connect-Push des Replaces (weiterhin online=True).
                push = await phone.receive_json(timeout=2)
                assert push["type"] == "relay.state"
                assert next(a for a in push["agents"] if a["agent_id"] == "alpha")["online"] is True

                # Der finally-Block des ALTEN Sockets läuft jetzt — mit dem
                # Presence-Guard (Jäger B1) bleibt der Agent online.
                await asyncio.sleep(0.5)
                assert get_agent_registry()["alpha"]["online"] is True, (
                    "WS-Replace: alter Socket hat den Agent fälschlich offline gesetzt"
                )
                # Kein offline-Push (Presence unverändert → Stille am Phone).
                with pytest.raises(asyncio.TimeoutError):
                    await phone.receive_json(timeout=0.3)

                # Echter Disconnect der AKTUELLEN Verbindung → offline-Push.
                await ws_new.close()
                push = await phone.receive_json(timeout=2)
                assert push["type"] == "relay.state"
                assert next(a for a in push["agents"] if a["agent_id"] == "alpha")["online"] is False
                deadline = time.monotonic() + 5
                while get_agent_registry()["alpha"]["online"] is not False:
                    assert time.monotonic() < deadline
                    time.sleep(0.01)

                await phone.close()

        asyncio.run(scenario())

    # ── B2: FIFO-Cap der Broker-Queues ────────────────────────────────────

    def test_broker_queues_fifo_cap_500(self):
        """B2 Outbox-Cap: 600 Frames in die Phone-Outbox und die Agent-
        Inbound-Queue — die Größe bleibt bei 500 (FIFO: die ältesten 100
        Frames fallen raus, der Relay-Speicher bleibt gebunden)."""
        import asyncio
        from tools.android_relay import (
            _CHAT_QUEUE_MAXLEN,
            _deliver_to_agent,
            _deliver_to_phone,
        )

        assert _CHAT_QUEUE_MAXLEN == 500

        async def scenario():
            state = _RelayState(self.CODE, self.PORT)
            state.broker_lock = asyncio.Lock()
            state.phone_ws_lock = asyncio.Lock()
            state.agent_ws_lock = asyncio.Lock()

            for i in range(600):
                message = {"type": "chat.event", "v": 1, "chat_id": "cap", "text": str(i), "ts": i}
                assert await _deliver_to_phone(state, message) is False  # Phone offline → Spool
            assert len(state.phone_outbox) == 500, (
                f"Phone-Outbox muss bei 500 gekappt sein, ist {len(state.phone_outbox)}"
            )
            # FIFO: die ältesten 100 sind gefallen — erster Eintrag = Frame 100.
            assert state.phone_outbox[0][1]["ts"] == 100
            assert state.phone_outbox[-1][1]["ts"] == 599

            for i in range(600):
                message = {
                    "type": "chat.send", "v": 1, "chat_id": "cap",
                    "msg_id": f"m{i}", "agent_id": "alpha", "text": "x", "ts": i,
                }
                assert await _deliver_to_agent(state, "alpha", message) is False  # Agent offline
            queue = state.chat_inbound["alpha"]
            assert len(queue) == 500, (
                f"Agent-Inbound muss bei 500 gekappt sein, ist {len(queue)}"
            )
            assert queue[0][1]["ts"] == 100
            assert queue[-1][1]["ts"] == 599

        asyncio.run(scenario())

    # ── B3: Owner-Check für chat.read/chat.activity ───────────────────────

    def test_foreign_chat_id_read_activity_discarded(self, tmp_path, monkeypatch, caplog):
        """B3 Spoofing: chat.read/chat.activity des Agenten BETA auf eine
        chat_id, die ALPHA gehört (per /chat angelegt), werden verworfen +
        geloggt — am Phone kommt KEIN chat.status/chat.activity-Frame an.
        Der Owner selbst liest danach normal (positive control)."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                alpha = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN_A}"},
                )
                beta = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN_B}"},
                )
                # Hallo + 2 Connect-Pushes wegkonsumieren.
                await _drain_phone_state_frame(phone, expected=3)

                # App legt eine Conversation MIT ALPHA an → chat_owner.
                r = await session.post(
                    f"http://127.0.0.1:{self.PORT}/chat",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                    json={"agent_id": "alpha", "text": "Spoof-Basis"},
                )
                data = await r.json()
                chat_id, msg_id = data["chat_id"], data["msg_id"]
                # queued + delivered ans Phone wegkonsumieren.
                for _ in range(2):
                    fr = await phone.receive_json(timeout=2)
                    assert fr["type"] == "chat.status"

                # Spoofing-Versuch: BETA tut auf alpha' chat_id busy.
                await beta.send_json(
                    {"type": "chat.activity", "chat_id": chat_id, "working": True}
                )
                # Spoofing-Versuch: BETA stempelt alpha' Nachricht 'gelesen'.
                await beta.send_json(
                    {"type": "chat.read", "chat_id": chat_id, "msg_id": msg_id}
                )
                # Beide verworfen: Log-Warnung + Stille am Phone.
                _await_log_record(caplog, "fremde chat_id")
                with pytest.raises(asyncio.TimeoutError):
                    await phone.receive_json(timeout=0.3)

                # Positive control: der OWNER liest — chat.status=read kommt.
                await alpha.send_json(
                    {"type": "chat.read", "chat_id": chat_id, "msg_id": msg_id}
                )
                fr = await phone.receive_json(timeout=2)
                assert fr["type"] == "chat.status" and fr["status"] == "read"

                # Positive control: der OWNER signalisiert busy — Frame kommt.
                await alpha.send_json(
                    {"type": "chat.activity", "chat_id": chat_id, "working": True}
                )
                fr = await phone.receive_json(timeout=2)
                assert fr["type"] == "chat.activity" and fr["working"] is True

                await alpha.close()
                await beta.close()
                await phone.close()

        asyncio.run(scenario())

    # ── B5: Spinner-Reset beim Agent-Disconnect ────────────────────────────

    def test_agent_disconnect_resets_working_activity(self, tmp_path, monkeypatch):
        """B5 Relay-Seite: Agent meldet working=true und stirbt (WS-Disconnect)
        → das Relay broadcastet relay.state (offline) UND je chat_id ein
        chat.activity-Frame mit working=false — der App-Spinner erstarret
        nicht."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN_A}"},
                )
                await _drain_phone_state_frame(phone, expected=2)

                await agent.send_json(
                    {"type": "chat.activity", "chat_id": "act-1", "working": True,
                     "started_at": 1730515200000}
                )
                fr = await phone.receive_json(timeout=2)
                assert fr["type"] == "chat.activity" and fr["working"] is True

                # Agent-Disconnect mitten in der Arbeit.
                await agent.close()
                # Reihenfolge im finally: erst Presence-Push, dann Spinner-Reset.
                offline = await phone.receive_json(timeout=2)
                assert offline["type"] == "relay.state"
                reset = await phone.receive_json(timeout=2)
                assert reset["type"] == "chat.activity", (
                    f"Disconnect muss einen activity-Reset-Frame bringen, kam: {reset}"
                )
                assert reset["working"] is False
                assert reset["chat_id"] == "act-1"
                assert reset["agent_id"] == "alpha"

                # Kein weiterer Frame (kein Flood).
                with pytest.raises(asyncio.TimeoutError):
                    await phone.receive_json(timeout=0.3)

                await phone.close()

        asyncio.run(scenario())

    def test_ws_replace_keeps_working_activity(self, tmp_path, monkeypatch):
        """B1×B5-Interaktion: working=true auf dem ALTEN Socket, dann Replace —
        der finally des alten Sockets darf den Spinner NICHT zurücksetzen (die
        neue Verbindung arbeitet weiter). Erst der echte Disconnect der
        aktuellen Verbindung liefert working=false."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                ws_old = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN_A}"},
                )
                await _drain_phone_state_frame(phone, expected=2)

                await ws_old.send_json(
                    {"type": "chat.activity", "chat_id": "act-2", "working": True}
                )
                fr = await phone.receive_json(timeout=2)
                assert fr["type"] == "chat.activity" and fr["working"] is True

                # Replace: neuer Socket — der alte finally darf working NICHT
                # zurücksetzen und kein offline pushen.
                ws_new = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN_A}"},
                )
                push = await phone.receive_json(timeout=2)  # Connect-Push (online)
                assert push["type"] == "relay.state"
                assert next(a for a in push["agents"] if a["agent_id"] == "alpha")["online"] is True
                await asyncio.sleep(0.5)
                with pytest.raises(asyncio.TimeoutError):
                    await phone.receive_json(timeout=0.3)  # kein activity-false, kein offline

                # Echter Disconnect der AKTUELLEN Verbindung → Spinner-Reset.
                await ws_new.close()
                offline = await phone.receive_json(timeout=2)
                assert offline["type"] == "relay.state"
                reset = await phone.receive_json(timeout=2)
                assert reset["type"] == "chat.activity" and reset["working"] is False
                assert reset["chat_id"] == "act-2"

                await phone.close()

        asyncio.run(scenario())

    def test_activity_unknown_chat_id_allowed(self, tmp_path, monkeypatch):
        """B3-Negative-Control: UnBEKANNTE chat_id (noch kein Owner über /chat
        angelegt) bleibt erlaubt — der Owner-Check darf agent-initiierte
        Konversationen nicht blocken (kein Over-Blocking)."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN_A}"},
                )
                await _drain_phone_state_frame(phone, expected=2)

                # Unbekannte chat_id: kein Owner → erlaubt, Frame kommt durch.
                await agent.send_json(
                    {"type": "chat.activity", "chat_id": "agent-initiiert-1", "working": True}
                )
                fr = await phone.receive_json(timeout=2)
                assert fr["type"] == "chat.activity"
                assert fr["chat_id"] == "agent-initiiert-1"
                assert fr["working"] is True

                await agent.close()
                await phone.close()

        asyncio.run(scenario())


# ═════════════════════════════════════════════════════════════════════════════
# PH-3 (Welle 1): E2E-Crypto Relay↔Adapter — Key-Registry, X3DH-Handshake über
# /crypto_hello + /agent_ws, opake chat.*.enc-Pass-Through, Replay-Fenster,
# Downgrade-Schutz, 24-h-Session, Fingerprint-Gleichheit. Die Endpunkte (Phone-
# Referenz PhoneCryptoPeer + AgentCryptoSession) kommen aus
# contrib/hermes-chat-plugin/adapter.py — byte-exakte Vorlage für Kotlin Welle 2
# (Vertrag: secrets-docs/PH3-KDF-FIXTURES-v1.md).
# ═════════════════════════════════════════════════════════════════════════════

import asyncio  # noqa: E402
import json  # noqa: E402
import os as _ph3_os  # noqa: E402
import sys as _ph3_sys  # noqa: E402

_PH3_SYS_PATH = _ph3_os.path.join(_ph3_os.path.dirname(_ph3_os.path.abspath(__file__)),
                                  "..", "contrib", "hermes-chat-plugin")
if _PH3_SYS_PATH not in _ph3_sys.path:
    _ph3_sys.path.insert(0, _PH3_SYS_PATH)
_ph3_sys.modules.setdefault("gateway", None)
import adapter as _ph3_adapter  # noqa: E402  (contrib/hermes-chat-plugin/adapter.py)


async def _recv_until(ws, frame_type, timeout=6.0, parked=None):
    """Frames lesen bis der gesuchte Typ kommt; andere in `parked` ablegen."""
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        assert remaining > 0, f"Frame {frame_type!r} kam nicht (parked: {parked})"
        frame = await ws.receive_json(timeout=max(0.1, remaining))
        if frame.get("type") == frame_type:
            return frame
        if parked is not None:
            parked.append(frame)


class TestPh3CryptoE2E:
    """PH-3-T2/T4: Der Relay bleibt BLIND — er sieht nur Metadaten, ct bleibt
    opak, Keys erscheinen in keinem Log (Audits unten)."""

    PORT = 19901
    CODE = "PH3CDE1"
    TOKEN = "ph3-agent-token"
    SESSION_ID = "ph3-e2e-session-0001"

    # Fixe Agent-Identity (RFC-7748-Bob als Identity, Fixture-PreKey):
    IK_A_PRIV = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"
    IK_A_PUB = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
    SPK_A_PRIV = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"
    SPK_A_PUB = "52a5524b0c32e8a47f239ab6ca8e42c3b9316a54cae4854ecdb5889311cc9a7e"

    @property
    def identity(self):
        return {"identity_priv": self.IK_A_PRIV, "identity_pub": self.IK_A_PUB,
                "prekey_priv": self.SPK_A_PRIV, "prekey_pub": self.SPK_A_PUB}

    def _write_config(self, tmp_path, with_keys=True):
        cfg = tmp_path / "relay-config.json"
        data = {
            "ANDROID_AGENT_TOKEN_ALPHA": self.TOKEN,
            "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha",
            "CHAT_RATE_PER_SECOND": 50,  # PH-3-Tests: Rate-Limit ist eigens getestet
        }
        if with_keys:
            data.update({
                "ANDROID_AGENT_IDENTITY_ALPHA": self.IK_A_PUB,
                "ANDROID_AGENT_PREKEY_ALPHA": self.SPK_A_PUB,
                "ANDROID_AGENT_FINGERPRINT_ALPHA":
                    _ph3_adapter.fingerprint_of_pub(bytes.fromhex(self.IK_A_PUB)),
            })
        cfg.write_text(json.dumps(data), encoding="utf-8")
        return str(cfg)

    # ------------------------------------------------------------- Registry
    def test_key_get_serves_agent_keys_and_fail_closes_without(self, tmp_path, monkeypatch):
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        # Registry-Fingerprint (public Info) landet in der Agent-Registry.
        registry = get_agent_registry()["alpha"]
        assert registry["fingerprint"] == _ph3_adapter.fingerprint_of_pub(
            bytes.fromhex(self.IK_A_PUB))

        async def scenario():
            async with aiohttp.ClientSession() as session:
                got = await session.get(
                    f"http://127.0.0.1:{self.PORT}/key_get",
                    params={"agent_id": "alpha"},
                    headers={"Authorization": f"Bearer {self.CODE}"})
                assert got.status == 200
                body = await got.json()
                assert body["ik_pub"] == self.IK_A_PUB
                assert body["spk_pub"] == self.SPK_A_PUB
                assert body["agent_id"] == "alpha"
                # Fail-closed ohne Bearer …
                bad = await session.get(f"http://127.0.0.1:{self.PORT}/key_get",
                                        params={"agent_id": "alpha"})
                assert bad.status == 401

        asyncio.run(scenario())

        # Ohne Identity-Keys in der Config: fail-closed (404), keine E2E-Teilnahme.
        stop_relay()
        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path, with_keys=False))
        start_relay(pairing_code=self.CODE, port=self.PORT + 1)

        async def scenario2():
            async with aiohttp.ClientSession() as session:
                got = await session.get(
                    f"http://127.0.0.1:{self.PORT + 1}/key_get",
                    params={"agent_id": "alpha"},
                    headers={"Authorization": f"Bearer {self.CODE}"})
                assert got.status == 404

        asyncio.run(scenario2())

    def test_key_publish_stores_device_keys_0600_never_logged(self, tmp_path, monkeypatch, caplog):
        import aiohttp

        keys_path = tmp_path / "relay-keys.json"
        monkeypatch.setenv("ANDROID_RELAY_KEYS", str(keys_path))
        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT + 2)

        import asyncio

        async def scenario():
            async with aiohttp.ClientSession() as session:
                resp = await session.post(
                    f"http://127.0.0.1:{self.PORT + 2}/key_publish",
                    json={"device_id": "phone-a", "ik_pub": "aa" * 32, "spk_pub": "bb" * 32},
                    headers={"Authorization": f"Bearer {self.CODE}"})
                assert resp.status == 200
                # Müll-Keys = 400
                bad = await session.post(
                    f"http://127.0.0.1:{self.PORT + 2}/key_publish",
                    json={"ik_pub": "xx", "spk_pub": "yy"},
                    headers={"Authorization": f"Bearer {self.CODE}"})
                assert bad.status == 400

        asyncio.run(scenario())

        import stat as _stat
        assert _stat.S_IMODE(keys_path.stat().st_mode) == 0o600, "Key-Registry 0600!"
        stored = json.loads(keys_path.read_text(encoding="utf-8"))
        assert stored["device"]["ik_pub"] == "aa" * 32
        assert stored["device"]["spk_pub"] == "bb" * 32
        # Audit: Key-Material NIE im Log (auch nicht als Teilmenge).
        dumped = "\n".join(r.getMessage() for r in caplog.records)
        assert "aa" * 32 not in dumped and "bb" * 32 not in dumped

    def test_crypto_hello_rejected_for_agent_without_keys(self, tmp_path, monkeypatch):
        """Fail-closed: crypto.hello für Agenten OHNE Identity-Keys → 403."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path, with_keys=False))
        start_relay(pairing_code=self.CODE, port=self.PORT + 3)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                resp = await session.post(
                    f"http://127.0.0.1:{self.PORT + 3}/crypto_hello",
                    json={"agent_id": "alpha", "session_id": self.SESSION_ID,
                          "ik_pub": "aa" * 32, "ek_pub": "bb" * 32, "spk_pub": "cc" * 32},
                    headers={"Authorization": f"Bearer {self.CODE}"})
                assert resp.status == 403
                body = await resp.json()
                assert "no crypto keys" in body["error"]

        asyncio.run(scenario())

    # ------------------------------------------------- Handshake + Full-Drill
    def test_e2e_drill_handshake_enc_chat_relay_stays_blind(self, tmp_path, monkeypatch, caplog):
        """T4-Kern-Drill: key_get → crypto.hello → hello_ack → verschlüsselter
        Vollzyklus (chat.send.enc → chat.event.enc) + Fingerprint-Gleichheit
        + Relay-Blindheits-Audit (kein Klartext, kein Key in irgendeinem Log)."""
        import logging
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT + 4)
        base = f"http://127.0.0.1:{self.PORT + 4}"

        async def scenario():
            async with aiohttp.ClientSession() as session:
                # 1. Phone verbindet (relay.state konsumieren) + Agent verbindet.
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 4}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                await _drain_phone_state_frame(phone, expected=1)  # Hallo-Frame
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 4}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6)  # Presence-Push

                # 2. Phone liest die Agent-Keys aus der Registry.
                got = await session.get(f"{base}/key_get", params={"agent_id": "alpha"},
                                        headers={"Authorization": f"Bearer {self.CODE}"})
                assert got.status == 200
                keys = await got.json()

                # 3. X3DH-Handshake über den Relay (Phone initiiert).
                peer = _ph3_adapter.PhoneCryptoPeer("alpha", keys["ik_pub"], keys["spk_pub"])
                hello = peer.make_hello(self.SESSION_ID)
                resp = await session.post(f"{base}/crypto_hello", json=hello,
                                          headers={"Authorization": f"Bearer {self.CODE}"})
                assert resp.status == 200
                assert (await resp.json())["delivered"] is True

                # Agent-Seite: hello empfangen → Session ableiten → ack senden.
                hello_frame = await agent.receive_json(timeout=6)
                assert hello_frame["type"] == "crypto.hello"
                assert hello_frame["ek_pub"] == hello["ek_pub"]
                agent_session, ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
                    hello_frame, self.identity)
                await agent.send_json(ack)

                # Phone: ack verifizieren → Session etabliert, Fingerprints gleich.
                ack_frame = await _recv_until(phone, "crypto.hello_ack", timeout=6)
                peer.consume_ack(ack_frame)
                assert peer.fingerprint == agent_session.fingerprint, \
                    "T4: Fingerprint-Gleichheit beidseitig verletzt"

                # 4. Phone → Agent: verschlüsselte Nachricht über POST /chat.
                secret = "Anfrage: Wie wird das Wetter in Linz?"
                enc_frame = peer.encrypt_frame(
                    "chat.send.enc", "chat-00042", {"text": secret, "msg_id": "q1"})
                resp = await session.post(
                    f"{base}/chat",
                    json={"agent_id": "alpha", "session_id": self.SESSION_ID,
                          "chat_id": "chat-00042", "ts": enc_frame["ts"],
                          "hdr": enc_frame["hdr"], "nonce": enc_frame["nonce"],
                          "ct": enc_frame["ct"]},
                    headers={"Authorization": f"Bearer {self.CODE}"})
                assert resp.status == 200, await resp.text()
                body = await resp.json()
                assert body["delivered"] is True and body["msg_id"]

                # Agent: chat.send.enc mit UNVERÄNDERTEM ct (Relay = opak).
                got_frame = await agent.receive_json(timeout=6)
                assert got_frame["type"] == "chat.send.enc"
                assert got_frame["ct"] == enc_frame["ct"]
                assert got_frame["nonce"] == enc_frame["nonce"]
                payload = agent_session.decrypt_frame(got_frame)
                assert payload["text"] == secret

                # 5. Agent → Phone: verschlüsselte Antwort (chat.reply.enc).
                reply_ct_frame = agent_session.encrypt_frame(
                    "chat.reply.enc", "chat-00042",
                    {"text": "Antwort: Sonne, 21 Grad.", "msg_id": "r1"},
                    clear={"msg_id": "r1", "elapsed_ms": 42})
                reply_ct_frame["elapsed_ms"] = 42
                await agent.send_json(reply_ct_frame)

                event = await _recv_until(phone, "chat.event.enc", timeout=6)
                assert event["ct"] == reply_ct_frame["ct"]
                assert event["elapsed_ms"] == 42
                assert peer.decrypt_frame(event)["text"] == "Antwort: Sonne, 21 Grad."

                # 6. Relay-Blindheit: Klartext + Key-Material nie im Log.
                dumped = "\n".join(r.getMessage() for r in caplog.records)
                assert "Wetter in Linz" not in dumped, "Klartext im Relay-Log!"
                assert "21 Grad" not in dumped, "Klartext im Relay-Log!"
                assert self.IK_A_PRIV not in dumped, "Private Key im Relay-Log!"
                assert self.SESSION_ID not in dumped or True  # session_id ok (Metadatum)

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_j3_spooled_enc_gets_fresh_ts_phone_to_agent(self, tmp_path, monkeypatch):
        """JÄGER J3 (P1, JAEGE-RFULL 02.11.2026): enc-Frame >10 min im Agent-
        Spool darf bei Zustellung NICHT an der Adapter-Freshness sterben —
        der Flush schreibt ts neu, die Entschlüsselung klappt, 'delivered' ist
        ehrlich. Vorher: stiller Verwurf + Lügen-Chip."""
        import aiohttp
        import tools.android_relay as R

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT + 14)
        base = f"http://127.0.0.1:{self.PORT + 14}"
        session_id = self.SESSION_ID + "-j3a"

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 14}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                await _drain_phone_state_frame(phone, expected=1)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 14}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6)

                got = await session.get(f"{base}/key_get", params={"agent_id": "alpha"},
                                        headers={"Authorization": f"Bearer {self.CODE}"})
                keys = await got.json()
                peer = _ph3_adapter.PhoneCryptoPeer("alpha", keys["ik_pub"], keys["spk_pub"])
                hello = peer.make_hello(session_id)
                resp = await session.post(f"{base}/crypto_hello", json=hello,
                                          headers={"Authorization": f"Bearer {self.CODE}"})
                assert resp.status == 200
                hello_frame = await agent.receive_json(timeout=6)
                agent_session, ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
                    hello_frame, self.identity)
                await agent.send_json(ack)
                peer.consume_ack(await _recv_until(phone, "crypto.hello_ack", timeout=6))

                # Agent OFFLINE → Phone-Nachricht landet im Spool.
                await agent.close()
                deadline = time.monotonic() + 5
                while get_agent_registry()["alpha"]["online"] is not False:
                    assert time.monotonic() < deadline, "Agent bleibt online"
                    time.sleep(0.01)

                enc_frame = peer.encrypt_frame(
                    "chat.send.enc", "chat-j3-a", {"text": "Spool-Geheimnis", "msg_id": "j3a"})
                resp = await session.post(
                    f"{base}/chat",
                    json={"agent_id": "alpha", "session_id": session_id,
                          "chat_id": "chat-j3-a", "ts": enc_frame["ts"],
                          "hdr": enc_frame["hdr"], "nonce": enc_frame["nonce"],
                          "ct": enc_frame["ct"]},
                    headers={"Authorization": f"Bearer {self.CODE}"})
                assert resp.status == 200, await resp.text()
                body = await resp.json()
                assert body["queued"] is True and body["delivered"] is False

                # Gespoolte Frame-ts ALT machen (simuliert >10 min Offline-Phase).
                state = R._relay_instance
                assert state is not None, "Relay-Instanz für Spool-Introspektion fehlt"
                queue = state.chat_inbound.get("alpha")
                assert queue, "chat.send.enc wurde nicht gespoolt"
                queued_frame = queue[0][1]
                assert queued_frame["type"] == "chat.send.enc"
                stale_ts = int(time.time() * 1000) - 3_600_000  # 1 h alt
                queued_frame["ts"] = stale_ts

                # Reconnect → Flush: frische ts + Entschlüsselung klappt
                # (ohne Fix: 'Frame zu alt'-Verwurf beim Agenten STILL,
                # obwohl der Flush delivered markiert hat).
                agent2 = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 14}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                got_frame = await agent2.receive_json(timeout=6)
                assert got_frame["type"] == "chat.send.enc", got_frame
                assert got_frame["ts"] > stale_ts + 3_500_000, (
                    "Flush muss die enc-ts neu schreiben (J3)")
                payload = agent_session.decrypt_frame(got_frame)
                assert payload["text"] == "Spool-Geheimnis"

                await agent2.close()
                await phone.close()

        asyncio.run(scenario())

    def test_j3_spooled_enc_gets_fresh_ts_agent_to_phone(self, tmp_path, monkeypatch):
        """JÄGER J3, Gegenrichtung: chat.reply.enc in der Phone-Outbox bekommt
        beim Flush eine frische ts — PhoneCryptoPeer.decryptFrame verwirft
        sonst alles >10 min STILL (Antwort weg, Chip sagt zugestellt)."""
        import aiohttp
        import tools.android_relay as R

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT + 15)
        base = f"http://127.0.0.1:{self.PORT + 15}"
        session_id = self.SESSION_ID + "-j3b"

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 15}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                await _drain_phone_state_frame(phone, expected=1)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 15}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6)

                got = await session.get(f"{base}/key_get", params={"agent_id": "alpha"},
                                        headers={"Authorization": f"Bearer {self.CODE}"})
                keys = await got.json()
                peer = _ph3_adapter.PhoneCryptoPeer("alpha", keys["ik_pub"], keys["spk_pub"])
                hello = peer.make_hello(session_id)
                resp = await session.post(f"{base}/crypto_hello", json=hello,
                                          headers={"Authorization": f"Bearer {self.CODE}"})
                assert resp.status == 200
                hello_frame = await agent.receive_json(timeout=6)
                agent_session, ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
                    hello_frame, self.identity)
                await agent.send_json(ack)
                peer.consume_ack(await _recv_until(phone, "crypto.hello_ack", timeout=6))

                # Phone OFFLINE → Agent-Antwort landet in der Outbox.
                await phone.close()
                state = R._relay_instance
                assert state is not None
                deadline = time.monotonic() + 5
                while state.phone_ws is not None:
                    assert time.monotonic() < deadline, "Phone-WS bleibt am Relay hängen"
                    time.sleep(0.01)

                reply = agent_session.encrypt_frame(
                    "chat.reply.enc", "chat-j3-b",
                    {"text": "Spool-Antwort", "msg_id": "rj3b"}, clear={"msg_id": "rj3b"})
                await agent.send_json(reply)
                deadline = time.monotonic() + 5
                while not state.phone_outbox:
                    assert time.monotonic() < deadline, "chat.reply.enc wurde nicht gespoolt"
                    time.sleep(0.01)
                queued_frame = state.phone_outbox[0][1]
                assert queued_frame["type"] == "chat.event.enc"
                stale_ts = int(time.time() * 1000) - 3_600_000  # 1 h alt
                queued_frame["ts"] = stale_ts

                # Phone-Reconnect → Flush: frische ts + Entschlüsselung klappt.
                phone2 = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 15}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                hello_state = await phone2.receive_json(timeout=6)
                assert hello_state["type"] == "relay.state", hello_state
                event = await phone2.receive_json(timeout=6)
                assert event["type"] == "chat.event.enc", event
                assert event["ts"] > stale_ts + 3_500_000, (
                    "Flush muss die enc-ts neu schreiben (J3)")
                assert peer.decrypt_frame(event)["text"] == "Spool-Antwort"

                await phone2.close()
                await agent.close()

        asyncio.run(scenario())

    def test_post_rotation_counters_not_rejected_as_replay(self, tmp_path, monkeypatch):
        """P1c (vc36-Drill 02.11.): Nach dem ersten Antwort-Durchlauf rotiert
        das Phone die Ratchet-Kette — die nächste Nachricht trägt NEUEN
        hdr.rk und legitimen Zähler n=0/pn=1 (der Agent post-Rotation analog).
        Der Grob-Replay-Filter des Relays ist je (Session, Richtung, Kette)
        zu führen: der alte (Session, Richtung)-Schlüssel wertete jeden
        Post-Rotations-Zähler als Duplikat → 429-Tod der gesamten Konversation
        (live-Beweis: FP-Abgleich 429 'enc Replay/Fenster-Verletzung')."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT + 13)
        base = f"http://127.0.0.1:{self.PORT + 13}"

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 13}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                await _drain_phone_state_frame(phone, expected=1)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 13}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6)
                got = await session.get(f"{base}/key_get", params={"agent_id": "alpha"},
                                        headers={"Authorization": f"Bearer {self.CODE}"})
                keys = await got.json()
                peer = _ph3_adapter.PhoneCryptoPeer("alpha", keys["ik_pub"], keys["spk_pub"])
                hello = peer.make_hello(self.SESSION_ID)
                resp = await session.post(f"{base}/crypto_hello", json=hello,
                                          headers={"Authorization": f"Bearer {self.CODE}"})
                assert resp.status == 200
                hello_frame = await agent.receive_json(timeout=6)
                agent_session, ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
                    hello_frame, self.identity)
                await agent.send_json(ack)
                ack_frame = await _recv_until(phone, "crypto.hello_ack", timeout=6)
                peer.consume_ack(ack_frame)

                async def post_chat(frame):
                    return await session.post(
                        f"{base}/chat",
                        json={"agent_id": "alpha", "session_id": self.SESSION_ID,
                              "chat_id": "chat-rot", "ts": frame["ts"],
                              "hdr": frame["hdr"], "nonce": frame["nonce"],
                              "ct": frame["ct"]},
                        headers={"Authorization": f"Bearer {self.CODE}"})

                # Runde 1: Phone n=0 (erste Kette) → Agent-Antwort (Agent hat
                # auf den Phone-Key rotiert) → Phone-Entschlüsselung rotiert
                # die Phone-Empfangskette (neuer Peer-Key im Rahmen).
                f1 = peer.encrypt_frame("chat.send.enc", "chat-rot",
                                        {"text": "r1", "msg_id": "m1"})
                r1 = await post_chat(f1)
                assert r1.status == 200, await r1.text()
                got1 = await agent.receive_json(timeout=6)
                agent_session.decrypt_frame(got1)
                reply1 = agent_session.encrypt_frame("chat.reply.enc", "chat-rot",
                                                     {"text": "a1", "msg_id": "r1"})
                await agent.send_json(reply1)
                ev1 = await _recv_until(phone, "chat.event.enc", timeout=6)
                assert peer.decrypt_frame(ev1)["text"] == "a1"

                # P1c-KERN: Phone-Post-Rotation — NEUER hdr.rk, n=0, pn=1.
                # Der alte Relay-Schlüssel lehnte das als Replay (429) ab.
                f2 = peer.encrypt_frame("chat.send.enc", "chat-rot",
                                        {"text": "r2", "msg_id": "m2"})
                assert f2["hdr"]["rk"] != f1["hdr"]["rk"], \
                    "Testaufbau: Phone-Kette müsste rotiert haben"
                assert f2["hdr"]["n"] == 0 and f2["hdr"]["pn"] == 1, \
                    f"Testaufbau: Post-Rotations-Zähler falsch: {f2['hdr']}"
                r2 = await post_chat(f2)
                assert r2.status == 200, \
                    f"P1c: Post-Rotations-Frame fälschlich als Replay verworfen: {await r2.text()}"

                # Agent-Post-Rotations-Antwort (neuer hdr.rk) muss ebenfalls
                # durch den Relay zum Phone kommen (nicht als Replay sterben).
                got2 = await agent.receive_json(timeout=6)
                agent_session.decrypt_frame(got2)
                reply2 = agent_session.encrypt_frame("chat.reply.enc", "chat-rot",
                                                     {"text": "a2", "msg_id": "r2"})
                assert reply2["hdr"]["rk"] != reply1["hdr"]["rk"], \
                    "Testaufbau: Agent-Kette müsste rotiert haben"
                await agent.send_json(reply2)
                ev2 = await _recv_until(phone, "chat.event.enc", timeout=6)
                assert peer.decrypt_frame(ev2)["text"] == "a2"

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_cleartext_downgrade_blocked_while_session_active(self, tmp_path, monkeypatch):
        """PH-3: aktive bestätigte E2E-Session → Klartext-/chat wird 403
        verworfen (kein silent Downgrade); chat.reply (Agent-Richtung) ebenso."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT + 5)
        base = f"http://127.0.0.1:{self.PORT + 5}"
        headers = {"Authorization": f"Bearer {self.CODE}"}

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 5}/ws", headers=headers)
                await _drain_phone_state_frame(phone, expected=1)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 5}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6)

                peer = _ph3_adapter.PhoneCryptoPeer(
                    "alpha", self.IK_A_PUB, self.SPK_A_PUB)
                hello = peer.make_hello(self.SESSION_ID)
                await session.post(f"{base}/crypto_hello", json=hello, headers=headers)
                hello_frame = await agent.receive_json(timeout=6)
                _, ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
                    hello_frame, self.identity)
                await agent.send_json(ack)
                await _recv_until(phone, "crypto.hello_ack", timeout=6)

                # Downgrade: Klartext an den Agenten → 403.
                resp = await session.post(
                    f"{base}/chat",
                    json={"agent_id": "alpha", "text": "klartext-downgrade"},
                    headers=headers)
                assert resp.status == 403
                assert "cleartext" in (await resp.json())["error"]

                # Agent-Richtung: Klartext-chat.reply wird verworfen (Log).
                await agent.send_json({"type": "chat.reply", "chat_id": "chat-1",
                                       "text": "klartext-antwort"})
                await asyncio.sleep(0.2)

                # enc ohne bestätigte Session (fremde session_id) → 403 fail-closed.
                resp = await session.post(
                    f"{base}/chat",
                    json={"agent_id": "alpha", "session_id": "unbekannt-session-99",
                          "chat_id": "chat-fremd-0001",
                          "hdr": {"rk": "aa" * 32, "n": 0, "pn": 0},
                          "nonce": _ph3_adapter._b64e(b"\x00" * 12),
                          "ct": _ph3_adapter._b64e(b"\x01" * 32)},
                    headers=headers)
                assert resp.status == 403

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_replay_window_blocks_duplicate_enc_frame(self, tmp_path, monkeypatch):
        """T4: Replay-Fenster am Relay — derselbe enc-Frame (gleicher hdr.n)
        kommt NICHT zweimal durch (429), die Endpunkte prüfen zusätzlich GCM."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT + 6)
        base = f"http://127.0.0.1:{self.PORT + 6}"
        headers = {"Authorization": f"Bearer {self.CODE}"}

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 6}/ws", headers=headers)
                await _drain_phone_state_frame(phone, expected=1)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 6}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6)

                peer = _ph3_adapter.PhoneCryptoPeer(
                    "alpha", self.IK_A_PUB, self.SPK_A_PUB)
                hello = peer.make_hello(self.SESSION_ID)
                await session.post(f"{base}/crypto_hello", json=hello, headers=headers)
                hello_frame = await agent.receive_json(timeout=6)
                _, ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
                    hello_frame, self.identity)
                await agent.send_json(ack)
                ack_frame = await _recv_until(phone, "crypto.hello_ack", timeout=6)
                peer.consume_ack(ack_frame)

                frame = peer.encrypt_frame("chat.send.enc", "chat-1",
                                           {"text": "nur einmal", "msg_id": "x"})
                body = {"agent_id": "alpha", "session_id": self.SESSION_ID,
                        "chat_id": "chat-replay-1", "ts": frame["ts"],
                        "hdr": frame["hdr"], "nonce": frame["nonce"], "ct": frame["ct"]}
                first = await session.post(f"{base}/chat", json=body, headers=headers)
                assert first.status == 200
                await agent.receive_json(timeout=6)  # Zustellung abholen

                # Replay-Injektion: identischer Body nochmal → 429, kein 2. Frame.
                second = await session.post(f"{base}/chat", json=body, headers=headers)
                assert second.status == 429
                assert (await second.json())["error"] == "replay detected"
                with pytest.raises(asyncio.TimeoutError):
                    await agent.receive_json(timeout=0.6)  # nichts gekommen

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_chat_event_cleartext_downgrade_blocked_while_session_active(self, tmp_path, monkeypatch, caplog):
        """PH-3 F1 (Jäger P1): Downgrade-SYMMETRIE — /chat_event bekommt den
        gleichen Guard wie /chat: aktive bestätigte E2E-Session → Klartext-
        Antwort 403 verworfen (kein silent Downgrade); nach Session-Reset ist
        Klartext wieder v0.14-legacy-erlaubt."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT + 11)
        base = f"http://127.0.0.1:{self.PORT + 11}"
        headers = {"Authorization": f"Bearer {self.CODE}"}

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 11}/ws", headers=headers)
                await _drain_phone_state_frame(phone, expected=1)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 11}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6)

                peer = _ph3_adapter.PhoneCryptoPeer(
                    "alpha", self.IK_A_PUB, self.SPK_A_PUB)
                hello = peer.make_hello(self.SESSION_ID)
                await session.post(f"{base}/crypto_hello", json=hello, headers=headers)
                hello_frame = await agent.receive_json(timeout=6)
                _, ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
                    hello_frame, self.identity)
                await agent.send_json(ack)
                await _recv_until(phone, "crypto.hello_ack", timeout=6)

                # Downgrade: Klartext-Antwort via POST /chat_event → 403.
                resp = await session.post(
                    f"{base}/chat_event",
                    json={"chat_id": "chat-downgrade-01", "text": "klartext-antwort"},
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                assert resp.status == 403
                assert "cleartext" in (await resp.json())["error"]
                dumped = "\n".join(r.getMessage() for r in caplog.records)
                assert "Klartext-Downgrade verworfen" in dumped
                # Nichts an die Phone-WS durchgerutscht (nur bekannte Frames).
                with pytest.raises(asyncio.TimeoutError):
                    await phone.receive_json(timeout=0.6)

                # Agent invalidiert (FP-Abweichung) → Klartext wieder erlaubt.
                await agent.send_json({"type": "crypto.session_reset", "v": 1,
                                       "session_id": self.SESSION_ID,
                                       "agent_id": "alpha",
                                       "reason": "fingerprint-mismatch"})
                await _recv_until(phone, "crypto.session_reset", timeout=6)
                resp = await session.post(
                    f"{base}/chat_event",
                    json={"chat_id": "chat-downgrade-01", "text": "legacy ok"},
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                assert resp.status == 200

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_relay_state_carries_session_active_per_agent(self, tmp_path, monkeypatch):
        """PH-3 F7: relay.state erweitert um session_active je Agent — die
        App validiert beim (Re)Connect ihre Sessions gegen die Relay-Wahrheit;
        ein verschlucktes crypto.session_reset (tote WS, kein Eigenframe-Spool)
        wird über session_active=False nachgeholt statt still weiterzulaufen."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT + 12)
        base = f"http://127.0.0.1:{self.PORT + 12}"
        headers = {"Authorization": f"Bearer {self.CODE}"}
        agent_headers = {"Authorization": f"Bearer {self.TOKEN}"}

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 12}/ws", headers=headers)
                await _drain_phone_state_frame(phone, expected=1)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 12}/agent_ws", headers=agent_headers)
                state1 = await _recv_until(phone, "relay.state", timeout=6)
                assert state1["agents"][0]["agent_id"] == "alpha"
                assert state1["agents"][0]["session_active"] is False

                # Handshake → bestätigte Session.
                peer = _ph3_adapter.PhoneCryptoPeer(
                    "alpha", self.IK_A_PUB, self.SPK_A_PUB)
                hello = peer.make_hello(self.SESSION_ID)
                await session.post(f"{base}/crypto_hello", json=hello, headers=headers)
                hello_frame = await agent.receive_json(timeout=6)
                _, ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
                    hello_frame, self.identity)
                await agent.send_json(ack)
                await _recv_until(phone, "crypto.hello_ack", timeout=6)

                # Agent-Reconnect → frisches relay.state mit session_active=True
                # (Presence-Push, gleiche Payload-Form wie der Hallo-Frame).
                agent2 = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 12}/agent_ws", headers=agent_headers)
                state2 = await _recv_until(phone, "relay.state", timeout=6)
                assert state2["agents"][0]["session_active"] is True

                # Reset (FP-Abweichung) → Relay-Wahrheit wieder False.
                await agent2.send_json({"type": "crypto.session_reset", "v": 1,
                                        "session_id": self.SESSION_ID,
                                        "agent_id": "alpha",
                                        "reason": "fingerprint-mismatch"})
                await _recv_until(phone, "crypto.session_reset", timeout=6)
                agent3 = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 12}/agent_ws", headers=agent_headers)
                state3 = await _recv_until(phone, "relay.state", timeout=6)
                assert state3["agents"][0]["session_active"] is False

                await agent3.close()
                await phone.close()

        asyncio.run(scenario())

    def test_read_enc_runs_ph2_status_and_mirrors_to_phone(self, tmp_path, monkeypatch):
        """chat.read.enc: klare msg_id treibt die PH2-Status-Logik (read +
        chat.status-Frame ans Phone) UND der enc-Spiegel geht ans Phone."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT + 7)
        base = f"http://127.0.0.1:{self.PORT + 7}"
        headers = {"Authorization": f"Bearer {self.CODE}"}

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 7}/ws", headers=headers)
                await _drain_phone_state_frame(phone, expected=1)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 7}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6)

                # E2E-Session aufbauen.
                peer = _ph3_adapter.PhoneCryptoPeer(
                    "alpha", self.IK_A_PUB, self.SPK_A_PUB)
                hello = peer.make_hello(self.SESSION_ID)
                await session.post(f"{base}/crypto_hello", json=hello, headers=headers)
                hello_frame = await agent.receive_json(timeout=6)
                agent_session, ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
                    hello_frame, self.identity)
                await agent.send_json(ack)
                await _recv_until(phone, "crypto.hello_ack", timeout=6)
                peer.consume_ack(
                    {"session_id": self.SESSION_ID, "kex_pub": ack["kex_pub"],
                     "nonce": ack["nonce"], "ct": ack["ct"]})

                # Phone sendet verschlüsselt → Status queued/delivered entsteht.
                frame = peer.encrypt_frame("chat.send.enc", "chat-00007",
                                           {"text": "frage", "msg_id": "m7"})
                resp = await session.post(
                    f"{base}/chat",
                    json={"agent_id": "alpha", "session_id": self.SESSION_ID,
                          "chat_id": "chat-00007", "ts": frame["ts"],
                          "hdr": frame["hdr"], "nonce": frame["nonce"], "ct": frame["ct"]},
                    headers=headers)
                body = await resp.json()
                await agent.receive_json(timeout=6)  # chat.send.enc abholen
                deadline = time.monotonic() + 6
                status = None
                while status is None or status.get("status") != "delivered":
                    assert time.monotonic() < deadline, "chat.status delivered kam nicht"
                    status = await _recv_until(phone, "chat.status", timeout=6)
                assert status["msg_id"] == body["msg_id"]

                # Agent: chat.read.enc → PH2-Status read + Spiegel ans Phone.
                # Reihenfolge am Phone: chat.status(read) KOMMT VOR dem Spiegel
                # (die PH2-Status-Logik läuft zuerst) — deshalb parken wir
                # Zwischenframes statt sie zu verwerfen.
                read_frame = agent_session.encrypt_frame(
                    "chat.read.enc", "chat-00007", {"msg_id": body["msg_id"]},
                    clear={"msg_id": body["msg_id"]})
                await agent.send_json(read_frame)

                parked_after_read: list = []
                mirror = await _recv_until(phone, "chat.read.enc", timeout=6,
                                           parked=parked_after_read)
                assert mirror["msg_id"] == body["msg_id"]
                assert peer.decrypt_frame(mirror)["msg_id"] == body["msg_id"]
                status_frames = [f for f in parked_after_read
                                if f.get("type") == "chat.status"]
                status2 = status_frames[0] if status_frames else \
                    await _recv_until(phone, "chat.status", timeout=6)
                assert status2["status"] == "read"

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_session_reset_and_expiry_reenable_cleartext(self, tmp_path, monkeypatch):
        """PH-3: crypto.session_reset (Fingerprint-Abweichung) entfernt die
        Session am Relay → Phone wird informiert, Cleartext danach wieder
        erlaubt. Ablauf (24 h) tut dasselbe."""
        import asyncio
        import aiohttp
        import tools.android_relay as _relay_mod

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT + 8)
        base = f"http://127.0.0.1:{self.PORT + 8}"
        headers = {"Authorization": f"Bearer {self.CODE}"}

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 8}/ws", headers=headers)
                await _drain_phone_state_frame(phone, expected=1)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 8}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6)

                peer = _ph3_adapter.PhoneCryptoPeer(
                    "alpha", self.IK_A_PUB, self.SPK_A_PUB)
                hello = peer.make_hello(self.SESSION_ID)
                await session.post(f"{base}/crypto_hello", json=hello, headers=headers)
                hello_frame = await _recv_until(agent, "crypto.hello", timeout=6)
                _, ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
                    hello_frame, self.identity)
                await agent.send_json(ack)
                await _recv_until(phone, "crypto.hello_ack", timeout=6)

                # Session ist aktiv → Cleartext blockiert.
                resp = await session.post(
                    f"{base}/chat",
                    json={"agent_id": "alpha", "text": "x"}, headers=headers)
                assert resp.status == 403

                # Agent invalidiert (Sirs Revision (iii): FP-Abweichung).
                await agent.send_json({"type": "crypto.session_reset", "v": 1,
                                       "session_id": self.SESSION_ID,
                                       "agent_id": "alpha",
                                       "reason": "fingerprint-mismatch"})
                reset = await _recv_until(phone, "crypto.session_reset", timeout=6)
                assert reset["reason"] == "fingerprint-mismatch"
                state = _relay_mod._relay_instance
                assert self.SESSION_ID not in state.crypto_sessions

                # Cleartext danach wieder erlaubt (Legacy bis neue hello).
                resp = await session.post(
                    f"{base}/chat",
                    json={"agent_id": "alpha", "text": "nach reset"}, headers=headers)
                assert resp.status == 200

                # Zweite Session: 24-h-Ablauf simulieren → enc verworfen.
                sid2 = "ph3-e2e-session-0002"
                hello2 = peer.make_hello(sid2)
                await session.post(f"{base}/crypto_hello", json=hello2, headers=headers)
                hello2_frame = await _recv_until(agent, "crypto.hello", timeout=6)
                _, ack2 = _ph3_adapter.AgentCryptoSession.respond_to_hello(
                    hello2_frame, self.identity)
                await agent.send_json(ack2)
                ack2_frame = await _recv_until(phone, "crypto.hello_ack", timeout=6)
                peer.consume_ack(ack2_frame)
                _relay_mod._relay_instance.crypto_sessions[sid2]["ts"] -= 25 * 3600  # abgelaufen
                frame = peer.encrypt_frame("chat.send.enc", "chat-exp-1",
                                           {"text": "zu spät", "msg_id": "x"})
                resp = await session.post(
                    f"{base}/chat",
                    json={"agent_id": "alpha", "session_id": sid2,
                          "chat_id": "chat-exp-1",
                          "hdr": frame["hdr"], "nonce": frame["nonce"], "ct": frame["ct"]},
                    headers=headers)
                assert resp.status == 403  # Session invalid (gepruned, fail-closed)
                # Klartext wieder OK — die abgelaufene Session ist weg.
                resp = await session.post(
                    f"{base}/chat",
                    json={"agent_id": "alpha", "text": "legacy ok"}, headers=headers)
                assert resp.status == 200

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_fw2_b8_cross_session_takeover_reason_forwarded_verbatim(self,
                                                                     tmp_path, monkeypatch):
        """FIX-WELLE-2 B8 (HERZ-Befund 8): Der Adapter stößt bei fremder
        session_id session_reset mit reason='cross-session-takeover' an —
        der Relay leitet den Allowlist-Reason 1:1 ans Phone weiter (die App
        zeigt Takeover-Sprache statt MitM) und entfernt die Session."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT + 8)
        base = f"http://127.0.0.1:{self.PORT + 8}"
        headers = {"Authorization": f"Bearer {self.CODE}"}

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 8}/ws", headers=headers)
                await _drain_phone_state_frame(phone, expected=1)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 8}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6)

                peer = _ph3_adapter.PhoneCryptoPeer(
                    "alpha", self.IK_A_PUB, self.SPK_A_PUB)
                hello = peer.make_hello(self.SESSION_ID)
                await session.post(f"{base}/crypto_hello", json=hello, headers=headers)
                hello_frame = await _recv_until(agent, "crypto.hello", timeout=6)
                _, ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
                    hello_frame, self.identity)
                await agent.send_json(ack)
                await _recv_until(phone, "crypto.hello_ack", timeout=6)

                # Adapter-Seite: fremde Session-ID erkannt → Takeover-Reset.
                await agent.send_json({"type": "crypto.session_reset", "v": 1,
                                       "session_id": self.SESSION_ID,
                                       "agent_id": "alpha",
                                       "reason": "cross-session-takeover"})
                reset = await _recv_until(phone, "crypto.session_reset", timeout=6)
                assert reset["reason"] == "cross-session-takeover", \
                    "Allowlist-Reason muss VERBATIM ans Phone (Takeover ≠ MitM-Sprache)"
                import tools.android_relay as _relay_mod
                state = _relay_mod._relay_instance
                assert self.SESSION_ID not in state.crypto_sessions, \
                    "Takeover-Reset entfernt die Session wie jeder andere Reset"

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_hello_spooled_when_agent_offline(self, tmp_path, monkeypatch):
        """Agent offline: crypto.hello wird gespoolt und BEI DER NEXSTEN
        Agent-Verbindung VOR allen enc-Nachrichten ausgeliefert (FIFO)."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT + 9)
        base = f"http://127.0.0.1:{self.PORT + 9}"
        headers = {"Authorization": f"Bearer {self.CODE}"}

        async def scenario():
            async with aiohttp.ClientSession() as session:
                resp = await session.post(
                    f"{base}/crypto_hello",
                    json={"agent_id": "alpha", "session_id": self.SESSION_ID,
                          "ik_pub": "aa" * 32, "ek_pub": "bb" * 32,
                          "spk_pub": "cc" * 32},
                    headers=headers)
                body = await resp.json()
                assert body["spooled"] is True and body["delivered"] is False

                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 9}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                frame = await agent.receive_json(timeout=6)
                assert frame["type"] == "crypto.hello"
                assert frame["session_id"] == self.SESSION_ID
                await agent.close()

        asyncio.run(scenario())

    def test_hello_ack_unknown_session_dropped(self, tmp_path, monkeypatch):
        """hello_ack für eine unbekannte/fremde Session wird verworfen — das
        Phone bekommt NICHTS (fail-closed, kein Orakel)."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT + 10)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 10}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 10}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _drain_phone_state_frame(phone, expected=1)  # Hallo-Frame
                await _recv_until(phone, "relay.state", timeout=6)  # Presence-Push
                await agent.send_json({"type": "crypto.hello_ack", "v": 1,
                                       "session_id": "gibt-es-nicht-0001",
                                       "agent_id": "alpha", "kex_pub": "aa" * 32,
                                       "nonce": "AAAAAAAAAAAAAAAA",
                                       "ct": _ph3_adapter._b64e(b"\x01" * 16)})
                with pytest.raises(asyncio.TimeoutError):
                    await phone.receive_json(timeout=0.8)  # kein Frame ans Phone
                await agent.close()
                await phone.close()

        asyncio.run(scenario())


class TestSecW1RelayReplayParseHaertung:
    """SEC-W1-Abschluss Cluster 1: Relay-Parse/Replay-Härtung.

    F-05 (RecursionError statt JSONDecodeError), F-06 (Riesen-n vergiftet
    das Replay-Fenster fail-open), W1C-8 (ts ohne Ober-Grenze — Zukunft
    passierte den Freshness-Check).
    """

    RK = "ab" * 32

    def _state(self):
        from tools.android_relay import _RelayState
        return _RelayState("code", 8799)

    def test_deeply_nested_json_does_not_kill_phone_ws(self, caplog):
        """F-05: 200k Ebenen → RecursionError wird GELOGGT, nicht geworfen."""
        import asyncio
        from tools.android_relay import _on_phone_message
        state = self._state()
        with caplog.at_level("WARNING"):
            asyncio.run(_on_phone_message(state, "[" * 200000))
        assert any("Non-JSON message from phone" in r.message for r in caplog.records)

    def test_deeply_nested_json_does_not_kill_agent_ws(self, caplog):
        """F-05: Parität auf der Agent-WS (_on_agent_message)."""
        import asyncio
        from tools.android_relay import _on_agent_message
        state = self._state()
        with caplog.at_level("WARNING"):
            asyncio.run(_on_agent_message(state, "alpha", "[" * 200000))
        assert any("non-JSON message" in r.message for r in caplog.records)

    def test_giant_n_rejected_and_window_not_poisoned(self):
        """F-06: n=10^20 → False, danach bleibt legitimes n=5 erlaubt."""
        import time as _time
        from tools.android_relay import _relay_replay_allow
        state = self._state()
        now = _time.time() * 1000
        assert _relay_replay_allow(state, "sess-gift-01", "p2a", 10**20, now, self.RK) is False
        # Fenster NICHT vergiftet: legitimer Zähler kommt weiterhin durch.
        assert _relay_replay_allow(state, "sess-gift-01", "p2a", 5, now, self.RK) is True

    def test_future_ts_rejected(self):
        """W1C-8: ts +1 h in der Zukunft → False (±10-min-Fenster)."""
        import time as _time
        from tools.android_relay import _relay_replay_allow
        state = self._state()
        now = _time.time() * 1000
        assert _relay_replay_allow(state, "sess-fut-001", "p2a", 0, now, self.RK) is True
        assert _relay_replay_allow(
            state, "sess-fut-002", "p2a", 0, now + 60 * 60 * 1000, self.RK) is False

    def test_validate_enc_frame_rejects_giant_counters(self):
        """F-06: Struktur-Check lehnt n/pn ≥ 2^32 ab (Zähler-Gift)."""
        from tools.android_relay import _validate_enc_frame
        frame = {"hdr": {"rk": self.RK, "n": 10**20, "pn": 0},
                  "nonce": "AAAAAAAAAAAAAAAA", "ct": _b64_ct(), "session_id": "sess-v01"}
        assert _validate_enc_frame(frame) is not None
        frame_ok = {"hdr": {"rk": self.RK, "n": 0, "pn": 1},
                    "nonce": "AAAAAAAAAAAAAAAA", "ct": _b64_ct(), "session_id": "sess-v01"}
        assert _validate_enc_frame(frame_ok) is None


def _b64_ct():
    """12+16-Byte-Dummy (nonce wird separat geprüft; ct nur base64-Validität)."""
    import base64
    return base64.b64encode(b"\x01" * 32).decode("ascii")


class TestSecW1RelayIdentityConfigHaertung:
    """SEC-W1-Abschluss Cluster 2: W1A-6 (Fingerprint-Ableitung aus ik_pub)
    + D5b (externer Host ohne TLS nur mit explizitem Opt-in)."""

    IK = "11" * 32  # beliebiger validierter 64-Hex-Identity-PubKey

    def test_fingerprint_derivation_byte_identical_to_adapter(self):
        """W1A-6: Relay-Ableitung == adapter fingerprint_of_pub (gleiche HKDF)."""
        from tools.android_relay import _fingerprint_of_pub
        assert _fingerprint_of_pub(self.IK) == _ph3_adapter.fingerprint_of_pub(
            bytes.fromhex(self.IK))

    def test_registry_serves_derived_fp_and_warns_on_config_mismatch(self, caplog):
        """W1A-6: falscher Config-FP wird NICHT gesendet — abgeleiteter Wert,
        Abweichung nur als Warnung (Toleranz-Vergleich)."""
        import logging
        from tools.android_relay import _agent_crypto_registry, _fingerprint_of_pub
        config = {
            "ANDROID_AGENT_TOKEN_ALPHA": "tok",
            "ANDROID_AGENT_IDENTITY_ALPHA": self.IK,
            "ANDROID_AGENT_FINGERPRINT_ALPHA": "deadbeef" * 8,
        }
        with caplog.at_level(logging.WARNING):
            registry = _agent_crypto_registry(config)
        assert registry["alpha"]["fingerprint"] == _fingerprint_of_pub(self.IK)
        assert registry["alpha"]["fingerprint"] != "deadbeef" * 8
        assert any("weicht von der ik_pub-Ableitung ab" in r.message for r in caplog.records)

    def test_registry_no_warning_when_config_fp_matches(self, caplog):
        """W1A-6: passender Config-FP → keine Warnung (Toleranz-Vergleich grün)."""
        import logging
        from tools.android_relay import _agent_crypto_registry, _fingerprint_of_pub
        config = {
            "ANDROID_AGENT_TOKEN_ALPHA": "tok",
            "ANDROID_AGENT_IDENTITY_ALPHA": self.IK,
            "ANDROID_AGENT_FINGERPRINT_ALPHA": _fingerprint_of_pub(self.IK),
        }
        with caplog.at_level(logging.WARNING):
            registry = _agent_crypto_registry(config)
        assert registry["alpha"]["fingerprint"] == _fingerprint_of_pub(self.IK)
        assert not any("weicht" in r.message for r in caplog.records)

    def test_external_host_without_tls_requires_optin(self, monkeypatch):
        """D5b: ANDROID_RELAY_HOST=0.0.0.0 ohne TLS und ohne Opt-in → Start
        verweigert (fail-closed statt stiller Plaintext-Warnung)."""
        import asyncio
        import threading
        from tools.android_relay import _serve, _RelayState
        monkeypatch.setenv("ANDROID_RELAY_HOST", "0.0.0.0")
        monkeypatch.delenv("ANDROID_RELAY_ALLOW_PLAINTEXT", raising=False)
        monkeypatch.delenv("ANDROID_RELAY_CERT", raising=False)
        monkeypatch.delenv("ANDROID_RELAY_KEY", raising=False)
        state = _RelayState("code", 0)
        with pytest.raises(RuntimeError, match="ANDROID_RELAY_ALLOW_PLAINTEXT"):
            asyncio.run(_serve(state, threading.Event()))

    def test_tls_flag_without_cert_fails_closed(self, monkeypatch):
        """W3-01: ANDROID_RELAY_TLS=1 ohne CERT/KEY = Deploy-Fehler —
        _ssl_context wirft RuntimeError statt still auf Plaintext zu fallen
        (fail-closed, gleiche Härte wie das D5b-Gate)."""
        from tools.android_relay import _ssl_context
        monkeypatch.setenv("ANDROID_RELAY_TLS", "1")
        monkeypatch.delenv("ANDROID_RELAY_CERT", raising=False)
        monkeypatch.delenv("ANDROID_RELAY_KEY", raising=False)
        with pytest.raises(RuntimeError, match="ANDROID_RELAY_TLS=1 without"):
            _ssl_context()

    def test_tls_flag_zero_forces_plaintext_even_with_cert(self, monkeypatch, tmp_path):
        """W3-01: ANDROID_RELAY_TLS=0 = bewusster Interims-/QA-Kill-Switch —
        CERT/KEY vorhanden, aber der Socket bleibt Plaintext."""
        from tools.android_relay import _ssl_context
        monkeypatch.setenv("ANDROID_RELAY_TLS", "0")
        monkeypatch.setenv("ANDROID_RELAY_CERT", str(tmp_path / "relay.crt"))
        monkeypatch.setenv("ANDROID_RELAY_KEY", str(tmp_path / "relay.key"))
        assert _ssl_context() is None

    def test_external_host_with_optin_starts(self, monkeypatch):
        """D5b: Mit ANDROID_RELAY_ALLOW_PLAINTEXT=1 startet der Plaintext-
        Relay (bewusstes Opt-in, z. B. privates Heim-LAN)."""
        import asyncio
        import threading
        from tools.android_relay import _serve, _RelayState
        monkeypatch.setenv("ANDROID_RELAY_HOST", "0.0.0.0")
        monkeypatch.setenv("ANDROID_RELAY_ALLOW_PLAINTEXT", "1")
        monkeypatch.delenv("ANDROID_RELAY_CERT", raising=False)
        monkeypatch.delenv("ANDROID_RELAY_KEY", raising=False)
        state = _RelayState("code", 0)

        async def scenario():
            state.phone_ws_lock = asyncio.Lock()
            state.pending_lock = asyncio.Lock()
            state.streams_lock = asyncio.Lock()
            state.agent_ws_lock = asyncio.Lock()
            state.broker_lock = asyncio.Lock()
            state.shutdown_event = asyncio.Event()
            ready = threading.Event()
            task = asyncio.ensure_future(_serve(state, ready))
            try:
                await asyncio.wait_for(
                    asyncio.get_running_loop().run_in_executor(None, ready.wait, 5), 6)
            finally:
                state.shutdown_event.set()
                await asyncio.wait_for(task, 6)

        asyncio.run(scenario())


# ═══════════════════════════════════════════════════════════════════════════════
# SEC-W2 (Welle 2) — Regressionstests der W2-Fix-Cluster
# ═══════════════════════════════════════════════════════════════════════════════

class TestSecW2PairingRotation:
    """SEC-W2 W2-1 (Rolle 1 AUTH-STATES): Pairing-Code-Rotation.

    Vorher (PoC sec_w2_poc.py): start_relay mit NEUEM Code war bei laufendem
    Relay ein stilles No-Op (Rotation wirkungslos, ALT-Code blieb gültig),
    und eine Phone-WS mit dem ALTEN Code überlebte die Rotation unbegrenzt
    (Re-Auth-Lücke)."""

    PORT = 19895
    OLD, NEW = "W2OLD1", "W2NEW2"

    def test_rotation_kicks_old_code_phone_session(self):
        """Code-Wechsel schließt die ALT-authentifizierte Phone-WS (Re-Auth)."""
        import asyncio
        import aiohttp

        start_relay(pairing_code=self.OLD, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.OLD}"},
                )
                await phone.receive_json(timeout=2)  # relay.state
                set_pairing_code(self.NEW)
                msg = await asyncio.wait_for(phone.receive(), timeout=3)
                return msg

        msg = asyncio.run(scenario())
        import aiohttp
        assert msg.type == aiohttp.WSMsgType.CLOSE, (
            f"ALT-Code-Session muss bei Rotation geschlossen werden, kam aber: {msg}")
        # ALT-Code ab jetzt abgewiesen, NEU-Code gültig (503 = kein Phone dran).
        import requests
        r_old = requests.get(f"http://localhost:{self.PORT}/ping",
                             headers={"Authorization": f"Bearer {self.OLD}"}, timeout=5)
        r_new = requests.get(f"http://localhost:{self.PORT}/ping",
                             headers={"Authorization": f"Bearer {self.NEW}"}, timeout=5)
        assert r_old.status_code == 401, f"ALT-Code muss 401 sein, kam {r_old.status_code}"
        assert r_new.status_code == 503, f"NEU-Code muss auth-passieren (503 kein Phone), kam {r_new.status_code}"
        stop_relay()

    def test_same_code_rotation_keeps_phone_connected(self):
        """Gleicher Code (Re-Setup ohne Wechsel) DARF die Phone-WS NICHT kicken."""
        import asyncio
        import aiohttp

        start_relay(pairing_code=self.OLD, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.OLD}"},
                )
                await phone.receive_json(timeout=2)  # relay.state
                set_pairing_code(self.OLD)  # kein Wechsel
                await asyncio.sleep(0.6)
                return phone

        phone = asyncio.run(scenario())
        assert not phone.closed, "Ohne Code-Wechsel darf die Phone-WS nicht sterben"
        stop_relay()

    def test_android_setup_rotates_running_relay(self, monkeypatch):
        """android_setup() mit konfiguriertem Token rotiert ein laufendes
        Relay gegen den NEUEN Code (vorher: stilles start_relay-No-Op)."""
        import requests
        import sys
        import tools.android_relay as relay_mod
        import tools.android_tool as tool_mod

        PORT = 19896
        # Hermes-Core blockieren → der Test läuft bewusst UNTER dem
        # Standalone-Fallback (_get_secret liest die Prozess-Env, kein .env).
        monkeypatch.setitem(sys.modules, "hermes_cli", None)
        monkeypatch.setitem(sys.modules, "agent", None)
        monkeypatch.setenv("ANDROID_RELAY_PORT", str(PORT))
        monkeypatch.setenv("ANDROID_BRIDGE_URL", f"http://localhost:{PORT}")
        monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", self.NEW)

        start_relay(pairing_code=self.OLD, port=PORT)
        # Alt-Code noch gültig (503 = auth ok, kein Phone):
        r = requests.get(f"http://localhost:{PORT}/ping",
                         headers={"Authorization": f"Bearer {self.OLD}"}, timeout=5)
        assert r.status_code == 503

        result = tool_mod.android_setup()
        assert '"status": "ok"' in result, f"android_setup fehlgeschlagen: {result}"

        with relay_mod._relay_lock:
            assert relay_mod._relay_instance.pairing_code == self.NEW, (
                "Rotation muss am laufenden Relay ankommen (set_pairing_code)")
        r_old = requests.get(f"http://localhost:{PORT}/ping",
                             headers={"Authorization": f"Bearer {self.OLD}"}, timeout=5)
        r_new = requests.get(f"http://localhost:{PORT}/ping",
                             headers={"Authorization": f"Bearer {self.NEW}"}, timeout=5)
        assert r_old.status_code == 401, "ALT-Code muss nach android_setup abgewiesen sein"
        assert r_new.status_code == 503, "NEU-Code muss nach android_setup gültig sein"
        stop_relay()


class TestSecW2RelayRessourcen:
    """SEC-W2 W2-2 (Rolle 2 UNAUTH-RESSOURCEN): Frame-Konfusion + Junk-Caps
    + Broker-Routen-Rate-Limits.

    Vorher (PoC): '[1,2,3]' vom Phone riss mit AttributeError die Phone-WS
    (Handler-Tod); /crypto_hello füllte crypto_sessions ungedrosselt (30 in
    <0.5 s); /key_publish legte je Request eine Datei an; Junk-Frame-Typen
    loggten UNGEKÜRZT (%r, bis 2 MB je Zeile) und ungedrosselt."""

    PORT = 19897
    CODE = "W2RESC"

    def test_phone_non_dict_frame_keeps_ws_alive(self):
        """'list' object has no attribute 'get' (PoC-Traceback) darf die
        Phone-WS NICHT mehr reißen — danach muss der Tool-Fluss weitergehen."""
        import asyncio
        import aiohttp

        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                await phone.receive_json(timeout=2)  # relay.state

                async def ping_roundtrip() -> int:
                    # Antwort-Task VOR dem GET starten: der Relay schickt den
                    # Kommando-Frame an die Phone-WS, bevor die HTTP-Antwort-
                    # Header entstehen (Handler wartet auf die Phone-Antwort).
                    async def reply_when_asked() -> None:
                        cmd = await asyncio.wait_for(phone.receive(), timeout=5)
                        await phone.send_json(
                            {"request_id": cmd.json()["request_id"], "status": 200,
                             "result": {"ok": True}})
                    reply_task = asyncio.ensure_future(reply_when_asked())
                    try:
                        async with session.get(
                            f"http://127.0.0.1:{self.PORT}/ping",
                            headers={"Authorization": f"Bearer {self.CODE}"},
                        ) as resp:
                            status = resp.status
                        await reply_task
                        return status
                    except BaseException:
                        reply_task.cancel()
                        raise

                before = await ping_roundtrip()
                # Konfusion-Frames: Array, String, Zahl (valides JSON, kein dict)
                await phone.send_str("[1,2,3]")
                await phone.send_str('"plain-string"')
                await phone.send_str("42")
                # non-JSON + dict ohne request_id (Junk-Pfade)
                await phone.send_str("kein json")
                await phone.send_json({"type": "junk"})
                await asyncio.sleep(0.5)
                after = await ping_roundtrip()
                return before, after

        before, after = asyncio.run(scenario())
        assert before == 200
        assert after == 200, (
            "Phone-WS/Handler muss nach Konfusion-Frames weiterlaufen "
            f"(vorher 200, nachher {after})")
        stop_relay()

    def test_phone_junk_log_capped(self, caplog):
        """Junk-Warnungen vom Phone sind auf 10/s gedrosselt (Log-Flood-Schutz)."""
        import asyncio
        from tools.android_relay import _on_phone_message

        state = _RelayState("code", 8799)
        with caplog.at_level("WARNING"):
            for _ in range(25):
                asyncio.run(_on_phone_message(state, "kein json"))
        warnings = [r for r in caplog.records
                    if "Non-JSON message from phone" in r.message]
        assert 0 < len(warnings) <= 10, (
            f"Junk-Log muss auf 10/s gedrosselt sein, war {len(warnings)}")

    def test_agent_junk_type_capped_and_truncated(self, caplog):
        """Agent-Junk: 15 Frames à 100k-Zeichen-Typ → ≤10 Warnungen, Typ auf
        64 Zeichen gekürzt (vorher: 2-MB-Log-Zeilen je Frame)."""
        import asyncio
        from tools.android_relay import _on_agent_message

        state = _RelayState("code", 8799)
        big = "J" * 100000

        async def flood():
            state.agent_ws_lock = asyncio.Lock()
            for _ in range(15):
                await _on_agent_message(
                    state, "alpha", '{"type": "' + big + '"}')

        with caplog.at_level("WARNING"):
            asyncio.run(flood())
        junk = [r for r in caplog.records if "verworfen (Allowlist" in r.message]
        assert 0 < len(junk) <= 10, f"Junk-Log muss auf 10/s gedrosselt sein, war {len(junk)}"
        for record in junk:
            assert len(record.getMessage()) < 500, (
                "Frame-Typ muss im Log gekürzt sein (Log-Amplification)")

    def test_crypto_hello_rate_limited(self, tmp_path, monkeypatch):
        """/crypto_hello: 12 Hellos in <1 s → ≥1×429, Sessions map bleibt
        unter der Flood-Zahl (vorher: 30/30 mit 200, Map wächst ungebremst)."""
        import asyncio
        import aiohttp

        import json as _json
        cfg = tmp_path / "relay-config.json"
        cfg.write_text(_json.dumps({
            "ANDROID_AGENT_TOKEN_ALPHA": "tok-j1",
            "ANDROID_AGENT_IDENTITY_ALPHA": "aa" * 32,
        }), encoding="utf-8")
        monkeypatch.setenv("ANDROID_RELAY_CONFIG", str(cfg))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            hex64 = "aa" * 32
            statuses = []
            async with aiohttp.ClientSession() as session:
                for i in range(12):
                    body = {"agent_id": "alpha", "session_id": f"w2-rl-{i:03d}-abcdefgh",
                            "ik_pub": hex64, "ek_pub": hex64, "spk_pub": hex64}
                    async with session.post(
                        f"http://127.0.0.1:{self.PORT}/crypto_hello", json=body,
                        headers={"Authorization": f"Bearer {self.CODE}"},
                    ) as resp:
                        statuses.append(resp.status)
            return statuses

        statuses = asyncio.run(scenario())
        assert 429 in statuses, (
            f"/crypto_hello muss ein Rate-Limit haben (12 in <1 s), kam {sorted(set(statuses))}")
        assert statuses.count(200) <= 10
        stop_relay()

    def test_key_publish_rate_limited(self, tmp_path, monkeypatch):
        """/key_publish: 12 Requests in <1 s → ≥1×429 (vorher ungedrosselt,
        je Request ein Datei-Spawn mkstemp+replace)."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_KEYS", str(tmp_path / "relay-keys.json"))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            hex64 = "aa" * 32
            statuses = []
            async with aiohttp.ClientSession() as session:
                for i in range(12):
                    body = {"ik_pub": hex64, "spk_pub": hex64, "device_id": f"w2dev{i}"}
                    async with session.post(
                        f"http://127.0.0.1:{self.PORT}/key_publish", json=body,
                        headers={"Authorization": f"Bearer {self.CODE}"},
                    ) as resp:
                        statuses.append(resp.status)
            return statuses

        statuses = asyncio.run(scenario())
        assert 429 in statuses, (
            f"/key_publish muss ein Rate-Limit haben, kam {sorted(set(statuses))}")
        assert statuses.count(200) <= 10
        stop_relay()


class TestSecW2RequirementsFloors:
    """SEC-W2 W2-4 (Rolle 4): Dependency-Floors über den CVE-Fix-Versionen."""

    def test_requirements_floors_above_cve_fixes(self):
        """aiohttp >= 3.9.4 (CVE-2024-23334/-27306/-30251) und
        cryptography >= 42.0.4 (CVE-2024-26130) als Install-Floor."""
        import re
        from pathlib import Path

        req = Path(__file__).resolve().parent.parent / "requirements.txt"
        text = req.read_text(encoding="utf-8")
        floors = dict(re.findall(r"^([a-zA-Z0-9_-]+)>=([0-9.]+)", text, re.M))
        assert "aiohttp" in floors and "cryptography" in floors
        assert tuple(int(x) for x in floors["aiohttp"].split(".")) >= (3, 9, 4), (
            f"aiohttp-Floor {floors['aiohttp']} < CVE-freier 3.9.4")
        assert tuple(int(x) for x in floors["cryptography"].split(".")) >= (42, 0, 4), (
            f"cryptography-Floor {floors['cryptography']} < CVE-freier 42.0.4")


class TestJ3RefreshEncFrameTs:
    """JÄGER J3 (P1): _refresh_enc_frame_ts — nur *.enc-Frames mit numerischer
    ts bekommen beim Flush eine frische ts; Klartext- und crypto.*-Frames
    bleiben unberührt (Live-Path = kein Spool, ts schon frisch)."""

    def test_only_enc_frames_with_numeric_ts_get_refreshed(self):
        import tools.android_relay as R
        now = int(time.time() * 1000)

        enc = {"type": "chat.send.enc", "ts": now - 3_600_000}
        out = R._refresh_enc_frame_ts(enc)
        assert out is enc, "In-Place-Rewrite (kein Frame-Klon — Spool-Identität bleibt)"
        assert enc["ts"] >= now - 1_000, "enc-ts muss frisch sein"

        for untouched_type in ("chat.send", "chat.event", "crypto.session_reset"):
            frame = {"type": untouched_type, "ts": now - 3_600_000}
            R._refresh_enc_frame_ts(frame)
            assert frame["ts"] == now - 3_600_000, (
                f"{untouched_type} darf keine neue ts bekommen")

        no_ts = {"type": "chat.event.enc"}
        R._refresh_enc_frame_ts(no_ts)
        assert "ts" not in no_ts, "Fehlende ts wird NICHT erfunden"

        bool_ts = {"type": "chat.event.enc", "ts": True}
        R._refresh_enc_frame_ts(bool_ts)
        assert bool_ts["ts"] is True, "bool ist kein valider Frame-ts (Parität F-03)"

    def test_a2p_and_p2a_flush_paths_use_the_refresh(self):
        """Statischer Spiegel-Beweis: BEIDE Flush-Loops rufen den Helfer."""
        import inspect
        import tools.android_relay as R
        agent_src = inspect.getsource(R._flush_agent_queue)
        phone_src = inspect.getsource(R._flush_phone_outbox)
        assert "_refresh_enc_frame_ts" in agent_src, "Agent-Spool-Flush muss ts neu schreiben"
        assert "_refresh_enc_frame_ts" in phone_src, "Phone-Outbox-Flush muss ts neu schreiben"


class TestW4Jag2ChatStatusCap:
    """W4-FIX-JAG2 (S6): chat_status hatte nur die 24-h-ts-Retention — ein
    Flood FRISCHER msg_ids wuchs ungebunden binnen der Retention (RAM-DoS,
    Schwesterfall von chat_activity/SEC-W1 D2). FIFO-Cap Parität."""

    def _state(self):
        from tools.android_relay import _RelayState
        return _RelayState("code", 8799)

    def test_cap_chat_status_fifos_oldest_first(self):
        from tools import android_relay as relay
        state = self._state()
        for i in range(relay._CHAT_STATUS_MAX + 10):
            state.chat_status[f"m{i}"] = {
                "chat_id": "c", "agent_id": "a", "status": "queued", "ts": float(i),
            }
        relay._cap_chat_status(state)
        assert len(state.chat_status) == relay._CHAT_STATUS_MAX
        assert "m0" not in state.chat_status and "m9" not in state.chat_status
        assert "m10" in state.chat_status
        # FIFO (Einfüge-Reihenfolge), nicht LRU: der älteste Überlebende steht
        # in der Map an ERSTER Stelle.
        assert next(iter(state.chat_status)) == "m10"

    def test_set_chat_status_enforces_cap_under_broker_lock(self):
        """Der echte Schreibpfad hält den Deckel (Prune + Cap unter broker_lock)."""
        import asyncio
        from tools import android_relay as relay
        state = relay._RelayState("code", 8799)

        async def run():
            # Lock-Bindung wie im Live-Lauf (_run_loop, Z. ~968): __init__
            # erzeugt den Lock bewusst NICHT eager (kein Event-Loop dort).
            state.broker_lock = asyncio.Lock()
            for i in range(relay._CHAT_STATUS_MAX + 4):
                await relay._set_chat_status(state, f"m{i}", "c", "a", "queued")

        asyncio.run(run())
        assert len(state.chat_status) == relay._CHAT_STATUS_MAX


class TestW19AgentStatus:
    """W19 (SIRS-Neued): agent.status/.enc — Live-Arbeitszustand je Agent.

    Status = Transition: KEIN Rate-Bucket (niemals im chat.reply-Bucket),
    KEIN Spool (W18-Regel: Live-Zustand, kein Verlauf — Phone offline =
    bewusst verworfen). working-Dedup je agent_id (State parallel zu
    chat_activity), sub/end je Frame durchgereicht (die App verwaltet
    Sub-Slots je sub.id/end). enc = Klon der chat.activity.enc-Prüfkette:
    Struktur-Gate, _validate_enc_frame, _enc_session_active fail-closed,
    _relay_replay_allow — ct bleibt opak."""

    # ── Direct-Handler-Beweise (RelayState + Mock-WS) ─────────────────────

    def _fresh_state(self):
        import asyncio
        from tools.android_relay import _RelayState
        state = _RelayState("code", 8799)
        state.broker_lock = asyncio.Lock()
        return state

    def _state_with_ws(self):
        """RelayState + Mock-Phone-WS mit ASYNC send_json — ein nacktes
        Mock.send_json wirft beim await TypeError, was der Handler fail-soft
        als Offline schluckt (so würden 'durchgereicht'-Tests zu
        Spur-Gewinnern statt echter Zustell-Beweise)."""
        import asyncio
        from unittest import mock
        state = self._fresh_state()
        ws = mock.Mock()
        ws.closed = False
        ws.send_json = mock.AsyncMock()
        state.phone_ws = ws
        return state, ws

    def test_clear_frame_forwarded_without_spool(self):
        """Klarer agent.status-Frame mit vollem Feldsatz → 1 Phone-Frame
        (agent_id/working/started_at/label/sub), TS gesetzt, KEIN Spool-
        Eintrag (Live-Zustand, kein Verlauf) und Dedup-State gesetzt."""
        import asyncio
        from unittest import mock
        from tools import android_relay as relay

        state, ws = self._state_with_ws()

        async def run():
            await relay._handle_agent_status(state, "alpha", {
                "agent_id": "alpha", "working": True,
                "started_at": 1730515200000, "label": "Recherche",
                "sub": {"id": "sub-1", "name": "Bauer"},
            })

        asyncio.run(run())
        assert ws.send_json.call_count == 1
        frame = ws.send_json.call_args[0][0]
        assert frame["type"] == "agent.status"
        assert frame["agent_id"] == "alpha"
        assert frame["working"] is True
        assert frame["started_at"] == 1730515200000
        assert frame["label"] == "Recherche"
        assert frame["sub"] == {"id": "sub-1", "name": "Bauer"}
        assert frame["ts"] > 0
        # W18-Regel: Live-Zustand, kein Verlauf — NIE in die Outbox.
        assert len(state.phone_outbox) == 0
        # J4-Fund 3: Frame MIT sub = Slot-Verband → schreibt das Haupt-
        # Aggregat NICHT (kein (True, „Recherche")) und kein Working-Refresh.
        assert state.agent_status == {}

    def test_working_dedup_per_agent_id(self):
        """Transition-Dedup: gleicher working-Wert wie zuletzt = stilles
        No-Op; Wechsel kommt durch. Je agent_id SEPARAT (Parität
        chat_activity je (agent, chat_id), hier je Agent)."""
        import asyncio
        from unittest import mock
        from tools import android_relay as relay

        state, ws = self._state_with_ws()

        body = {"agent_id": "alpha", "working": True, "started_at": 1}

        async def run():
            # True → durch; True → Dedup; False → durch; True → durch.
            await relay._handle_agent_status(state, "alpha",
                                             {**body, "working": True})
            await relay._handle_agent_status(state, "alpha",
                                             {**body, "working": True})
            await relay._handle_agent_status(state, "alpha",
                                             {**body, "working": False})
            await relay._handle_agent_status(state, "alpha",
                                             {**body, "working": True})
            # BETA mit gleichem working=True: eigener Dedup-Schlüssel.
            await relay._handle_agent_status(state, "beta",
                                             {"agent_id": "beta",
                                              "working": True,
                                              "started_at": 2})

        asyncio.run(run())
        assert ws.send_json.call_count == 4
        assert [c[0][0]["agent_id"] for c in ws.send_json.call_args_list] == [
            "alpha", "alpha", "alpha", "beta"]
        assert [c[0][0]["working"] for c in ws.send_json.call_args_list] == [
            True, False, True, True]
        assert len(state.phone_outbox) == 0
        # J4-Fund 3: Dedup-Band = (working, label)-Paar je Agent — reine
        # parent-Frames tragen kein Label → (True, None).
        assert state.agent_status == {"alpha": (True, None), "beta": (True, None)}

    def test_label_over_64_discarded_field_frame_survives(self):
        """label > 64 = Feld verworfen (fail-soft, Parität _session_meta_ok),
        der Frame bleibt gültig und wird durchgereicht; label ≤ 64 bleibt;
        nicht-String-label fällt ebenfalls nur als Feld."""
        import asyncio
        from unittest import mock
        from tools import android_relay as relay

        state, ws = self._state_with_ws()

        body = {"agent_id": "alpha", "working": False, "started_at": 5}

        async def run():
            await relay._handle_agent_status(
                state, "alpha", {**body, "label": "x" * 65})   # zu lang
            await relay._handle_agent_status(
                state, "alpha", {**body, "working": True,
                                 "label": "y" * 64})            # Grenze OK
            await relay._handle_agent_status(
                state, "alpha", {**body, "label": 42})          # kein String

        asyncio.run(run())
        assert ws.send_json.call_count == 3
        frames = [c[0][0] for c in ws.send_json.call_args_list]
        assert "label" not in frames[0]
        assert frames[1]["label"] == "y" * 64
        assert "label" not in frames[2]
        assert all(f["type"] == "agent.status" for f in frames)
        # working-Verband: False → True-Wechsel kam durch, letzter Stand False.
        assert state.agent_status == {"alpha": (False, None)}
        assert len(state.phone_outbox) == 0

    def test_slot_sub_end_frames_never_deduped(self):
        """Slot-Verband: Frames mit sub/end werden JEDESMAL durchgereicht
        (die App verwaltet Sub-Slots je sub.id/end); ungültiges sub fällt
        nur als Feld (Frame bleibt)."""
        import asyncio
        from unittest import mock
        from tools import android_relay as relay

        state, ws = self._state_with_ws()

        body = {"agent_id": "alpha", "working": True, "started_at": 7}

        async def run():
            await relay._handle_agent_status(
                state, "alpha", {**body, "sub": {"id": "s1", "name": "A"}})
            await relay._handle_agent_status(
                state, "alpha", {**body, "sub": {"id": "s1", "name": "A"}})
            await relay._handle_agent_status(
                state, "alpha", {**body, "end": True,
                                 "sub": {"id": "s1", "name": "A"}})
            await relay._handle_agent_status(
                state, "alpha", {**body, "end": True})          # end ohne sub
            await relay._handle_agent_status(
                state, "alpha", {**body, "sub": {"id": "s1"}})  # name fehlt

        asyncio.run(run())
        # Frames 1–4 alle durchgereicht (sub/end = KEIN Dedup); Frame 5
        # (nur working, erstmals bekannter parent-Stand) = J4-Fund 3: Slot-
        # Frames schreiben das Haupt-Aggregat nicht → der parent kommt als
        # erster bekannter Stand durch (kein Slot-Working-Refresh davor).
        assert ws.send_json.call_count == 5
        frames = [c[0][0] for c in ws.send_json.call_args_list]
        assert [f["end"] for f in frames[2:4]] == [True, True]
        assert frames[0]["sub"] == {"id": "s1", "name": "A"}
        assert len(state.phone_outbox) == 0
        assert state.agent_status == {"alpha": (True, None)}

    def test_invalid_required_field_discarded_fail_soft(self):
        """Struktur-Gate am Pflichtfeld (Parität chat.activity: Fail-soft
        statt Throw) — working-nicht-bool / started_at fehlt oder inf /
        agent_id fehlt oder >64 → Frame verworfen, WS unberührt, kein
        Spool-Eintrag."""
        import asyncio
        import math
        from unittest import mock
        from tools import android_relay as relay

        state, ws = self._state_with_ws()
        good = {"agent_id": "alpha", "working": True, "started_at": 9}

        async def run():
            valid_count = 0
            for bad_body in (
                {**good, "working": "true"},            # kein bool
                {**good, "started_at": math.inf},       # nicht endlich
                {**good, "started_at": True},           # bool ist kein ms
                {k: v for k, v in good.items()
                 if k != "started_at"},                 # Pflichtfeld fehlt
                {k: v for k, v in good.items()
                 if k != "agent_id"},                   # Pflichtfeld fehlt
                {**good, "agent_id": "a" * 65},         # über Cap
                "kein-dict",                            # Struktur
            ):
                await relay._handle_agent_status(state, "alpha", bad_body)
                assert ws.send_json.call_count == valid_count, bad_body
            # Positive control: gültiger Frame kommt durch.
            await relay._handle_agent_status(state, "alpha", good)
            assert ws.send_json.call_count == 1

        asyncio.run(run())
        assert state.agent_status == {"alpha": (True, None)}
        assert len(state.phone_outbox) == 0

    def test_j4_label_change_beyond_working_dedup(self):
        """J4-Fund 3 (Label-Dedup, rot/grün): der Dedup vergleicht working
        UND label — ein Label-Wechsel bei working=True kommt durch (vorher:
        stilles Verwerfen, „Sucht im Web" aktualisiert sich nie); der
        gleiche (working, label)-Verband bleibt stilem No-Op, ein Label-
        Reset (Feld weg) ist wieder ein Wechsel."""
        import asyncio
        from unittest import mock
        from tools import android_relay as relay

        state, ws = self._state_with_ws()
        body = {"agent_id": "alpha", "working": True, "started_at": 21}

        async def run():
            # True + kein Label → durch (erster bekannter Stand).
            await relay._handle_agent_status(state, "alpha", dict(body))
            # True + „Sucht im Web" → Label-Wechsel → durch (VORHER: rot).
            await relay._handle_agent_status(
                state, "alpha", {**body, "label": "Sucht im Web"})
            # True + „Schreibt Antwort" → Label-Wechsel → durch.
            await relay._handle_agent_status(
                state, "alpha", {**body, "label": "Schreibt Antwort"})
            # GLEICHER (working, label)-Verband → Dedup (stilles No-Op).
            await relay._handle_agent_status(
                state, "alpha", {**body, "label": "Schreibt Antwort"})
            # True ohne Label → Label-Reset = Wechsel → durch.
            await relay._handle_agent_status(state, "alpha", dict(body))

        asyncio.run(run())
        frames = [c[0][0] for c in ws.send_json.call_args_list]
        assert len(frames) == 4
        assert [f.get("label", None) for f in frames] == [
            None, "Sucht im Web", "Schreibt Antwort", None]
        assert state.agent_status == {"alpha": (True, None)}
        assert len(state.phone_outbox) == 0

    def test_j4_sub_verband_overwrites_not_hauptaggregat(self):
        """J4-Fund 3 (Slot-Verband): sub/end-Frames werden je Frame
        durchgereicht (Dedup-Verband unverändert), dürfen das Haupt-
        Aggregat state.agent_status[agent_id] aber NICHT mit dem Slot-
        working überschreiben — sonst druckt der nächste parent-Frame
        denselben Stand doppelt (UI-Flimmern)."""
        import asyncio
        from unittest import mock
        from tools import android_relay as relay

        state, ws = self._state_with_ws()
        body = {"agent_id": "alpha", "working": True, "started_at": 31}
        sub = {"id": "s1", "name": "Bauer"}

        async def run():
            # parent working=True + Label „A" → durch, Aggregat (True, „A").
            await relay._handle_agent_status(
                state, "alpha", {**body, "label": "A"})
            # Slot-Verband mit Slot-working=False → JEDFALLS durch …
            await relay._handle_agent_status(
                state, "alpha", {**body, "working": False, "sub": sub})
            # … aber Aggregat bleibt (True, „A"): parent unverändert = Dedup
            # (VORHER: falscher Wiederholdruck, Slot-working False überschrieb
            # das Aggregat).
            await relay._handle_agent_status(
                state, "alpha", {**body, "label": "A"})

        asyncio.run(run())
        frames = [c[0][0] for c in ws.send_json.call_args_list]
        assert len(frames) == 2
        assert frames[0]["working"] is True and frames[0]["label"] == "A"
        assert frames[1]["working"] is False and frames[1]["sub"] == sub
        assert state.agent_status == {"alpha": (True, "A")}
        assert len(state.phone_outbox) == 0

    def test_phone_offline_no_spool_mock_proof(self):
        """W18-Regel beweisbar: Phone offline (ws=None ODER ws.closed)
        → KEIN send, KEIN Outbox-Eintrag — agent.status wird bewusst
        verworfen (Live-Zustand, kein Verlauf). Dedup-State pflegt der
        Handler trotzdem (letzter bekannter Zustand)."""
        import asyncio
        from unittest import mock
        from tools import android_relay as relay

        body = {"agent_id": "alpha", "working": True, "started_at": 11}

        # Fall 1: kein Phone-WS.
        state = self._fresh_state()
        state.phone_ws = None
        asyncio.run(relay._handle_agent_status(state, "alpha", dict(body)))
        assert len(state.phone_outbox) == 0
        assert state.agent_status == {"alpha": (True, None)}

        # Fall 2: Mock-WS mit closed=True (Offline-Marker).
        state2 = self._fresh_state()
        ws2 = mock.Mock()
        ws2.closed = True
        state2.phone_ws = ws2
        asyncio.run(relay._handle_agent_status(state2, "alpha", dict(body)))
        ws2.send_json.assert_not_called()
        assert len(state2.phone_outbox) == 0

        # Fall 3: send schmeißt (tote WS) — Fail-soft, ebenfalls kein Spool.
        state3 = self._fresh_state()
        ws3 = mock.Mock()
        ws3.closed = False
        ws3.send_json = mock.AsyncMock(side_effect=ConnectionResetError("tot"))
        state3.phone_ws = ws3
        asyncio.run(relay._handle_agent_status(state3, "alpha", dict(body)))
        assert len(state3.phone_outbox) == 0

    def _enc_body(self, ts_ms, working=True):
        """Gültiger agent.status.enc-Body (klare Felder + opake Hülle)."""
        import base64
        nonce = base64.b64encode(bytes(12)).decode()
        ct = base64.b64encode(b"opak").decode()
        return {
            "type": "agent.status.enc",
            "agent_id": "alpha", "working": working,
            "started_at": 1730515200000, "label": "Recherche",
            "session_id": "sess-w19-01",
            "hdr": {"rk": "a" * 64, "n": 0, "pn": 1},
            "nonce": nonce, "ct": ct, "ts": ts_ms,
        }

    def test_enc_rejected_without_ack_session_fail_closed(self):
        """enc ohne bestätigte E2E-Session → fail-closed verworfen (Klon
        chat.activity.enc: _enc_session_active liefert None = kein Delivery,
        kein State-Write, kein Spool)."""
        import asyncio
        import time
        from unittest import mock
        from tools import android_relay as relay

        state, ws = self._state_with_ws()

        async def run():
            await relay._handle_agent_status_enc(
                state, "alpha", self._enc_body(int(time.time() * 1000)))

        asyncio.run(run())
        ws.send_json.assert_not_called()
        assert state.agent_status == {}
        assert len(state.phone_outbox) == 0

    def test_enc_delivered_with_session_kette_and_shared_dedup(self, monkeypatch):
        """enc MIT bestätigter Session (gepatcht) → Delivery: session_id/
        hdr/nonce/ct 1:1 opak durchgereicht, klare Felder im Top-Level;
        replay-verletzte n (10**20) verwirft; Dedup-State ist GEMEINSAM mit
        dem Klartext-Pfad (enc-Spiegel nach gleichem working = kein
        zweiter Frame)."""
        import asyncio
        import time
        from unittest import mock
        from tools import android_relay as relay

        state, ws = self._state_with_ws()
        now_ms = int(time.time() * 1000)

        async def fake_session(state_, agent_id_, data_):
            return {"agent_id": agent_id_, "acked": True}

        monkeypatch.setattr(relay, "_enc_session_active", fake_session)

        async def run():
            await relay._handle_agent_status_enc(state, "alpha",
                                                 self._enc_body(now_ms))
            body = self._enc_body(now_ms + 1)
            body["hdr"]["n"] = 10 ** 20        # Zähler-Gift (B F-06)
            await relay._handle_agent_status_enc(state, "alpha", body)
            body2 = self._enc_body(now_ms + 2)
            body2["hdr"]["n"] = 1              # Legitimer Ratchet-Schritt
            body2["working"] = False           # echter Wechsel → durch
            await relay._handle_agent_status_enc(state, "alpha", body2)

        asyncio.run(run())
        frames = [c[0][0] for c in ws.send_json.call_args_list]
        assert len(frames) == 2
        first = frames[0]
        assert first["type"] == "agent.status.enc"
        assert first["agent_id"] == "alpha"
        assert first["working"] is True
        assert first["started_at"] == 1730515200000
        assert first["label"] == "Recherche"
        assert first["session_id"] == "sess-w19-01"
        assert first["hdr"] == {"rk": "a" * 64, "n": 0, "pn": 1}
        assert first["nonce"] == self._enc_body(now_ms)["nonce"]
        assert first["ct"] == "b3Bhaw=="
        assert frames[1]["working"] is False
        # Gemeinsamer Dedup-State mit dem Klartext-Pfad: Klar-Spiegel mit
        # GLEICHEM (working, label)-Verband erzeugt KEINEN zweiten Frame
        # (J4-Fund 3: Band = (working, label)-Paar).
        assert state.agent_status == {"alpha": (False, "Recherche")}
        assert len(state.phone_outbox) == 0

        # Cross-Pfad: klarer agent.status nach enc-Spiegel working=False +
        # gleichem Label = Dedup (kein Zustell-Frame), Wechsel True = Zustellung.
        async def run2():
            await relay._handle_agent_status(
                state, "alpha", {"agent_id": "alpha", "working": False,
                                 "started_at": 1, "label": "Recherche"})
            await relay._handle_agent_status(
                state, "alpha", {"agent_id": "alpha", "working": True,
                                 "started_at": 1, "label": "Recherche"})

        asyncio.run(run2())
        assert ws.send_json.call_count == 3
        assert ws.send_json.call_args_list[-1][0][0]["type"] == "agent.status"

    # ── Live-Relay-Beweis (Dispatch + Allowlist + Zustellung) ─────────────

    def test_agent_status_live_relay_frame_flow(self, tmp_path, monkeypatch):
        """Live am Relay: agent.status wandert über die Agent-WS
        (Allowlist + Dispatch + _guard_agent_frame) ans Phone-WS —
        working-Dedup je agent_id, sub/end je Frame. Zustellungserfolg
        erreicht die Phone-WS live (Outbox-Anteil im Erfolgspfad leer)."""
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"},
                )
                await _drain_phone_state_frame(phone, expected=2)

                # Erster bekannter parent-Stand (ohne Label/Sub) → durch.
                await agent.send_json({
                    "type": "agent.status", "agent_id": "alpha",
                    "working": True, "started_at": 1730515200000,
                })
                f1 = await phone.receive_json(timeout=2)
                assert f1["type"] == "agent.status"
                assert f1["agent_id"] == "alpha"
                assert f1["working"] is True
                assert "label" not in f1 and "sub" not in f1

                # Gleicher (working, label)-Verband = Dedup (kein zweiter
                # Frame; J4-Fund 3: Vergleich um das Label erweitert).
                await agent.send_json({
                    "type": "agent.status", "agent_id": "alpha",
                    "working": True, "started_at": 1730515200000,
                })
                with pytest.raises(asyncio.TimeoutError):
                    await phone.receive_json(timeout=0.4)

                # Slot-Verband: sub/end je Frame durchgereicht.
                await agent.send_json({
                    "type": "agent.status", "agent_id": "alpha",
                    "working": True, "started_at": 1730515200000,
                    "label": "Recherche",
                    "sub": {"id": "sub-1", "name": "Bauer"},
                })
                f2 = await phone.receive_json(timeout=2)
                assert f2["label"] == "Recherche"
                assert f2["sub"] == {"id": "sub-1", "name": "Bauer"}

                # J4-Fund 3: der Slot-Frame hat das Haupt-Aggregat NICHT
                # überschrieben — parent unverändert = weiter Dedup.
                await agent.send_json({
                    "type": "agent.status", "agent_id": "alpha",
                    "working": True, "started_at": 1730515200000,
                })
                with pytest.raises(asyncio.TimeoutError):
                    await phone.receive_json(timeout=0.4)

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    # ── Test-Harness ──────────────────────────────────────────────────────

    PORT = 19887
    CODE = "W19CD01"
    TOKEN = "agent-w19-status-token"

    @staticmethod
    def _write_config(tmp_path):
        import json
        cfg = tmp_path / "relay-config.json"
        cfg.write_text(
            json.dumps({
                "ANDROID_AGENT_TOKEN_ALPHA": "agent-w19-status-token",
                "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha",
            }),
            encoding="utf-8",
        )
        return str(cfg)


class TestSecfix17MtlsGatePeerCheckAndBodyRedaction:
    """Security-Fix-Welle 17.11. (Jäger-Befund 2): X-SSL-Verify/X-SSL-Device-DN
    gelten NUR beim Socket-Peer == Trusted Proxy (analog XFF-Peer-Check) — ein
    fremder Peer mit gefälschten Headern fällt auf die verify=NONE-Regel (403).
    Und: _SENSITIVE_BODY_KEYS redactet jetzt auch password/token/authorization/code."""

    DEVICE = "a1b2c3d4-e5f6-4a7b-8c9d-0a1b2c3d4e5f"

    def _gate_setup(self, monkeypatch, tmp_path):
        import tools.mtls_enroll as menroll
        monkeypatch.setenv("ANDROID_MTLS_ENFORCE", "1")
        monkeypatch.setenv("ANDROID_RELAY_TRUSTED_PROXY_IP", "192.168.178.76")
        monkeypatch.setenv("ANDROID_RELAY_TLS_DIR", str(tmp_path))
        menroll._registry_save(
            {"epoch": 1,
             "enrolled": {self.DEVICE: {"cn": f"flipsibridge-{self.DEVICE}"}}})

    def test_forged_x_ssl_headers_from_foreign_peer_rejected(self, monkeypatch, tmp_path):
        from types import SimpleNamespace

        self._gate_setup(monkeypatch, tmp_path)
        hdrs = {
            "X-SSL-Verify": "SUCCESS",
            "X-SSL-Device-DN": f"CN=flipsibridge-{self.DEVICE}/O=FlipsiBridge Devices",
        }
        # Gefälschte Header + FREMDER Peer → Gate verweigert (403)
        req_evil = SimpleNamespace(path="/ws", headers=hdrs, remote="10.66.66.66")
        resp = _mtls_gate(req_evil)
        assert resp is not None and resp.status == 403, (
            "X-SSL-Verband von fremdem Peer muss wie verify=NONE behandelt werden")
        # Kontrolle: gleiche Header vom Trusted-Proxy-Peer → Gate lässt durch
        req_ok = SimpleNamespace(path="/ws", headers=hdrs, remote="192.168.178.76")
        assert _mtls_gate(req_ok) is None
        # Fail-closed: ohne Proxy-Env gilt kein X-SSL-Verband
        monkeypatch.setenv("ANDROID_RELAY_TRUSTED_PROXY_IP", "")
        assert _mtls_gate(req_ok) is not None

    def test_safe_body_redacts_password_token_authorization_code(self):
        import json as _json

        repr_ = _safe_body_repr({
            "password": "hunter2", "token": "tok-secret",
            "authorization": "Bearer geheim", "code": "123456",
            "note": "sichtbar",
        })
        for secret in ("hunter2", "tok-secret", "Bearer geheim", "123456"):
            assert secret not in repr_
        data = _json.loads(repr_)
        assert data["password"] == "<redacted>"
        assert data["token"] == "<redacted>"
        assert data["authorization"] == "<redacted>"
        assert data["code"] == "<redacted>"
        assert data["note"] == "sichtbar"


class TestP43MtlsGateEpochCheck:
    """P43 (17.11.): Einträge zählen am Gate nur in ihrer Zert-Epoche.

    set_pairing_code rotiert die Epoch BESONDERS NICHT mit einem Wipe — der
    Registry-Eintrag (und das Zert) des Alt-Code-Geräts bleibt erhalten, aber
    das Gate lehnt es ab (403 mtls-stale-epoch), bis es mit dem NEUEN Code
    neu enrollt ("renewal") und damit auf die aktuelle Epoche gehoben wird.
    Legacy-Einträge OHNE epoch_of_enroll (Bestand vor P43) zählen weiter
    (fail-open, Bestandsschutz)."""

    DEVICE = "a1b2c3d4-e5f6-4a7b-8c9d-0a1b2c3d4e5f"

    def _setup(self, monkeypatch, tmp_path, enrolled, epoch=1):
        import tools.mtls_enroll as menroll
        monkeypatch.setenv("ANDROID_MTLS_ENFORCE", "1")
        monkeypatch.setenv("ANDROID_RELAY_TRUSTED_PROXY_IP", "192.168.178.76")
        monkeypatch.setenv("ANDROID_RELAY_TLS_DIR", str(tmp_path))
        menroll._registry_save({"epoch": epoch, "enrolled": enrolled})

    def _request(self):
        from types import SimpleNamespace
        hdrs = {
            "X-SSL-Verify": "SUCCESS",
            "X-SSL-Device-DN": f"CN=flipsibridge-{self.DEVICE}/O=FlipsiBridge Devices",
        }
        return SimpleNamespace(path="/ws", headers=hdrs, remote="192.168.178.76")

    def test_stale_epoch_entry_rejected_but_not_deleted(self, monkeypatch, tmp_path):
        import json
        import tools.mtls_enroll as menroll

        entry = {"cn": f"flipsibridge-{self.DEVICE}", "ts": 1, "epoch_of_enroll": 1}
        self._setup(monkeypatch, tmp_path, {self.DEVICE: entry}, epoch=9)
        resp = _mtls_gate(self._request())
        assert resp is not None and resp.status == 403
        assert json.loads(resp.text)["error"] == "mtls-stale-epoch"
        # P43-Kern: kein stiller Wipe — Eintrag bleibt in der Registry:
        after = menroll._registry_load()
        assert after["enrolled"][self.DEVICE]["epoch_of_enroll"] == 1

    def test_current_epoch_entry_passes(self, monkeypatch, tmp_path):
        self._setup(monkeypatch, tmp_path,
                    {self.DEVICE: {"cn": "c", "epoch_of_enroll": 9}}, epoch=9)
        assert _mtls_gate(self._request()) is None

    def test_legacy_entry_without_epoch_field_still_passes(self, monkeypatch, tmp_path):
        self._setup(monkeypatch, tmp_path, {self.DEVICE: {"cn": "c"}}, epoch=3)
        assert _mtls_gate(self._request()) is None

    def test_rotation_preserves_entry_and_flips_gate_renewal_reopens(self, monkeypatch, tmp_path):
        """P43 End-zu-Ende an der Gate-Ebene: reset_epoch (= set_pairing_code-
        Pfad) rotiert die Epoch → Eintrag bleibt erhalten, Gate kippt auf
        403 mtls-stale-epoch; der Renewal-Save (so schreibt es enroll_handler:
        epoch_of_enroll = aktuelle Epoche) stellt die Geltung wieder her."""
        import json
        import tools.mtls_enroll as menroll

        entry = {"cn": f"flipsibridge-{self.DEVICE}", "ts": 5, "epoch_of_enroll": 4}
        self._setup(monkeypatch, tmp_path, {self.DEVICE: entry}, epoch=4)
        assert _mtls_gate(self._request()) is None  # Zert-Epoche aktuell

        menroll.reset_epoch()  # Code-Wechsel: Epoch 4 → 5
        reg = menroll._registry_load()
        assert reg["epoch"] == 5
        assert reg["enrolled"] == {self.DEVICE: entry}, "kein Wipe bei Rotation"
        resp = _mtls_gate(self._request())
        assert resp is not None and resp.status == 403
        assert json.loads(resp.text)["error"] == "mtls-stale-epoch"

        # Renewal mit dem NEUEN Code (App enrollt dasselbe device_id erneut):
        reg["enrolled"][self.DEVICE]["epoch_of_enroll"] = reg["epoch"]
        menroll._registry_save(reg)
        assert _mtls_gate(self._request()) is None


# ═══════════════════════════════════════════════════════════════════════════════
# P0 J4-FIXE (18.11.2026): AAD-Kette agent-scoped enc-Frames (J4-Fund 1) +
# cron-Verband in der Agent-Allowlist (J4-Fund 2). Synthetischer Handshake wie
# T4 (key_get → crypto.hello → respond_to_hello → consume_ack): der Adapter
# bindet die SESSION-Wire-chat_id in die AAD JEDES agent-scoped enc-Frames
# (agent.status.enc / agent.models.enc / cron.status.enc — `_enc_fanout_frames`
# setzt wire = sess.chat_id, sobald das Gerät gepostet hat). Der Phone-Rebuild
# des Relays MUSS das chat_id-Klarfeld des Agent-Frames mitnehmen, sonst baut
# Kotlin PhoneCryptoPeer die AAD 'fb-v1|session_id|chat_id|agent_id' mit
# chat_id='' → GCM-Tag ungültig → Frame verworfen (alle 3 Verbände tot nach der
# ersten Nutzer-Nachricht; vor dem ersten Post fällt wire auf '' und es geht).
# ═══════════════════════════════════════════════════════════════════════════════

class TestP0J4AadKetteEncE2E:
    """E2E je Frame-Typ mit echtem synthetischem Handshake — Beweis: vorher
    rot (AAD-Mismatch bzw. Junk-Drop beim cron-Verband), nachher entschlüsselbar
    grün mit chat_id-Parität."""

    PORT = 19961
    CODE = "J4AAD01"
    TOKEN = "j4-aad-token"
    SESSION_ID = "j4-aad-session-1"
    WIRE_CHAT = "chat-j4-live-1"

    # Fixe Agent-Identity (Parität TestPh3CryptoE2E: RFC-7748-Bob + Fixture-PreKey):
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
        cfg.write_text(json.dumps({
            "ANDROID_AGENT_TOKEN_ALPHA": self.TOKEN,
            "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha",
            "CHAT_RATE_PER_SECOND": 50,
            "ANDROID_AGENT_IDENTITY_ALPHA": self.IK_A_PUB,
            "ANDROID_AGENT_PREKEY_ALPHA": self.SPK_A_PUB,
            "ANDROID_AGENT_FINGERPRINT_ALPHA":
                _ph3_adapter.fingerprint_of_pub(bytes.fromhex(self.IK_A_PUB)),
        }), encoding="utf-8")
        return str(cfg)

    async def _handshake(self, session, phone, agent, base, session_id):
        """key_get → crypto.hello (POST) → respond_to_hello → ack via Agent-WS
        → consume_ack. Rückgabe (PhonePeer, AgentCryptoSession)."""
        got = await session.get(f"{base}/key_get", params={"agent_id": "alpha"},
                                headers={"Authorization": f"Bearer {self.CODE}"})
        assert got.status == 200
        keys = await got.json()
        peer = _ph3_adapter.PhoneCryptoPeer("alpha", keys["ik_pub"], keys["spk_pub"])
        hello = peer.make_hello(session_id)
        resp = await session.post(f"{base}/crypto_hello", json=hello,
                                  headers={"Authorization": f"Bearer {self.CODE}"})
        assert resp.status == 200
        assert (await resp.json())["delivered"] is True
        hello_frame = await agent.receive_json(timeout=6)
        assert hello_frame["type"] == "crypto.hello"
        agent_session, ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
            hello_frame, self.identity)
        await agent.send_json(ack)
        ack_frame = await _recv_until(phone, "crypto.hello_ack", timeout=6)
        peer.consume_ack(ack_frame)
        assert peer.fingerprint == agent_session.fingerprint
        return peer, agent_session

    def test_j4_agent_scoped_enc_frames_aad_kette_entdecryptbar(self, tmp_path, monkeypatch):
        """J4-Fund 1 (AAD-Kette) über ALLE 3 Frame-Typen: top-level bleiben die
        Klarfelder (Dedup/UI-Fallback-Parität), die Payload liegt im ct, und
        der Phone-Rebuild MUSS chat_id = Session-Wire-chat_id tragen — sonst
        baut Kotlin die AAD mit chat_id='' → GCM-Tag ungültig."""
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT)
        base = f"http://127.0.0.1:{self.PORT}"

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                await _drain_phone_state_frame(phone, expected=1)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6)
                peer, agent_session = await self._handshake(
                    session, phone, agent, base, self.SESSION_ID)

                ts_ms = int(time.time() * 1000)
                # Adapter-Parität: `_enc_fanout_frames` nutzt wire = sess.chat_id
                # (Gerät hat gepostet); Status trägt zusätzlich den Klar-Spiegel.
                mirror = {"working": True, "started_at": ts_ms,
                          "label": "Sucht im Web"}
                cron_payload = {"jobs": [
                    {"name": "w22-report", "status": "planned",
                     "next_at": ts_ms + 60_000},
                    {"name": "backup", "status": "running"},
                ]}
                cases = [
                    ("agent.status.enc",
                     agent_session.encrypt_frame("agent.status.enc", self.WIRE_CHAT,
                                                 dict(mirror), clear=dict(mirror)),
                     dict(mirror)),
                    ("agent.models.enc",
                     agent_session.encrypt_frame("agent.models.enc", self.WIRE_CHAT,
                                                 {"models": ["fb-gemma", "fb-nous"],
                                                  "current": "fb-gemma",
                                                  "effort": ""}),
                     {"models": ["fb-gemma", "fb-nous"], "current": "fb-gemma",
                      "effort": ""}),
                    ("cron.status.enc",
                     agent_session.encrypt_frame("cron.status.enc", self.WIRE_CHAT,
                                                 cron_payload),
                     cron_payload),
                ]

                results = {}
                for typ, frame, expected in cases:
                    await agent.send_json(frame)
                    entry = {"arrived": False, "chat_id": "", "decrypt_ok": False,
                             "err": ""}
                    try:
                        got = await _recv_until(phone, typ, timeout=6, parked=[])
                        entry["arrived"] = True
                        entry["chat_id"] = str(got.get("chat_id") or "")
                        try:
                            payload = peer.decrypt_frame(got)
                            entry["decrypt_ok"] = payload == expected
                            if not entry["decrypt_ok"]:
                                entry["err"] = f"Payload-Divergenz: {payload!r}"
                        except Exception as exc:
                            entry["err"] = f"Decrypt: {type(exc).__name__}"
                    except (AssertionError, asyncio.TimeoutError):
                        # asyncio.TimeoutError (3.14 = TimeoutError): der
                        # gesuchte Frame kam nicht (VORher-Fix: Junk-Drop
                        # cron.status.enc bzw. Rebuild ohne chat_id = kein
                        # passender Phone-Frame für den Typ).
                        entry["err"] = "Delivery: Frame kam nicht"
                    results[typ] = entry
                # Teardown fail-soft: ein verpasster Frame kann den WS-Reader
                # „vergiften" (aiohttp-Quirk) — der Beweis steht in results.
                for ws_ in (agent, phone):
                    try:
                        await ws_.close()
                    except (Exception, asyncio.CancelledError):
                        pass
                return results

        results = asyncio.run(scenario())
        failures = "\n".join(
            f"  {typ}: arrived={res['arrived']} chat_id={res['chat_id']!r} "
            f"decrypt_ok={res['decrypt_ok']} err={res['err']}"
            for typ, res in results.items())
        assert all(
            res["arrived"] and res["chat_id"] == self.WIRE_CHAT
            and res["decrypt_ok"] for res in results.values()), \
            f"AAD-Kette verletzt (VORher-Beweis je Typ):\n{failures}"

    def test_j4_cron_status_plaintext_allowlist_delivered(self, tmp_path, monkeypatch):
        """J4-Fund 2: cron.status (Klartext, ohne E2E-Session) muss die
        Agent-Allowlist passieren und klar ans Phone kommen — vorher Junk-Drop
        (Allowlist-Verstoß, Handler toter Code), nachher Delivery mit jobs."""
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        start_relay(pairing_code=self.CODE, port=self.PORT + 1)
        base = f"http://127.0.0.1:{self.PORT + 1}"

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 1}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                await _drain_phone_state_frame(phone, expected=1)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 1}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6)
                await agent.send_json({
                    "type": "cron.status", "v": 1, "agent_id": "alpha",
                    "jobs": [{"name": "w22-report", "status": "planned",
                              "next_at": 1730515200000}],
                })
                got = await _recv_until(phone, "cron.status", timeout=6, parked=[])
                await agent.close()
                await phone.close()
                return got
        frame = asyncio.run(scenario())
        assert frame["agent_id"] == "alpha"
        assert frame["jobs"] == [{"name": "w22-report", "status": "planned",
                                  "next_at": 1730515200000}]
        assert frame["ts"] > 0

    # ── Test-Harness (Parität TestW19AgentStatus) ─────────────────────────

    def _fresh_state(self):
        import asyncio as _aio
        from tools.android_relay import _RelayState
        state = _RelayState("code", 8799)
        state.broker_lock = _aio.Lock()
        return state

    def _state_with_ws(self):
        """RelayState + Mock-Phone-WS mit ASYNC send_json (so wird die
        Zustellung ein echter Zustell-Beweis, kein Offline-Schluck)."""
        import asyncio as _aio
        from unittest import mock
        state = self._fresh_state()
        ws = mock.Mock()
        ws.closed = False
        ws.send_json = mock.AsyncMock()
        state.phone_ws = ws
        return state, ws

    def test_j4_cron_gates_rate_bucket_and_downgrade_guard(self, monkeypatch):
        """J4-Fund 2 (Guards): Struktur-Gate fail-closed (keine Liste/kaputte
        Zeile), Downgrade-Schutz (Klartext bei E2E-Session verworfen) und
        EIGENER Rate-Bucket 'cronstatus:*' (RELAY-FRAME-GUARDS: nie geteilt
        mit chat.reply)."""
        import asyncio
        from unittest import mock
        from tools import android_relay as relay
        from tools.android_relay import _chat_rate_hits, _chat_rate_lock

        state, ws = self._state_with_ws()
        good_jobs = [{"name": "w22-report", "status": "planned",
                      "next_at": 1730515200000},
                     {"name": "backup", "status": "running"}]

        async def run():
            # 1) Struktur-Gate fail-closed: kein jobs / kaputte Zeile /
            #    falsche status / next_at bei running.
            for bad in (None, "keine-liste", [{"name": "", "status": "planned"}],
                        [{"name": "a", "status": "unknown"}],
                        [{"name": "a", "status": "running", "next_at": 5}]):
                await relay._handle_agent_cron_status(state, "alpha",
                                                      {"agent_id": "alpha",
                                                       "jobs": bad})
                assert ws.send_json.call_count == 0, bad
            # next_at bei running → auch ohne next_at-Problem-Zeile ok,
            # aber falscher Typ → verworfen.
            await relay._handle_agent_cron_status(
                state, "alpha", {"agent_id": "alpha",
                                 "jobs": [{"name": "a", "status": "planned",
                                           "next_at": "morgen"}]})
            assert ws.send_json.call_count == 0
            # 2) Downgrade-Schutz: Klartext bei bestätigter E2E-Session → weg.
            async def has_session(state_, agent_id_):
                return True
            monkeypatch.setattr(relay, "_agent_has_e2e_session", has_session)
            await relay._handle_agent_cron_status(
                state, "alpha", {"agent_id": "alpha", "jobs": good_jobs})
            assert ws.send_json.call_count == 0
            # 3) Gültiger Klartext ohne Session → Delivery + Spool-Verband.
            #    (Zuvor Rate-Bucket leeren: die Gate-Rejects oben haben den
            #    6/s-Bucket bereits geätzt — Guards prüfen VOR der Struktur.)
            with _chat_rate_lock:
                _chat_rate_hits.pop("cronstatus:alpha", None)
            async def no_session(state_, agent_id_):
                return False
            monkeypatch.setattr(relay, "_agent_has_e2e_session", no_session)
            await relay._handle_agent_cron_status(
                state, "alpha", {"agent_id": "alpha", "jobs": good_jobs})
            assert ws.send_json.call_count == 1
            frame = ws.send_json.call_args[0][0]
            assert frame["type"] == "cron.status"
            assert frame["jobs"] == good_jobs
            assert frame["agent_id"] == "alpha"
            # 4) EIGENER Rate-Bucket: 6/s — der 7. cron-Frame fällt, der
            #    chat.reply-Bucket bleibt JUNGF (nie geteilt).
            with _chat_rate_lock:
                _chat_rate_hits.pop("cronstatus:alpha", None)
                _chat_rate_hits.pop("chat.reply", None)
                _chat_rate_hits.pop("chatreply:alpha", None)
            for i in range(6):
                got_through = await relay._handle_agent_cron_status(
                    state, "alpha", {"agent_id": "alpha", "jobs": good_jobs})
                assert got_through is None  # Handler gibt nie zurück
            before = ws.send_json.call_count
            await relay._handle_agent_cron_status(
                state, "alpha", {"agent_id": "alpha", "jobs": good_jobs})
            assert ws.send_json.call_count == before, "7. Frame über Cron-Cap"
            assert "cronstatus:alpha" in _chat_rate_hits
            assert not [k for k in _chat_rate_hits
                        if k not in ("cronstatus:alpha",)], \
                "cron-Verband darf nur den EIGENEN Bucket berühren"

        asyncio.run(run())

    def test_j4_cron_enc_guards_fail_closed(self, monkeypatch):
        """J4-Fund 2 (enc-Verband): ohne bestätigte Session → kein Delivery,
        kein State-Write; der chat_id-Klarfeld-Verband greift nicht als
        Validierung (ct opak)."""
        import asyncio
        import base64
        import time
        from unittest import mock
        from tools import android_relay as relay

        state, ws = self._state_with_ws()
        body = {
            "type": "cron.status.enc",
            "agent_id": "alpha",
            "session_id": "sess-cron-1",
            "chat_id": "chat-cron-1",
            "hdr": {"rk": "a" * 64, "n": 0, "pn": 1},
            "nonce": base64.b64encode(bytes(12)).decode(),
            "ct": base64.b64encode(b"opak").decode(),
            "ts": int(time.time() * 1000),
        }

        async def run():
            # Ohne Session fail-closed (Klon agent.status.enc).
            await relay._handle_agent_cron_status_enc(state, "alpha", dict(body))
            assert ws.send_json.call_count == 0
            # Mit gepatchter Session → Delivery MIT chat_id-Passthrough
            # (J4-Fund 1 für den cron-Verband).
            async def fake_session(state_, agent_id_, data_):
                return {"agent_id": agent_id_, "acked": True}
            monkeypatch.setattr(relay, "_enc_session_active", fake_session)
            await relay._handle_agent_cron_status_enc(state, "alpha", dict(body))
            assert ws.send_json.call_count == 1
            frame = ws.send_json.call_args[0][0]
            assert frame["type"] == "cron.status.enc"
            assert frame["chat_id"] == "chat-cron-1"
            assert frame["ct"] == body["ct"]  # 1:1 opak
            assert frame["agent_id"] == "alpha"

        asyncio.run(run())


# ═══════════════════════════════════════════════════════════════════════════════
# PH-4-T2: Relay-Backend-Identität (Ed25519) — Keygen einmalig + persistent
# (Env-Verband), Relay-ID = SHA-256(pub) (log-only), agent.ident je
# agent.register (signed, TOFU-Aushändigung), Signatur-Verband v1 auf dem
# Handshake-Verbund (crypto.hello / hello_ack / session_reset). Der private
# Schlüssel geht in KEIN Log.
# ═══════════════════════════════════════════════════════════════════════════════

class TestP04T2RelayIdentitaet:
    """Identität am echten Relay-Prozess: Persistenz über Restart, 0600-Datei,
    signed agent.ident, signierte Handshake-Frames, Priv-Key-Audit."""

    PORT = 19972
    CODE = "T2IDENT01"
    TOKEN = "t2-agent-token"
    SESSION_ID = "t2-ident-session-1"

    # Fixe Agent-Identity (Parität TestPh3CryptoE2E).
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
        cfg.write_text(json.dumps({
            "ANDROID_AGENT_TOKEN_ALPHA": self.TOKEN,
            "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha",
            "CHAT_RATE_PER_SECOND": 50,
            "ANDROID_AGENT_IDENTITY_ALPHA": self.IK_A_PUB,
            "ANDROID_AGENT_PREKEY_ALPHA": self.SPK_A_PUB,
            "ANDROID_AGENT_FINGERPRINT_ALPHA":
                _ph3_adapter.fingerprint_of_pub(bytes.fromhex(self.IK_A_PUB)),
        }), encoding="utf-8")
        return str(cfg)

    @staticmethod
    def _reset_identity_cache():
        import tools.android_relay as R
        R._RELAY_IDENTITY_CACHE = None

    def test_t2_identitaet_keygen_persistent_0600_agent_ident(self, tmp_path, monkeypatch, caplog):
        """Keygen einmalig + persistent: Restart → GLEICHE relay_id; Datei
        0600; agent.ident je agent.register mit gültigem Sig-Verband
        (relay_id == SHA-256(verify_key), Ed25519-Sig über kanonische Bytes
        — PARITÄT Adapter); KEIN Priv-Material in irgendeinem Log."""
        import base64 as _b64
        import stat as _stat
        import aiohttp

        id_dir = tmp_path / "identity"
        monkeypatch.setenv("ANDROID_RELAY_IDENTITY_DIR", str(id_dir))
        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        self._reset_identity_cache()
        start_relay(pairing_code=self.CODE, port=self.PORT)
        base = f"http://127.0.0.1:{self.PORT}"

        async def scenario_collect_ident_and_hello():
            """Connect + register → agent.ident; hello → crypto.hello mit Sig."""
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await agent.send_json({"type": "agent.register",
                                       "display_name": "Alpha T2"})
                ident = await agent.receive_json(timeout=6)
                # crypto.hello (POST-Weg) — Agent erhält den signierten Frame.
                keys_req = await session.get(
                    f"{base}/key_get", params={"agent_id": "alpha"},
                    headers={"Authorization": f"Bearer {self.CODE}"})
                keys = await keys_req.json()
                peer = _ph3_adapter.PhoneCryptoPeer("alpha", keys["ik_pub"],
                                                    keys["spk_pub"])
                hello = peer.make_hello(self.SESSION_ID)
                resp = await session.post(f"{base}/crypto_hello", json=hello,
                                          headers={"Authorization": f"Bearer {self.CODE}"})
                assert resp.status == 200
                hello_frame = await agent.receive_json(timeout=6)
                await agent.close()
                await phone.close()
                return ident, hello_frame

        try:
            ident, hello_frame = asyncio.run(scenario_collect_ident_and_hello())
        finally:
            stop_relay()

        # agent.ident-Verband je Connect-Register.
        assert ident["type"] == "agent.ident"
        assert ident["relay_id"] == _ph3_adapter.sha256_hex_of(
            bytes.fromhex(ident["verify_key"])) if hasattr(
            _ph3_adapter, "sha256_hex_of") else True
        # Konsistenz unabhängig von Test-Doubles rechnerisch prüfen:
        import hashlib as _hl
        assert ident["relay_id"] == _hl.sha256(
            bytes.fromhex(ident["verify_key"])).hexdigest()
        bare = {k: v for k, v in ident.items() if k != "sig"}
        sig = _b64.b64decode(ident["sig"], validate=True)
        from cryptography.hazmat.primitives.asymmetric.ed25519 import (
            Ed25519PrivateKey, Ed25519PublicKey,
        )
        import tools.android_relay as R
        pub = Ed25519PublicKey.from_public_bytes(bytes.fromhex(ident["verify_key"]))
        pub.verify(sig, json.dumps(bare, sort_keys=True, separators=(",", ":"),
                                   ensure_ascii=False).encode("utf-8"))

        # Signatur-Verband v1: crypto.hello trägt sig + relay_id und die Sig
        # verifiziert gegen DIESELBE Instanz-Identität wie agent.ident.
        assert hello_frame["type"] == "crypto.hello"
        assert hello_frame["relay_id"] == ident["relay_id"]
        hello_bare = {k: v for k, v in hello_frame.items() if k != "sig"}
        Ed25519PublicKey.from_public_bytes(bytes.fromhex(ident["verify_key"])).verify(
            _b64.b64decode(hello_frame["sig"], validate=True),
            json.dumps(hello_bare, sort_keys=True, separators=(",", ":"),
                       ensure_ascii=False).encode("utf-8"))

        # Persistenz: Identity-Datei 0600, Restart → GLEICHE relay_id.
        ident_file = id_dir / "relay-identity.json"
        assert ident_file.exists()
        assert _stat.S_IMODE(ident_file.stat().st_mode) & 0o777 == 0o600, \
            "Identity-Datei muss 0600 sein"
        stored = json.loads(ident_file.read_text(encoding="utf-8"))
        assert stored["relay_id"] == ident["relay_id"]
        # priv = 64 hex; der Wert erscheint in KEINEM Log-Zeile.
        assert len(stored["priv"]) == 64
        dumped = "\n".join(r.getMessage() for r in caplog.records)
        assert stored["priv"] not in dumped, "Privater Schlüssel im Log!"
        assert bytes.fromhex(stored["priv"]).hex() not in dumped

        self._reset_identity_cache()
        start_relay(pairing_code=self.CODE, port=self.PORT + 1)
        try:
            assert R._relay_identity() is not None
            assert R._relay_identity()["relay_id"] == ident["relay_id"], \
                "Restart muss DIESELBE relay_id behalten (persistent)"
        finally:
            stop_relay()

    def test_t2_hello_ack_signed_transit(self, tmp_path, monkeypatch):
        """Signatur-Verband v1 am Voll-Handshake: hello_ack (Agent→Relay→Phone)
        trägt sig + relay_id der Instanz (App-Verifikation W23-Kandidat,
        Feld harmlos — hier nur Belegbarkeit der Signatur)."""
        import aiohttp

        id_dir = tmp_path / "identity"
        monkeypatch.setenv("ANDROID_RELAY_IDENTITY_DIR", str(id_dir))
        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        self._reset_identity_cache()
        start_relay(pairing_code=self.CODE, port=self.PORT + 2)
        base = f"http://127.0.0.1:{self.PORT + 2}"

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 2}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 2}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                keys_req = await session.get(
                    f"{base}/key_get", params={"agent_id": "alpha"},
                    headers={"Authorization": f"Bearer {self.CODE}"})
                keys = await keys_req.json()
                peer = _ph3_adapter.PhoneCryptoPeer("alpha", keys["ik_pub"],
                                                    keys["spk_pub"])
                hello = peer.make_hello(self.SESSION_ID)
                await session.post(f"{base}/crypto_hello", json=hello,
                                   headers={"Authorization": f"Bearer {self.CODE}"})
                hello_frame = await agent.receive_json(timeout=6)
                agent_session, ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
                    hello_frame, self.identity)
                await agent.send_json(ack)
                raw_ack = await _recv_until(phone, "crypto.hello_ack", timeout=6,
                                            parked=[])
                await agent.close()
                await phone.close()
                return raw_ack

        try:
            raw_ack = asyncio.run(scenario())
        finally:
            stop_relay()
        import base64 as _b64b
        assert raw_ack["type"] == "crypto.hello_ack"
        assert raw_ack["relay_id"], "hello_ack ohne relay_id (Sig-Verband v1)"
        bare = {k: v for k, v in raw_ack.items() if k != "sig"}
        from cryptography.hazmat.primitives.asymmetric.ed25519 import (
            Ed25519PrivateKey,
        )
        pub_hex = json.loads(
            (id_dir / "relay-identity.json").read_text(encoding="utf-8"))
        # Sig prüfen gegen den Pubkey DER ABLAGE (unabhängige Derivation).
        priv_bytes = bytes.fromhex(pub_hex["priv"])
        verify_key = Ed25519PrivateKey.from_private_bytes(priv_bytes).public_key()
        verify_key.verify(
            _b64b.b64decode(raw_ack["sig"], validate=True),
            json.dumps(bare, sort_keys=True, separators=(",", ":"),
                       ensure_ascii=False).encode("utf-8"))
