package com.hello.chatapp.service;

import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.BadRequestException;
import com.hello.chatapp.exception.ForbiddenException;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.repository.GroupBanRepository;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class GroupAuthorizationService {

    private final GroupRepository groupRepository;
    private final GroupParticipantRepository groupParticipantRepository;
    private final GroupBanRepository groupBanRepository;

    public GroupAuthorizationService(
            GroupRepository groupRepository,
            GroupParticipantRepository groupParticipantRepository,
            GroupBanRepository groupBanRepository) {
        this.groupRepository = groupRepository;
        this.groupParticipantRepository = groupParticipantRepository;
        this.groupBanRepository = groupBanRepository;
    }

    public GroupParticipant requireMember(User user, Long groupId) {
        Group group = loadGroup(groupId);
        requireNotBanned(user, group);
        return loadParticipant(group, user);
    }

    public Group requirePermission(User user, Long groupId, GroupPermission permission) {
        GroupParticipant participant = requireMember(user, groupId);
        if (!hasPermission(resolveRole(participant), permission)) {
            throw new ForbiddenException("You do not have permission to " + permission.name().toLowerCase());
        }
        return participant.getGroup();
    }

    public void requireCanManageTarget(User actor, Long groupId, User target, GroupPermission permission) {
        GroupParticipant actorParticipant = requireMember(actor, groupId);
        GroupParticipant targetParticipant = requireMember(target, groupId);

        if (Objects.equals(actor.getId(), target.getId())) {
            throw new ForbiddenException("You cannot perform this action on yourself");
        }

        GroupRole actorRole = resolveRole(actorParticipant);
        GroupRole targetRole = resolveRole(targetParticipant);

        if (!hasPermission(actorRole, permission)) {
            throw new ForbiddenException("You do not have permission to " + permission.name().toLowerCase());
        }
        if (targetRole == GroupRole.LEADER) {
            throw new ForbiddenException("The leader cannot be managed by this action");
        }
        if (!actorRole.isSameOrHigherThan(targetRole)) {
            throw new ForbiddenException("You cannot manage a member with a higher role than yours");
        }
    }

    public void requireCanEditMessage(User user, Message message) {
        Message nonNullMessage = Objects.requireNonNull(message, "message must not be null");
        User actor = Objects.requireNonNull(user, "user must not be null");

        if (nonNullMessage.getMessageType() != MessageType.TEXT) {
            throw new BadRequestException("Only text messages can be edited");
        }
        if (isMessageOwner(actor, nonNullMessage)) {
            return;
        }

        Group group = nonNullMessage.getGroup();
        if (group == null) {
            throw new ForbiddenException("You can only edit your own public messages");
        }

        requirePermission(actor, Objects.requireNonNull(group.getId()), GroupPermission.EDIT_ANY_TEXT_MESSAGE);
    }

    public void requireCanDeleteMessage(User user, Message message) {
        Message nonNullMessage = Objects.requireNonNull(message, "message must not be null");
        User actor = Objects.requireNonNull(user, "user must not be null");

        if (isMessageOwner(actor, nonNullMessage)) {
            return;
        }

        Group group = nonNullMessage.getGroup();
        if (group == null) {
            throw new ForbiddenException("You can only delete your own public messages");
        }

        requirePermission(actor, Objects.requireNonNull(group.getId()), GroupPermission.DELETE_ANY_MESSAGE);
    }

    public void requireNotBanned(User user, Long groupId) {
        requireNotBanned(user, loadGroup(groupId));
    }

    public void requireUserTopicAccess(User user, String topicUsername) {
        User actor = Objects.requireNonNull(user, "user must not be null");
        String safeTopicUsername = Objects.requireNonNull(topicUsername, "topicUsername must not be null");
        if (!safeTopicUsername.equals(actor.getUsername())) {
            throw new ForbiddenException("You can only subscribe to your own user topic");
        }
    }

    private Group loadGroup(Long groupId) {
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        return groupRepository.findById(safeGroupId)
                .orElseThrow(() -> new NotFoundException("Group with id " + safeGroupId + " not found"));
    }

    private GroupParticipant loadParticipant(Group group, User user) {
        return groupParticipantRepository.findByGroupAndUser(group, requireUser(user))
                .orElseThrow(() -> new ForbiddenException("You are not a member of this group"));
    }

    private void requireNotBanned(User user, Group group) {
        if (groupBanRepository.existsByGroupAndUser(group, requireUser(user))) {
            throw new ForbiddenException("You are banned from this group");
        }
    }

    private User requireUser(User user) {
        return Objects.requireNonNull(user, "user must not be null");
    }

    private GroupRole resolveRole(GroupParticipant participant) {
        GroupRole role = participant.getRole();
        return role == null ? GroupRole.MEMBER : role;
    }

    private boolean hasPermission(GroupRole role, GroupPermission permission) {
        return switch (role) {
            case LEADER -> true;
            case CO_LEADER -> EnumSet.of(
                    GroupPermission.READ_MESSAGES,
                    GroupPermission.SEND_MESSAGES,
                    GroupPermission.CREATE_JOIN_LINK,
                    GroupPermission.ADD_MEMBERS,
                    GroupPermission.KICK_MEMBERS,
                    GroupPermission.BAN_MEMBERS,
                    GroupPermission.UNBAN_MEMBERS,
                    GroupPermission.MANAGE_ROLES,
                    GroupPermission.MANAGE_GROUP_DETAILS,
                    GroupPermission.EDIT_ANY_TEXT_MESSAGE,
                    GroupPermission.DELETE_ANY_MESSAGE).contains(permission);
            case ELDER -> EnumSet.of(
                    GroupPermission.READ_MESSAGES,
                    GroupPermission.SEND_MESSAGES,
                    GroupPermission.CREATE_JOIN_LINK,
                    GroupPermission.ADD_MEMBERS,
                    GroupPermission.KICK_MEMBERS).contains(permission);
            case MEMBER -> EnumSet.of(
                    GroupPermission.READ_MESSAGES,
                    GroupPermission.SEND_MESSAGES).contains(permission);
        };
    }

    private boolean isMessageOwner(User user, Message message) {
        Long actorId = user.getId();
        Long messageOwnerId = message.getUser() == null ? null : message.getUser().getId();
        return actorId != null && actorId.equals(messageOwnerId);
    }
}
