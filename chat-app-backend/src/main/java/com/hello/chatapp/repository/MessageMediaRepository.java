package com.hello.chatapp.repository;

import com.hello.chatapp.entity.MessageMedia;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Loads attachments whose replaced original is still waiting for object-storage deletion.
     *
     * @return media rows with a pending replaced-original object key
     */
    List<MessageMedia> findByReplacedOriginalObjectKeyIsNotNull();

    /**
     * Clears pending original cleanup only when the stored key still matches.
     *
     * @param id attachment identifier
     * @param objectKey replaced original key that was deleted or is already absent
     * @return {@code 1} when the pending key was cleared
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE MessageMedia m
            SET m.replacedOriginalObjectKey = NULL
            WHERE m.id = :id
                AND m.replacedOriginalObjectKey = :objectKey
            """)
    int clearReplacedOriginalObjectKey(@Param("id") Long id, @Param("objectKey") String objectKey);
}
