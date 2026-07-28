package com.hello.chatapp.service;

import com.hello.chatapp.dto.GroupSummaryUpdate;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.repository.GroupParticipantRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class GroupRealtimeUpdateService {

    private final GroupParticipantRepository groupParticipantRepository;
    private final GroupSummaryUpdatePublisher groupSummaryUpdatePublisher;
    private final GroupAuthorizationService groupAuthorizationService;

    public GroupRealtimeUpdateService(
            GroupParticipantRepository groupParticipantRepository,
            GroupSummaryUpdatePublisher groupSummaryUpdatePublisher,
            GroupAuthorizationService groupAuthorizationService) {
        this.groupParticipantRepository = groupParticipantRepository;
        this.groupSummaryUpdatePublisher = groupSummaryUpdatePublisher;
        this.groupAuthorizationService = groupAuthorizationService;
    }

    public void publishCurrentMembersSnapshot(Long groupId) {
        for (GroupParticipant participant : groupParticipantRepository.findByGroupIdWithUser(groupId)) {
            Group group = participant.getGroup();
            groupSummaryUpdatePublisher.publishToUsername(
                    participant.getUser().getUsername(),
                    GroupSummaryUpdate.upsert(
                            Objects.requireNonNull(group.getId()),
                            group.getName(),
                            group.getDescription(),
                            group.getLatestMessage(),
                            group.getLatestMessageSender(),
                            group.getLatestMessageAt(),
                            groupAuthorizationService.getRole(participant),
                            groupAuthorizationService.getPermissions(participant).stream().toList()));
        }
    }

    public void publishRemovedGroup(Long groupId, String username) {
        groupSummaryUpdatePublisher.publishToUsername(username, GroupSummaryUpdate.removed(groupId));
    }
}
