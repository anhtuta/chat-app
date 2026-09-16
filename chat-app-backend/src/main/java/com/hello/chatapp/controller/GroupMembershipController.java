package com.hello.chatapp.controller;

import com.hello.chatapp.dto.AddGroupMemberRequest;
import com.hello.chatapp.dto.BanGroupMemberRequest;
import com.hello.chatapp.dto.CreateGroupJoinLinkRequest;
import com.hello.chatapp.dto.GroupBanResponse;
import com.hello.chatapp.dto.GroupJoinLinkResponse;
import com.hello.chatapp.dto.GroupMemberPageResponse;
import com.hello.chatapp.dto.GroupMemberResponse;
import com.hello.chatapp.dto.TransferLeadershipRequest;
import com.hello.chatapp.dto.UpdateGroupMemberRoleRequest;
import com.hello.chatapp.dto.UserResponse;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.UnauthorizedException;
import com.hello.chatapp.service.GroupMembershipService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * HTTP API for group membership, bans, join links, and leadership transfer.
 */
@RestController
@RequestMapping("/api/groups")
public class GroupMembershipController {

    private final GroupMembershipService groupMembershipService;

    public GroupMembershipController(GroupMembershipService groupMembershipService) {
        this.groupMembershipService = groupMembershipService;
    }

    @GetMapping("/{groupId}/members")
    public ResponseEntity<GroupMemberPageResponse> listMembers(
            @PathVariable Long groupId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            HttpSession session) {
        return ResponseEntity.ok(groupMembershipService.listMembers(
                getAuthenticatedUser(session),
                groupId,
                q,
                page,
                size));
    }

    @GetMapping("/{groupId}/addable-users")
    public ResponseEntity<List<UserResponse>> listAddableUsers(
            @PathVariable Long groupId,
            @RequestParam(required = false) String q,
            HttpSession session) {
        List<UserResponse> users = groupMembershipService
                .listAddableUsers(getAuthenticatedUser(session), groupId, q)
                .stream()
                .map(UserResponse::fromUser)
                .toList();
        return ResponseEntity.ok(users);
    }

    /**
     * Adds one or more users as {@code MEMBER} in a single request.
     */
    @PostMapping("/{groupId}/members")
    public ResponseEntity<List<GroupMemberResponse>> addMembers(
            @PathVariable Long groupId,
            @Valid @RequestBody AddGroupMemberRequest request,
            HttpSession session) {
        return ResponseEntity.ok(groupMembershipService.addMembers(
                getAuthenticatedUser(session),
                groupId,
                request.getUserIds()));
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    public ResponseEntity<Void> kickMember(
            @PathVariable Long groupId,
            @PathVariable Long userId,
            HttpSession session) {
        groupMembershipService.kickMember(getAuthenticatedUser(session), groupId, userId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{groupId}/members/me")
    public ResponseEntity<Void> leaveGroup(
            @PathVariable Long groupId,
            HttpSession session) {
        groupMembershipService.leaveGroup(getAuthenticatedUser(session), groupId);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{groupId}/members/{userId}/role")
    public ResponseEntity<GroupMemberResponse> updateMemberRole(
            @PathVariable Long groupId,
            @PathVariable Long userId,
            @Valid @RequestBody UpdateGroupMemberRoleRequest request,
            HttpSession session) {
        return ResponseEntity.ok(groupMembershipService.updateMemberRole(
                getAuthenticatedUser(session),
                groupId,
                userId,
                request.getRole()));
    }

    @GetMapping("/{groupId}/bans")
    public ResponseEntity<List<GroupBanResponse>> listBans(
            @PathVariable Long groupId,
            HttpSession session) {
        return ResponseEntity.ok(groupMembershipService.listBans(getAuthenticatedUser(session), groupId));
    }

    @PostMapping("/{groupId}/bans")
    public ResponseEntity<Void> banMember(
            @PathVariable Long groupId,
            @Valid @RequestBody BanGroupMemberRequest request,
            HttpSession session) {
        groupMembershipService.banMember(
                getAuthenticatedUser(session),
                groupId,
                request.getUserId(),
                request.getReason());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{groupId}/bans/{userId}")
    public ResponseEntity<Void> unbanMember(
            @PathVariable Long groupId,
            @PathVariable Long userId,
            HttpSession session) {
        groupMembershipService.unbanMember(getAuthenticatedUser(session), groupId, userId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{groupId}/leadership-transfer")
    public ResponseEntity<Void> transferLeadership(
            @PathVariable Long groupId,
            @Valid @RequestBody TransferLeadershipRequest request,
            HttpSession session) {
        groupMembershipService.transferLeadership(
                getAuthenticatedUser(session),
                groupId,
                request.getNewLeaderUserId());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{groupId}/join-links")
    public ResponseEntity<List<GroupJoinLinkResponse>> listJoinLinks(
            @PathVariable Long groupId,
            HttpSession session) {
        return ResponseEntity.ok(groupMembershipService.listJoinLinks(getAuthenticatedUser(session), groupId));
    }

    @PostMapping("/{groupId}/join-links")
    public ResponseEntity<GroupJoinLinkResponse> createJoinLink(
            @PathVariable Long groupId,
            @RequestBody(required = false) CreateGroupJoinLinkRequest request,
            HttpSession session) {
        return ResponseEntity.ok(groupMembershipService.createJoinLink(
                getAuthenticatedUser(session),
                groupId,
                request == null ? null : request.getExpiresAt()));
    }

    @DeleteMapping("/{groupId}/join-links/{joinLinkId}")
    public ResponseEntity<Void> revokeJoinLink(
            @PathVariable Long groupId,
            @PathVariable Long joinLinkId,
            HttpSession session) {
        groupMembershipService.revokeJoinLink(getAuthenticatedUser(session), groupId, joinLinkId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/join-links/{token}/join")
    public ResponseEntity<GroupMemberResponse> joinByToken(
            @PathVariable String token,
            HttpSession session) {
        return ResponseEntity.ok(groupMembershipService.joinByToken(getAuthenticatedUser(session), token));
    }

    private User getAuthenticatedUser(HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            throw new UnauthorizedException("User is not authenticated");
        }
        return user;
    }
}
