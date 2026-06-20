package com.hello.chatapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreparedMediaAttachmentResponse {
    private String attachmentId;
    private String objectKey;
    private UploadStrategy uploadStrategy;
    private String presignedUrl;
    private String multipartUploadId;
    private Long recommendedPartSize;
    private LocalDateTime completeBy;
}
