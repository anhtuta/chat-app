package com.hello.chatapp.dto;

import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.constant.MediaScanStatus;
import com.hello.chatapp.constant.MediaStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageAttachmentResponse {
    private Long id;
    private Integer attachmentOrder;
    private String originalFilename;
    private String mimeType;
    private Long sizeBytes;
    private MediaStatus status;
    private MediaScanStatus scanStatus;
    private Integer width;
    private Integer height;
    private Long durationMs;
    private String contentUrl;
    private String thumbnailUrl;
    private String previewUrl;
    private String transcodedUrl;
    // private String thumbnailObjectKey;
    // private String previewObjectKey;
    // private String transcodedObjectKey;

    public static MessageAttachmentResponse fromEntity(MessageMedia media) {
        if (media == null) {
            return null;
        }
        return MessageAttachmentResponse.builder()
                .id(media.getId())
                .attachmentOrder(media.getAttachmentOrder())
                .originalFilename(media.getOriginalFilename())
                .mimeType(media.getDetectedMimeType() != null ? media.getDetectedMimeType() : media.getDeclaredMimeType())
                .sizeBytes(media.getSizeBytes())
                .status(media.getStatus())
                .scanStatus(media.getScanStatus())
                .width(media.getWidth())
                .height(media.getHeight())
                .durationMs(media.getDurationMs())
                // .thumbnailObjectKey(media.getThumbnailObjectKey())
                // .previewObjectKey(media.getPreviewObjectKey())
                // .transcodedObjectKey(media.getTranscodedObjectKey())
                .build();
    }
}
