package com.hello.chatapp.service;

import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.dto.GroupJoinLinkResponse;
import com.hello.chatapp.dto.GroupMemberResponse;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupBan;
import com.hello.chatapp.entity.GroupJoinLink;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.BadRequestException;
import com.hello.chatapp.exception.ForbiddenException;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.repository.GroupBanRepository;
import com.hello.chatapp.repository.GroupJoinLinkRepository;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@Service
public class GroupMembershipService {

    private static final int JOIN_TOKEN_BYTES = 32;
    private static final int MAX_BAN_REASON_LENGTH = 500;
    private static final String ARCHIVE_REASON_LAST_MEMBER_LEFT = "LAST_MEMBER_LEFT";

    private final GroupAuthorizationService groupAuthorizationService;
    private final GroupParticipantRepository groupParticipantRepository;
    private final GroupBanRepository groupBanRepository;
    private final GroupJoinLinkRepository groupJoinLinkRepository;
    private final GroupRepository groupRepository;
    private final SystemMessageService systemMessageService;
    private final UserRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public GroupMembershipService(
            GroupAuthorizationService groupAuthorizationService,
            GroupParticipantRepository groupParticipantRepository,
            GroupBanRepository groupBanRepository,
            GroupJoinLinkRepository groupJoinLinkRepository,
            GroupRepository groupRepository,
            SystemMessageService systemMessageService,
            UserRepository userRepository) {
        this.groupAuthorizationService = groupAuthorizationService;
        this.groupParticipantRepository = groupParticipantRepository;
        this.groupBanRepository = groupBanRepository;
        this.groupJoinLinkRepository = groupJoinLinkRepository;
        this.groupRepository = groupRepository;
        this.systemMessageService = systemMessageService;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<GroupMemberResponse> listMembers(User actor, Long groupId) {
        groupAuthorizationService.requireMember(actor, groupId);
        return groupParticipantRepository.findByGroupIdWithUser(groupId).stream()
                .map(GroupMemberResponse::fromParticipant)
                .toList();
    }

    @Transactional
    public GroupMemberResponse addMember(User actor, Long groupId, Long userId) {
        Group group = groupAuthorizationService.requirePermission(actor, groupId, GroupPermission.ADD_MEMBERS);
        ensureActive(group);
        User target = loadUser(userId);
        groupAuthorizationService.requireNotBanned(target, groupId);

        if (groupParticipantRepository.findByGroupIdAndUserId(groupId, userId).isPresent()) {
            throw new BadRequestException("User is already a member of this group");
        }

        GroupParticipant participant = new GroupParticipant(group, target);
        participant.setRole(GroupRole.MEMBER);
        GroupParticipant savedParticipant = groupParticipantRepository.save(participant);
        systemMessageService.recordGroupEvent(group, target, actor, SystemEventType.USER_JOINED);
        return GroupMemberResponse.fromParticipant(savedParticipant);
    }

    @Transactional
    public GroupJoinLinkResponse createJoinLink(User actor, Long groupId, LocalDateTime expiresAt) {
        Group group = groupAuthorizationService.requirePermission(actor, groupId, GroupPermission.CREATE_JOIN_LINK);
        ensureActive(group);
        if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) {
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

        return groupParticipantRepository.findByGroupIdAndUserId(groupId, user.getId())
                .map(GroupMemberResponse::fromParticipant)
                .orElseGet(() -> {
                    GroupParticipant participant = new GroupParticipant(group, user);
                    participant.setRole(GroupRole.MEMBER);
                    GroupParticipant savedParticipant = groupParticipantRepository.save(participant);
                    systemMessageService.recordGroupEvent(group, user, user, SystemEventType.USER_JOINED);
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
        groupParticipantRepository.delete(targetParticipant);
        systemMessageService.recordGroupEvent(targetParticipant.getGroup(), targetParticipant.getUser(), actor, SystemEventType.USER_KICKED);
    }

    @Transactional
    public void banMember(User actor, Long groupId, Long userId, String reason) {
        Group group = groupAuthorizationService.requirePermission(actor, groupId, GroupPermission.BAN_MEMBERS);
        ensureActive(group);
        User target = loadUser(userId);
        if (Objects.equals(actor.getId(), target.getId())) {
            throw new ForbiddenException("You cannot perform this action on yourself");
        }
        validateBanReason(reason);

        groupParticipantRepository.findByGroupIdAndUserId(groupId, userId).ifPresent(participant -> {
            groupAuthorizationService.requireCanManageTarget(actor, groupId, target, GroupPermission.BAN_MEMBERS);
            groupParticipantRepository.delete(Objects.requireNonNull(participant));
        });

        if (groupBanRepository.findByGroupIdAndUserId(groupId, userId).isPresent()) {
            throw new BadRequestException("User is already banned from this group");
        }

        GroupBan ban = new GroupBan();
        ban.setGroup(group);
        ban.setUser(target);
        ban.setBannedBy(actor);
        ban.setReason(reason);
        groupBanRepository.save(ban);
        systemMessageService.recordGroupEvent(group, target, actor, SystemEventType.USER_BANNED);
    }

    @Transactional
    public void unbanMember(User actor, Long groupId, Long userId) {
        Group group = groupAuthorizationService.requirePermission(actor, groupId, GroupPermission.UNBAN_MEMBERS);
        ensureActive(group);
        GroupBan ban = groupBanRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new NotFoundException("Ban not found"));
        groupBanRepository.delete(Objects.requireNonNull(ban));
        systemMessageService.recordGroupEvent(group, ban.getUser(), actor, SystemEventType.USER_UNBANNED);
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
        systemMessageService.recordGroupEvent(currentLeader.getGroup(), newLeader.getUser(), actor, SystemEventType.LEADERSHIP_TRANSFERRED);
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
        systemMessageService.recordGroupEvent(group, actor, actor, SystemEventType.USER_LEFT);
        if (memberCount <= 1) {
            systemMessageService.recordGroupEvent(group, actor, actor, SystemEventType.GROUP_ARCHIVED);
        }
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
        if (joinLink.getExpiresAt() != null && !joinLink.getExpiresAt().isAfter(LocalDateTime.now())) {
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
