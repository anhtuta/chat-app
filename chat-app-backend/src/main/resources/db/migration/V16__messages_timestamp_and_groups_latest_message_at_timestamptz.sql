-- Store message timeline ordering fields as absolute instants (timestamptz).
-- Existing naive timestamp values are interpreted as UTC wall-clock.
ALTER TABLE messages
    ALTER COLUMN timestamp TYPE timestamptz
    USING (
        CASE
            WHEN timestamp IS NULL THEN NULL
            ELSE timestamp AT TIME ZONE 'UTC'
        END
    );

ALTER TABLE groups
    ALTER COLUMN latest_message_at TYPE timestamptz
    USING (
        CASE
            WHEN latest_message_at IS NULL THEN NULL
            ELSE latest_message_at AT TIME ZONE 'UTC'
        END
    );
