package com.hello.chatapp.repository;

import com.hello.chatapp.entity.MediaUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MediaUploadRepository extends JpaRepository<MediaUpload, Long> {
    Optional<MediaUpload> findByUploadId(String uploadId);

    List<MediaUpload> findByUploadSessionIdOrderByIdAsc(String uploadSessionId);

    Optional<MediaUpload> findByUploadSessionIdAndUploadId(String uploadSessionId, String uploadId);
}
