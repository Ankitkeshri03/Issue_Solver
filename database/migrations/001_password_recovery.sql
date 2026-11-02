-- Apply once to an existing database before starting the backend with the prod profile.
-- Uses an additive migration; existing accounts, passwords, tickets, and repos are kept.
BEGIN;
ALTER TABLE users ADD COLUMN IF NOT EXISTS auth_version BIGINT NOT NULL DEFAULT 0;

-- This fails deliberately if legacy accounts have duplicate emails differing only by
-- case. Resolve those account records before retrying; do not arbitrarily merge them.
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email_lower ON users (lower(email));

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reset_token_user ON password_reset_tokens(user_id);

CREATE TABLE IF NOT EXISTS auth_rate_limits (
    key_hash VARCHAR(64) PRIMARY KEY,
    window_start BIGINT NOT NULL,
    attempts INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_auth_limit_window ON auth_rate_limits(window_start);
COMMIT;
