-- Optimistic lock token for concurrent message edit/delete (doc 19).
-- Existing rows backfill to 0. Do not reuse updated_at as a version.
ALTER TABLE messages
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
