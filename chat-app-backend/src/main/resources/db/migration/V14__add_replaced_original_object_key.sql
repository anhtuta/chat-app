ALTER TABLE public.message_media
    ADD COLUMN replaced_original_object_key varchar(512) NULL;

CREATE INDEX idx_message_media_replaced_original_object_key
    ON public.message_media (replaced_original_object_key)
    WHERE replaced_original_object_key IS NOT NULL;
