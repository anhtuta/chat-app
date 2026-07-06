-- Rollback: remove group roles/permissions phase 1 schema.
DROP INDEX IF EXISTS public.idx_message_edit_history_message_updated_at;
DROP INDEX IF EXISTS public.idx_messages_deleted_at;
DROP INDEX IF EXISTS public.idx_group_join_links_group_active;
DROP INDEX IF EXISTS public.idx_group_bans_user_id;
DROP INDEX IF EXISTS public.idx_groups_archived_at;
DROP INDEX IF EXISTS public.ux_group_participants_one_leader;

DROP TABLE IF EXISTS public.message_edit_history;
DROP TABLE IF EXISTS public.group_join_links;
DROP TABLE IF EXISTS public.group_bans;

ALTER TABLE public.messages
    DROP CONSTRAINT IF EXISTS fk_messages_deleted_by_users,
    DROP CONSTRAINT IF EXISTS fk_messages_updated_by_users;

ALTER TABLE public.messages
    DROP COLUMN IF EXISTS deleted_at,
    DROP COLUMN IF EXISTS deleted_by,
    DROP COLUMN IF EXISTS updated_at,
    DROP COLUMN IF EXISTS updated_by;

ALTER TABLE public."groups"
    DROP CONSTRAINT IF EXISTS fk_groups_archived_by_users;

ALTER TABLE public."groups"
    DROP COLUMN IF EXISTS archive_reason,
    DROP COLUMN IF EXISTS archived_by,
    DROP COLUMN IF EXISTS archived_at,
    DROP COLUMN IF EXISTS description;

ALTER TABLE public.group_participants
    DROP COLUMN IF EXISTS role;
