package com.hello.chatapp.repository;

import com.hello.chatapp.constant.UploadSessionStatus;
import com.hello.chatapp.entity.MediaUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MediaUploadRepository extends JpaRepository<MediaUpload, Long> {
    Optional<MediaUpload> findByUploadId(String uploadId);

    List<MediaUpload> findByUploadSessionIdOrderByIdAsc(String uploadSessionId);

    Optional<MediaUpload> findByUploadSessionIdAndUploadId(String uploadSessionId, String uploadId);

    /**
     * Atomically claims {@code multipartUploadId} for a prepared attachment when still unset.
     * Returns {@code 1} if this caller won the claim, or {@code 0} if another request already set it
     * (or the row is no longer in {@link UploadSessionStatus#UPLOAD_INITIATED}).
     *
     * @param id primary key of the {@link MediaUpload} row
     * @param multipartUploadId provider multipart upload id to claim
     * @param expectedStatus only claim while the row is still in this status (typically {@code UPLOAD_INITIATED})
     * @return number of rows updated ({@code 0} or {@code 1})
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE MediaUpload mu
            SET mu.multipartUploadId = :multipartUploadId
            WHERE mu.id = :id
                AND mu.multipartUploadId IS NULL
                AND mu.status = :expectedStatus
            """)
    int claimMultipartUploadId(
            @Param("id") Long id,
            @Param("multipartUploadId") String multipartUploadId,
            @Param("expectedStatus") UploadSessionStatus expectedStatus);
}
