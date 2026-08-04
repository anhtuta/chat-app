package com.hello.chatapp.dto;

import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupParticipant;
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
public class GroupMemberResponse {
    private Long userId;
    private String username;
    private String fullname;
    private GroupRole role;
    private LocalDateTime joinedAt;
    private Long groupId;
    private String groupName;

    public static GroupMemberResponse fromParticipant(GroupParticipant participant) {
        if (participant == null) {
            return null;
        }
        User user = participant.getUser();
        Group group = participant.getGroup();
        return GroupMemberResponse.builder()
                .userId(user == null ? null : user.getId())
                .username(user == null ? null : user.getUsername())
                .fullname(user == null ? null : user.getFullname())
                .role(participant.getRole())
                .joinedAt(participant.getJoinedAt())
                .groupId(group == null ? null : group.getId())
                .groupName(group == null ? null : group.getName())
                .build();
    }
}
