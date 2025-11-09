package com.hello.chatapp.dto;

import com.hello.chatapp.entity.Group;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupResponse {
    private Long id;
    private String name;
    private Long createdById;
    private String createdByUsername;
    private LocalDateTime createdAt;

    public static GroupResponse fromGroup(Group group) {
        if (group == null) {
            return null;
        }
        return GroupResponse.builder()
                .id(group.getId())
                .name(group.getName())
                .createdById(group.getCreatedBy().getId())
                .createdByUsername(group.getCreatedBy().getUsername())
                .createdAt(group.getCreatedAt())
                .build();
    }
}

