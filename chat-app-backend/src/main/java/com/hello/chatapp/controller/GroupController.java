package com.hello.chatapp.controller;

import com.hello.chatapp.dto.CreateGroupRequest;
import com.hello.chatapp.dto.GroupResponse;
import com.hello.chatapp.dto.MarkGroupReadRequest;
import com.hello.chatapp.dto.UpdateGroupRequest;
import com.hello.chatapp.dto.UserResponse;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.UnauthorizedException;
import com.hello.chatapp.service.GroupService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * HTTP API for group create, list, details, and profile updates (including {@code maxMembers}).
 */
@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    /**
     * Creates a group and persists optional {@code maxMembers} from the request.
     */
    @PostMapping
    public ResponseEntity<GroupResponse> createGroup(@Valid @RequestBody CreateGroupRequest request, HttpSession session) {
        return ResponseEntity.ok(groupService.createGroup(
                request.getName(),
                request.getDescription(),
                getAuthenticatedUser(session),
                request.getParticipantIds(),
                request.getMaxMembers()));
    }

    @GetMapping("/users")
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        List<User> users = groupService.getAllUsers();
        List<UserResponse> userResponses = users.stream()
                .map(UserResponse::fromUser)
                .collect(Collectors.toList());
        return ResponseEntity.ok(userResponses);
    }

    @GetMapping
    public ResponseEntity<List<GroupResponse>> getUserGroups(HttpSession session) {
        return ResponseEntity.ok(groupService.getUserGroups(getAuthenticatedUser(session)));
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<GroupResponse> getGroupDetails(@PathVariable Long groupId, HttpSession session) {
        return ResponseEntity.ok(groupService.getGroupDetails(getAuthenticatedUser(session), groupId));
    }

    /**
     * Patches group details. Omitted {@code maxMembers} is left unchanged.
     */
    @PatchMapping("/{groupId}")
    public ResponseEntity<GroupResponse> updateGroup(
            @PathVariable Long groupId,
            @Valid @RequestBody UpdateGroupRequest request,
            HttpSession session) {
        return ResponseEntity.ok(groupService.updateGroupDetails(
                getAuthenticatedUser(session),
                groupId,
                request.getName(),
                request.getDescription(),
                request.getMaxMembers(),
                request.isMaxMembersPresent()));
    }

    @PostMapping("/{groupId}/read")
    public ResponseEntity<Void> markGroupRead(
            @PathVariable Long groupId,
            @RequestBody(required = false) MarkGroupReadRequest request,
            HttpSession session) {
        Long lastReadMessageId = request == null ? null : request.getLastReadMessageId();
        groupService.markGroupAsRead(getAuthenticatedUser(session), groupId, lastReadMessageId);
        return ResponseEntity.ok().build();
    }

    private User getAuthenticatedUser(HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            throw new UnauthorizedException("User is not authenticated");
        }
        return user;
    }
}
