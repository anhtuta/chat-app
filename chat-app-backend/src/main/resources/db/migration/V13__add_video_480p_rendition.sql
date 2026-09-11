ALTER TABLE public.message_media
    ADD COLUMN rendition_480p_object_key varchar(512) NULL,
    ADD COLUMN rendition_480p_size_bytes bigint NULL;
