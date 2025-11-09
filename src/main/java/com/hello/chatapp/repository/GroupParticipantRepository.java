package com.hello.chatapp.repository;

import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupParticipantRepository extends JpaRepository<GroupParticipant, Long> {
    List<GroupParticipant> findByGroup(Group group);
    
    @Query("SELECT gp FROM GroupParticipant gp JOIN FETCH gp.group g JOIN FETCH g.createdBy WHERE gp.user = :user")
    List<GroupParticipant> findByUser(User user);
    
    Optional<GroupParticipant> findByGroupAndUser(Group group, User user);
    boolean existsByGroupAndUser(Group group, User user);
}
