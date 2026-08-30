package com.hello.chatapp.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request body for {@code POST /api/groups/{groupId}/members}.
 * Adds one or more users in a single request.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddGroupMemberRequest {

    @NotEmpty(message = "At least one userId is required")
    @Size(max = 500, message = "At most 500 userIds are allowed")
    private List<@NotNull(message = "userId is required") Long> userIds;
}
