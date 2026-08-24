ALTER TABLE public."groups"
    DROP CONSTRAINT IF EXISTS groups_max_members_non_negative;

ALTER TABLE public."groups"
    DROP COLUMN IF EXISTS max_members;
