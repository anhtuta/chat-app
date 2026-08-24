package com.hello.chatapp.repository;

import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for active group membership rows. Each row counts toward group member capacity.
 */
@Repository
public interface GroupParticipantRepository extends JpaRepository<GroupParticipant, Long> {
    List<GroupParticipant> findByGroup(Group group);

    @Query("""
            SELECT gp FROM GroupParticipant gp
            JOIN FETCH gp.group g
            JOIN FETCH g.createdBy
            WHERE gp.user = :user
              AND g.archivedAt IS NULL
            ORDER BY COALESCE(g.latestMessageAt, g.createdAt) DESC, g.id DESC
            """)
    List<GroupParticipant> findByUser(User user);

    @Query("""
            SELECT u.username
            FROM GroupParticipant gp
            JOIN gp.user u
            WHERE gp.group.id = :groupId
            """)
    List<String> findParticipantUsernamesByGroupId(@Param("groupId") Long groupId);

    @Query("""
            SELECT gp FROM GroupParticipant gp
            JOIN FETCH gp.group g
            WHERE g.id = :groupId AND gp.user = :user
            """)
    Optional<GroupParticipant> findByGroupIdAndUser(@Param("groupId") Long groupId, @Param("user") User user);

    /**
     * <p>
     * Find group participants by group ID and search text.
     * Note:
     * 1. PostgreSQL was inferring the CONCAT('%', :search, '%') / || expression as bytea, so LOWER(...) failed.
     * We need to cast the search as string.
     * 2. We don't need to add index on username and fullname because:
     * This query always filters by group_id first (covered by uk_group_participants_group_user),
     * then filters that small set by name. That’s cheap for normal group sizes.
     * If the group is large, we can use pg_trgm GIN indexes.
     * </p>
     * 
     * @param groupId the group ID
     * @param search the search text, we search by user's username or fullname
     * @param pageable the pageable
     * @return the page of group participants
     */
    @Query(
            value = """
                    SELECT gp FROM GroupParticipant gp
                    JOIN FETCH gp.user u
                    WHERE gp.group.id = :groupId
                      AND (
                        :search IS NULL
                        OR LOWER(u.username) LIKE LOWER(CAST(:search AS string)) ESCAPE '\\'
                        OR LOWER(u.fullname) LIKE LOWER(CAST(:search AS string)) ESCAPE '\\'
                      )
                    ORDER BY gp.joinedAt ASC, gp.id ASC
                    """,
            countQuery = """
                    SELECT COUNT(gp) FROM GroupParticipant gp
                    JOIN gp.user u
                    WHERE gp.group.id = :groupId
                      AND (
                        :search IS NULL
                        OR LOWER(u.username) LIKE LOWER(CAST(:search AS string)) ESCAPE '\\'
                        OR LOWER(u.fullname) LIKE LOWER(CAST(:search AS string)) ESCAPE '\\'
                      )
                    """)
    Page<GroupParticipant> findByGroupIdWithUser(
            @Param("groupId") Long groupId,
            @Param("search") String search,
            Pageable pageable);

    @Query("""
            SELECT gp FROM GroupParticipant gp
            JOIN FETCH gp.user
            JOIN FETCH gp.group
            WHERE gp.group.id = :groupId AND gp.user.id = :userId
            """)
    Optional<GroupParticipant> findByGroupIdAndUserId(
            @Param("groupId") Long groupId,
            @Param("userId") Long userId);

    /**
     * Locks the actor’s membership row ({@code SELECT … FOR UPDATE}) without joining {@code groups},
     * so concurrent edits by different members do not serialize on the group row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT gp FROM GroupParticipant gp
            WHERE gp.group.id = :groupId AND gp.user.id = :userId
            """)
    Optional<GroupParticipant> findByGroupIdAndUserIdForUpdate(
            @Param("groupId") Long groupId,
            @Param("userId") Long userId);

    @Query("""
            SELECT COUNT(gp)
            FROM GroupParticipant gp
            WHERE gp.group.id = :groupId
            """)
    /**
     * Counts active participants for the group. There is no soft-delete participant state today,
     * so every {@code group_participants} row counts toward member capacity.
     */
    long countByGroupId(@Param("groupId") Long groupId);

    Optional<GroupParticipant> findByGroupAndUser(Group group, User user);
    boolean existsByGroupAndUser(Group group, User user);
}
