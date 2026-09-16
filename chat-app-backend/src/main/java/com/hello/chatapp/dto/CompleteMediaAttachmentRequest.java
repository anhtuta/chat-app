package com.hello.chatapp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompleteMediaAttachmentRequest {

    @NotBlank
    private String attachmentId;

    private String etag;

    @Valid
    private List<CompletedMultipartPartRequest> parts;
}
