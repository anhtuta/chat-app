package com.hello.chatapp.dto;

import com.hello.chatapp.constant.GroupRole;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateGroupMemberRoleRequest {
    @NotNull(message = "role is required")
    private GroupRole role;
}
