#!/usr/bin/env python3
"""OAuth (Benutzer-Token) für die Play-Route — kein Service-Account nötig.

Verwendet /root/.secrets/play-oauth-client.json (client_id + client_secret),
das der Agent aus der Cloud-Console liest. Die Einwilligung läuft im
PC-Chrome; der ?code= wird vom lokalen Listener unter 8765 gefangen.

Aufruf:
  python3 play/oauth_flow.py url        -> gibt die Einwilligungs-URL aus
  python3 play/oauth_flow.py listen     -> startet den Listener (bis Code da)
  python3 play/oauth_flow.py exchange   -> tauscht Code gegen Tokens (Datei)
Deutsche Kommentare (Sir-Regel), Werte NIE im Chat.
"""
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlencode, urlparse

import requests

CLIENT_FILE = "/root/.secrets/play-oauth-client.json"
TOKEN_FILE = "/root/.secrets/play-oauth.json"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
REDIRECT_URI = "http://127.0.0.1:8765/"

def _client():
    d = json.load(open(CLIENT_FILE))
    return d["client_id"], d["client_secret"]

def build_url():
    cid, _ = _client()
    q = urlencode({
        "client_id": cid,
        "redirect_uri": REDIRECT_URI,
        "response_type": "code",
        "scope": SCOPE,
        "access_type": "offline",
        "prompt": "consent",
    })
    return "https://accounts.google.com/o/oauth2/v2/auth?" + q

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        qs = parse_qs(urlparse(self.path).query)
        code = qs.get("code", [None])[0]
        body = ("Code empfangen — Jarvis." if code else "KEIN Code im Redirect.")
        try:
            f = open("/tmp/play-oauth-code.txt", "w")
            f.write(code or "")
            f.close()
        except Exception:
            pass
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(body.encode("utf-8"))

    def log_message(self, *args):
        pass

def listen(timeout_s=420):
    srv = HTTPServer(("0.0.0.0", 8765), Handler)
    srv.timeout = 5
    ende = time.time() + timeout_s
    print("Listener auf 8765 aktiv…", flush=True)
    while time.time() < ende:
        srv.handle_request()
        if os.path.exists("/tmp/play-oauth-code.txt"):
            print("CODE FANGEN.", flush=True)
            return
    print("TIMEOUT ohne Code.", flush=True)

def exchange():
    cid, secret = _client()
    code = open("/tmp/play-oauth-code.txt").read().strip()
    if not code:
        print("Kein Code gefangen.")
        return
    r = requests.post(
        "https://oauth2.googleapis.com/token",
        data={
            "code": code,
            "client_id": cid,
            "client_secret": secret,
            "redirect_uri": REDIRECT_URI,
            "grant_type": "authorization_code",
        },
        timeout=30,
    )
    d = r.json()
    if "refresh_token" in d:
        os.chmod(TOKEN_FILE, 0o600) if os.path.exists(TOKEN_FILE) else None
        with open(TOKEN_FILE, "w") as f:
            f.write(json.dumps({"refresh_token": d["refresh_token"],
                                "access_token": d.get("access_token", ""),
                                "expires_in": d.get("expires_in", 0),
                                "client_id": cid,
                                "client_secret": secret}))
        os.chmod(TOKEN_FILE, 0o600)
        print("TOKENS GESICHERT (0600). Refresh-Token-Länge:", len(d["refresh_token"]))
    else:
        print("Fehler:", json.dumps(d)[:200])

if __name__ == "__main__":
    m = sys.argv[1] if len(sys.argv) > 1 else "url"
    if m == "url":
        print(build_url())
    elif m == "listen":
        try:
            os.remove("/tmp/play-oauth-code.txt")
        except FileNotFoundError:
            pass
        listen()
    elif m == "exchange":
        exchange()
