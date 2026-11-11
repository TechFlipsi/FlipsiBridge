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

PACKAGE = "at.flipsi.bridge.play"
JSON_PATH = "/root/.secrets/play-service-account.json"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
TRACK = "internal"  # Interner Test-Track


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
                        "name": f"0.15.2 Komposer und Überspringen ({version})",
                        "versionCodes": [str(version)],
                        "status": "completed",
                        "releaseNotes": [{"language": "de-DE", "text": "Komposer mit Modell- und Denken-Auswahl; Einrichtungsassistent jetzt überspringbar."}],
                    }
                ],
            },
        ).execute()
        edits.commit(editId=eid, packageName=PACKAGE).execute()
        print(f"OK: {PACKAGE} versionCode {version} → Track '{TRACK}'")
    else:
        print(f"Unbekannter Befehl: {cmd}")
        sys.exit(1)


if __name__ == "__main__":
    main()
