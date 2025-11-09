package com.hello.chatapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageRequest {
    private String content;
    private Long groupId; // Optional: null for public messages, groupId for group messages
}

