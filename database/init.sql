-- AI Software Engineering Agent — schema
-- Single Postgres database for users, tickets, and code embeddings (pgvector).

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(100) UNIQUE NOT NULL,
    email         VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    auth_version  BIGINT NOT NULL DEFAULT 0,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('QA', 'DEVELOPER')),
    -- The user's own GitHub personal access token, set via "Connect GitHub" in the UI.
    -- Stored as plaintext -- fine for personal/local use, NOT production-safe as-is
    -- (would need encryption-at-rest or a secrets manager before multi-tenant deployment).
    github_token  TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

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

CREATE TABLE IF NOT EXISTS repos (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    github_owner    VARCHAR(255) NOT NULL,
    github_repo     VARCHAR(255) NOT NULL,
    owner_user_id   BIGINT REFERENCES users(id),
    clone_url       VARCHAR(500) NOT NULL,
    default_branch  VARCHAR(100) NOT NULL DEFAULT 'main',
    -- Token of whichever user connected this repo; used for issue reads, branch
    -- pushes, and PR creation against it. Same plaintext-storage caveat as users.github_token.
    github_token    TEXT,
    indexed_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_user_id, github_owner, github_repo)
);

CREATE TABLE IF NOT EXISTS tickets (
    id                  BIGSERIAL PRIMARY KEY,
    repo_id             BIGINT NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
    github_issue_number INTEGER NOT NULL,
    github_pull_request BOOLEAN NOT NULL DEFAULT FALSE,
    github_issue_closed BOOLEAN NOT NULL DEFAULT FALSE,
    title               VARCHAR(500) NOT NULL,
    description         TEXT,
    status              VARCHAR(30) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN','ANALYZING','PLAN_READY','PLAN_APPROVED',
                           'IMPLEMENTING','TESTING','PR_CREATED','RESOLVED','FAILED')),
    plan                TEXT,
    pr_url              VARCHAR(500),
    branch_name         VARCHAR(255),
    assigned_to         BIGINT REFERENCES users(id),
    created_by          BIGINT REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (repo_id, github_issue_number)
);

CREATE TABLE IF NOT EXISTS agent_steps (
    id          BIGSERIAL PRIMARY KEY,
    ticket_id   BIGINT NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    step_name   VARCHAR(100) NOT NULL,
    status      VARCHAR(20)  NOT NULL CHECK (status IN ('RUNNING','DONE','FAILED')),
    message     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Gemini text-embedding-004 produces 768-dim vectors.
CREATE TABLE IF NOT EXISTS code_embeddings (
    id          BIGSERIAL PRIMARY KEY,
    repo_id     BIGINT NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
    file_path   VARCHAR(1000) NOT NULL,
    code_chunk  TEXT NOT NULL,
    embedding   vector(768) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS code_embeddings_embedding_idx
    ON code_embeddings USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

CREATE INDEX IF NOT EXISTS idx_tickets_repo ON tickets(repo_id);
CREATE INDEX IF NOT EXISTS idx_tickets_status ON tickets(status);
CREATE INDEX IF NOT EXISTS idx_agent_steps_ticket ON agent_steps(ticket_id);
CREATE INDEX IF NOT EXISTS idx_code_embeddings_repo ON code_embeddings(repo_id);

-- psql resolves this relative to init.sql. Existing installations run this file
-- from database/migrations explicitly; this inclusion handles fresh databases.
\ir migrations/004_workspace_roles.sql
\ir migrations/005_company_onboarding.sql
