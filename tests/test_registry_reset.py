"""P44 (17.11.): Registry-Reset-Tool (tools/registry_reset.py) — expliziter
Admin-Reset der mTLS-Geräte-Registry: Backupt ZEIT-gestempelt, schreibt
leere Registry mit Epoch-Bump, KEIN Auto-Weg / KEINE Route
(--yes-Pflicht; ohne --yes bleibt der Stand unberührt).

B2 (Multi-Gerät): Komplett-Reset schreibt die leere Registry im v2-Format
({"v": 2, "epoch": n, "devices": {}}); --device entfernt GENAU EINEN
Eintrag (je Gerät) und erhält das Bestandsformat (v1 bleibt v1, v2 bleibt v2).
Legacy-v1-Erwartungen der P44-Ära sind auf v2 gebogen (Schema-Welle)."""
import json
import os
import subprocess
import sys

from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
TOOL = REPO_ROOT / "tools" / "registry_reset.py"


def _run(cli_args, monkeypatch, cwd=REPO_ROOT, env_epoch=None, drop_tls_env=True):
    env = os.environ.copy()
    if drop_tls_env:
        env.pop("ANDROID_RELAY_TLS_DIR", None)
    env.pop("ANDROID_MTLS_EPOCH", None)
    if env_epoch is not None:
        env["ANDROID_MTLS_EPOCH"] = str(env_epoch)
    proc = subprocess.run(
        [sys.executable, str(TOOL), *cli_args],
        cwd=str(cwd), env=env, capture_output=True, text=True, timeout=60,
    )
    # Env-Verschmutzung über die Testprozessgrenze ausschließen (das Tool
    # setzt ANDROID_RELAY_TLS_DIR nur in SEINEM eigenen Prozess).
    monkeypatch.delenv("ANDROID_RELAY_TLS_DIR", raising=False)
    return proc


def _seed_registry(tls_dir: Path, epoch=2, devices=("drill_a", "drill_b")):
    reg = tls_dir / "mtls_devices.json"
    reg.write_text(json.dumps(
        {"epoch": epoch, "enrolled": {d: {"cn": f"flipsibridge-{d}", "ts": 1}
                                       for d in devices}}), encoding="utf-8")
    return reg


def test_default_rejects_without_explicit_yes(monkeypatch, tmp_path):
    """Ohne --yes: nichts geändert (Exit 2, kein Backup, Registry byte-stabil)."""
    d = tmp_path / "tls"
    d.mkdir()
    reg = _seed_registry(d, epoch=5)
    before = reg.read_bytes()
    proc = _run(["--tls-dir", str(d)], monkeypatch)
    assert proc.returncode == 2, proc.stdout
    assert "nichts geändert" in proc.stdout.lower() or "NICHTS geändert" in proc.stdout
    assert reg.read_bytes() == before, "ohne --yes darf nichts geschrieben werden"
    assert not list(d.glob("*.bak-*")), "ohne --yes kein Backup"


def test_reset_backups_with_timestamp_and_empties(monkeypatch, tmp_path):
    """Mit --yes: ZEIT-stempel-Backup (byte-genaues Original) + leere Registry
    mit Epoch+1; Zert-Dateien bleiben unberührt."""
    d = tmp_path / "tls"
    (d / "devices").mkdir(parents=True)
    cert = d / "devices" / "drill_a.pem"
    cert.write_bytes(b"-----BEGIN CERTIFICATE-----drill-----")
    reg = _seed_registry(d, epoch=5, devices=("drill_a", "drill_b"))
    original = reg.read_bytes()

    proc = _run(["--tls-dir", str(d), "--yes"], monkeypatch)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    backups = list(d.glob("mtls_devices.json.bak-*"))
    assert len(backups) == 1 and backups[0].read_bytes() == original, \
        "byte-genaues, zeitgestempeltes Backup"
    new = json.loads(reg.read_text(encoding="utf-8"))
    assert new == {"v": 2, "epoch": 6, "devices": {}}, f"leere v2-Registry +1: {new}"
    assert cert.read_bytes().startswith(b"-----BEGIN CERTIFICATE"), "Zert bleibt erhalten"
    assert "drill_a" in proc.stdout and "drill_b" in proc.stdout, "Bericht nennt geräumte Geräte"


def test_reset_missing_registry_uses_env_epoch_base(monkeypatch, tmp_path):
    """Fehlende Datei = frische Registry: Basis-Epoch aus env
    ANDROID_MTLS_EPOCH (Parität zu mtls_enroll._registry_default), dann +1;
    kein Backup (nichts zu sichern)."""
    d = tmp_path / "tls"
    d.mkdir()
    proc = _run(["--tls-dir", str(d), "--yes"], monkeypatch, env_epoch=7)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    reg = d / "mtls_devices.json"
    assert json.loads(reg.read_text(encoding="utf-8")) == {
        "v": 2, "epoch": 8, "devices": {}}
    assert not list(d.glob("*.bak-*"))


def test_reset_repairs_corrupt_registry(monkeypatch, tmp_path):
    """Unlesbare Datei = Reparatur-Fall: bytes-genaues Backup + leerer Stand (v2)."""
    d = tmp_path / "tls"
    d.mkdir()
    reg = d / "mtls_devices.json"
    broken = b'{"epoch": 5, "enrolled": {"devOld1": {"cn": "flipsibridge-devOld1"'
    reg.write_bytes(broken)

    proc = _run(["--tls-dir", str(d), "--yes"], monkeypatch)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    backups = list(d.glob("mtls_devices.json.bak-*"))
    assert len(backups) == 1 and backups[0].read_bytes() == broken
    assert json.loads(reg.read_text(encoding="utf-8")) == {
        "v": 2, "epoch": 2, "devices": {}}


def test_reset_epoch_override_respected(monkeypatch, tmp_path):
    d = tmp_path / "tls"
    d.mkdir()
    _seed_registry(d, epoch=5)
    proc = _run(["--tls-dir", str(d), "--yes", "--epoch", "42"], monkeypatch)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    reg = d / "mtls_devices.json"
    assert json.loads(reg.read_text(encoding="utf-8")) == {
        "v": 2, "epoch": 42, "devices": {}}


def test_reset_per_device_keeps_rest_and_format_v1(monkeypatch, tmp_path):
    """B2 je Gerät: --device entfernt GENAU den einen Eintrag; das übrige
    Format (v1) + Epoch + die REST-Geräte bleiben unverändert; Backup entsteht."""
    d = tmp_path / "tls"
    d.mkdir()
    reg = _seed_registry(d, epoch=5, devices=("drill_a", "drill_b"))
    before = json.loads(reg.read_text(encoding="utf-8"))
    proc = _run(["--tls-dir", str(d), "--yes", "--device", "drill_a"], monkeypatch)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert "drill_a" in proc.stdout and "DONE" in proc.stdout
    after = json.loads(reg.read_text(encoding="utf-8"))
    assert set(after["enrolled"]) == {"drill_b"}, "nur drill_a entfernt"
    assert after["epoch"] == 5, "Epoch bleibt bei je-Gerät-Löschung"
    backups = list(d.glob("mtls_devices.json.bak-*"))
    assert len(backups) == 1 and json.loads(backups[0].read_text()) == before


def test_reset_per_device_keeps_rest_and_format_v2(monkeypatch, tmp_path):
    """B2 je Gerät an einer v2-Registry: 'devices'-Form bleibt, Rest bleibt."""
    d = tmp_path / "tls"
    d.mkdir()
    row = {"cn": "flipsibridge-drill_a", "enrolled_at": 17, "epoch_of_enroll": 2}
    reg = d / "mtls_devices.json"
    reg.write_text(json.dumps(
        {"v": 2, "epoch": 3,
         "devices": {"drill_a": row,
                     "drill_b": {"cn": "flipsibridge-drill_b",
                                 "enrolled_at": 18, "epoch_of_enroll": 3}}}),
        encoding="utf-8")
    proc = _run(["--tls-dir", str(d), "--yes", "--device", "drill_b"], monkeypatch)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    after = json.loads(reg.read_text(encoding="utf-8"))
    assert after["v"] == 2 and after["epoch"] == 3
    assert set(after["devices"]) == {"drill_a"}
    assert after["devices"]["drill_a"] == row, "Rest-Zeile byte-honest"


def test_reset_per_device_unknown_id_reports_without_damage(monkeypatch, tmp_path):
    d = tmp_path / "tls"
    d.mkdir()
    reg = _seed_registry(d, epoch=9, devices=("drill_a",))
    before = reg.read_text(encoding="utf-8")
    proc = _run(["--tls-dir", str(d), "--yes", "--device", "nicht_da"], monkeypatch)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert "KEIN-TREFFER" in proc.stdout
    after = json.loads(reg.read_text(encoding="utf-8"))
    assert set(after["enrolled"]) == {"drill_a"}, "kein Kollateral"
    assert json.loads(reg.read_text(encoding="utf-8"))["epoch"] == 9


def test_reset_per_device_missing_registry_is_honest_noop(monkeypatch, tmp_path):
    d = tmp_path / "tls"
    d.mkdir()
    proc = _run(["--tls-dir", str(d), "--yes", "--device", "drill_x"], monkeypatch)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert not (d / "mtls_devices.json").exists(), "nichts erstellt"
    assert "nichts zu entfernen" in proc.stdout


def test_flat_import_from_any_cwd(monkeypatch, tmp_path):
    """Auch ohne Repo-Root als cwd lauffähig (Skript-Verzeichnis liegt auf
    sys.path → Flat-Import von mtls_enroll), Parität zum Relay-Weg."""
    d = tmp_path / "tls"
    d.mkdir()
    _seed_registry(d, epoch=1, devices=("drill_c",))
    elsewhere = tmp_path / "anderswo"
    elsewhere.mkdir()
    proc = _run(["--tls-dir", str(d), "--yes"], monkeypatch, cwd=elsewhere)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert json.loads((d / "mtls_devices.json").read_text(encoding="utf-8")) \
        == {"v": 2, "epoch": 2, "devices": {}}
