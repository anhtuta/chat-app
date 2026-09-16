package com.hello.chatapp.repository;

import com.hello.chatapp.entity.Group;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface GroupRepository extends JpaRepository<Group, Long> {
    @Query("SELECT g FROM Group g JOIN FETCH g.createdBy WHERE g.id = :id")
    Optional<Group> findByIdWithCreator(Long id);

    /**
     * Locks the group row ({@code SELECT … FOR UPDATE}) for membership lifecycle changes.
     * Used to serialize last-member leave/archive against concurrent add/join so a new
     * participant cannot be inserted into a group that is about to be (or already) archived.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM Group g WHERE g.id = :id")
    Optional<Group> findByIdForUpdate(@Param("id") Long id);

    /**
     * Atomically updates the group's latest message summary fields if the provided message is newer than the current latest message.
     * This method uses a conditional update to ensure that it only updates the group if the new message is more recent
     * than the existing latest message, preventing stale updates in concurrent scenarios.
     * About the last OR condition (OR g.latestMessageAt = :latestMessageAt...):
     * 1. If timestamps tie, only let the row with bigger message id win, preventing an older/equal candidate from overwriting latest.
     * 2. Timestamp alone cannot decide which one is latest.
     * 3. Message id provides deterministic ordering inside that timestamp.
     * 4. Lệnh OR này xảy ra khi 2 message có cùng timestamp, lúc này message nào có id lớn hơn sẽ được coi là mới hơn và cập nhật vào group.
     * @param groupId
     * @param latestMessage
     * @param latestMessageSender
     * @param latestMessageAt
     * @param messageId
     * @return the number of rows affected (should be 1 if the update was successful, or 0 if the existing latest message is newer)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Group g
            SET g.latestMessage = :latestMessage,
                g.latestMessageSender = :latestMessageSender,
                g.latestMessageAt = :latestMessageAt
            WHERE g.id = :groupId
                AND (
                    g.latestMessageAt IS NULL
                    OR g.latestMessageAt < :latestMessageAt
                    OR (
                        g.latestMessageAt = :latestMessageAt
                        AND (
                            SELECT COALESCE(MAX(m.id), 0)
                            FROM Message m
                            WHERE m.group.id = :groupId
                              AND m.timestamp = g.latestMessageAt
                        ) < :messageId
                    )
                )
            """)
    int updateLatestMessageIfNewer(
            @Param("groupId") Long groupId,
            @Param("latestMessage") String latestMessage,
            @Param("latestMessageSender") String latestMessageSender,
            @Param("latestMessageAt") LocalDateTime latestMessageAt,
            @Param("messageId") Long messageId);

    /**
     * Like {@link #updateLatestMessageIfNewer}, but also allows rewriting the summary when the candidate
     * message is already the stored latest (same timestamp and same winning message id).
     * <p>
     * Used by {@code refreshGroupLatestMessage} after edit/delete: the chronologically latest row may be
     * unchanged while its preview must change. The tie-break uses {@code <=} instead of {@code <} so that
     * case succeeds, while a concurrently written newer message still causes {@code 0} rows updated.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Group g
            SET g.latestMessage = :latestMessage,
                g.latestMessageSender = :latestMessageSender,
                g.latestMessageAt = :latestMessageAt
            WHERE g.id = :groupId
                AND (
                    g.latestMessageAt IS NULL
                    OR g.latestMessageAt < :latestMessageAt
                    OR (
                        g.latestMessageAt = :latestMessageAt
                        AND (
                            SELECT COALESCE(MAX(m.id), 0)
                            FROM Message m
                            WHERE m.group.id = :groupId
                              AND m.timestamp = g.latestMessageAt
                        ) <= :messageId
                    )
                )
            """)
    int updateLatestMessageIfNotStale(
            @Param("groupId") Long groupId,
            @Param("latestMessage") String latestMessage,
            @Param("latestMessageSender") String latestMessageSender,
            @Param("latestMessageAt") LocalDateTime latestMessageAt,
            @Param("messageId") Long messageId);

    /**
     * Clears denormalized latest-message fields only when the group has no messages.
     * Avoids wiping a concurrent send's summary with an unconditional entity save.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Group g
            SET g.latestMessage = null,
                g.latestMessageSender = null,
                g.latestMessageAt = null
            WHERE g.id = :groupId
              AND NOT EXISTS (
                  SELECT 1 FROM Message m WHERE m.group.id = :groupId
              )
            """)
    int clearLatestMessageIfEmpty(@Param("groupId") Long groupId);
}
