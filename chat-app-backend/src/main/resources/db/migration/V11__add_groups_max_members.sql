-- Optional group member capacity. NULL and 0 mean unlimited.
-- Existing rows stay NULL so current groups remain uncapped.
ALTER TABLE public."groups"
    ADD COLUMN max_members INTEGER NULL;

ALTER TABLE public."groups"
    ADD CONSTRAINT groups_max_members_non_negative
    CHECK (max_members IS NULL OR max_members >= 0);
