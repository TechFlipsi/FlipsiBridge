# FIX-WELLE-2 B3 (P3, HERZ-Befund 3) — Home-Kanal-Hinweis-Drossel.
#
# W18 (17.11.26, Sir): Der Hinweis („No home channel … /sethome") darf NICHT
# im App-Chat landen — die 24-h-Window-Drossel ließ je Fenster EINEN Hinweis
# durch und Sir meldete ihn als Chat-Verschmutzung. Neu: IMMER still
# wegwerfen (success=True), normale Nachrichten unberührt.
#
# Root-Cause (historisch, B3): Das Hermes-Gateway liefert den „No home
# channel is set … /sethome"-Hinweis bei JEDEM Turn an die Plattform aus,
# solange kein Home-Kanal gesetzt ist → der Hinweis flutete jede
# Konversation. Erste Stufe war die 24-h-Drossel, zweite Stufe (W18) das
# volle Nie-Zustellen.

import pytest

from _fw2_common import _make_crypto_adapter


def _replies(ws) -> list[dict]:
    """chat.reply*-Frames je Session-Stand: Legacy-Adapter (kein Handshake)
    antwortet als plain 'chat.reply', Adapter mit aktiver E2E-Session als
    'chat.reply.enc' — beide zählen hier als Zustell-Frames, chat.activity/
    agent.models & Co. bleiben bewusst außen vor."""
    return [f for f in ws.sent if str(f.get("type", "")).startswith("chat.reply")]


@pytest.mark.asyncio
async def test_fw2_b3_home_channel_notice_once_per_window(tmp_path) -> None:
    """Der Home-Kanal-Hinweis (EN- und DE-Locale-Variante) wird NIEMALS
    zugestellt (W18) — jede Phase meldet success, ohne einen chat.reply*-Frame
    zu erzeugen; normale Nachrichten laufen unangetastet durch."""
    a, ws = _make_crypto_adapter(tmp_path)
    await a.connect()
    notice_en = ("No home channel is set for Flipsibridge. A home channel is "
                 "where Hermes delivers cron job results.\n\nType /sethome to "
                 "make this your home channel, or ignore to skip.")
    notice_de = ("Für Flipsibridge ist kein Home-Kanal festgelegt.\n\n"
                 "Geben Sie /sethome ein, um diesen Chat zu Ihrem Home-Kanal "
                 "zu machen.")

    # Erster Turn (EN): unterdrückt — 0 Frames, trotzdem success.
    r1 = await a.send("device-1", notice_en)
    assert r1.success
    assert len(_replies(ws)) == 0

    # Zweiter Turn, gleiche Locale: weiterhin unterdrückt.
    r2 = await a.send("device-1", notice_en)
    assert r2.success, "Unterdrückung ist kein Fehlerfall"
    assert len(_replies(ws)) == 0

    # Dritter Turn, DE-Locale-Variante: ebenfalls unterdrückt.
    r3 = await a.send("device-1", notice_de)
    assert r3.success
    assert len(_replies(ws)) == 0

    # Normale Agenten-Antworten sind von der Unterdrückung unberührt: GENU
    # 1 zusätzlicher chat.reply*-Frame (dieser Test-Adapter fährt keinen
    # Handshake → plain 'chat.reply'; mit Session liege derselbe Frame als
    # 'chat.reply.enc' vor — der Prefix-Helper fängt beide).
    before = len(_replies(ws))
    r4 = await a.send("device-1", "Normale Antwort")
    assert r4.success
    r4_frames = _replies(ws)[before:]
    assert len(r4_frames) == 1
    assert r4_frames[0].get("type") in ("chat.reply", "chat.reply.enc")

    # W18: auch nach fiktivem Fensterablauf bleibt der Hinweis unterdrückt —
    # der alte 24-h-Stempel hat keine Wirkung mehr (tote Mechanik bleibt als
    # Abschaltpfad im Adapter; der Verband ist jetzt „nie zustellen").
    a._home_notice_last_ts = 0.0
    r5 = await a.send("device-1", notice_en)
    assert r5.success
    assert len(_replies(ws)) == before + 1

    await a.disconnect()
    print("OK FW2-B3/W18: Home-Kanal-Hinweis wird NIE zugestellt")
