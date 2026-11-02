import io
import json
from pathlib import Path
import tempfile
import threading
from http.server import HTTPServer
from urllib.request import urlopen
from urllib.error import HTTPError
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs

import setup_gmail


class SetupGmailTests(unittest.TestCase):
    def test_callback_rejects_wrong_state_then_accepts_valid_code(self):
        result = {}
        with HTTPServer(("127.0.0.1", 0), setup_gmail.callback_handler("expected-state", result)) as server:
            def serve_two():
                server.handle_request()
                server.handle_request()
            thread = threading.Thread(target=serve_two, daemon=True)
            thread.start()
            base = f"http://127.0.0.1:{server.server_port}/oauth/callback"
            with self.assertRaises(HTTPError) as rejected:
                urlopen(base + "?state=wrong&code=bad", timeout=3)
            self.assertEqual(rejected.exception.code, 400)
            self.assertFalse(result)
            with urlopen(base + "?state=expected-state&code=valid", timeout=3) as response:
                self.assertEqual(response.headers["Cache-Control"], "no-store")
            thread.join(timeout=3)
            self.assertEqual(result, {"code": "valid"})

    def test_env_preserves_other_settings_removes_duplicate_keys_and_limits_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text("# keep\nJWT_SECRET=existing\nFRONTEND_URL=https://app.example.com\nMAIL_PROVIDER=smtp\nMAIL_PROVIDER=smtp\n")
            setup_gmail.write_env(path, {"MAIL_PROVIDER": "gmail_api", "GMAIL_REFRESH_TOKEN": "test-token"})
            contents = path.read_text()
            self.assertIn("JWT_SECRET=existing", contents)
            self.assertIn("FRONTEND_URL=https://app.example.com", contents)
            self.assertEqual(contents.count("MAIL_PROVIDER="), 1)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_env_rejects_newline_injection_and_nonignored_filename(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                setup_gmail.write_env(Path(directory) / ".env", {"TOKEN": "bad\nOTHER=value"})
            with self.assertRaises(ValueError):
                setup_gmail.write_env(Path(directory) / "config.txt", {"TOKEN": "secret"})

    def test_code_exchange_uses_https_pkce_and_checks_send_scope(self):
        response = io.BytesIO(json.dumps({"refresh_token": "new-refresh", "scope": setup_gmail.SCOPE}).encode())
        with patch.object(setup_gmail, "urlopen", return_value=response) as opened:
            refresh = setup_gmail.exchange_code({"client_id": "id", "client_secret": "secret"}, "code", "http://127.0.0.1:1/oauth/callback", "verifier")
        request = opened.call_args.args[0]
        self.assertEqual(request.full_url, "https://oauth2.googleapis.com/token")
        self.assertEqual(parse_qs(request.data.decode())["code_verifier"], ["verifier"])
        self.assertEqual(refresh, "new-refresh")

    def test_code_exchange_rejects_missing_send_scope(self):
        response = io.BytesIO(b'{"refresh_token":"secret","scope":"openid"}')
        with patch.object(setup_gmail, "urlopen", return_value=response), self.assertRaises(ValueError):
            setup_gmail.exchange_code({"client_id": "id", "client_secret": "secret"}, "code", "http://127.0.0.1:1/oauth/callback", "verifier")


if __name__ == "__main__":
    unittest.main()
