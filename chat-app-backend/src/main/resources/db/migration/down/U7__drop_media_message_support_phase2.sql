-- Rollback: remove media upload/message metadata tables and message type column
DROP INDEX IF EXISTS public.idx_media_uploads_upload_session_id;
DROP INDEX IF EXISTS public.idx_message_media_message_id_attachment_order;

DROP TABLE IF EXISTS public.media_uploads;
DROP TABLE IF EXISTS public.message_media;

ALTER TABLE public.messages
DROP COLUMN IF EXISTS message_type;

UPDATE public.messages
SET content = ''
WHERE content IS NULL;

ALTER TABLE public.messages
ALTER COLUMN content SET NOT NULL;
