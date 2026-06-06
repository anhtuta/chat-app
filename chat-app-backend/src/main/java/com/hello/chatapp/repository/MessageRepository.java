package com.hello.chatapp.repository;

import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {
    @Query("SELECT m FROM Message m JOIN FETCH m.user WHERE m.group IS NULL ORDER BY m.timestamp ASC")
    List<Message> findAllPublicMessages();

    @Query("SELECT m FROM Message m JOIN FETCH m.user WHERE m.group = :group ORDER BY m.timestamp ASC")
    List<Message> findByGroupOrderByTimestampAsc(Group group);

    @Query("""
            SELECT m FROM Message m
            JOIN FETCH m.user
            WHERE m.group = :group
            ORDER BY m.timestamp DESC, m.id DESC
            """)
    List<Message> findLatestGroupMessages(
            @Param("group") Group group,
            Pageable pageable);

    @Query("""
            SELECT m FROM Message m
            JOIN FETCH m.user
            WHERE m.group = :group
                AND (m.timestamp < :beforeTimestamp OR (m.timestamp = :beforeTimestamp AND m.id < :beforeId))
            ORDER BY m.timestamp DESC, m.id DESC
            """)
    List<Message> findGroupMessagesBeforeCursor(
            @Param("group") Group group,
            @Param("beforeTimestamp") LocalDateTime beforeTimestamp,
            @Param("beforeId") Long beforeId,
            Pageable pageable);

    @Query("""
            SELECT COUNT(m) FROM Message m
            WHERE m.group.id = :groupId
              AND (:lastReadMessageId IS NULL OR m.id > :lastReadMessageId)
            """)
    long countUnreadByGroupIdAndLastReadMessageId(
            @Param("groupId") Long groupId,
            @Param("lastReadMessageId") Long lastReadMessageId);

    boolean existsByIdAndGroup_Id(Long id, Long groupId);
}

