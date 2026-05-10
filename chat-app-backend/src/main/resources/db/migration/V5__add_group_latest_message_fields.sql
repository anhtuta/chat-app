ALTER TABLE public."groups"
ADD COLUMN latest_message varchar(255),
ADD COLUMN latest_message_sender varchar(255),
ADD COLUMN latest_message_at timestamp(6);

CREATE INDEX idx_groups_latest_message_at
    ON public."groups" (latest_message_at DESC);

CREATE INDEX idx_messages_group_timestamp_id
    ON public.messages (group_id, "timestamp" DESC, id DESC);