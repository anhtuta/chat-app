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
import com.hello.chatapp.repository.GroupBanRepository;
import com.hello.chatapp.repository.GroupParticipantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class GroupAuthorizationService {

    private final GroupParticipantRepository groupParticipantRepository;
    private final GroupBanRepository groupBanRepository;

    public GroupAuthorizationService(
            GroupParticipantRepository groupParticipantRepository,
            GroupBanRepository groupBanRepository) {
        this.groupParticipantRepository = groupParticipantRepository;
        this.groupBanRepository = groupBanRepository;
    }

    public GroupParticipant requireMember(User user, Long groupId) {
        requireNotBanned(user, groupId);
        return loadParticipant(groupId, user);
    }

    public Group requirePermission(User user, Long groupId, GroupPermission permission) {
        GroupParticipant participant = requireMember(user, groupId);
        if (!hasPermission(resolveRole(participant), permission)) {
            throw new ForbiddenException("You do not have permission to " + permission.name().toLowerCase());
        }
        return participant.getGroup();
    }

    /**
     * Ensures the user is a member, has the given permission, and the group is active.
     * This is the helper for flows that must reject archived groups such as new sends,
     * membership mutations, and live WebSocket subscriptions.
     *
     * <p>
     * Why this exists separately from {@link #requirePermission(User, Long, GroupPermission)}:
     * authorization and lifecycle state are different concerns. Some callers may still
     * need a plain permission check for explicit history/audit/archive views, while
     * active-only product flows should call this method.
     * </p>
     *
     * @return the active group entity attached to the participant
     * @throws ForbiddenException if banned, not a member, or lacking the permission
     * @throws BadRequestException if the group is archived
     */
    public Group requireActivePermission(User user, Long groupId, GroupPermission permission) {
        Group group = requirePermission(user, groupId, permission);
        if (group.getArchivedAt() != null) {
            throw new BadRequestException("Group is archived");
        }
        return group;
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
        User safeUser = requireUser(user);
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        Long safeUserId = Objects.requireNonNull(safeUser.getId(), "user id must not be null");
        if (groupBanRepository.existsByGroup_IdAndUser_Id(safeGroupId, safeUserId)) {
            throw new ForbiddenException("You are banned from this group");
        }
    }

    public void requireUserTopicAccess(User user, String topicUsername) {
        User actor = Objects.requireNonNull(user, "user must not be null");
        String safeTopicUsername = Objects.requireNonNull(topicUsername, "topicUsername must not be null");
        if (!safeTopicUsername.equals(actor.getUsername())) {
            throw new ForbiddenException("You can only subscribe to your own user topic");
        }
    }

    public GroupRole getRole(GroupParticipant participant) {
        return resolveRole(Objects.requireNonNull(participant, "participant must not be null"));
    }

    public List<GroupPermission> getPermissions(GroupParticipant participant) {
        return getPermissions(getRole(participant));
    }

    public List<GroupPermission> getPermissions(GroupRole role) {
        GroupRole safeRole = Objects.requireNonNull(role, "role must not be null");
        return List.copyOf(permissionsForRole(safeRole));
    }

    private GroupParticipant loadParticipant(Long groupId, User user) {
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        return groupParticipantRepository.findByGroupIdAndUser(safeGroupId, requireUser(user))
                .orElseThrow(() -> new ForbiddenException("You are not a member of this group"));
    }

    private User requireUser(User user) {
        return Objects.requireNonNull(user, "user must not be null");
    }

    private GroupRole resolveRole(GroupParticipant participant) {
        GroupRole role = participant.getRole();
        return role == null ? GroupRole.MEMBER : role;
    }

    private boolean hasPermission(GroupRole role, GroupPermission permission) {
        return permissionsForRole(role).contains(permission);
    }

    private EnumSet<GroupPermission> permissionsForRole(GroupRole role) {
        return switch (role) {
            case LEADER -> EnumSet.allOf(GroupPermission.class);
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
                    GroupPermission.DELETE_ANY_MESSAGE);
            case ELDER -> EnumSet.of(
                    GroupPermission.READ_MESSAGES,
                    GroupPermission.SEND_MESSAGES,
                    GroupPermission.CREATE_JOIN_LINK,
                    GroupPermission.ADD_MEMBERS,
                    GroupPermission.KICK_MEMBERS);
            case MEMBER -> EnumSet.of(
                    GroupPermission.READ_MESSAGES,
                    GroupPermission.SEND_MESSAGES);
        };
    }

    private boolean isMessageOwner(User user, Message message) {
        Long actorId = user.getId();
        Long messageOwnerId = message.getUser() == null ? null : message.getUser().getId();
        return actorId != null && actorId.equals(messageOwnerId);
    }
}
