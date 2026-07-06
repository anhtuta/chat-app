package com.hello.chatapp.service;

import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.dto.GroupUnreadCountDto;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.BadRequestException;
import com.hello.chatapp.exception.ForbiddenException;
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

    public GroupService(GroupRepository groupRepository,
            GroupParticipantRepository groupParticipantRepository,
            UserRepository userRepository,
            MessageRepository messageRepository) {
        this.groupRepository = groupRepository;
        this.groupParticipantRepository = groupParticipantRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public Group createGroup(String name, User creator, List<Long> participantIds) {
        // Create the group
        Group group = new Group(name, creator);
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
        return groupRepository.findByIdWithCreator(group.getId()).orElse(group);
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public List<Group> getGroupsByUser(User user) {
        List<GroupParticipant> participants = groupParticipantRepository.findByUser(user);
        return participants.stream()
                .map(GroupParticipant::getGroup)
                .collect(Collectors.toList());
    }

    public List<GroupParticipant> getGroupParticipantsByUser(User user) {
        return groupParticipantRepository.findByUser(user);
    }

    public Map<Long, Long> getUnreadCountByGroupId(User user) {
        Long userId = Objects.requireNonNull(user.getId(), "user id must not be null");
        List<GroupUnreadCountDto> unreadRows = messageRepository.findUnreadCountRowsByUserId(userId);
        return unreadRows.stream()
                .collect(Collectors.toMap(GroupUnreadCountDto::getGroupId, GroupUnreadCountDto::getUnreadCount));
    }

    public long getTotalUnreadCount(User user) {
        return getUnreadCountByGroupId(user).values().stream()
                .mapToLong(Long::longValue)
                .sum();
    }

    @Transactional
    public void markGroupAsRead(User user, Long groupId, Long lastReadMessageId) {
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");

        Group group = groupRepository.findById(safeGroupId)
            .orElseThrow(() -> new NotFoundException("Group with id " + safeGroupId + " not found"));

        GroupParticipant participant = groupParticipantRepository.findByGroupAndUser(group, user)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this group"));

        if (lastReadMessageId != null && !messageRepository.existsByIdAndGroup_Id(lastReadMessageId, safeGroupId)) {
            throw new BadRequestException("lastReadMessageId does not belong to this group");
        }

        participant.setLastReadMessageId(lastReadMessageId);
        groupParticipantRepository.save(participant);
    }
}
