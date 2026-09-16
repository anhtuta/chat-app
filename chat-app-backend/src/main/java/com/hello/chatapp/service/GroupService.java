package com.hello.chatapp.service;

import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.dto.GroupResponse;
import com.hello.chatapp.dto.GroupUnreadCountDto;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.BadRequestException;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.MessageRepository;
import com.hello.chatapp.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Creates and updates groups, including optional member-capacity ({@code maxMembers}) on create/PATCH.
 */
@Service
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupParticipantRepository groupParticipantRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final GroupAuthorizationService groupAuthorizationService;
    private final SystemMessageService systemMessageService;
    private final GroupProfileRealtimePublisher groupProfileRealtimePublisher;

    public GroupService(GroupRepository groupRepository,
            GroupParticipantRepository groupParticipantRepository,
            UserRepository userRepository,
            MessageRepository messageRepository,
            GroupAuthorizationService groupAuthorizationService,
            SystemMessageService systemMessageService,
            GroupProfileRealtimePublisher groupProfileRealtimePublisher) {
        this.groupRepository = groupRepository;
        this.groupParticipantRepository = groupParticipantRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.groupAuthorizationService = groupAuthorizationService;
        this.systemMessageService = systemMessageService;
        this.groupProfileRealtimePublisher = groupProfileRealtimePublisher;
    }

    /**
     * Creates a group, persists {@code maxMembers}, and inserts the creator plus invited participants.
     * Rejects the whole request (no group or participant rows) when a positive limit is smaller
     * than the distinct set of {@code {creator} ∪ participantIds}.
     *
     * @param maxMembers omitted/{@code null}/{@code 0} means unlimited; values below {@code 0} are rejected
     */
    @Transactional
    public GroupResponse createGroup(
            String name,
            String description,
            User creator,
            List<Long> participantIds,
            Integer maxMembers) {
        Integer storedMaxMembers = normalizeStoredMaxMembers(maxMembers);
        validateInitialMembershipFitsLimit(creator, participantIds, storedMaxMembers);

        Group group = new Group(normalizeRequiredName(name), creator);
        group.setDescription(normalizeDescription(description));
        group.setMaxMembers(storedMaxMembers);
        group = groupRepository.save(group);

        // Add creator as participant
        GroupParticipant creatorParticipant = new GroupParticipant(group, creator);
        creatorParticipant.setRole(GroupRole.LEADER);
        groupParticipantRepository.save(creatorParticipant);

        // Add other participants
        List<Long> invitedIds = participantIds == null ? List.of() : participantIds;
        for (Long userId : invitedIds) {
            // Skip if trying to add creator again
            if (userId == null || userId.equals(creator.getId())) {
                continue;
            }

            User participant = userRepository.findById(userId)
                    .orElseThrow(() -> new NotFoundException("User with id " + userId + " not found"));

            // Check if already a participant
            if (!groupParticipantRepository.existsByGroupAndUser(group, participant)) {
                GroupParticipant groupParticipant = new GroupParticipant(group, participant);
                groupParticipantRepository.save(groupParticipant);
            }
        }

        // Fetch group with creator to avoid LazyInitializationException
        Group persistedGroup = groupRepository.findByIdWithCreator(group.getId()).orElse(group);
        return GroupResponse.fromGroup(
                persistedGroup,
                GroupRole.LEADER,
                groupAuthorizationService.getPermissions(GroupRole.LEADER),
                0L);
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<GroupResponse> getUserGroups(User currentUser) {
        List<GroupParticipant> participants = groupParticipantRepository.findByUser(currentUser);
        Map<Long, Long> unreadCountByGroupId = getUnreadCountByGroupId(currentUser);
        // Sidebar list: role + unread only. Permissions stay on getGroupDetails.
        return participants.stream()
                .map(participant -> GroupResponse.fromParticipant(
                        participant,
                        unreadCountByGroupId.getOrDefault(participant.getGroup().getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public GroupResponse getGroupDetails(User currentUser, Long groupId) {
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        GroupParticipant participant = groupAuthorizationService.requireMember(currentUser, safeGroupId);
        // Detail view is opened from an already-selected chat; unread stays on the sidebar list API.
        return GroupResponse.fromParticipant(
                participant,
                groupAuthorizationService.getPermissions(participant));
    }

    /**
     * Patches group name, description, and/or {@code maxMembers}.
     * Omitted {@code maxMembers} leaves the current limit unchanged. Explicit {@code null} or {@code 0}
     * stores unlimited. Lowering below the current participant count is allowed (over-limit state).
     *
     * @param maxMembersPresent {@code true} when the PATCH body included {@code maxMembers}
     */
    @Transactional
    public GroupResponse updateGroupDetails(
            User actor,
            Long groupId,
            String name,
            String description,
            Integer maxMembers,
            boolean maxMembersPresent) {
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        if (name == null && description == null && !maxMembersPresent) {
            throw new BadRequestException("At least one of name, description, or maxMembers must be provided");
        }

        Group group = groupAuthorizationService.requireActivePermission(
                actor,
                safeGroupId,
                GroupPermission.MANAGE_GROUP_DETAILS);
        String originalName = group.getName();
        String originalDescription = group.getDescription();

        if (name != null) {
            group.setName(normalizeRequiredName(name));
        }
        if (description != null) {
            group.setDescription(normalizeDescription(description));
        }
        if (maxMembersPresent) {
            group.setMaxMembers(normalizeStoredMaxMembers(maxMembers));
        }

        Group savedGroup = groupRepository.save(group);
        if (!Objects.equals(group.getName(), originalName)) {
            Message systemMessage = systemMessageService.recordGroupEvent(
                    savedGroup,
                    actor,
                    actor,
                    SystemEventType.GROUP_NAME_UPDATED);
            groupProfileRealtimePublisher.publishGroupProfileChange(
                    savedGroup,
                    systemMessage,
                    SystemEventType.GROUP_NAME_UPDATED.latestPreview());
        }
        if (!Objects.equals(group.getDescription(), originalDescription)) {
            Message systemMessage = systemMessageService.recordGroupEvent(
                    savedGroup,
                    actor,
                    actor,
                    SystemEventType.GROUP_DESCRIPTION_UPDATED);
            groupProfileRealtimePublisher.publishGroupProfileChange(
                    savedGroup,
                    systemMessage,
                    SystemEventType.GROUP_DESCRIPTION_UPDATED.latestPreview());
        }
        // TODO: confirm whether max-member changes should create a system message like group name/description updates.

        // Unread/role/permissions are unchanged; clients keep existing values (realtime fan-out: Phase 12).
        // recordGroupEvent → updateLatestMessageIfNewer clears the persistence context
        // (@Modifying(clearAutomatically = true)), so re-fetch createdBy before mapping the response.
        Group responseGroup = groupRepository.findByIdWithCreator(safeGroupId).orElse(savedGroup);
        return GroupResponse.fromGroup(responseGroup);
    }

    public Map<Long, Long> getUnreadCountByGroupId(User currentUser) {
        Long userId = Objects.requireNonNull(currentUser.getId(), "user id must not be null");
        List<GroupUnreadCountDto> unreadRows = messageRepository.findUnreadCountRowsByUserId(userId);
        return unreadRows.stream()
                .collect(Collectors.toMap(row -> row.getGroupId(), row -> row.getUnreadCount()));
    }

    @Transactional
    public void markGroupAsRead(User actor, Long groupId, Long lastReadMessageId) {
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        GroupParticipant participant = groupAuthorizationService.requireMember(actor, safeGroupId);

        if (lastReadMessageId != null && !messageRepository.existsByIdAndGroup_Id(lastReadMessageId, safeGroupId)) {
            throw new BadRequestException("lastReadMessageId does not belong to this group");
        }

        participant.setLastReadMessageId(lastReadMessageId);
        groupParticipantRepository.save(participant);
    }

    /**
     * Stores only {@code null}, {@code 0} (unlimited), or a positive cap. Rejects negatives before persistence.
     */
    private Integer normalizeStoredMaxMembers(Integer maxMembers) {
        if (maxMembers == null) {
            return null;
        }
        if (maxMembers < 0) {
            throw new BadRequestException("maxMembers must not be negative");
        }
        return maxMembers;
    }

    /**
     * Rejects create when a positive cap is smaller than the distinct invited membership including the creator.
     */
    private void validateInitialMembershipFitsLimit(User creator, List<Long> participantIds, Integer maxMembers) {
        if (maxMembers == null || maxMembers == 0) {
            return;
        }
        Set<Long> distinctMemberIds = new HashSet<>();
        if (creator != null && creator.getId() != null) {
            distinctMemberIds.add(creator.getId());
        }
        if (participantIds != null) {
            for (Long participantId : participantIds) {
                if (participantId != null) {
                    distinctMemberIds.add(participantId);
                }
            }
        }
        if (distinctMemberIds.size() > maxMembers) {
            throw new BadRequestException("Initial membership exceeds the group member limit");
        }
    }

    private String normalizeRequiredName(String name) {
        String normalizedName = Objects.requireNonNull(name, "name must not be null").trim();
        if (normalizedName.isEmpty()) {
            throw new BadRequestException("Group name is required");
        }
        return normalizedName;
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String normalizedDescription = description.trim();
        return normalizedDescription.isEmpty() ? null : normalizedDescription;
    }

}
