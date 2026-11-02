import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import provision_company as cli


class ProvisionCompanyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "backend").mkdir()
        self.env = self.root / "backend/.env"
        self.env.write_text("MAIL_PROVIDER=gmail_api\nGMAIL_REFRESH_TOKEN=private-existing-value\n")
        self.root_patch = patch.object(cli, "ROOT", self.root)
        self.root_patch.start(); self.addCleanup(self.root_patch.stop)

    def run_cli(self, *args):
        stream = io.StringIO()
        with contextlib.redirect_stdout(stream):
            code = cli.main(["--env-file", str(self.env), *args])
        return code, stream.getvalue()

    def test_configure_preserves_mail_credentials_and_does_not_print_or_rotate_key(self):
        code, output = self.run_cli("--configure")
        self.assertEqual(code, 0)
        key = cli.settings(self.env)["COMPANY_PROVISIONING_KEY"]
        self.assertGreaterEqual(len(key), 32)
        self.assertNotIn(key, output)
        self.assertIn("GMAIL_REFRESH_TOKEN=private-existing-value", self.env.read_text())
        self.assertEqual(self.env.stat().st_mode & 0o777, 0o600)
        self.run_cli("--configure")
        self.assertEqual(cli.settings(self.env)["COMPANY_PROVISIONING_KEY"], key)

    def test_missing_key_never_sends_a_request(self):
        with patch.object(cli, "provision") as request:
            code, _ = self.run_cli("--company", "Example", "--admin-email", "admin@example.com")
        self.assertEqual(code, 1); request.assert_not_called()

    def test_success_saves_private_link_and_request_id_without_printing_credentials(self):
        self.run_cli("--configure")
        output_file = self.root / ".run/invite.json"
        result = {"companyId": 7, "invitationUrl": "https://example.com/invitations#token=private-link", "expiresAt": "later", "emailSent": True}
        with patch.object(cli, "provision", return_value=result) as request:
            code, stdout = self.run_cli("--company", "Example", "--admin-email", "admin@example.com", "--output", str(output_file))
        self.assertEqual(code, 0)
        self.assertNotIn("private-link", stdout)
        self.assertNotIn(cli.settings(self.env)["COMPANY_PROVISIONING_KEY"], stdout)
        self.assertEqual(json.loads(output_file.read_text())["invitationUrl"], result["invitationUrl"])
        self.assertEqual(output_file.stat().st_mode & 0o777, 0o600)
        self.assertEqual(output_file.parent.stat().st_mode & 0o777, 0o700)
        self.assertTrue(request.call_args.args[3]["sendEmail"])

    def test_ambiguous_failure_preserves_request_id_and_never_overwrites_previous_result(self):
        self.run_cli("--configure")
        output_file = self.root / ".run/invite.json"
        with patch.object(cli, "provision", side_effect=ValueError("Backend unavailable")) as request:
            code, _ = self.run_cli("--company", "Example", "--admin-email", "admin@example.com", "--output", str(output_file))
        self.assertEqual(code, 1)
        self.assertIn("requestId", json.loads(output_file.read_text()))
        previous = output_file.read_text()
        with patch.object(cli, "provision") as request:
            code, _ = self.run_cli("--company", "Example", "--admin-email", "admin@example.com", "--output", str(output_file))
        self.assertEqual(code, 1); request.assert_not_called()
        self.assertEqual(output_file.read_text(), previous)

    def test_unignored_outputs_and_remote_http_are_rejected(self):
        self.run_cli("--configure")
        with patch.object(cli, "provision") as request:
            code, _ = self.run_cli("--company", "Example", "--admin-email", "admin@example.com", "--output", str(self.root / "public.json"))
        self.assertEqual(code, 1); request.assert_not_called()
        with self.assertRaises(ValueError):
            cli.provision("http://remote.example.com", "private-key", "/api/operator/companies", {})

    def test_reissue_uses_existing_company_and_respects_no_email(self):
        self.run_cli("--configure")
        result = {"companyId": 7, "invitationUrl": "https://example.com/invitations#token=private-link", "emailSent": False}
        with patch.object(cli, "provision", return_value=result) as request:
            code, stdout = self.run_cli("--reissue", "7", "--admin-email", "admin@example.com", "--no-email")
        self.assertEqual(code, 0)
        self.assertEqual(request.call_args.args[2], "/api/operator/companies/7/admin-invitation")
        self.assertFalse(request.call_args.args[3]["sendEmail"])
        self.assertNotIn("password", request.call_args.args[3])
        self.assertIn("not confirmed", stdout)


if __name__ == "__main__":
    unittest.main()
