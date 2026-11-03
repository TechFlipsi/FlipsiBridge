#!/usr/bin/env python3
"""Relay-Log-Wächter (W3-04): Botnet-/Brute-Force-Alarm für flipsibridge-relay.

Liest das Relay-Journal (journalctl) über ein Zeitfenster und alarmiert über
einen Notify-Kanal (z. B. notify-discord.sh → Telegram/Discord), wenn

  (a) mehr als RELAY_BOTNET_IPS DISTINCT Quell-IPs mit Auth-Fehlern im
      Fenster aufgetreten sind (klassisches Botnet: viele IPs, je wenige
      Versuche — der 5-Fail-IP-Block des Relays reicht dagegen allein
      nicht aus), ODER
  (b) mehr als RELAY_BOTNET_FAILS Auth-Fehler GESAMT im Fenster passiert
      sind (Einzel-IP-Brute-Force-Sturm).

Sonst ist der Wächter STILL: kein Output, Exit 0 (Erfolg nervt nicht).
Alarm-Cooldown über State-Datei (RELAY_BOTNET_COOLDOWN_MIN), damit ein
Dauerangriff nicht je Lauf eine neue Meldung erzeugt.

Alle Schwellwerte sind ENV-konfigurierbar (keine hard-coded Live-Werte
im öffentlichen Repo nötig); Defaults siehe unten.

Cron-Einbindung (kibot, alle 5 min):
  */5 * * * *  /usr/bin/python3 /opt/flipsibridge/tools/relay_botnet_watch.py
oder als systemd-Service+Timer (siehe contrib/flipsibridge-botnet-watch.service/.timer).
"""

import os
import re
import subprocess
import sys
import time
from typing import Iterable, Optional

# --- ENV-Konfiguration (Defaults bewusst generisch; Live-Werte via env) ---
DEFAULT_WINDOW_MIN = int(os.environ.get("RELAY_BOTNET_WINDOW_MIN", "60"))
DEFAULT_IP_THRESHOLD = int(os.environ.get("RELAY_BOTNET_IPS", "10"))
DEFAULT_FAIL_THRESHOLD = int(os.environ.get("RELAY_BOTNET_FAILS", "120"))
DEFAULT_COOLDOWN_MIN = int(os.environ.get("RELAY_BOTNET_COOLDOWN_MIN", "30"))
DEFAULT_UNIT = os.environ.get("RELAY_BOTNET_UNIT", "flipsibridge-relay")
DEFAULT_NOTIFY = os.environ.get("RELAY_BOTNET_NOTIFY", "")  # Pflicht-ENV (kein Instanz-Default)
DEFAULT_STATE = os.environ.get(
    "RELAY_BOTNET_STATE",
    "/var/lib/flipsibridge/botnet-watch-state.json")

# Auth-Fehler-Zeilen des Relays (alle Varianten, siehe android_relay.py):
#   "HTTP /chat rejected — bad auth from <ip>"
#   "Phone WS rejected — bad token (got ****) from <ip>"
#   "Agent WS rejected — bad agent token from <ip>"
#   "APK latest rejected — bad auth from <ip>"  (usw. — einheitliches Muster)
_RE_REJECTED_IP = re.compile(r"rejected .*? from (\d{1,3}(?:\.\d{1,3}){3})")
# "IP <ip> blocked for 300s after 5 failed auth attempts" (Ereignis zählt
# als EIN Auth-Fehler-Komplex je IP — nicht je Fehlversuch — sonst bläht ein
# Block-Ereignis die Gesamtzahl künstlich auf.)
_RE_BLOCKED_IP = re.compile(
    r"IP (\d{1,3}(?:\.\d{1,3}){3}) blocked for \d+s")


def analyze_lines(lines: Iterable[str]) -> tuple[int, int, set]:
    """Journal-Zeilen → (Fehler-Gesamtzahl, DISTINCT-IP-Zahl, IP-Menge).

    Reine Funktion (Tests füttern Beispiel-Zeilen): zählt Auth-Fehler je
    'rejected ... from <ip>'-Zeile und sammelt die Quell-IPs. Block- und
    429-Zeilen fließen NICHT in die Gesamtzahl ein (sie sind Folgen, keine
    neuen Versuche), aber deren IP zählt zur Botnet-Verteilung.
    """
    total = 0
    ips: set = set()
    for line in lines:
        m = _RE_REJECTED_IP.search(line)
        if m:
            total += 1
            ips.add(m.group(1))
            continue
        m = _RE_BLOCKED_IP.search(line)
        if m:
            ips.add(m.group(1))
    return total, len(ips), ips


def read_journal(unit: str, window_min: int) -> list:
    """journalctl seit window_min Minuten lesen (leer bei Fehler — ein
    Wächter, der sein eigenes Log-Problem zur Alarmmeldung macht, wäre
    selbst Lärm)."""
    try:
        out = subprocess.run(
            ["journalctl", "-u", unit, "--since", f"-{window_min} min",
             "--no-pager", "-q"],
            capture_output=True, text=True, timeout=60, check=False)
    except (OSError, subprocess.SubprocessError):
        return []
    return out.stdout.splitlines() if out.returncode == 0 else []


def cooldown_active(state_path: str, now: Optional[float] = None) -> bool:
    """True, solange der Alarm-Cooldown läuft (State-Datei hält letzte
    Alarmzeit; beschädigte/fehlende Datei = kein Cooldown = alarmierbar)."""
    try:
        import json
        with open(state_path, "r", encoding="utf-8") as fh:
            last = float(json.load(fh).get("last_alarm_ts", 0))
    except (OSError, ValueError, TypeError):
        return False
    now = time.time() if now is None else now
    return (now - last) < DEFAULT_COOLDOWN_MIN * 60


def write_state(state_path: str, now: Optional[float] = None) -> None:
    """Alarm-Zeitstempel persistieren (best-effort — ein unwritables
    State-File darf den Wächter nie werfen)."""
    try:
        import json
        os.makedirs(os.path.dirname(state_path), exist_ok=True)
        with open(state_path, "w", encoding="utf-8") as fh:
            json.dump({"last_alarm_ts": time.time() if now is None else now},
                      fh)
    except OSError:
        pass


def send_alarm(notify_path: str, title: str, message: str) -> bool:
    """Alarm über das Notify-Skript zustellen (best-effort, nie werfend)."""
    if not notify_path or not os.path.exists(notify_path):
        return False
    try:
        return subprocess.run(
            [notify_path, title, message, "warn"],
            timeout=30, check=False).returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def main() -> int:
    lines = read_journal(DEFAULT_UNIT, DEFAULT_WINDOW_MIN)
    total, ip_count, ips = analyze_lines(lines)
    botnet = ip_count > DEFAULT_IP_THRESHOLD
    sturm = total > DEFAULT_FAIL_THRESHOLD
    if not (botnet or sturm):
        return 0  # Alles OK: still (kein Output, keine Meldung)
    if cooldown_active(DEFAULT_STATE):
        return 0  # Dauer-Alarmquelle im Cooldown — still
    reason = []
    if botnet:
        reason.append(
            f"{ip_count} DISTINCT IPs mit Auth-Fehlern im "
            f"{DEFAULT_WINDOW_MIN}-min-Fenster (Schwelle {DEFAULT_IP_THRESHOLD})")
    if sturm:
        reason.append(
            f"{total} Auth-Fehler gesamt im {DEFAULT_WINDOW_MIN}-min-Fenster "
            f"(Schwelle {DEFAULT_FAIL_THRESHOLD})")
    beispiel = ", ".join(sorted(ips)[:5]) or "unbekannt"
    title = "⚠️ FlipsiBridge-Relay: Botnet-/Brute-Force-Alarm"
    message = (" + ".join(reason)
               + f" — Beispiel-IPs: {beispiel}"
               + " — Relay-IP-Block (5 Fails/300 s) läuft weiter; "
               "Handlung nur bei echter Masse nötig.")
    send_alarm(DEFAULT_NOTIFY, title, message)
    write_state(DEFAULT_STATE)
    return 0  # Alarm-Zustellung ist best-effort; der Wächter bleibt nie rot


if __name__ == "__main__":
    sys.exit(main())