ALTER TABLE public.message_media
    DROP COLUMN IF EXISTS rendition_480p_object_key,
    DROP COLUMN IF EXISTS rendition_480p_size_bytes;
