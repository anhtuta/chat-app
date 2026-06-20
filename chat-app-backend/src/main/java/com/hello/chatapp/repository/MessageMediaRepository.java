package com.hello.chatapp.repository;

import com.hello.chatapp.entity.MessageMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageMediaRepository extends JpaRepository<MessageMedia, Long> {
}
