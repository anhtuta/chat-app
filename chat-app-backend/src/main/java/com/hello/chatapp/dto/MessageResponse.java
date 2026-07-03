package com.hello.chatapp.dto;

import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.entity.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageResponse {
    private Long id;
    private UserResponse user;
    private Long groupId;
    private MessageType messageType;
    private String content;
    private List<MessageAttachmentResponse> attachments;
    private LocalDateTime timestamp;

    public static MessageResponse fromMessage(Message message) {
        if (message == null) {
            return null;
        }
        return MessageResponse.builder()
                .id(message.getId())
                .user(message.getUser() != null ? UserResponse.fromUser(message.getUser()) : null)
                .groupId(message.getGroup() != null ? message.getGroup().getId() : null)
                .messageType(message.getMessageType())
                .content(message.getContent())
                .attachments(message.getAttachments() == null
                        ? Collections.emptyList()
                        : message.getAttachments().stream().map(MessageAttachmentResponse::fromEntity).toList())
                .timestamp(message.getTimestamp())
                .build();
    }
}

