package com.hello.chatapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JSON body for the public liveness endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthResponse {
    private String status;
}
