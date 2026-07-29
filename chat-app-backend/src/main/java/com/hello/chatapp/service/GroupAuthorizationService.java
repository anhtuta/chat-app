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

/**
 * Central authorization gate for group membership, roles, and permissions.
 * Controllers and other services should call this instead of checking
 * {@code group_participants} or ban state ad hoc.
 *
 * <p>
 * Rules encoded here:
 * <ul>
 * <li>Banned users are rejected before membership/permission checks.</li>
 * <li>Permissions are derived from the participant's static {@link GroupRole}.</li>
 * <li>Target-management actions also enforce hierarchy (same-or-higher rank,
 * and the leader is never a manage target).</li>
 * <li>Message edit/delete allow the owner always; otherwise require the matching
 * elevate permission for group messages only.</li>
 * </ul>
 *
 * See {@code docs/15_GROUP_ROLES_AND_PERMISSIONS.md} for the full role/permission matrix.
 */
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

    /**
     * Ensures the user is an active (non-banned) participant of the group.
     * How it works:
     * 1. Reject if the user is banned from the group.
     * 2. Load the participant row; throw if missing (invalid groupId and non-membership
     * are treated the same).
     *
     * @return the loaded participant (role included)
     * @throws ForbiddenException if banned or not a member
     */
    public GroupParticipant requireMember(User user, Long groupId) {
        requireNotBanned(user, groupId);
        return loadParticipant(groupId, user);
    }

    /**
     * Ensures the user is a member and has the given permission for the group.
     * How it works:
     * 1. Resolve membership via {@link #requireMember(User, Long)}.
     * 2. Map the participant role to its permission set and check {@code permission}.
     *
     * @return the group entity attached to the participant
     * @throws ForbiddenException if banned, not a member, or lacking the permission
     */
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

    /**
     * Ensures the actor may perform a target-directed moderation/membership action
     * (kick, ban, promote/demote, etc.) on {@code target} in the group.
     * How it works:
     * 1. Both actor and target must be non-banned members.
     * 2. Actor cannot act on themselves.
     * 3. Actor must hold {@code permission}.
     * 4. The leader cannot be managed by this path (use leadership transfer instead).
     * 5. Actor role must be same or higher rank than the target role
     * ({@link GroupRole#isSameOrHigherThan(GroupRole)}).
     *
     * @throws ForbiddenException if any of the above rules fail
     */
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

    /**
     * Ensures the user may edit the given message.
     * How it works:
     * 1. Only {@link MessageType#TEXT} messages are editable.
     * 2. The message owner may always edit their own text message.
     * 3. For someone else's message: must be a group message, and the actor needs
     * {@link GroupPermission#EDIT_ANY_TEXT_MESSAGE} (leader / co-leader).
     * 4. Public (non-group) messages can only be edited by their owner.
     *
     * @throws BadRequestException if the message is not text
     * @throws ForbiddenException if the actor is not allowed to edit it
     */
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

    /**
     * Ensures the user may delete the given message.
     * How it works:
     * 1. The message owner may always delete their own message (text or media).
     * 2. For someone else's message: must be a group message, and the actor needs
     * {@link GroupPermission#DELETE_ANY_MESSAGE} (leader / co-leader).
     * 3. Public (non-group) messages can only be deleted by their owner.
     *
     * @throws ForbiddenException if the actor is not allowed to delete it
     */
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

    /**
     * Rejects the request if the user has an active ban for the group.
     * Used as the first gate for membership and permission checks, and for join paths.
     *
     * @throws ForbiddenException if a ban row exists for (groupId, userId)
     */
    public void requireNotBanned(User user, Long groupId) {
        User safeUser = requireUser(user);
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        Long safeUserId = Objects.requireNonNull(safeUser.getId(), "user id must not be null");
        if (groupBanRepository.existsByGroup_IdAndUser_Id(safeGroupId, safeUserId)) {
            // Todo: we have 2 cases to call this method: 1. when user is adding a member to a group,
            // 2. when user is joining a group. We need different messages for each case.
            throw new ForbiddenException("You are banned from this group");
        }
    }

    /**
     * Ensures a WebSocket (or similar) subscriber may only attach to their own
     * personal user topic (e.g. {@code /topic/user.{username}.group-updates}).
     *
     * @throws ForbiddenException if {@code topicUsername} does not match the authenticated user
     */
    public void requireUserTopicAccess(User user, String topicUsername) {
        User actor = Objects.requireNonNull(user, "user must not be null");
        String safeTopicUsername = Objects.requireNonNull(topicUsername, "topicUsername must not be null");
        if (!safeTopicUsername.equals(actor.getUsername())) {
            throw new ForbiddenException("You can only subscribe to your own user topic");
        }
    }

    /**
     * Returns the effective role for a participant.
     * Null stored roles are treated as {@link GroupRole#MEMBER}.
     */
    public GroupRole getRole(GroupParticipant participant) {
        return resolveRole(Objects.requireNonNull(participant, "participant must not be null"));
    }

    /**
     * Returns the permission list for the participant's effective role.
     */
    public List<GroupPermission> getPermissions(GroupParticipant participant) {
        return getPermissions(getRole(participant));
    }

    /**
     * Returns an immutable list of permissions granted to the given static role.
     * Source of truth is {@link #permissionsForRole(GroupRole)}.
     */
    public List<GroupPermission> getPermissions(GroupRole role) {
        GroupRole safeRole = Objects.requireNonNull(role, "role must not be null");
        return List.copyOf(permissionsForRole(safeRole));
    }

    /**
     * Loads the participant for (groupId, user), or fails with a membership Forbidden.
     */
    private GroupParticipant loadParticipant(Long groupId, User user) {
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        return groupParticipantRepository.findByGroupIdAndUser(safeGroupId, requireUser(user))
                .orElseThrow(() -> new ForbiddenException("You are not a member of this group"));
    }

    /** Null-checks the user argument. */
    private User requireUser(User user) {
        return Objects.requireNonNull(user, "user must not be null");
    }

    /**
     * Resolves the effective role; null DB role defaults to {@link GroupRole#MEMBER}
     * for backward compatibility with pre-role rows.
     */
    private GroupRole resolveRole(GroupParticipant participant) {
        GroupRole role = participant.getRole();
        return role == null ? GroupRole.MEMBER : role;
    }

    /** Whether {@code role}'s permission set contains {@code permission}. */
    private boolean hasPermission(GroupRole role, GroupPermission permission) {
        return permissionsForRole(role).contains(permission);
    }

    /**
     * Static permission matrix for each role (see docs permission table).
     * <ul>
     * <li>{@link GroupRole#LEADER} — all permissions, including transfer leadership.</li>
     * <li>{@link GroupRole#CO_LEADER} — almost all except {@link GroupPermission#TRANSFER_LEADERSHIP}.</li>
     * <li>{@link GroupRole#ELDER} — read/send, join link, add, and kick.</li>
     * <li>{@link GroupRole#MEMBER} — read and send only.</li>
     * </ul>
     */
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

    /** True when the actor's id matches the message author's id. */
    private boolean isMessageOwner(User user, Message message) {
        Long actorId = user.getId();
        Long messageOwnerId = message.getUser() == null ? null : message.getUser().getId();
        return actorId != null && actorId.equals(messageOwnerId);
    }
}
