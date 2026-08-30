package com.hello.chatapp.repository;

import com.hello.chatapp.dto.GroupUnreadCountDto;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {
    // TODO: Add pagination, for now, only get latest 100 public messages
    @Query("SELECT m.id FROM Message m WHERE m.group IS NULL ORDER BY m.timestamp ASC LIMIT 100")
    List<Long> findAllPublicMessageIds();

    @Query("SELECT m FROM Message m JOIN FETCH m.user WHERE m.group = :group ORDER BY m.timestamp ASC")
    List<Message> findByGroupOrderByTimestampAsc(Group group);

    @Query("""
            SELECT m.id FROM Message m
            WHERE m.group = :group
            ORDER BY m.timestamp DESC, m.id DESC
            """)
    List<Long> findLatestGroupMessageIds(
            @Param("group") Group group,
            Pageable pageable);

    @Query("""
            SELECT m.id FROM Message m
            WHERE m.group = :group
                AND (m.timestamp < :beforeTimestamp OR (m.timestamp = :beforeTimestamp AND m.id < :beforeId))
            ORDER BY m.timestamp DESC, m.id DESC
            """)
    List<Long> findGroupMessageIdsBeforeCursor(
            @Param("group") Group group,
            @Param("beforeTimestamp") LocalDateTime beforeTimestamp,
            @Param("beforeId") Long beforeId,
            Pageable pageable);

    @Query("""
            SELECT new com.hello.chatapp.dto.GroupUnreadCountDto(
                gp.group.id,
                COUNT(m.id)
            )
            FROM GroupParticipant gp
            LEFT JOIN Message m
                ON m.group.id = gp.group.id
                AND (gp.lastReadMessageId IS NULL OR m.id > gp.lastReadMessageId)
            WHERE gp.user.id = :userId
            GROUP BY gp.group.id
            """)
    List<GroupUnreadCountDto> findUnreadCountRowsByUserId(@Param("userId") Long userId);

    boolean existsByIdAndGroup_Id(Long id, Long groupId);

    /**
     * Similar to {@link JpaRepository#findById()}, but eagerly fetches user, group, and attachments.
     * (Note: WithMedia is not a JPA keyword, it's descriptive only and will be ignored).
     * Why we use this name? If we use `findById`, we cannot add that overload on JpaRepository without conflicting with the
     * inherited findById. That is why teams often use a descriptive alias like findWithMediaById or findMessageGraphById.
     */
    @EntityGraph(attributePaths = {"user", "group", "updatedBy", "deletedBy", "attachments"})
    Optional<Message> findWithMediaById(Long id);

    @EntityGraph(attributePaths = {"user", "group", "updatedBy", "deletedBy", "attachments"})
    List<Message> findWithMediaByIdIn(Collection<Long> ids);

    @EntityGraph(attributePaths = {"user", "updatedBy", "deletedBy", "attachments"})
    Optional<Message> findTopByGroup_IdOrderByTimestampDescIdDesc(Long groupId);
}

