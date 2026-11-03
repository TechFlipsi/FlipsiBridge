# FIX-WELLE-2 B3 (P3, HERZ-Befund 3) — Home-Kanal-Hinweis-Drossel.
#
# Root-Cause: Das Hermes-Gateway liefert den „No home channel is set … /
# /sethome"-Hinweis bei JEDEM Turn an die Plattform aus, solange kein Home-
# Kanal gesetzt ist → der Hinweis flutete jede Konversation (Verlaufs-/
# Notification-Flood, 16:54/16:55 u.ö. im HERZ-Realtest). Fix im Adapter
# (Sendepfad): 1 Hinweis je Agent (Adapter-Instanz) pro 24-h-Zeitfenster;
# normale Nachrichten unberührt; Fensterablauf liefert wieder.

import pytest

from _fw2_common import _make_crypto_adapter


@pytest.mark.asyncio
async def test_fw2_b3_home_channel_notice_once_per_window(tmp_path) -> None:
    """Der Home-Kanal-Hinweis (EN- und DE-Locale-Variante) wird 1× je Agent
    je Zeitfenster zugestellt — Folge-Turns unterdrückt, normale Nachrichten
    unberührt, nach Fensterablauf wieder zugestellt."""
    a, ws = _make_crypto_adapter(tmp_path)
    await a.connect()
    notice_en = ("No home channel is set for Flipsibridge. A home channel is "
                 "where Hermes delivers cron job results.\n\nType /sethome to "
                 "make this your home channel, or ignore to skip.")
    notice_de = ("Für Flipsibridge ist kein Home-Kanal festgelegt.\n\n"
                 "Geben Sie /sethome ein, um diesen Chat zu Ihrem Home-Kanal "
                 "zu machen.")

    r1 = await a.send("device-1", notice_en)
    assert r1.success
    assert sum(1 for f in ws.sent if f.get("type") == "chat.reply") == 1

    # Zweiter Turn, gleiche Locale: unterdrückt (success, aber KEIN Frame).
    r2 = await a.send("device-1", notice_en)
    assert r2.success, "Unterdrückung ist kein Fehlerfall"
    assert sum(1 for f in ws.sent if f.get("type") == "chat.reply") == 1

    # Dritter Turn, DE-Locale-Variante: ebenfalls unterdrückt (gleiches Fenster).
    r3 = await a.send("device-1", notice_de)
    assert r3.success
    assert sum(1 for f in ws.sent if f.get("type") == "chat.reply") == 1

    # Normale Agenten-Antworten sind von der Drossel unberührt.
    r4 = await a.send("device-1", "Normale Antwort")
    assert r4.success
    assert sum(1 for f in ws.sent if f.get("type") == "chat.reply") == 2

    # Fenster abgelaufen (Zeitstempel zurückstellen): Hinweis wieder zu.
    a._home_notice_last_ts = 0.0
    r5 = await a.send("device-1", notice_en)
    assert r5.success
    assert sum(1 for f in ws.sent if f.get("type") == "chat.reply") == 3

    await a.disconnect()
    print("OK FW2-B3: Home-Kanal-Hinweis 1× je 24-h-Fenster je Agent")