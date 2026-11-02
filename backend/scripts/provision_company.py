#!/usr/bin/env python3
"""Provision a company's first admin invitation; never generate or print a password.

Uses the operator key in backend/.env. Private invitation results are saved under
ignored .run/. No deployment occurs. Email is requested unless --no-email is used.
"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler
import uuid

from setup_gmail import write_env

ROOT = Path(__file__).resolve().parents[2]


def settings(path):
    values = {}
    if path.exists():
        for line in path.read_text().splitlines():
            if line.strip() and not line.lstrip().startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    return values


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


def provision(base_url, key, path, payload):
    parsed = urlsplit(base_url)
    local = parsed.hostname in ("localhost", "127.0.0.1", "::1")
    if (not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment
            or not (parsed.scheme == "https" or parsed.scheme == "http" and local)):
        raise ValueError("Use an HTTPS backend URL; HTTP is allowed only for localhost")
    request = Request(base_url.rstrip("/") + path, data=json.dumps(payload).encode(),
                      headers={"Content-Type": "application/json", "X-Provisioning-Key": key}, method="POST")
    try:
        with build_opener(NoRedirect()).open(request, timeout=30) as response:
            result = json.load(response)
    except HTTPError as exc:
        raise ValueError(f"Provisioning returned HTTP {exc.code}. Check the operator key, request ID and company state; no password was changed.") from None
    except (URLError, TimeoutError, ValueError):
        raise ValueError("No valid response from the backend. Preserve the saved request ID and check the company before retrying.") from None
    if not isinstance(result, dict) or not isinstance(result.get("companyId"), int) or not isinstance(result.get("invitationUrl"), str):
        raise ValueError("The backend returned an invalid provisioning result")
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--configure", action="store_true", help="Save an operator key in backend/.env, without provisioning a company")
    mode.add_argument("--company", help="Company/workspace name")
    mode.add_argument("--reissue", type=int, metavar="COMPANY_ID", help="Reissue an unaccepted first-admin invitation; invalidates its old link")
    parser.add_argument("--admin-email", help="First admin's own email address")
    parser.add_argument("--backend-url", default="http://127.0.0.1:8081")
    parser.add_argument("--env-file", type=Path, default=ROOT / "backend/.env")
    parser.add_argument("--request-id", type=uuid.UUID, help="Stable request ID for tracing an initial provisioning request")
    parser.add_argument("--output", type=Path, help="Private result file under this project's ignored .run directory")
    parser.add_argument("--no-email", action="store_true", help="Create only a private link, without sending email")
    args = parser.parse_args(argv)
    try:
        config = settings(args.env_file)
        key = config.get("COMPANY_PROVISIONING_KEY", "")
        if args.configure:
            if key and len(key) < 32:
                raise ValueError("The existing operator key is too short; correct it before proceeding")
            if not key:
                write_env(args.env_file, {"COMPANY_PROVISIONING_KEY": secrets.token_urlsafe(48)})
            else:
                args.env_file.chmod(0o600)
            print("Operator key configured privately. Restart the backend before provisioning. No company or email was created.")
            return 0
        if len(key) < 32:
            raise ValueError("Run --configure and restart the backend first")
        if not args.admin_email or not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", args.admin_email):
            raise ValueError("Provide the first admin's email with --admin-email")
        if args.company is not None and not 1 <= len(args.company.strip()) <= 150:
            raise ValueError("Company name must have 1–150 characters")
        if args.reissue is not None and args.reissue <= 0:
            raise ValueError("Company ID must be positive")
        request_id = str(args.request_id or uuid.uuid4())
        output = (args.output or ROOT / ".run/company-invitations" / (request_id + ".json")).resolve()
        if not output.is_relative_to((ROOT / ".run").resolve()):
            raise ValueError("Save private invitation results under this project's ignored .run directory")
        output.parent.mkdir(parents=True, exist_ok=True); output.parent.chmod(0o700)
        payload = {"adminEmail": args.admin_email.strip().lower(), "sendEmail": not args.no_email}
        path = "/api/operator/companies"
        if args.company is not None:
            payload.update(companyName=args.company.strip(), requestId=request_id)
        else:
            path += f"/{args.reissue}/admin-invitation"
        # Exclusive owner-only file prevents accidental overwrite and preserves the
        # request ID if a network failure makes delivery ambiguous.
        with os.fdopen(os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
            json.dump({"requestId": request_id, "request": payload}, stream, indent=2); stream.flush(); os.fsync(stream.fileno())
            print(f"Private request record: {output}")
            result = provision(args.backend_url, key, path, payload)
            stream.seek(0); stream.truncate()
            json.dump({"requestId": request_id, **result}, stream, indent=2); stream.write("\n")
        print(f"Company {result['companyId']} provisioned. Private invitation saved to {output}")
        print("Invitation email sent." if result.get("emailSent") else "Email delivery was not confirmed. Share the private activation link from that file with the intended admin.")
        return 0
    except (ValueError, OSError) as exc:
        # Errors must never include the operator key or a private activation link.
        print(f"Provisioning failed: {exc}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
