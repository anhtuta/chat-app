-- Rollback: drop unread cursor column and related index from group participants
DROP INDEX IF EXISTS public.idx_group_participants_user_last_read;

ALTER TABLE public.group_participants
DROP COLUMN IF EXISTS last_read_message_id;
