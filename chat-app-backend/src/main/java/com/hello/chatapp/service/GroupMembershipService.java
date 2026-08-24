package com.hello.chatapp.service;

import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.dto.GroupBanResponse;
import com.hello.chatapp.dto.GroupJoinLinkResponse;
import com.hello.chatapp.dto.GroupMemberPageResponse;
import com.hello.chatapp.dto.GroupMemberResponse;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupBan;
import com.hello.chatapp.entity.GroupJoinLink;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.BadRequestException;
import com.hello.chatapp.exception.ForbiddenException;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.repository.GroupBanRepository;
import com.hello.chatapp.repository.GroupJoinLinkRepository;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.UserRepository;
import com.hello.chatapp.util.PageableUtil;
import com.hello.chatapp.util.StringUtil;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Membership mutations (add, join, kick, ban, leave, roles) for a group.
 * New inserts apply the member-limit insertion rule while holding the group row lock.
 */
@Service
public class GroupMembershipService {

    private static final int JOIN_TOKEN_BYTES = 32;
    private static final int MAX_BAN_REASON_LENGTH = 500;
    private static final int DEFAULT_MEMBER_PAGE_SIZE = 100;
    private static final int MAX_MEMBER_PAGE_SIZE = 100;
    private static final int MAX_ADDABLE_USERS = 500;
    private static final String ARCHIVE_REASON_LAST_MEMBER_LEFT = "LAST_MEMBER_LEFT";
    private static final String GROUP_MEMBER_LIMIT_REACHED = "Group member limit has been reached";

    private final GroupAuthorizationService groupAuthorizationService;
    private final GroupParticipantRepository groupParticipantRepository;
    private final GroupBanRepository groupBanRepository;
    private final GroupJoinLinkRepository groupJoinLinkRepository;
    private final GroupRepository groupRepository;
    private final SystemMessageService systemMessageService;
    private final GroupMembershipRealtimePublisher membershipRealtimePublisher;
    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final SecureRandom secureRandom = new SecureRandom();

    public GroupMembershipService(
            GroupAuthorizationService groupAuthorizationService,
            GroupParticipantRepository groupParticipantRepository,
            GroupBanRepository groupBanRepository,
            GroupJoinLinkRepository groupJoinLinkRepository,
            GroupRepository groupRepository,
            SystemMessageService systemMessageService,
            GroupMembershipRealtimePublisher membershipRealtimePublisher,
            UserRepository userRepository,
            EntityManager entityManager) {
        this.groupAuthorizationService = groupAuthorizationService;
        this.groupParticipantRepository = groupParticipantRepository;
        this.groupBanRepository = groupBanRepository;
        this.groupJoinLinkRepository = groupJoinLinkRepository;
        this.groupRepository = groupRepository;
        this.systemMessageService = systemMessageService;
        this.membershipRealtimePublisher = membershipRealtimePublisher;
        this.userRepository = userRepository;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public GroupMemberPageResponse listMembers(User actor, Long groupId, String search, int page, int size) {
        groupAuthorizationService.requireMember(actor, groupId);
        int resolvedPage = Math.max(page, 0);
        int resolvedSize = size <= 0 ? DEFAULT_MEMBER_PAGE_SIZE : Math.min(size, MAX_MEMBER_PAGE_SIZE);
        Pageable pageable = PageableUtil.of(resolvedPage, resolvedSize);
        String normalizedSearch = StringUtil.normalizeSqlLikeSearch(search);
        Page<GroupParticipant> memberPage = groupParticipantRepository.findByGroupIdWithUser(
                groupId,
                normalizedSearch,
                pageable);
        return GroupMemberPageResponse.builder()
                .content(memberPage.getContent().stream()
                        .map(GroupMemberResponse::fromParticipant)
                        .toList())
                .page(memberPage.getNumber())
                .size(memberPage.getSize())
                .totalElements(memberPage.getTotalElements())
                .totalPages(memberPage.getTotalPages())
                .hasNext(memberPage.hasNext())
                .build();
    }

    @Transactional(readOnly = true)
    public List<User> listAddableUsers(User actor, Long groupId, String search) {
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        groupAuthorizationService.requireActivePermission(actor, safeGroupId, GroupPermission.ADD_MEMBERS);
        String normalizedSearch = StringUtil.normalizeSqlLikeSearch(search);
        // Cap at MAX_ADDABLE_USERS; no pagination response by design.
        return userRepository.findAddableUsersForGroup(
                safeGroupId,
                normalizedSearch,
                PageableUtil.of(0, MAX_ADDABLE_USERS));
    }

    /**
     * Adds one or more users as {@code MEMBER} after locking the group, authorizing {@code ADD_MEMBERS},
     * and applying the member-limit insertion rule to the whole batch.
     * Duplicate ids are ignored. If any target is banned, already a member, missing, or the batch
     * would exceed capacity, the request fails and no participant rows are inserted.
     *
     * @param userIds users to add; must be non-empty after nulls are removed
     * @return saved memberships in request order (distinct ids)
     */
    @Transactional
    public List<GroupMemberResponse> addMembers(User actor, Long groupId, List<Long> userIds) {
        // Lock before auth so a concurrent demotion cannot leave a former leader authorized to add.
        Group group = lockActiveGroup(groupId);
        groupAuthorizationService.requireActivePermission(actor, groupId, GroupPermission.ADD_MEMBERS);

        // Validate input
        List<Long> distinctUserIds = distinctUserIds(userIds);
        if (distinctUserIds.isEmpty()) {
            throw new BadRequestException("At least one userId is required");
        }
        if (distinctUserIds.size() > MAX_ADDABLE_USERS) {
            throw new BadRequestException("At most " + MAX_ADDABLE_USERS + " userIds are allowed");
        }

        List<User> targets = new ArrayList<>(distinctUserIds.size());
        for (Long userId : distinctUserIds) {
            User target = loadUser(userId);
            groupAuthorizationService.requireNotBanned(target, groupId);
            if (groupParticipantRepository.findByGroupIdAndUserId(groupId, userId).isPresent()) {
                throw new BadRequestException("User is already a member of this group");
            }
            targets.add(target);
        }

        ensureGroupHasCapacityForNewMembers(group, targets.size());

        List<GroupMemberResponse> addedMembers = new ArrayList<>(targets.size());
        for (User target : targets) {
            GroupParticipant participant = new GroupParticipant(group, target);
            participant.setRole(GroupRole.MEMBER);
            GroupParticipant savedParticipant = groupParticipantRepository.save(participant);
            publishMembershipEvent(group, target, actor, SystemEventType.USER_JOINED, null);
            addedMembers.add(GroupMemberResponse.fromParticipant(savedParticipant));
        }
        return addedMembers;
    }

    @Transactional(readOnly = true)
    public List<GroupJoinLinkResponse> listJoinLinks(User actor, Long groupId) {
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        groupAuthorizationService.requireActivePermission(actor, safeGroupId, GroupPermission.CREATE_JOIN_LINK);
        // Token hashes are stored; list responses omit the raw token (only create returns it once).
        return groupJoinLinkRepository.findByGroupIdWithCreator(safeGroupId).stream()
                .map(joinLink -> GroupJoinLinkResponse.fromJoinLink(joinLink, null))
                .toList();
    }

    @Transactional
    public GroupJoinLinkResponse createJoinLink(User actor, Long groupId, Instant expiresAt) {
        Group group = lockActiveGroup(groupId);
        groupAuthorizationService.requireActivePermission(actor, groupId, GroupPermission.CREATE_JOIN_LINK);
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new BadRequestException("expiresAt must be in the future");
        }

        String token = generateJoinToken();
        GroupJoinLink joinLink = new GroupJoinLink();
        joinLink.setGroup(group);
        joinLink.setCreatedBy(actor);
        joinLink.setTokenHash(hashToken(token));
        joinLink.setExpiresAt(expiresAt);
        return GroupJoinLinkResponse.fromJoinLink(groupJoinLinkRepository.save(joinLink), token);
    }

    /**
     * Joins via a link token. Existing members are returned without a capacity check.
     * New members are inserted only when the group is unlimited or {@code count < maxMembers}.
     */
    @Transactional
    public GroupMemberResponse joinByToken(User user, String token) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException("Join token is required");
        }

        GroupJoinLink joinLink = groupJoinLinkRepository.findByTokenHashWithGroup(hashToken(token))
                .orElseThrow(() -> new NotFoundException("Join link not found"));

        // 1. First validate: optional optimization, the first check is only a fail-fast pre-check.
        validateJoinLink(joinLink);

        Long groupId = Objects.requireNonNull(joinLink.getGroup().getId());

        // 2. Then the code waits for the group lock.
        // While waiting, another transaction can revoke the link or archive the group.
        // Same group lock as other membership mutations (archive + auth serialization).
        Group group = lockActiveGroup(groupId);

        // 3. Reloads the latest DB state.
        entityManager.refresh(joinLink);

        // 4. Validate again. This second validate is required.
        validateJoinLink(joinLink);

        groupAuthorizationService.requireNotBanned(user, groupId);

        // If they're already a participant, no new row is saved and no USER_JOINED system message is recorded
        return groupParticipantRepository.findByGroupIdAndUserId(groupId, user.getId())
                .map(GroupMemberResponse::fromParticipant)
                .orElseGet(() -> {
                    ensureGroupHasCapacityForNewMember(group);
                    GroupParticipant participant = new GroupParticipant(group, user);
                    participant.setRole(GroupRole.MEMBER);
                    GroupParticipant savedParticipant = groupParticipantRepository.save(participant);
                    publishMembershipEvent(group, user, user, SystemEventType.USER_JOINED, null);
                    return GroupMemberResponse.fromParticipant(savedParticipant);
                });
    }

    @Transactional
    public void revokeJoinLink(User actor, Long groupId, Long joinLinkId) {
        lockActiveGroup(groupId);
        groupAuthorizationService.requirePermission(actor, groupId, GroupPermission.CREATE_JOIN_LINK);
        GroupJoinLink joinLink = groupJoinLinkRepository.findByIdAndGroupId(joinLinkId, groupId)
                .orElseThrow(() -> new NotFoundException("Join link not found"));
        if (joinLink.getRevokedAt() == null) {
            joinLink.setRevokedAt(LocalDateTime.now());
            joinLink.setRevokedBy(actor);
        }
    }

    @Transactional
    public void kickMember(User actor, Long groupId, Long userId) {
        Group group = lockActiveGroup(groupId);
        User target = loadUser(userId);
        groupAuthorizationService.requireCanManageTarget(actor, groupId, target, GroupPermission.KICK_MEMBERS);
        GroupParticipant targetParticipant = loadParticipant(groupId, userId);
        User targetUser = targetParticipant.getUser();
        String removedUsername = targetUser.getUsername();
        // No race while editing message (see leaveGroup below)
        groupParticipantRepository.delete(targetParticipant);
        publishMembershipEvent(group, targetUser, actor, SystemEventType.USER_KICKED, removedUsername);
    }

    @Transactional(readOnly = true)
    public List<GroupBanResponse> listBans(User actor, Long groupId) {
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        // Banned roster is for unban/moderation; BAN_MEMBERS and UNBAN_MEMBERS travel together in the matrix.
        groupAuthorizationService.requireActivePermission(actor, safeGroupId, GroupPermission.UNBAN_MEMBERS);
        return groupBanRepository.findByGroupIdWithUsers(safeGroupId).stream()
                .map(GroupBanResponse::fromBan)
                .toList();
    }

    @Transactional
    public void banMember(User actor, Long groupId, Long userId, String reason) {
        Group group = lockActiveGroup(groupId);
        groupAuthorizationService.requireActivePermission(actor, groupId, GroupPermission.BAN_MEMBERS);
        User target = loadUser(userId);
        if (Objects.equals(actor.getId(), target.getId())) {
            throw new ForbiddenException("You cannot perform this action on yourself");
        }
        validateBanReason(reason);

        String removedUsername = groupParticipantRepository.findByGroupIdAndUserId(groupId, userId)
                .map(participant -> {
                    groupAuthorizationService.requireCanManageTarget(actor, groupId, target, GroupPermission.BAN_MEMBERS);
                    // No race while editing message (see leaveGroup below)
                    groupParticipantRepository.delete(Objects.requireNonNull(participant));
                    return target.getUsername();
                })
                .orElse(null);

        if (groupBanRepository.findByGroupIdAndUserId(groupId, userId).isPresent()) {
            throw new BadRequestException("User is already banned from this group");
        }

        GroupBan ban = new GroupBan();
        ban.setGroup(group);
        ban.setUser(target);
        ban.setBannedBy(actor);
        ban.setReason(reason);
        groupBanRepository.save(ban);
        publishMembershipEvent(group, target, actor, SystemEventType.USER_BANNED, removedUsername);
    }

    @Transactional
    public void unbanMember(User actor, Long groupId, Long userId) {
        Group group = lockActiveGroup(groupId);
        groupAuthorizationService.requireActivePermission(actor, groupId, GroupPermission.UNBAN_MEMBERS);
        GroupBan ban = groupBanRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new NotFoundException("Ban not found"));
        groupBanRepository.delete(Objects.requireNonNull(ban));
        publishMembershipEvent(group, ban.getUser(), actor, SystemEventType.USER_UNBANNED, null);
    }

    @Transactional
    public GroupMemberResponse updateMemberRole(User actor, Long groupId, Long userId, GroupRole role) {
        if (role == null) {
            throw new BadRequestException("role is required");
        }
        if (role == GroupRole.LEADER) {
            throw new BadRequestException("Use leadership transfer to assign LEADER");
        }

        Group group = lockActiveGroup(groupId);
        User target = loadUser(userId);
        groupAuthorizationService.requireCanManageTarget(actor, groupId, target, GroupPermission.MANAGE_ROLES);
        GroupParticipant targetParticipant = loadParticipant(groupId, userId);
        GroupRole previousRole = targetParticipant.getRole() == null ? GroupRole.MEMBER : targetParticipant.getRole();
        targetParticipant.setRole(role);
        GroupParticipant savedParticipant = groupParticipantRepository.save(targetParticipant);
        if (previousRole != role) {
            SystemEventType eventType = role.getRank() < previousRole.getRank()
                    ? SystemEventType.USER_PROMOTED
                    : SystemEventType.USER_DEMOTED;
            systemMessageService.recordGroupEvent(group, target, actor, eventType);
        }
        return GroupMemberResponse.fromParticipant(savedParticipant);
    }

    @Transactional
    public void transferLeadership(User actor, Long groupId, Long newLeaderUserId) {
        Group group = lockActiveGroup(groupId);
        GroupParticipant currentLeader = groupAuthorizationService.requireMember(actor, groupId);
        if (currentLeader.getRole() != GroupRole.LEADER) {
            throw new ForbiddenException("Only the leader can transfer leadership");
        }
        if (Objects.equals(actor.getId(), newLeaderUserId)) {
            throw new BadRequestException("newLeaderUserId must be another member");
        }

        GroupParticipant newLeader = loadParticipant(groupId, newLeaderUserId);
        currentLeader.setRole(GroupRole.MEMBER);
        groupParticipantRepository.saveAndFlush(currentLeader);

        newLeader.setRole(GroupRole.LEADER);
        groupParticipantRepository.save(newLeader);
        systemMessageService.recordGroupEvent(group, newLeader.getUser(), actor,
                SystemEventType.LEADERSHIP_TRANSFERRED);
    }

    @Transactional
    public void leaveGroup(User actor, Long groupId) {
        // Lock before membership/auth reads so concurrent role/archive mutations serialize on this row.
        Group group = lockActiveGroup(groupId);
        GroupParticipant participant = groupAuthorizationService.requireMember(actor, groupId);

        // Count under the lock so "last member" is decided against the same serialized membership set.
        long memberCount = groupParticipantRepository.countByGroupId(groupId);
        if (participant.getRole() == GroupRole.LEADER && memberCount > 1) {
            throw new ForbiddenException("Transfer leadership before leaving the group");
        }

        if (memberCount <= 1) {
            group.setArchivedAt(LocalDateTime.now());
            group.setArchivedBy(actor);
            group.setArchiveReason(ARCHIVE_REASON_LAST_MEMBER_LEFT);
            groupRepository.save(group);
        }

        // If the user is editing their message, they must hold a lock on their own participant row in edit method.
        // Which means this line will wait for the lock to be released (wait for them to finish editing their message).
        groupParticipantRepository.delete(participant);
        publishMembershipEvent(group, actor, actor, SystemEventType.USER_LEFT, actor.getUsername());
        if (memberCount <= 1) {
            // Archive system line is part of last-member leave; profile-wide archive paths stay in Task 12.3.
            publishMembershipEvent(group, actor, actor, SystemEventType.GROUP_ARCHIVED, null);
        }
    }

    /**
     * Locks the actor’s {@code group_participants} row for group message edit/delete (doc 23).
     * <p>
     * Kick/ban/leave {@code DELETE} and demote {@code UPDATE} that same row wait on this lock,
     * so membership cannot change between {@code requireCan*} and the message save. Other members’
     * participant rows are independent — concurrent unrelated edits are not serialized.
     * Must run inside the caller’s write transaction (no {@code REQUIRES_NEW}).
     *
     * @param groupId group the message belongs to
     * @param userId actor whose membership/role is being authorized
     * @return the locked participant
     */
    public GroupParticipant lockActorParticipantForModeration(Long groupId, Long userId) {
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        Long safeUserId = Objects.requireNonNull(userId, "userId must not be null");
        GroupParticipant participant = groupParticipantRepository
                .findByGroupIdAndUserIdForUpdate(safeGroupId, safeUserId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this group"));
        ensureActive(participant.getGroup());
        return participant;
    }

    /**
     * Persists a structured membership {@code SYSTEM} message, then schedules realtime delivery
     * for after the surrounding transaction commits (via {@link GroupMembershipRealtimePublisher}).
     *
     * <p>
     * Realtime effects:
     * <ul>
     * <li>push the system message to {@code /topic/group.{groupId}}</li>
     * <li>fan out a sidebar {@code GroupSummaryUpdate} to remaining members</li>
     * <li>when {@code removedUsername} is set, immediately notify that user with
     * {@code removed=true} so their sidebar drops the group</li>
     * </ul>
     *
     * @param group the group where the membership event occurred
     * @param subjectUser the user the event is about (e.g. joined, kicked, banned)
     * @param actor the user who performed the action (may be the same as {@code subjectUser})
     * @param eventType stable system event stored on the message and used for the sidebar preview
     * @param removedUsername username to receive a personal {@code removed} update
     *        (kick / ban of a participant / leave); {@code null} when nobody should be removed
     *        from their own sidebar (e.g. add, join, unban)
     */
    private void publishMembershipEvent(
            Group group,
            User subjectUser,
            User actor,
            SystemEventType eventType,
            String removedUsername) {
        Message systemMessage = systemMessageService.recordGroupEvent(group, subjectUser, actor, eventType);
        membershipRealtimePublisher.publishMembershipChange(
                group,
                systemMessage,
                eventType.latestPreview(),
                removedUsername);
    }

    private User loadUser(Long userId) {
        Long safeUserId = Objects.requireNonNull(userId, "userId must not be null");
        return userRepository.findById(safeUserId)
                .orElseThrow(() -> new NotFoundException("User with id " + safeUserId + " not found"));
    }

    /**
     * Acquires a pessimistic write lock on the group lifecycle row and ensures the group is active.
     * <p>
     * Call this from an existing write transaction <em>before</em> authorization so concurrent
     * kick/ban/demote/leave/archive cannot invalidate a permission check that already passed.
     * Shared mutex for membership mutations (docs 21/24).
     *
     * @param groupId group to lock
     * @return the locked active group
     */
    private Group lockActiveGroup(Long groupId) {
        Group group = lockGroupForLifecycleUpdate(groupId);
        ensureActive(group);
        return group;
    }

    /**
     * Insertion rule: a new participant may be saved only when the group is unlimited
     * ({@code maxMembers} null or 0) or the current participant count is strictly below a positive cap.
     * Must run while this transaction holds {@code findByIdForUpdate} on the group row.
     *
     * @param group locked active group whose {@code maxMembers} is applied
     */
    private void ensureGroupHasCapacityForNewMember(Group group) {
        ensureGroupHasCapacityForNewMembers(group, 1);
    }

    /**
     * Same insertion rule as {@link #ensureGroupHasCapacityForNewMember(Group)} for a batch of new rows.
     * Rejects the whole batch when {@code currentCount + newMemberCount} would exceed a positive cap.
     *
     * @param newMemberCount distinct users about to be inserted; ignored when {@code <= 0}
     */
    private void ensureGroupHasCapacityForNewMembers(Group group, int newMemberCount) {
        if (newMemberCount <= 0) {
            return;
        }
        Integer maxMembers = group.getMaxMembers();
        if (maxMembers == null || maxMembers <= 0) {
            return;
        }
        long currentCount = groupParticipantRepository.countByGroupId(group.getId());
        if (currentCount + newMemberCount > maxMembers) {
            throw new BadRequestException(GROUP_MEMBER_LIMIT_REACHED);
        }
    }

    /**
     * Deduplicates ids while preserving request order. Null entries are dropped.
     */
    private List<Long> distinctUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        Set<Long> distinctIds = new LinkedHashSet<>();
        for (Long userId : userIds) {
            if (userId != null) {
                distinctIds.add(userId);
            }
        }
        return new ArrayList<>(distinctIds);
    }

    /**
     * Acquires a pessimistic write lock on the group lifecycle row.
     */
    private Group lockGroupForLifecycleUpdate(Long groupId) {
        Long safeGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        return groupRepository.findByIdForUpdate(safeGroupId)
                .orElseThrow(() -> new NotFoundException("Group with id " + safeGroupId + " not found"));
    }

    private GroupParticipant loadParticipant(Long groupId, Long userId) {
        return groupParticipantRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this group"));
    }

    private void ensureActive(Group group) {
        if (group.getArchivedAt() != null) {
            throw new BadRequestException("Group is archived");
        }
    }

    private void validateJoinLink(GroupJoinLink joinLink) {
        if (joinLink.getRevokedAt() != null) {
            throw new BadRequestException("Join link has been revoked");
        }
        if (joinLink.getExpiresAt() != null && !joinLink.getExpiresAt().isAfter(Instant.now())) {
            throw new BadRequestException("Join link has expired");
        }
        ensureActive(joinLink.getGroup());
    }

    private void validateBanReason(String reason) {
        if (reason != null && reason.length() > MAX_BAN_REASON_LENGTH) {
            throw new BadRequestException("Ban reason must be at most " + MAX_BAN_REASON_LENGTH + " characters");
        }
    }

    private String generateJoinToken() {
        byte[] bytes = new byte[JOIN_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
