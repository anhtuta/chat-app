ALTER TABLE public.group_participants
ADD COLUMN last_read_message_id int8;

CREATE INDEX idx_group_participants_user_last_read
    ON public.group_participants (user_id, group_id, last_read_message_id);
