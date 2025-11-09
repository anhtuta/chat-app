package com.hello.chatapp.repository;

import com.hello.chatapp.entity.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GroupRepository extends JpaRepository<Group, Long> {
    @Query("SELECT g FROM Group g JOIN FETCH g.createdBy WHERE g.id = :id")
    Optional<Group> findByIdWithCreator(Long id);
}
