package com.hello.chatapp.repository;

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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE MediaUpload mu
            SET mu.multipartUploadId = :multipartUploadId
            WHERE mu.objectKey = :objectKey
                AND mu.multipartUploadId IS NULL
                AND mu.status = UPLOAD_INITIATED
            """)
    int updateMultipartUploadIdIfNewer(
            @Param("objectKey") String objectKey,
            @Param("multipartUploadId") String multipartUploadId);
}
