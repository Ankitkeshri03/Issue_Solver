#!/usr/bin/env python3
"""Authorize the project sender once and write OAuth credentials to backend/.env.

Uses only Python's standard library. Does not print credentials, send email or deploy.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import tempfile
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import Request, urlopen
import webbrowser

SCOPE = "https://www.googleapis.com/auth/gmail.send"
TOKEN_URL = "https://oauth2.googleapis.com/token"
DEFAULT_ENV = Path(__file__).resolve().parents[1] / ".env"


def write_env(path, updates):
    """Preserve unrelated settings and atomically save with owner-only permissions."""
    path = Path(path).absolute()
    if path.name != ".env" or path.is_symlink():
        raise ValueError("Use a regular file named .env to keep credentials out of Git")
    if any("\n" in value or "\r" in value for value in updates.values()):
        raise ValueError("Configuration values must be single-line strings")
    lines = path.read_text().splitlines() if path.exists() else []
    output, written = [], set()
    for line in lines:
        key = line.partition("=")[0].strip()
        if key in updates:
            if key not in written:
                output.append(f"{key}={updates[key]}")
                written.add(key)
        else:
            output.append(line)
    output += [f"{key}={value}" for key, value in updates.items() if key not in written]
    existing_keys = {line.partition("=")[0].strip() for line in lines if not line.lstrip().startswith("#")}
    if "FRONTEND_URL" not in existing_keys:
        output.append("FRONTEND_URL=http://localhost:5173")
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, prefix=".gmail-setup-", delete=False) as stream:
            temp_path = Path(stream.name)
            os.chmod(temp_path, 0o600)
            stream.write("\n".join(output) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp_path, path)
    finally:
        if temp_path is not None and temp_path.exists():
            temp_path.unlink()


def callback_handler(expected_state, result):
    class Callback(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass  # The URL contains the authorization code. Never log it.

        def do_GET(self):
            parsed = urlsplit(self.path)
            values = parse_qs(parsed.query)
            if parsed.path != "/oauth/callback":
                self.send_error(404)
                return
            if not secrets.compare_digest(values.get("state", [""])[0].encode(), expected_state.encode()):
                self.send_error(400, "Invalid authorization state")
                return
            if "error" in values:
                result["error"] = True
            elif len(values.get("code", [])) == 1 and values["code"][0]:
                result["code"] = values["code"][0]
            else:
                self.send_error(400, "Missing authorization code")
                return
            body = b"Authorization received. Close this tab and return to your terminal."
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
    return Callback


def exchange_code(client, code, redirect_uri, verifier):
    form = {"client_id": client["client_id"], "client_secret": client["client_secret"],
            "code": code, "redirect_uri": redirect_uri, "code_verifier": verifier,
            "grant_type": "authorization_code"}
    request = Request(TOKEN_URL, data=urlencode(form).encode(),
                      headers={"Content-Type": "application/x-www-form-urlencoded"}, method="POST")
    try:
        with urlopen(request, timeout=20) as response:
            tokens = json.load(response)
    except (HTTPError, URLError, TimeoutError, ValueError):
        raise ValueError("Google could not exchange the code. Check the OAuth client and try again.") from None
    refresh = tokens.get("refresh_token")
    if not isinstance(refresh, str) or not refresh:
        raise ValueError("Google did not issue a refresh token. Re-run and grant offline access.")
    if SCOPE not in tokens.get("scope", "").split():
        raise ValueError("Gmail send permission was not granted. Configuration was not changed.")
    return refresh


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client-file", type=Path, required=True, help="Downloaded Google Desktop app OAuth client JSON")
    parser.add_argument("--sender-email", required=True, help="Dedicated Gmail account you will authorize")
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV, help="Destination .env (default: backend/.env)")
    args = parser.parse_args()
    try:
        if not re.fullmatch(r"[A-Za-z0-9._%+-]+@gmail\.com", args.sender_email, re.IGNORECASE):
            raise ValueError("Enter your dedicated sender's @gmail.com address")
        client = json.loads(args.client_file.read_text()).get("installed", {})
        if not all(isinstance(client.get(k), str) and client[k] for k in ("client_id", "client_secret")):
            raise ValueError("Download an OAuth client of type Desktop app, not Web application")
        if args.env_file.name != ".env" or args.env_file.is_symlink():
            raise ValueError("The destination must be a regular file named .env")
        state, verifier = secrets.token_urlsafe(32), secrets.token_urlsafe(64)
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
        result = {}
        with HTTPServer(("127.0.0.1", 0), callback_handler(state, result)) as server:
            server.timeout = 1
            redirect = f"http://127.0.0.1:{server.server_port}/oauth/callback"
            params = {"client_id": client["client_id"], "redirect_uri": redirect, "response_type": "code",
                      "scope": SCOPE, "access_type": "offline", "prompt": "consent", "state": state,
                      "code_challenge": challenge, "code_challenge_method": "S256", "login_hint": args.sender_email}
            url = "https://accounts.google.com/o/oauth2/v2/auth?" + urlencode(params)
            print("Opening Google authorization. Choose your dedicated sender account and approve sending email.")
            print("If the browser does not open, use this link (it contains no client secret):\n" + url)
            webbrowser.open(url)
            deadline = time.monotonic() + 300
            while not result and time.monotonic() < deadline:
                server.handle_request()
        if result.get("error"):
            raise ValueError("Authorization was declined. Configuration was not changed.")
        if not result.get("code"):
            raise ValueError("Authorization timed out. Re-run the command to try again.")
        refresh = exchange_code(client, result["code"], redirect, verifier)
        write_env(args.env_file, {"MAIL_PROVIDER": "gmail_api", "PASSWORD_RESET_ENABLED": "true",
                  "MAIL_FROM": args.sender_email, "GMAIL_CLIENT_ID": client["client_id"],
                  "GMAIL_CLIENT_SECRET": client["client_secret"], "GMAIL_REFRESH_TOKEN": refresh})
        print(f"Authorization saved to {args.env_file.absolute()} with owner-only permissions. No email was sent.")
        print("Restart the backend, then test Forgot password with a registered email you control.")
        print("OAuth Testing tokens expire after 7 days. See docs/gmail-api-setup.md before public use.")
    except (ValueError, OSError) as exc:
        print(f"Setup failed: {exc}")
        return 1
    except KeyboardInterrupt:
        print("Setup cancelled. Configuration was not changed.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
