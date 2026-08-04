package com.hello.chatapp.repository;

import com.hello.chatapp.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);

    /**
     * Users who can be added to the group: not already members and not banned.
     * Optional {@code search} matches username/fullname (normalized LIKE pattern, or null for no filter).
     * Pass a {@link Pageable} to cap the result size (no pagination response).
     */
    @Query("""
            SELECT u FROM User u
            WHERE NOT EXISTS (
                SELECT 1 FROM GroupParticipant gp
                WHERE gp.group.id = :groupId AND gp.user = u
            )
            AND NOT EXISTS (
                SELECT 1 FROM GroupBan gb
                WHERE gb.group.id = :groupId AND gb.user = u
            )
            AND (
                :search IS NULL
                OR LOWER(u.username) LIKE LOWER(CAST(:search AS string)) ESCAPE '\\'
                OR LOWER(u.fullname) LIKE LOWER(CAST(:search AS string)) ESCAPE '\\'
            )
            ORDER BY u.username ASC
            """)
    List<User> findAddableUsersForGroup(
            @Param("groupId") Long groupId,
            @Param("search") String search,
            Pageable pageable);
}
