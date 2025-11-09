package com.hello.chatapp.repository;

import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {
    @Query("SELECT m FROM Message m JOIN FETCH m.user WHERE m.group IS NULL ORDER BY m.timestamp ASC")
    List<Message> findAllPublicMessages();
    
    @Query("SELECT m FROM Message m JOIN FETCH m.user WHERE m.group = :group ORDER BY m.timestamp ASC")
    List<Message> findByGroupOrderByTimestampAsc(Group group);
}

