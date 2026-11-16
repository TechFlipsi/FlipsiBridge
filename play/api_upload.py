#!/usr/bin/env python3
"""Play-API-Route für FlipsiBridge (wartet auf den Service-Account-JSON von Sir).

Nutzung (nach Einrichtung in der Play-Console: Setup → API-Zugriff → Cloud-Projekt
verknüpft + Service-Konto mit Rechten versehen; JSON liegt als
/root/.secrets/play-service-account.json, chmod 600 — Werte NIEMALS im Chat):

    python3 /root/FlipsiBridge/play/api_upload.py upload-aab \
        /root/FlipsiBridge/artifacts/flipsibridge-0.15.0-play.aab

Flow: Service-Account-JWT (scope androidpublisher) → edit.insert für die App →
aab upload (internes Test-Track-Release mit Notiz) → Verify-Print.
App-Paket: at.flipsi.bridge.play. Änderungen NUR mit Sirs Freigabe im Chat (FREIGABE).
"""
import sys
import json
import hashlib

PACKAGE = "at.flipsi.bridge.play"
JSON_PATH = "/root/.secrets/play-service-account.json"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
TRACK = "internal"  # Interner Test-Track
LANGS = ["de-DE", "en-US"]


def service():
    """Anmeldeweg automatisch: OAuth-Benutzer-Token vorziehen (Play-Route ohne
    Service-Account-Accept-Falle); SA-JWT = Fallback."""
    from googleapiclient.discovery import build
    oauth_path = "/root/.secrets/play-oauth.json"
    import os

    if os.path.exists(oauth_path):
        from google.oauth2.credentials import Credentials

        d = json.load(open(oauth_path))
        creds = Credentials(
            token=d.get("access_token", None),
            refresh_token=d["refresh_token"],
            token_uri="https://oauth2.googleapis.com/token",
            client_id=d["client_id"],
            client_secret=d["client_secret"],
            scopes=[SCOPE],
        )
        return build("androidpublisher", "v3", credentials=creds)

    from google.oauth2 import service_account

    creds = service_account.Credentials.from_service_account_file(
        JSON_PATH, scopes=[SCOPE]
    )
    return build("androidpublisher", "v3", credentials=creds)


def list_images(api, langs, image_type=None):
    """Bilder je Sprache listen (Anzahl + sha1). Gibt {lang: bilder} zurück."""
    out = {}
    for lang in langs:
        edit = api.edits().insert(body={}, packageName=PACKAGE).execute()
        eid = edit["id"]
        res = (
            api.edits()
            .images()
            .list(
                editId=eid,
                packageName=PACKAGE,
                language=lang,
                imageType=image_type or "phoneScreenshots",
            )
            .execute()
        )
        imgs = res.get("images", [])
        out[lang] = imgs
        print(f"{lang} [{image_type or 'phoneScreenshots'}]: {len(imgs)} Bilder")
        for im in imgs:
            print(f"  id={im.get('id')} sha1={im.get('sha1')}")
        # Listen-Edits nicht committen; verwerfen reicht (kein Cleanup-API nötig)
    return out


def upload_screenshots(api, paths, langs=None):
    """phoneScreenshots je Sprache hochladen, verifizieren (Anzahl+sha1), dann committen."""
    from googleapiclient.http import MediaFileUpload

    langs = langs or LANGS
    edits = api.edits()
    edit = edits.insert(body={}, packageName=PACKAGE).execute()
    eid = edit["id"]

    # Vorhandene phoneScreenshots je Sprache im Edit entfernen (Ersetzungs-Semantik)
    for lang in langs:
        res = (
            edits.images()
            .list(editId=eid, packageName=PACKAGE, language=lang, imageType="phoneScreenshots")
            .execute()
        )
        for im in res.get("images", []):
            edits.images().delete(
                editId=eid,
                packageName=PACKAGE,
                language=lang,
                imageType="phoneScreenshots",
                imageId=im["id"],
            ).execute()
            print(f"{lang}: altes Bild gelöscht id={im['id']} sha1={im.get('sha1')}")

    # Neue Bilder je Sprache hochladen (gleiche Dateien, gleiche Reihenfolge)
    uploaded = {}
    expected_sha1s = {
        p: hashlib.sha1(open(p, "rb").read()).hexdigest() for p in paths
    }
    for lang in langs:
        uploaded[lang] = []
        for p in paths:
            media = MediaFileUpload(p, mimetype="image/png")
            resp = (
                edits.images()
                .upload(
                    editId=eid,
                    packageName=PACKAGE,
                    language=lang,
                    imageType="phoneScreenshots",
                    media_body=media,
                )
                .execute()
            )
            img = resp.get("image", resp)
            uploaded[lang].append(img)
            print(
                f"{lang}: hochgeladen {p.split('/')[-1]} "
                f"sha1={img.get('sha1')} (lokal {expected_sha1s[p]})"
            )

    # Verifikation je Sprache: Anzahl + sha1 gegen lokale Datei-sha1s
    ok = True
    expect_set = set(expected_sha1s.values())
    for lang in langs:
        res = (
            edits.images()
            .list(editId=eid, packageName=PACKAGE, language=lang, imageType="phoneScreenshots")
            .execute()
        )
        imgs = res.get("images", [])
        sha1s = [im.get("sha1") for im in imgs]
        print(f"VERIFY {lang}: {len(imgs)} Bilder, sha1s={sha1s}")
        if len(imgs) != len(paths):
            print(f"FEHLER {lang}: erwartet {len(paths)} Bilder, gefunden {len(imgs)}")
            ok = False
        if set(sha1s) != expect_set:
            print(f"FEHLER {lang}: sha1-Menge weicht von den lokalen Dateien ab")
            ok = False

    if not ok:
        print("ABBRUCH: Edit NICHT committet (Verifikation fehlgeschlagen)")
        sys.exit(2)

    edits.commit(editId=eid, packageName=PACKAGE).execute()
    print(f"OK: {len(paths)} phoneScreenshots je Sprache {langs} committet")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    api = service()
    cmd = sys.argv[1]
    if cmd == "app-info":
        d = api.edits()
        edit = d.insert(body={}, packageName=PACKAGE).execute()
        detail = d.details().get(editId=edit["id"], packageName=PACKAGE).execute()
        print(json.dumps(detail, indent=1, ensure_ascii=False))
    elif cmd == "upload-aab":
        aab = sys.argv[2]
        edits = api.edits()
        edit = edits.insert(body={}, packageName=PACKAGE).execute()
        eid = edit["id"]
        from googleapiclient.http import MediaFileUpload
        media = MediaFileUpload(aab, mimetype="application/octet-stream")
        bundle = edits.bundles().upload(
            editId=eid, packageName=PACKAGE, media_body=media
        ).execute()
        version = bundle["versionCode"]
        edits.tracks().update(
            editId=eid,
            packageName=PACKAGE,
            track=TRACK,
            body={
                "track": TRACK,
                "releases": [
                    {
                        "name": f"0.15.3 Welle 15: Sirs Fixliste ({version})",
                        "versionCodes": [str(version)],
                        "status": "completed",
                        "releaseNotes": [{"language": "de-DE", "text": "Welle 15 — Sirs Fixliste: Chat-Bubbles mit Status-Häkchen (versendet/zugestellt/gelesen), neues Status-Dashboard als Startseite, Sprachwechsel wirkt live, Einstellungen mit Pfad-Anzeige und schlanken Kacheln."}],
                    }
                ],
            },
        ).execute()
        edits.commit(editId=eid, packageName=PACKAGE).execute()
        print(f"OK: {PACKAGE} versionCode {version} → Track '{TRACK}'")
    elif cmd == "list-images":
        langs = sys.argv[2:] or LANGS
        list_images(api, langs)
    elif cmd == "upload-screenshots":
        paths = sys.argv[2:]
        if not paths:
            print("Nutzung: upload-screenshots <png1> <png2> ...")
            sys.exit(1)
        upload_screenshots(api, paths)
    else:
        print(f"Unbekannter Befehl: {cmd}")
        sys.exit(1)


if __name__ == "__main__":
    main()
