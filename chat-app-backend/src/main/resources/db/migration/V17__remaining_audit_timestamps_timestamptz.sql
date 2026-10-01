-- Store remaining audit/lifecycle moments as absolute instants (timestamptz).
-- Existing naive timestamp values are interpreted as UTC wall-clock.
ALTER TABLE users
    ALTER COLUMN created_at TYPE timestamptz
    USING (
        CASE
            WHEN created_at IS NULL THEN NULL
            ELSE created_at AT TIME ZONE 'UTC'
        END
    );

ALTER TABLE groups
    ALTER COLUMN created_at TYPE timestamptz
    USING (
        CASE
            WHEN created_at IS NULL THEN NULL
            ELSE created_at AT TIME ZONE 'UTC'
        END
    ),
    ALTER COLUMN archived_at TYPE timestamptz
    USING (
        CASE
            WHEN archived_at IS NULL THEN NULL
            ELSE archived_at AT TIME ZONE 'UTC'
        END
    );

ALTER TABLE group_participants
    ALTER COLUMN joined_at TYPE timestamptz
    USING (
        CASE
            WHEN joined_at IS NULL THEN NULL
            ELSE joined_at AT TIME ZONE 'UTC'
        END
    );

ALTER TABLE messages
    ALTER COLUMN updated_at TYPE timestamptz
    USING (
        CASE
            WHEN updated_at IS NULL THEN NULL
            ELSE updated_at AT TIME ZONE 'UTC'
        END
    ),
    ALTER COLUMN deleted_at TYPE timestamptz
    USING (
        CASE
            WHEN deleted_at IS NULL THEN NULL
            ELSE deleted_at AT TIME ZONE 'UTC'
        END
    );

ALTER TABLE message_edit_history
    ALTER COLUMN updated_at TYPE timestamptz
    USING (
        CASE
            WHEN updated_at IS NULL THEN NULL
            ELSE updated_at AT TIME ZONE 'UTC'
        END
    );

ALTER TABLE message_media
    ALTER COLUMN created_at TYPE timestamptz
    USING (
        CASE
            WHEN created_at IS NULL THEN NULL
            ELSE created_at AT TIME ZONE 'UTC'
        END
    ),
    ALTER COLUMN updated_at TYPE timestamptz
    USING (
        CASE
            WHEN updated_at IS NULL THEN NULL
            ELSE updated_at AT TIME ZONE 'UTC'
        END
    );

ALTER TABLE media_uploads
    ALTER COLUMN created_at TYPE timestamptz
    USING (
        CASE
            WHEN created_at IS NULL THEN NULL
            ELSE created_at AT TIME ZONE 'UTC'
        END
    ),
    ALTER COLUMN updated_at TYPE timestamptz
    USING (
        CASE
            WHEN updated_at IS NULL THEN NULL
            ELSE updated_at AT TIME ZONE 'UTC'
        END
    );

ALTER TABLE group_bans
    ALTER COLUMN banned_at TYPE timestamptz
    USING (
        CASE
            WHEN banned_at IS NULL THEN NULL
            ELSE banned_at AT TIME ZONE 'UTC'
        END
    );

ALTER TABLE group_join_links
    ALTER COLUMN created_at TYPE timestamptz
    USING (
        CASE
            WHEN created_at IS NULL THEN NULL
            ELSE created_at AT TIME ZONE 'UTC'
        END
    ),
    ALTER COLUMN revoked_at TYPE timestamptz
    USING (
        CASE
            WHEN revoked_at IS NULL THEN NULL
            ELSE revoked_at AT TIME ZONE 'UTC'
        END
    );
