package com.hello.chatapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for {@code GET /api/auth/check}: whether the current session is authenticated,
 * plus identity fields when it is.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthCheckResponse {
    private boolean authenticated;
    private String username;
    private String fullname;
}
