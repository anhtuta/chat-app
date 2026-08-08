package com.hello.chatapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One attachment plan returned by prepare-upload. For {@link UploadStrategy#MULTIPART},
 * {@code multipartUploadId} is issued later by the parts endpoint, not here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreparedMediaAttachmentResponse {
    private String attachmentId;
    private String objectKey;
    private UploadStrategy uploadStrategy;
    private String presignedUrl;
    private Long recommendedPartSize;
    // TODO: rename to expiresAt?
    private LocalDateTime completeBy;
}
