-- Optional JSON for SYSTEM events that need more than actor + one subject
-- (batch add-members: {"subjectNames":["Bob","Carol"]}).
ALTER TABLE public.messages
    ADD COLUMN system_event_payload JSONB NULL;
