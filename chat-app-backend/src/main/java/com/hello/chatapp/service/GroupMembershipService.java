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
import java.util.List;
import java.util.Objects;

@Service
public class GroupMembershipService {

    private static final int JOIN_TOKEN_BYTES = 32;
    private static final int MAX_BAN_REASON_LENGTH = 500;
    private static final int DEFAULT_MEMBER_PAGE_SIZE = 100;
    private static final int MAX_MEMBER_PAGE_SIZE = 100;
    private static final int MAX_ADDABLE_USERS = 500;
    private static final String ARCHIVE_REASON_LAST_MEMBER_LEFT = "LAST_MEMBER_LEFT";

    private final GroupAuthorizationService groupAuthorizationService;
    private final GroupParticipantRepository groupParticipantRepository;
    private final GroupBanRepository groupBanRepository;
    private final GroupJoinLinkRepository groupJoinLinkRepository;
    private final GroupRepository groupRepository;
    private final SystemMessageService systemMessageService;
    private final GroupMembershipRealtimePublisher membershipRealtimePublisher;
    private final UserRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public GroupMembershipService(
            GroupAuthorizationService groupAuthorizationService,
            GroupParticipantRepository groupParticipantRepository,
            GroupBanRepository groupBanRepository,
            GroupJoinLinkRepository groupJoinLinkRepository,
            GroupRepository groupRepository,
            SystemMessageService systemMessageService,
            GroupMembershipRealtimePublisher membershipRealtimePublisher,
            UserRepository userRepository) {
        this.groupAuthorizationService = groupAuthorizationService;
        this.groupParticipantRepository = groupParticipantRepository;
        this.groupBanRepository = groupBanRepository;
        this.groupJoinLinkRepository = groupJoinLinkRepository;
        this.groupRepository = groupRepository;
        this.systemMessageService = systemMessageService;
        this.membershipRealtimePublisher = membershipRealtimePublisher;
        this.userRepository = userRepository;
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

    @Transactional
    public GroupMemberResponse addMember(User actor, Long groupId, Long userId) {
        Group group = groupAuthorizationService.requireActivePermission(actor, groupId, GroupPermission.ADD_MEMBERS);
        User target = loadUser(userId);
        groupAuthorizationService.requireNotBanned(target, groupId);

        if (groupParticipantRepository.findByGroupIdAndUserId(groupId, userId).isPresent()) {
            throw new BadRequestException("User is already a member of this group");
        }

        GroupParticipant participant = new GroupParticipant(group, target);
        participant.setRole(GroupRole.MEMBER);
        GroupParticipant savedParticipant = groupParticipantRepository.save(participant);
        publishMembershipEvent(group, target, actor, SystemEventType.USER_JOINED, null);
        return GroupMemberResponse.fromParticipant(savedParticipant);
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
        Group group = groupAuthorizationService.requireActivePermission(actor, groupId, GroupPermission.CREATE_JOIN_LINK);
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

    @Transactional
    public GroupMemberResponse joinByToken(User user, String token) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException("Join token is required");
        }

        GroupJoinLink joinLink = groupJoinLinkRepository.findByTokenHashWithGroup(hashToken(token))
                .orElseThrow(() -> new NotFoundException("Join link not found"));
        validateJoinLink(joinLink);

        Group group = joinLink.getGroup();
        Long groupId = Objects.requireNonNull(group.getId());
        groupAuthorizationService.requireNotBanned(user, groupId);

        // If they're already a participant, no new row is saved and no USER_JOINED system message is recorded
        return groupParticipantRepository.findByGroupIdAndUserId(groupId, user.getId())
                .map(GroupMemberResponse::fromParticipant)
                .orElseGet(() -> {
                    GroupParticipant participant = new GroupParticipant(group, user);
                    participant.setRole(GroupRole.MEMBER);
                    GroupParticipant savedParticipant = groupParticipantRepository.save(participant);
                    publishMembershipEvent(group, user, user, SystemEventType.USER_JOINED, null);
                    return GroupMemberResponse.fromParticipant(savedParticipant);
                });
    }

    @Transactional
    public void revokeJoinLink(User actor, Long groupId, Long joinLinkId) {
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
        groupAuthorizationService.requireCanManageTarget(actor, groupId, loadUser(userId), GroupPermission.KICK_MEMBERS);
        GroupParticipant targetParticipant = loadParticipant(groupId, userId);
        ensureActive(targetParticipant.getGroup());
        User targetUser = targetParticipant.getUser();
        Group group = targetParticipant.getGroup();
        String removedUsername = targetUser.getUsername();
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
        Group group = groupAuthorizationService.requireActivePermission(actor, groupId, GroupPermission.BAN_MEMBERS);
        User target = loadUser(userId);
        if (Objects.equals(actor.getId(), target.getId())) {
            throw new ForbiddenException("You cannot perform this action on yourself");
        }
        validateBanReason(reason);

        String removedUsername = groupParticipantRepository.findByGroupIdAndUserId(groupId, userId)
                .map(participant -> {
                    groupAuthorizationService.requireCanManageTarget(actor, groupId, target, GroupPermission.BAN_MEMBERS);
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
        Group group = groupAuthorizationService.requireActivePermission(actor, groupId, GroupPermission.UNBAN_MEMBERS);
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

        User target = loadUser(userId);
        groupAuthorizationService.requireCanManageTarget(actor, groupId, target, GroupPermission.MANAGE_ROLES);
        GroupParticipant targetParticipant = loadParticipant(groupId, userId);
        ensureActive(targetParticipant.getGroup());
        GroupRole previousRole = targetParticipant.getRole() == null ? GroupRole.MEMBER : targetParticipant.getRole();
        targetParticipant.setRole(role);
        GroupParticipant savedParticipant = groupParticipantRepository.save(targetParticipant);
        if (previousRole != role) {
            SystemEventType eventType = role.getRank() < previousRole.getRank()
                    ? SystemEventType.USER_PROMOTED
                    : SystemEventType.USER_DEMOTED;
            systemMessageService.recordGroupEvent(targetParticipant.getGroup(), target, actor, eventType);
        }
        return GroupMemberResponse.fromParticipant(savedParticipant);
    }

    @Transactional
    public void transferLeadership(User actor, Long groupId, Long newLeaderUserId) {
        GroupParticipant currentLeader = groupAuthorizationService.requireMember(actor, groupId);
        ensureActive(currentLeader.getGroup());
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
        systemMessageService.recordGroupEvent(currentLeader.getGroup(), newLeader.getUser(), actor,
                SystemEventType.LEADERSHIP_TRANSFERRED);
    }

    @Transactional
    public void leaveGroup(User actor, Long groupId) {
        GroupParticipant participant = groupAuthorizationService.requireMember(actor, groupId);
        Group group = participant.getGroup();
        ensureActive(group);

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

        groupParticipantRepository.delete(participant);
        publishMembershipEvent(group, actor, actor, SystemEventType.USER_LEFT, actor.getUsername());
        if (memberCount <= 1) {
            // Archive system line is part of last-member leave; profile-wide archive paths stay in Task 12.3.
            publishMembershipEvent(group, actor, actor, SystemEventType.GROUP_ARCHIVED, null);
        }
    }

    /**
     * Persists a structured membership {@code SYSTEM} message, then schedules realtime delivery
     * for after the surrounding transaction commits (via {@link GroupMembershipRealtimePublisher}).
     *
     * <p>Realtime effects:
     * <ul>
     *   <li>push the system message to {@code /topic/group.{groupId}}</li>
     *   <li>fan out a sidebar {@code GroupSummaryUpdate} to remaining members</li>
     *   <li>when {@code removedUsername} is set, immediately notify that user with
     *       {@code removed=true} so their sidebar drops the group</li>
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
