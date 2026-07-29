package com.hello.chatapp.service;

import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.dto.GroupResponse;
import com.hello.chatapp.dto.GroupUnreadCountDto;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.BadRequestException;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.MessageRepository;
import com.hello.chatapp.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupParticipantRepository groupParticipantRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final GroupAuthorizationService groupAuthorizationService;
    private final SystemMessageService systemMessageService;

    public GroupService(GroupRepository groupRepository,
            GroupParticipantRepository groupParticipantRepository,
            UserRepository userRepository,
            MessageRepository messageRepository,
            GroupAuthorizationService groupAuthorizationService,
            SystemMessageService systemMessageService) {
        this.groupRepository = groupRepository;
        this.groupParticipantRepository = groupParticipantRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.groupAuthorizationService = groupAuthorizationService;
        this.systemMessageService = systemMessageService;
    }

    @Transactional
    public GroupResponse createGroup(String name, String description, User creator, List<Long> participantIds) {
        // Create the group
        Group group = new Group(normalizeRequiredName(name), creator);
        group.setDescription(normalizeDescription(description));
        group = groupRepository.save(group);

        // Add creator as participant
        GroupParticipant creatorParticipant = new GroupParticipant(group, creator);
        creatorParticipant.setRole(GroupRole.LEADER);
        groupParticipantRepository.save(creatorParticipant);

        // Add other participants
        for (Long userId : participantIds) {
            // Skip if trying to add creator again
            if (userId.equals(creator.getId())) {
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
    public List<GroupResponse> getUserGroups(User user) {
        List<GroupParticipant> participants = groupParticipantRepository.findByUser(user);
        Map<Long, Long> unreadCountByGroupId = getUnreadCountByGroupId(user);
        // Sidebar list: role + unread only. Permissions stay on getGroupDetails.
        return participants.stream()
                .map(participant -> GroupResponse.fromParticipant(
                        participant,
                        unreadCountByGroupId.getOrDefault(participant.getGroup().getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public GroupResponse getGroupDetails(User user, Long groupId) {
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        GroupParticipant participant = groupAuthorizationService.requireMember(user, safeGroupId);
        // Detail view is opened from an already-selected chat; unread stays on the sidebar list API.
        return GroupResponse.fromParticipant(
                participant,
                groupAuthorizationService.getPermissions(participant));
    }

    @Transactional
    public GroupResponse updateGroupDetails(User user, Long groupId, String name, String description) {
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        if (name == null && description == null) {
            throw new BadRequestException("At least one of name or description must be provided");
        }

        Group group = groupAuthorizationService.requireActivePermission(
                user,
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

        Group savedGroup = groupRepository.save(group);
        if (!Objects.equals(group.getName(), originalName)) {
            systemMessageService.recordGroupEvent(savedGroup, user, user, SystemEventType.GROUP_NAME_UPDATED);
        }
        if (!Objects.equals(group.getDescription(), originalDescription)) {
            systemMessageService.recordGroupEvent(savedGroup, user, user, SystemEventType.GROUP_DESCRIPTION_UPDATED);
        }
        // Unread/role/permissions are unchanged; clients keep existing values (realtime fan-out: Phase 12).
        return GroupResponse.fromGroup(savedGroup);
    }

    public Map<Long, Long> getUnreadCountByGroupId(User user) {
        Long userId = Objects.requireNonNull(user.getId(), "user id must not be null");
        List<GroupUnreadCountDto> unreadRows = messageRepository.findUnreadCountRowsByUserId(userId);
        return unreadRows.stream()
                .collect(Collectors.toMap(row -> row.getGroupId(), row -> row.getUnreadCount()));
    }

    public long getTotalUnreadCount(User user) {
        return getUnreadCountByGroupId(user).values().stream()
                .mapToLong(unreadCount -> unreadCount)
                .sum();
    }

    @Transactional
    public void markGroupAsRead(User user, Long groupId, Long lastReadMessageId) {
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        GroupParticipant participant = groupAuthorizationService.requireMember(user, safeGroupId);

        if (lastReadMessageId != null && !messageRepository.existsByIdAndGroup_Id(lastReadMessageId, safeGroupId)) {
            throw new BadRequestException("lastReadMessageId does not belong to this group");
        }

        participant.setLastReadMessageId(lastReadMessageId);
        groupParticipantRepository.save(participant);
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
