package com.hello.chatapp.repository;

import com.hello.chatapp.entity.MessageEditHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageEditHistoryRepository extends JpaRepository<MessageEditHistory, Long> {
}
