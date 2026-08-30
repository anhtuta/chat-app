package com.hello.chatapp.dto;

import com.hello.chatapp.entity.GroupJoinLink;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupJoinLinkResponse {
    private Long id;
    private Long groupId;
    private String token;
    private Long createdById;
    private String createdByUsername;
    private LocalDateTime createdAt;
    private Instant expiresAt;
    private LocalDateTime revokedAt;

    public static GroupJoinLinkResponse fromJoinLink(GroupJoinLink joinLink, String token) {
        if (joinLink == null) {
            return null;
        }
        return GroupJoinLinkResponse.builder()
                .id(joinLink.getId())
                .groupId(joinLink.getGroup() == null ? null : joinLink.getGroup().getId())
                .token(token)
                .createdById(joinLink.getCreatedBy() == null ? null : joinLink.getCreatedBy().getId())
                .createdByUsername(joinLink.getCreatedBy() == null ? null : joinLink.getCreatedBy().getUsername())
                .createdAt(joinLink.getCreatedAt())
                .expiresAt(joinLink.getExpiresAt())
                .revokedAt(joinLink.getRevokedAt())
                .build();
    }
}
