package com.hello.chatapp.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddGroupMemberRequest {
    @NotNull(message = "userId is required")
    private Long userId;
}
