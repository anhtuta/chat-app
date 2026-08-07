package com.hello.chatapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateGroupJoinLinkRequest {
    /** Absolute expiry instant (UTC ISO-8601). Null means the link does not expire. */
    private Instant expiresAt;
}
