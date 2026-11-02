BEGIN;
ALTER TABLE repos ADD COLUMN IF NOT EXISTS owner_user_id BIGINT REFERENCES users(id);
-- Replace the original globally-unique connection with a per-user connection.
DO $$
DECLARE item RECORD;
BEGIN
    FOR item IN SELECT conname FROM pg_constraint
        WHERE conrelid = 'repos'::regclass AND contype = 'u'
          AND conkey = ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid='repos'::regclass AND attname='github_owner'),
                             (SELECT attnum FROM pg_attribute WHERE attrelid='repos'::regclass AND attname='github_repo')]::smallint[]
    LOOP EXECUTE format('ALTER TABLE repos DROP CONSTRAINT %I', item.conname); END LOOP;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS idx_repos_owner_repository ON repos(owner_user_id, github_owner, github_repo);
COMMIT;
