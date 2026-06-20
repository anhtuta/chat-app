package com.hello.chatapp.entity;

import com.hello.chatapp.storage.ObjectStorageProviderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "media_uploads")
@Getter
@Setter
@NoArgsConstructor
public class MediaUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upload_id", nullable = false, unique = true, length = 255)
    private String uploadId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "chat_scope", nullable = false, length = 32)
    private ChatScope chatScope;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private Group group;

    @Column(name = "upload_session_id", nullable = false, length = 255)
    private String uploadSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_message_type", nullable = false, length = 32)
    private MessageType requestedMessageType;

    @Column(name = "requested_filename", nullable = false, length = 255)
    private String requestedFilename;

    @Column(name = "requested_size_bytes", nullable = false)
    private Long requestedSizeBytes;

    @Column(name = "requested_mime_type", nullable = false, length = 255)
    private String requestedMimeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_provider", nullable = false, length = 32)
    private ObjectStorageProviderType storageProvider;

    @Column(nullable = false, length = 255)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "multipart_upload_id", length = 255)
    private String multipartUploadId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private UploadSessionStatus status = UploadSessionStatus.UPLOAD_INITIATED;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
