# Password recovery and email sign-in

The login screen accepts an existing username or registered email. New email addresses
are stored in lowercase; email lookup is case-insensitive. Usernames cannot contain
`@`, so new accounts do not introduce ambiguous email/username identifiers.

New accounts are created through [company/team invitations](team-workflow.md), not
public registration. Password recovery is for accounts that already exist. Admins,
managers, QA and developers all use the same login and recovery pages.

## User flow

1. Choose **Forgot password?** and enter the registered email.
2. If the email is unknown, the API returns **No account found with this email. Ask
   your company admin or project manager for an invitation.** No email is sent.
3. If the email exists, the backend emails a reset link to the account's stored email.
4. The link opens `/reset-password#token=...`. The user enters the registered email and
   a new password twice. The server checks that the email, token, and expiry match before
   changing anything. An email address alone cannot reset a password.
5. On success, the link is consumed, other reset links for that account are removed,
   existing JWT logins are invalidated, and the user signs in with the new password.

Links expire after 15 minutes. Requesting a new link replaces the previous one. A
database row lock prevents concurrent requests from consuming a link twice. Only a
SHA-256 hash of the random 256-bit token is stored. The raw token appears only in the
email and reset page memory; it is not returned in an API response or stored in localStorage.
The URL fragment is cleared when the reset page opens. Reopen the email link after a refresh.

Recovery attempts use shared PostgreSQL counters: 5 requests per email/hour, 20 requests
per client address/hour, and 20 reset attempts per client address/15 minutes. Limits
survive failures, process restarts, and multiple backend instances. They use the servlet
client address; behind a proxy, configure trusted client-IP handling at the deployment
edge and strip untrusted forwarding headers. Otherwise clients behind that proxy share
the address limit. Old counter rows are removed after two hours.

## Real email through the Gmail HTTPS API

The default mail provider is `gmail_api`. Authorize your dedicated sender using the
[step-by-step Gmail API setup guide](gmail-api-setup.md). The helper saves OAuth
credentials in ignored `backend/.env`; no Gmail password, App Password or SMTP
connection is needed. The backend refreshes access tokens automatically.

Set `MAIL_PROVIDER=gmail_api`, `PASSWORD_RESET_ENABLED=true`, `MAIL_FROM`,
`GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET`, `GMAIL_REFRESH_TOKEN` and `FRONTEND_URL`.
Spring reads the optional properties-format `.env` when launched from `backend/`.
Use unquoted values and restart after changes. The production profile defaults to
Gmail API and validates its credentials, HTTPS frontend, JWT secret, token-encryption
key and shared AI-service secret. See `backend/.env.production.example`.

Google OAuth Testing refresh tokens expire after seven days. Resolve OAuth
publishing and any applicable verification requirements before unattended public
use, as described in the Gmail guide. HTTPS works on hosts that block SMTP ports.
Deployment is a separate action.

Workspace invitation email uses the same provider and sender credentials, but has
its own [invitation acceptance flow](team-workflow.md). An invitation does not change
an existing account's password. New accounts activate through their invited email
and choose their own password. Project permissions come from memberships. Invitation links
expire after seven days; password-reset links expire after 15 minutes.

SMTP remains available by explicitly setting `MAIL_PROVIDER=smtp`, `SMTP_HOST`,
`SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD` and the existing TLS/auth settings.
No email-provider account is otherwise required besides the authorized Gmail sender.
Gmail API failures return a generic 503 without credential diagnostics, and do not
change the password. Email acceptance does not guarantee inbox placement.

For Docker Compose, use project-root `.env` or shell variables. Keep all mail
credentials on the backend. Build the frontend with the appropriate
`VITE_API_BASE_URL`; configure its host to serve `index.html` for `/login` and
`/reset-password`, `/invitations`, `/workspaces/*`, `/projects/*`, `/repos/*`, and
`/tickets/*`. `FRONTEND_URL` controls reset/invitation links and the allowed browser origin.

## Database setup

For a fresh installation, apply `database/init.sql` to the PostgreSQL database (requires
pgvector). For an existing installation, apply the additive recovery migration before
starting the backend in production:

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f database/migrations/001_password_recovery.sql
```

Here `DATABASE_URL` is a PostgreSQL connection URL accepted by `psql`, not the Java JDBC
URL. The migration preserves existing passwords, accounts, repositories, and tickets.
If legacy accounts have the same email with different capitalization, resolve those
conflicting records before retrying the unique-index migration. Local Hibernate `update`
creates recovery tables/columns automatically, but the case-insensitive unique index
still requires applying the migration. Updating `init.sql` alone does not migrate an
existing database volume.

The recovery migration is one step of the complete upgrade. Existing installations
must also apply later migrations in order, including workspace/project roles (004)
and company onboarding (005); see
[the upgrade sequence](team-workflow.md#existing-installations). Local Hibernate
schema updates do not perform the ownership/membership data migration.

## Verification

Backend tests use an isolated database named `gitissuesolver_test`; never point the
test profile at an application database because its schema is created and dropped.
Create the test database once, then run:

```bash
cd backend
TEST_DATABASE_URL=jdbc:postgresql://localhost:5433/gitissuesolver_test \
TEST_DATABASE_USERNAME=gitissuesolver TEST_DATABASE_PASSWORD=gitissuesolver ./mvnw test
```

Recovery tests replace mail delivery with test doubles. Gmail transport tests mock Google HTTP endpoints; automated tests send no external emails.
They cover username/email login, unknown email, token hashing, email mismatch, expiry,
replay, replacement, password validation, JWT invalidation, SMTP failure rollback,
concurrent consumption, and persistent rate limits. The frontend is checked with
`npm run build` from `frontend/`.

After configuring a real provider, verify one request using an inbox you control and
confirm receipt, successful reset, old-password rejection, old-session rejection, and
expired/used-link errors. No real provider delivery is claimed by the automated tests.
