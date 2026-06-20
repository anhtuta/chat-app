package com.hello.chatapp.dto;

import com.hello.chatapp.entity.ChatScope;
import com.hello.chatapp.entity.MessageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrepareMediaMessageResponse {
    private String uploadSessionId;
    private MessageType messageType;
    private ChatScope chatScope;
    private LocalDateTime expiresAt;
    private Integer retentionDays;
    private Limits limits;
    private List<PreparedMediaAttachmentResponse> attachments;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Limits {
        private Long maxSizeBytes;
        private Integer maxAttachmentCount;
    }
}
