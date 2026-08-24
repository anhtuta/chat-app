-- Snapshot of extra subject display names for SYSTEM events that mention more than
-- messages.user_id (batch add-members). JSON array of strings, nullable.
ALTER TABLE public.messages
    ADD COLUMN system_event_subject_names TEXT NULL;
