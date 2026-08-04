package com.hello.chatapp.dto;

import com.hello.chatapp.entity.GroupBan;
import com.hello.chatapp.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupBanResponse {
    private Long userId;
    private String username;
    private String fullname;
    private String reason;
    private LocalDateTime bannedAt;
    private Long bannedByUserId;
    private String bannedByUsername;

    public static GroupBanResponse fromBan(GroupBan ban) {
        if (ban == null) {
            return null;
        }
        User user = ban.getUser();
        User bannedBy = ban.getBannedBy();
        return GroupBanResponse.builder()
                .userId(user == null ? null : user.getId())
                .username(user == null ? null : user.getUsername())
                .fullname(user == null ? null : user.getFullname())
                .reason(ban.getReason())
                .bannedAt(ban.getBannedAt())
                .bannedByUserId(bannedBy == null ? null : bannedBy.getId())
                .bannedByUsername(bannedBy == null ? null : bannedBy.getUsername())
                .build();
    }
}
