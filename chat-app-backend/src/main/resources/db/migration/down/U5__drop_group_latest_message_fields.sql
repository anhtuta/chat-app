-- Rollback: Drop group latest-message fields and supporting indexes
DROP INDEX IF EXISTS public.idx_messages_group_timestamp_id;
DROP INDEX IF EXISTS public.idx_groups_latest_message_at;

ALTER TABLE public."groups"
DROP COLUMN IF EXISTS latest_message_at,
DROP COLUMN IF EXISTS latest_message_sender,
DROP COLUMN IF EXISTS latest_message;