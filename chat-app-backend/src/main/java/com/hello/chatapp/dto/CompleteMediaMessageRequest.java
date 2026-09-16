package com.hello.chatapp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompleteMediaMessageRequest {

    @Valid
    @NotEmpty
    private List<CompleteMediaAttachmentRequest> attachments;
}
