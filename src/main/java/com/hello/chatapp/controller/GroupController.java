package com.hello.chatapp.controller;

import com.hello.chatapp.dto.CreateGroupRequest;
import com.hello.chatapp.dto.GroupResponse;
import com.hello.chatapp.dto.UserResponse;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.UnauthorizedException;
import com.hello.chatapp.service.GroupService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @PostMapping
    public ResponseEntity<GroupResponse> createGroup(@Valid @RequestBody CreateGroupRequest request, HttpSession session) {
        // Get authenticated user from session
        User creator = (User) session.getAttribute("user");
        if (creator == null) {
            throw new UnauthorizedException("User is not authenticated");
        }

        // Create group
        Group group = groupService.createGroup(request.getName(), creator, request.getParticipantIds());
        return ResponseEntity.ok(GroupResponse.fromGroup(group));
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
        // Get authenticated user from session
        User user = (User) session.getAttribute("user");
        if (user == null) {
            throw new UnauthorizedException("User is not authenticated");
        }

        List<Group> groups = groupService.getGroupsByUser(user);
        List<GroupResponse> groupResponses = groups.stream()
                .map(GroupResponse::fromGroup)
                .collect(Collectors.toList());
        return ResponseEntity.ok(groupResponses);
    }
}
