package com.hello.chatapp.entity;

import com.hello.chatapp.constant.MediaScanStatus;
import com.hello.chatapp.constant.MediaStatus;
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

/**
 * media_uploads is the staging ledger for direct-to-storage uploads;
 * message_media is the permanent attachment record once the message is published
 */
@Entity
@Table(name = "message_media")
@Getter
@Setter
@NoArgsConstructor
public class MessageMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @Column(name = "attachment_order", nullable = false)
    private Integer attachmentOrder = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_provider", nullable = false, length = 32)
    private ObjectStorageProviderType storageProvider;

    @Column(nullable = false, length = 255)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "declared_mime_type", nullable = false, length = 255)
    private String declaredMimeType;

    @Column(name = "detected_mime_type", length = 255)
    private String detectedMimeType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "checksum_sha256", length = 128)
    private String checksumSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 64)
    private MediaStatus status = MediaStatus.UPLOAD_COMPLETED;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false, length = 64)
    private MediaScanStatus scanStatus = MediaScanStatus.SCAN_PENDING;

    @Column
    private Integer width;

    @Column
    private Integer height;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "thumbnail_object_key", length = 512)
    private String thumbnailObjectKey;

    @Column(name = "preview_object_key", length = 512)
    private String previewObjectKey;

    @Column(name = "transcoded_object_key", length = 512)
    private String transcodedObjectKey;

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
