-- Retain mistakenly imported PR tickets for history, but exclude them from issues.
-- Existing rows are classified against GitHub when QA refreshes the repository.
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS github_pull_request BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS github_issue_closed BOOLEAN NOT NULL DEFAULT FALSE;
