-- Store join-link expiry as an absolute instant (timestamptz).
-- Existing naive timestamp values are interpreted as UTC wall-clock.
ALTER TABLE group_join_links
    ALTER COLUMN expires_at TYPE timestamptz
    USING (
        CASE
            WHEN expires_at IS NULL THEN NULL
            ELSE expires_at AT TIME ZONE 'UTC'
        END
    );
