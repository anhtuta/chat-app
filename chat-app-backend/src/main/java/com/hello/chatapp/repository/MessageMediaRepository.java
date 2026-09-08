package com.hello.chatapp.repository;

import com.hello.chatapp.entity.MessageMedia;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageMediaRepository extends JpaRepository<MessageMedia, Long> {
    List<MessageMedia> findByMessageIdOrderByAttachmentOrderAscIdAsc(Long messageId);

    /**
     * Locks one attachment while an idempotent processing callback updates its canonical object pointer.
     *
     * @param id attachment identifier
     * @param messageId expected parent message identifier
     * @return matching locked attachment
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MessageMedia> findByIdAndMessageId(Long id, Long messageId);
}
