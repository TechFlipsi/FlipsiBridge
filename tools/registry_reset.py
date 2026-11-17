#!/usr/bin/env python3
"""P44 (17.11.): EXPLIZITER Registry-Reset für die mTLS-Geräte-Registry.

Wozu: Die Test-Generation (Emulator-Drills) hat Geräteeinträge in
mtls_devices.json hinterlassen. Damit der ERSTE echte Enrollment-Durchgang
(nach einem bewussten Admin-Reset) sauber anfängt, stellt dieses Tool die
Registry auf leer — bewusst NICHT automatisch und bewusst KEINE HTTP-Route
(kein Bearer, kein Enroll-Pfad): Reset ist eine Handaktion des Admins am Host.

Aufruf (bestätigungspflichtig — ohne --yes wird nur geplant, nichts geändert):
    python3 tools/registry_reset.py [--tls-dir /etc/flipsibridge/tls] --yes

Ablauf:
  1. Registry lesen (mtls_devices.json im TLS-Verzeichnis, env
     ANDROID_RELAY_TLS_DIR oder /etc/flipsibridge/tls).
  2. Byte-genaues Backup unter Zeitstempel schreiben:
     mtls_devices.json.bak-<YYYYmmdd-HHMMSS>
  3. Leere Registry {"epoch": alt+1, "enrolled": {}} ATOMAR schreiben
     (tmp + os.replace, Format-PARITÄT zu tools/mtls_enroll._registry_save).
     Epoch alt+1 ist sicherer Default: Alt-Code-/Alt-Epochen-Geräte bleiben
     am Gate blockiert (_mtls_gate P43-Check), selbst wenn sie ihr Zert noch
     in Händen halten — --epoch übersteuert (Admin entscheidet).

Zert-Dateien unter devices/ werden NICHT gelöscht (Nachvollziehbarkeit,
Rollback über das Backup); nach dem Reset gilt keines davon mehr am Gate
(CN fehlt der Registry). Räumung bleibt explizit am Admin.

Eine unlesbare/schema-beschädigte Datei ist ausdrücklich ein Reparatur-Fall:
sie wird genauso gebackupt und resettet (mit Warnung).

WICHTIG: Läuft das Relay gegen dasselbe TLS-Verzeichnis, kann ein Enroll
MITTEN im Reset den Stand neu beschreiben (Datei-Rennen). Empfehlung: Relay
anhalten oder einen Moment ohne Enrollment wählen.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

# Dual-Import: Repo-Root-Paket ODER Flat (aus tools/ gestartet) — Parität zu
# android_relay.py, damit der Tool-Weg überall läuft, wo der Relay läuft.
try:
    from tools.mtls_enroll import _registry_default, _tls_paths
except ImportError:
    from mtls_enroll import _registry_default, _tls_paths  # type: ignore[no-redef]


_TOOL_NAME = "registry_reset"


def _backup_path(registry: Path) -> Path:
    stamp = time.strftime("%Y%m%d-%H%M%S")
    return registry.with_name(f"{registry.name}.bak-{stamp}")


def _read_legacy(registry: Path):
    """Byte-genau lesen + best-effort parse (NUR fürs Reporting; beschädigte
    Dateien werden bewusst TROTZDEM gebackupt + resettet = Reparatur-Weg).
    Rückgabe (raw, epoch, device_ids); epoch None = unlesbar."""
    raw = registry.read_bytes()
    try:
        data = json.loads(raw)
        try:
            epoch = int(data.get("epoch")) if isinstance(data, dict) else None
        except (TypeError, ValueError):
            epoch = None
        enrolled = (data.get("enrolled") or {}) if isinstance(data, dict) else {}
        return raw, epoch, [str(k) for k in enrolled.keys()]
    except Exception:
        return raw, None, []


def plan(tls_dir: str) -> dict:
    """Was würde passieren? (ohne Schreiben — Grundlage für --yes-Frage)."""
    # _tls_paths() liest das Env LAZY je Aufruf — das Tool setzt es für seinen
    # eigenen Prozess; das CLI ist kurzlebig, kein Seiten-Effekt auf Dritte.
    os.environ["ANDROID_RELAY_TLS_DIR"] = tls_dir
    _tls_dir_, _ck, _cc, registry, devices_sub = _tls_paths()
    device_ids = []
    epoch = None
    raw = b""
    exists = registry.exists()
    if exists:
        raw, epoch, device_ids = _read_legacy(registry)
    certs = sorted(p.name for p in devices_sub.glob("*.pem")) if devices_sub.exists() else []
    unlesbar = bool(exists and epoch is None)
    return {
        "registry": str(registry),
        "exists": exists,
        "bytes": len(raw),
        "epoch": epoch,
        "devices": device_ids,
        "device_count": len(device_ids),
        "cert_files": certs,
        "cert_count": len(certs),
        "backup": str(_backup_path(registry)),
        "unlesbar": unlesbar,
    }


def _target_epoch(info: dict, override: int | None) -> int:
    if override is not None:
        return int(override)
    if info["exists"] and isinstance(info["epoch"], int):
        return info["epoch"] + 1
    if info["exists"] and info["epoch"] is None:
        # Unlesbar: Alt-Epoch unbekannt → kleinster sauberer Stand (2),
        # Alt-Einträge sind ohnehin verloren/repariert.
        return 2
    # Fehlende Datei = frische Registry; Basis-Epoch aus Env (Parität zu
    # mtls_enroll._registry_default), +1 öffnet das Code-Fenster neu.
    return int((_registry_default() or {}).get("epoch", 1)) + 1


def reset_registry(tls_dir: str, epoch: int | None) -> dict:
    """Reset ausführen: Backup + leere Registry (atomar). Rückgabe = Bericht."""
    info = plan(tls_dir)
    registry = Path(info["registry"])
    backup = None
    if info["exists"]:
        backup = _backup_path(registry)
        # Byte-genaues Original — frisch vom Datenträger (plan() trägt nur die
        # Länge als Berichtswert):
        backup.write_bytes(registry.read_bytes())
    target = _target_epoch(info, epoch)
    new_reg = {"epoch": target, "enrolled": {}}
    tmp = registry.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(new_reg, indent=1, ensure_ascii=False), encoding="utf-8")
    os.replace(tmp, registry)
    info.update({"backup_written": str(backup) if backup else None,
                 "target_epoch": target,
                 "new_registry": new_reg})
    return info


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        prog=_TOOL_NAME,
        description="P44: mTLS-Geräte-Registry EXPLIZIT leeren (Backup + leerer Stand) "
                    "— kein Auto-Weg, keine Route, bestätigungspflichtig.",
    )
    ap.add_argument("--tls-dir", default=os.getenv("ANDROID_RELAY_TLS_DIR", "/etc/flipsibridge/tls"),
                    help="TLS-Verzeichnis (env ANDROID_RELAY_TLS_DIR)")
    ap.add_argument("--epoch", type=int, default=None,
                    help="Neue Epoch (Default: alt+1 — Alt-Epochen-Geräte bleiben am Gate blockiert)")
    ap.add_argument("--yes", action="store_true",
                    help="RESET AUSFÜHREN (bewusst explizit — ohne --yes wird nur geplant)")
    args = ap.parse_args(argv)

    try:
        info = plan(args.tls_dir)
    except OSError as exc:
        print(f"FEHLER: TLS-Verzeichnis unlesbar: {exc}", file=sys.stderr)
        return 3

    print(f"Registry   : {info['registry']}")
    print(f"Status     : {'vorhanden (' + str(info['bytes']) + ' B)' if info['exists'] else 'FEHLT (frische Registry)'}")
    if info["exists"]:
        print(f"Epoch      : {info['epoch'] if info['epoch'] is not None else 'UNLESBAR (schema-beschädigt)'}")
        print(f"Geräte     : {info['device_count']} Eintrag(e) {info['devices']}")
    print(f"Zertdateien: {info['cert_count']} unter devices/ (bleiben ERHALTEN)")
    print(f"Backup     : {info['backup']}")
    target = _target_epoch(info, args.epoch)
    print(f"Ziel       : leere Registry mit Epoch {target}")

    if not args.yes:
        print("\n[--yes fehlt] NICHTS geändert — trocken geplant.")
        return 2

    try:
        result = reset_registry(args.tls_dir, args.epoch)
    except OSError as exc:
        print(f"FEHLER: Reset abgebrochen (Registry-Pfad unberührt): {exc}", file=sys.stderr)
        return 3
    print(f"\nDONE: leere Registry geschrieben (Epoch {result['target_epoch']})")
    if result.get("backup_written"):
        print(f"Backup : {result['backup_written']}")
    print("Hinweis: Zert-Dateien unter devices/ wurden NICHT gelöscht; sie gelten")
    print("am Gate NICHT mehr (CN fehlt der Registry). Das Relay startet NICHT neu —")
    print("die Registry wird je Read frisch geladen; Enrollment am laufenden Relay")
    print("während des Resets vermeiden (Datei-Rennen).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
