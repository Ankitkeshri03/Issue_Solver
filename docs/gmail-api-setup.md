# Gmail API setup for recovery and invitation emails

The backend sends from one dedicated Gmail account using HTTPS. Gmail passwords,
App Passwords, SMTP ports and a purchased domain are not needed for this setup.
Recipients do not authorize Google access: only the operator's sender account does.
No deployment is performed by this guide or its setup script.

The same configured sender supports workspace invitation emails and operator-created
first-admin activation emails. No second OAuth client, extra Gmail scope, or
additional mail credentials are needed. Invitation forms have email delivery selected
by default; an admin or authorized project manager can uncheck it to create only a
private link. The operator CLI requests email unless `--no-email` is supplied.
`PASSWORD_RESET_ENABLED` controls recovery, not invitation delivery. See
[company provisioning](team-workflow.md#provision-a-company) for that workflow.

## 1. Prepare your Google project

1. Sign in to [Google Cloud Console](https://console.cloud.google.com/) using the
   dedicated sender account and create or select a project.
2. In **APIs & Services → Library**, find **Gmail API** and enable it.
3. Open **Google Auth Platform** (or **OAuth consent screen**). Set the application
   name to **Issue Solver**, and fill in the required contact information.
4. For a personal Gmail account, choose **External**. During initial testing, add
   your dedicated sender address to **Audience → Test users**.
5. Under **Data Access**, add only
   `https://www.googleapis.com/auth/gmail.send`. This permission sends emails; the
   project does not request inbox-reading or deletion permissions.
6. Under **Clients**, create an OAuth client with type **Desktop app**. The local
   setup helper uses a desktop loopback callback; no hosted callback is required.
7. Download its JSON file. Keep it outside the repository, for example at
   `~/Downloads/google-oauth-client.json`. Do not share its contents in chat.

See Google's [scope documentation](https://developers.google.com/workspace/gmail/api/auth/scopes)
and [desktop OAuth instructions](https://developers.google.com/identity/protocols/oauth2/native-app).
Console labels can vary slightly by account.

## 2. Authorize your sender locally

Run this from the project folder, replacing the sender address:

```bash
cd /Users/ankit/Desktop/Git_Issue_solver
python3 backend/scripts/setup_gmail.py \
  --client-file ~/Downloads/google-oauth-client.json \
  --sender-email your-project-sender@gmail.com
```

The script opens Google's consent page. Choose the same sender account and approve
the permission to send email. It verifies the callback's random state, uses PKCE,
and exchanges the authorization code directly with Google over HTTPS. The local
callback listener binds only to `127.0.0.1` and closes after authorization or five
minutes. No external Python packages are required.

The script writes the following to ignored `backend/.env`, preserving unrelated
settings and setting file permissions to `600`:

```dotenv
MAIL_PROVIDER=gmail_api
PASSWORD_RESET_ENABLED=true
MAIL_FROM=your-project-sender@gmail.com
GMAIL_CLIENT_ID=<from your OAuth client>
GMAIL_CLIENT_SECRET=<from your OAuth client>
GMAIL_REFRESH_TOKEN=<from your authorization>
FRONTEND_URL=http://localhost:5173
```

An existing `FRONTEND_URL` is preserved. Values use unquoted `KEY=value` syntax.
The script never prints tokens or the client secret, never sends an email, and
does not change passwords. Do not commit `.env` or your downloaded OAuth JSON.
Use the actual authorized sender for `MAIL_FROM`, not an arbitrary address.

## 3. Restart and check recovery

If services are running through the launcher, reload the new backend settings by
stopping app services while keeping PostgreSQL, then starting again:

```bash
cd /Users/ankit/Desktop/Git_Issue_solver
./stop.sh --keep-db
./start.sh
```

For independently started services, use the [manual startup instructions](../RUNNING.md#manual-startup-alternative).
Open `http://localhost:5173/login`, choose **Forgot password?**, and enter an email
already registered in Issue Solver whose inbox you control. The recipient may be
different from the dedicated Gmail sender.

Check the inbox and Spam folder. The link must accept the matching registered
email, allow one password change, and reject reuse. Verify the old password no
longer works. Google acceptance does not guarantee inbox placement.

The backend refreshes short-lived access tokens automatically and caches them in
memory. Its Google HTTP requests have bounded timeouts. Failed sends return a
generic 503 for password recovery without credentials or provider response bodies, and the reset
transaction rolls back so an earlier valid link is preserved. Sends are not
automatically retried after ambiguous failures to avoid duplicate delivery.

To check invitation delivery, an admin opens **Manage workspace & team**, or a
manager opens **Invite to this project** in a project they manage. Use a recipient
inbox you control, choose an allowed role/project, leave email selected and create
the invitation. If delivery fails, the invitation still exists and the UI offers
its private link; it does not report successful delivery.

Open the received link. A new recipient activates their account by choosing a
username/password and then uses the common login page. An existing recipient signs
in with the invited email and accepts without a password change. Verify expiry,
single-use behavior, and that only the invited workspace/project access is granted.

The three expiration periods are separate: password-reset links last 15 minutes,
workspace invitation links last seven days, and Gmail-scoped OAuth refresh tokens
issued while the Google app is in Testing expire after seven days. Reauthorizing
Gmail renews sender access; it does not renew existing reset or invitation links.

## OAuth lifetime and future hosting

**An External OAuth app left in Testing receives refresh tokens that expire after
seven days for `gmail.send`.** This is Google's policy, not the reset link's
15-minute expiry. For continued testing, rerun authorization when needed. Before
relying on unattended public use, configure the OAuth app's publishing status and
meet any verification requirements Google applies to your use case, then authorize
again. Publishing does not exempt the app from Google's policies or prevent token
revocation. See [refresh-token expiration](https://developers.google.com/identity/protocols/oauth2#expiration)
and [verification requirements](https://developers.google.com/identity/protocols/oauth2/production-readiness/policy-compliance).

If Google access is revoked or Gmail-scoped tokens are invalidated after a password
change, rerun the helper and restart the backend. It does not silently switch to
mock email or SMTP. Gmail's sending limits still apply.

If you deploy later, set `MAIL_PROVIDER=gmail_api`, `MAIL_FROM` and the three
`GMAIL_*` values in the backend host's secret fields. Set `FRONTEND_URL` to the real
HTTPS frontend origin. Only the backend receives these secrets; never use a
frontend `VITE_*` variable for them. HTTPS avoids the SMTP-port restriction on
[Render Free](https://render.com/docs/free#other-limitations). Deployment remains a
separate action. The `prod` profile also requires the existing JWT, database,
GitHub-token encryption and shared AI-service authentication settings shown in
`backend/.env.production.example`.

For Docker Compose, set variables in project-root `.env` (or export them in the
shell). The helper's `backend/.env` is used when starting Spring from `backend/`;
Compose does not automatically read that file.

## Automated verification

```bash
cd backend
./mvnw -Dtest=GmailApiEmailSenderTests,ProductionAuthConfigTests test
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

The Gmail HTTP and helper tests use fakes. Full recovery tests use an isolated
PostgreSQL test database as described in `password-recovery.md`. Automated tests
do not send real email or prove actual Google account authorization.
