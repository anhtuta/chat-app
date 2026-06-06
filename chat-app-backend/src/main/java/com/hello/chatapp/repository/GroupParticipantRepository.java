package com.hello.chatapp.repository;

import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupParticipantRepository extends JpaRepository<GroupParticipant, Long> {
    List<GroupParticipant> findByGroup(Group group);

    @Query("""
            SELECT gp FROM GroupParticipant gp
            JOIN FETCH gp.group g
            JOIN FETCH g.createdBy
            WHERE gp.user = :user
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

    Optional<GroupParticipant> findByGroupAndUser(Group group, User user);
    boolean existsByGroupAndUser(Group group, User user);
}
