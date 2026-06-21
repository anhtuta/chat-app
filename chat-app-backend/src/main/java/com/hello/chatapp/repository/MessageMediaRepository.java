package com.hello.chatapp.repository;

import com.hello.chatapp.entity.MessageMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageMediaRepository extends JpaRepository<MessageMedia, Long> {
    List<MessageMedia> findByMessageIdOrderByAttachmentOrderAscIdAsc(Long messageId);
}
