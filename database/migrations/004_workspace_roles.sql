BEGIN;

CREATE TABLE IF NOT EXISTS workspaces (
    id BIGSERIAL PRIMARY KEY, name VARCHAR(150) NOT NULL,
    owner_user_id BIGINT NOT NULL REFERENCES users(id), legacy_owner_user_id BIGINT UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS workspace_members (
    id BIGSERIAL PRIMARY KEY, workspace_id BIGINT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id), admin BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE(workspace_id, user_id)
);
CREATE TABLE IF NOT EXISTS projects (
    id BIGSERIAL PRIMARY KEY, workspace_id BIGINT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name VARCHAR(150) NOT NULL, description VARCHAR(2000), legacy_repo_id BIGINT UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS project_members (
    id BIGSERIAL PRIMARY KEY, project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id), role VARCHAR(20) NOT NULL,
    UNIQUE(project_id, user_id), CHECK(role IN ('PROJECT_MANAGER','QA','DEVELOPER'))
);
CREATE TABLE IF NOT EXISTS workspace_invitations (
    id BIGSERIAL PRIMARY KEY, workspace_id BIGINT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    project_id BIGINT REFERENCES projects(id) ON DELETE CASCADE, email VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK(role IN ('WORKSPACE_ADMIN','PROJECT_MANAGER','QA','DEVELOPER')),
    token_hash VARCHAR(64) NOT NULL UNIQUE, created_by BIGINT NOT NULL REFERENCES users(id),
    expires_at TIMESTAMPTZ NOT NULL, accepted_at TIMESTAMPTZ, revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((role = 'WORKSPACE_ADMIN' AND project_id IS NULL) OR (role <> 'WORKSPACE_ADMIN' AND project_id IS NOT NULL))
);
CREATE TABLE IF NOT EXISTS ticket_activities (
    id BIGSERIAL PRIMARY KEY, ticket_id BIGINT NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    actor_user_id BIGINT NOT NULL REFERENCES users(id), action VARCHAR(40) NOT NULL,
    message TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE repos ADD COLUMN IF NOT EXISTS project_id BIGINT REFERENCES projects(id);
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS reproduction_steps TEXT;
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS expected_behavior TEXT;
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS actual_behavior TEXT;
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS priority VARCHAR(20) NOT NULL DEFAULT 'MEDIUM';
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS severity VARCHAR(20) NOT NULL DEFAULT 'MEDIUM';
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS qa_verified_by BIGINT REFERENCES users(id);
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS qa_verified_at TIMESTAMPTZ;
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS qa_notes TEXT;
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS qa_reopened BOOLEAN NOT NULL DEFAULT FALSE;

DO $$ DECLARE item RECORD; BEGIN
    FOR item IN SELECT conname FROM pg_constraint WHERE conrelid='users'::regclass AND contype='c'
        AND pg_get_constraintdef(oid) LIKE '%role%'
    LOOP EXECUTE format('ALTER TABLE users DROP CONSTRAINT %I', item.conname); END LOOP;
    FOR item IN SELECT conname FROM pg_constraint WHERE conrelid='tickets'::regclass AND contype='c'
        AND pg_get_constraintdef(oid) LIKE '%status%'
    LOOP EXECUTE format('ALTER TABLE tickets DROP CONSTRAINT %I', item.conname); END LOOP;
    FOR item IN SELECT conname FROM pg_constraint WHERE conrelid='repos'::regclass AND contype='u'
        AND pg_get_constraintdef(oid) = 'UNIQUE (owner_user_id, github_owner, github_repo)'
    LOOP EXECUTE format('ALTER TABLE repos DROP CONSTRAINT %I', item.conname); END LOOP;
END $$;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK(role IN ('WORKSPACE_ADMIN','PROJECT_MANAGER','QA','DEVELOPER'));
ALTER TABLE tickets ADD CONSTRAINT tickets_status_check CHECK(status IN
    ('OPEN','ANALYZING','PLAN_READY','PLAN_APPROVED','IMPLEMENTING','TESTING','PR_CREATED','AWAITING_QA','RESOLVED','FAILED'));
DROP INDEX IF EXISTS idx_repos_owner_repository;
CREATE UNIQUE INDEX IF NOT EXISTS idx_repos_project_repository ON repos(project_id,github_owner,github_repo) WHERE project_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_workspace_members_user ON workspace_members(user_id);
CREATE INDEX IF NOT EXISTS idx_project_members_user ON project_members(user_id);
CREATE INDEX IF NOT EXISTS idx_ticket_activities_ticket ON ticket_activities(ticket_id);

CREATE TABLE IF NOT EXISTS app_migrations (name VARCHAR(100) PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now());

-- Upgrade only existing per-user repository connections. No account passwords,
-- GitHub tokens, issue plans, PR links, or issue history are changed.
DO $$ BEGIN
IF NOT EXISTS (SELECT 1 FROM app_migrations WHERE name='004_workspace_roles') THEN
INSERT INTO workspaces(name,owner_user_id,legacy_owner_user_id,created_at)
SELECT DISTINCT left(u.username || '''s workspace',150),u.id,u.id,now() FROM repos r JOIN users u ON u.id=r.owner_user_id
WHERE r.project_id IS NULL ON CONFLICT(legacy_owner_user_id) DO NOTHING;
INSERT INTO workspace_members(workspace_id,user_id,admin)
SELECT w.id,w.owner_user_id,TRUE FROM workspaces w WHERE w.legacy_owner_user_id IS NOT NULL
ON CONFLICT(workspace_id,user_id) DO NOTHING;
INSERT INTO projects(workspace_id,name,legacy_repo_id,created_at)
SELECT w.id,left(r.name,150),r.id,now() FROM repos r JOIN workspaces w ON w.legacy_owner_user_id=r.owner_user_id
WHERE r.project_id IS NULL ON CONFLICT(legacy_repo_id) DO NOTHING;
UPDATE repos r SET project_id=p.id FROM projects p WHERE p.legacy_repo_id=r.id AND r.project_id IS NULL;

INSERT INTO workspace_members(workspace_id,user_id,admin)
SELECT DISTINCT p.workspace_id,participant.user_id,FALSE FROM repos r JOIN projects p ON p.id=r.project_id
JOIN tickets t ON t.repo_id=r.id CROSS JOIN LATERAL (VALUES(t.created_by),(t.assigned_to)) participant(user_id)
WHERE p.legacy_repo_id IS NOT NULL AND participant.user_id IS NOT NULL
ON CONFLICT(workspace_id,user_id) DO NOTHING;
INSERT INTO project_members(project_id,user_id,role)
SELECT p.id,u.id,CASE WHEN u.role IN ('QA','DEVELOPER') THEN u.role ELSE 'PROJECT_MANAGER' END
FROM projects p JOIN repos r ON r.id=p.legacy_repo_id JOIN users u ON u.id=r.owner_user_id
ON CONFLICT(project_id,user_id) DO NOTHING;
INSERT INTO project_members(project_id,user_id,role)
SELECT DISTINCT r.project_id,t.created_by,'QA' FROM tickets t JOIN repos r ON r.id=t.repo_id JOIN projects p ON p.id=r.project_id
WHERE p.legacy_repo_id IS NOT NULL AND t.created_by IS NOT NULL
ON CONFLICT(project_id,user_id) DO NOTHING;
INSERT INTO project_members(project_id,user_id,role)
SELECT DISTINCT r.project_id,t.assigned_to,'DEVELOPER' FROM tickets t JOIN repos r ON r.id=t.repo_id JOIN projects p ON p.id=r.project_id
WHERE p.legacy_repo_id IS NOT NULL AND t.assigned_to IS NOT NULL
ON CONFLICT(project_id,user_id) DO NOTHING;

-- Earlier versions treated a merged PR/closed issue as solved without QA approval.
UPDATE tickets SET status='AWAITING_QA' WHERE status='RESOLVED' AND qa_verified_at IS NULL AND github_pull_request=FALSE;
INSERT INTO app_migrations(name) VALUES('004_workspace_roles');
END IF;
END $$;
COMMIT;
