package com.hello.chatapp.dto;

import com.hello.chatapp.entity.ChatScope;
import com.hello.chatapp.entity.MessageType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrepareMediaMessageRequest {

    @NotNull
    private ChatScope chatScope;

    private Long groupId;

    @NotNull
    private MessageType messageType;

    @Valid
    @NotEmpty
    private List<PrepareMediaAttachmentRequest> attachments;
}
