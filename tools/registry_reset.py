#!/usr/bin/env python3
"""P44 (17.11.): EXPLIZITER Registry-Reset für die mTLS-Geräte-Registry.

Wozu: Die Test-Generation (Emulator-Drills) hat Geräteeinträge in
mtls_devices.json hinterlassen. Damit der ERSTE echte Enrollment-Durchgang
(nach einem bewussten Admin-Reset) sauber anfängt, stellt dieses Tool die
Registry auf leer — bewusst NICHT automatisch und bewusst KEINE HTTP-Route
(kein Bearer, kein Enroll-Pfad): Reset ist eine Handaktion des Admins am Host.

B2 (Multi-Gerät/17.11.): Die Registry ist seit der Key-Registry-Welle v2
({"v": 2, "epoch": n, "devices": {device_id: entry}}); Legacy v1
({"epoch": n, "enrolled": {...}}) bleibt LESEKOMPATIBEL für beide Wege:
  * Komplett-Reset (ohne --device): schreibt eine leere v2-Registry (Neuformat,
    epoch+1 bzw. --epoch; Legacy-Bytes liegen vorher im Backup).
  * --device ID: entfernt GENAU EINEN Geräte-Eintrag (je Gerät) — das übrige
    Format der Datei bleibt unverändert (v1 bleibt v1, v2 bleibt v2).

Aufruf (bestätigungspflichtig — ohne --yes wird nur geplant, nichts geändert):
    python3 tools/registry_reset.py [--tls-dir /etc/flipsibridge/tls] [--device ID] --yes

Ablauf:
  1. Registry lesen (mtls_devices.json im TLS-Verzeichnis, env
     ANDROID_RELAY_TLS_DIR oder /etc/flipsibridge/tls).
  2. Byte-genaues Backup unter Zeitstempel schreiben:
     mtls_devices.json.bak-<YYYYmmdd-HHMMSS>
  3. Leere Registry {"v": 2, "epoch": alt+1, "devices": {}} ATOMAR schreiben
     (tmp + os.replace, Format-PARITÄT zu tools/mtls_enroll._registry_save;
     --device-Wege erhalten das Bestandsformat).
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
    Rückgabe (raw, epoch, device_ids, raw_dict, format): format = v1|v2|broken;
    epoch None = unlesbar."""
    raw = registry.read_bytes()
    try:
        data = json.loads(raw)
    except Exception:
        return raw, None, [], None, "broken"
    if not isinstance(data, dict):
        return raw, None, [], None, "broken"
    try:
        epoch = int(data.get("epoch"))
    except (TypeError, ValueError):
        epoch = None
    devices = data.get("devices") if isinstance(data.get("devices"), dict) else None
    if devices is None:
        enrolled = data.get("enrolled")
        devices = enrolled if isinstance(enrolled, dict) else {}
    fmt = "v2" if data.get("v") == 2 and isinstance(data.get("devices"), dict) else "v1"
    return raw, epoch, [str(k) for k in devices.keys()], data, fmt


def plan(tls_dir: str) -> dict:
    """Was würde passieren? (ohne Schreiben — Grundlage für --yes-Frage)."""
    # _tls_paths() liest das Env LAZY je Aufruf — das Tool setzt es für seinen
    # eigenen Prozess; das CLI ist kurzlebig, kein Seiten-Effekt auf Dritte.
    os.environ["ANDROID_RELAY_TLS_DIR"] = tls_dir
    _tls_dir_, _ck, _cc, registry, devices_sub = _tls_paths()
    device_ids = []
    epoch = None
    raw = b""
    fmt = "v1"
    exists = registry.exists()
    if exists:
        raw, epoch, device_ids, _data, fmt = _read_legacy(registry)
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
        "format": fmt,
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


def reset_registry(tls_dir: str, epoch: int | None,
                   device: str | None = None) -> dict:
    """Reset ausführen: Backup + leere v2-Registry (atomar) ODER — mit
    --device — genau EINEN Geräte-Eintrag entfernen (Bestandsformat bleibt).
    Rückgabe = Bericht."""
    info = plan(tls_dir)
    registry = Path(info["registry"])
    backup = None
    if info["exists"]:
        backup = _backup_path(registry)
        # Byte-genaues Original — frisch vom Datenträger (plan() trägt nur die
        # Länge als Berichtswert):
        backup.write_bytes(registry.read_bytes())
    if device is not None:
        if not info["exists"]:
            info.update({"mode": "device", "target_device": device,
                         "device_removed": False,
                         "note": "Registry fehlt — kein Eintrag zu entfernen"})
            return info
        _raw, _epoch, _ids, data, fmt = _read_legacy(registry)
        if data is None:
            info.update({"mode": "device", "target_device": device,
                         "device_removed": False,
                         "note": "Registry unlesbar — je-Gerät-Weg verweigert "
                                 "(Reparatur über Komplett-Reset)"})
            return info
        devices = data.get("devices") if isinstance(data.get("devices"), dict) else None
        if devices is None:
            enrolled = data.get("enrolled")
            devices = enrolled if isinstance(enrolled, dict) else {}
            if data.get("v") == 2 and isinstance(data.get("devices"), dict):
                fmt = "v2"
        removed = devices.pop(device, None)
        # je-Gerät-Weg: Epoch bleibt BESTANDS-Epoch; fehlt sie (defekte Meta-
        # Zeile), greift der Basis-Epoch-Default; --epoch übersteuert.
        if epoch is not None:
            data["epoch"] = int(epoch)
        elif not isinstance(data.get("epoch"), int):
            data["epoch"] = int((_registry_default() or {}).get("epoch", 1))
        if fmt == "v2":
            data["devices"] = devices
        else:
            data["enrolled"] = devices
        tmp = registry.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(data, indent=1, ensure_ascii=False),
                       encoding="utf-8")
        os.replace(tmp, registry)
        info.update({"mode": "device", "target_device": device,
                     "device_removed": removed is not None,
                     "device_row": removed,
                     "format": fmt,
                     "new_registry": data,
                     "target_epoch": int(data.get("epoch") or 0),
                     "backup_written": str(backup) if backup else None})
        return info
    target = _target_epoch(info, epoch)
    new_reg = {"v": 2, "epoch": target, "devices": {}}
    tmp = registry.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(new_reg, indent=1, ensure_ascii=False), encoding="utf-8")
    os.replace(tmp, registry)
    info.update({"mode": "full",
                 "backup_written": str(backup) if backup else None,
                 "target_epoch": target,
                 "new_registry": new_reg,
                 "format": "v2"})
    return info


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        prog=_TOOL_NAME,
        description="P44: mTLS-Geräte-Registry EXPLIZIT leeren (Backup + leerer Stand) "
                    "oder — mit --device — GENAU EINEN Geräte-Eintrag entfernen; "
                    "kein Auto-Weg, keine Route, bestätigungspflichtig.",
    )
    ap.add_argument("--tls-dir", default=os.getenv("ANDROID_RELAY_TLS_DIR", "/etc/flipsibridge/tls"),
                    help="TLS-Verzeichnis (env ANDROID_RELAY_TLS_DIR)")
    ap.add_argument("--device", default=None,
                    help="B2: GENAU DIESER Geräte-Eintrag wird entfernt (je Gerät); "
                         "ohne --device: Komplett-Reset (leere v2-Registry)")
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

    print(f"Registry   : {info['registry']} (Format: {info.get('format')})")
    print(f"Status     : {'vorhanden (' + str(info['bytes']) + ' B)' if info['exists'] else 'FEHLT (frische Registry)'}")
    if info["exists"]:
        print(f"Epoch      : {info['epoch'] if info['epoch'] is not None else 'UNLESBAR (schema-beschädigt)'}")
        print(f"Geräte     : {info['device_count']} Eintrag(e) {info['devices']}")
    print(f"Zertdateien: {info['cert_count']} unter devices/ (bleiben ERHALTEN)")
    print(f"Backup     : {info['backup']}")
    if args.device is not None:
        in_registry = args.device in info["devices"]
        target = (f"Eintrag {args.device} entfernen ({'vorhanden' if in_registry else 'NICHT vorhanden'})"
                  if info["exists"] else "Registry fehlt — nichts zu entfernen")
        print(f"Ziel       : {target}")
    else:
        target = _target_epoch(info, args.epoch)
        print(f"Ziel       : leere v2-Registry mit Epoch {target}")

    if not args.yes:
        print("\n[--yes fehlt] NICHTS geändert — trocken geplant.")
        return 2

    try:
        result = reset_registry(args.tls_dir, args.epoch, device=args.device)
    except OSError as exc:
        print(f"FEHLER: Reset abgebrochen (Registry-Pfad unberührt): {exc}", file=sys.stderr)
        return 3
    if result.get("mode") == "device":
        if result.get("device_removed"):
            print(f"\nDONE: Eintrag {result['target_device']} entfernt "
                  f"(Format {result.get('format')} erhalten, Epoch {result['target_epoch']})")
        else:
            print(f"\nDONE-KEIN-TREFFER: {result.get('target_device')} war nicht eingetragen "
                  f"(Registry unverändert außer ggf. Epoch)") 
        if result.get("backup_written"):
            print(f"Backup : {result['backup_written']}")
        print("Hinweis: Zert-Dateien unter devices/ wurden NICHT gelöscht (auch das "
              "des entfernten Geräts nicht); am Gate gilt der CN ab sofort NICHT mehr.")
        return 0
    print(f"\nDONE: leere v2-Registry geschrieben (Epoch {result['target_epoch']})")
    if result.get("backup_written"):
        print(f"Backup : {result['backup_written']}")
    print("Hinweis: Zert-Dateien unter devices/ wurden NICHT gelöscht; sie gelten")
    print("am Gate NICHT mehr (CN fehlt der Registry). Das Relay startet NICHT neu —")
    print("die Registry wird je Read frisch geladen; Enrollment am laufenden Relay")
    print("während des Resets vermeiden (Datei-Rennen).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
