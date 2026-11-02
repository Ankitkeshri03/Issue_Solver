BEGIN;
ALTER TABLE workspaces ALTER COLUMN owner_user_id DROP NOT NULL;
ALTER TABLE workspaces ADD COLUMN IF NOT EXISTS provisioning_pending BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workspaces ALTER COLUMN provisioning_pending SET DEFAULT FALSE;
ALTER TABLE workspaces ADD COLUMN IF NOT EXISTS provisioning_request_id VARCHAR(36);
CREATE UNIQUE INDEX IF NOT EXISTS idx_workspace_provisioning_request ON workspaces(provisioning_request_id);
ALTER TABLE workspace_invitations ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE workspace_invitations ADD COLUMN IF NOT EXISTS bootstrap BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workspace_invitations ALTER COLUMN bootstrap SET DEFAULT FALSE;
-- Existing accounts, workspace owners, memberships, passwords and invitations stay intact.
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='workspaces'::regclass AND conname='workspace_owner_activation') THEN
        ALTER TABLE workspaces ADD CONSTRAINT workspace_owner_activation CHECK
          ((provisioning_pending AND owner_user_id IS NULL) OR (NOT provisioning_pending AND owner_user_id IS NOT NULL));
    END IF;
    IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='workspace_invitations'::regclass AND conname='invitation_bootstrap_authority') THEN
        ALTER TABLE workspace_invitations ADD CONSTRAINT invitation_bootstrap_authority CHECK
          ((bootstrap AND created_by IS NULL AND role='WORKSPACE_ADMIN' AND project_id IS NULL)
           OR (NOT bootstrap AND created_by IS NOT NULL));
    END IF;
END $$;
COMMIT;
