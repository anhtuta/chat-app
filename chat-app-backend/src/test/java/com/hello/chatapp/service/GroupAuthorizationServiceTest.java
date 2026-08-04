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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class GroupAuthorizationServiceTest {

    @Mock
    private GroupParticipantRepository groupParticipantRepository;

    @Mock
    private GroupBanRepository groupBanRepository;

    @InjectMocks
    private GroupAuthorizationService groupAuthorizationService;

    private Group group;
    private User actor;
    private User otherUser;

    @BeforeEach
    void setUp() {
        group = new Group();
        group.setId(100L);

        actor = new User();
        actor.setId(1L);
        actor.setUsername("alice");

        otherUser = new User();
        otherUser.setId(2L);
        otherUser.setUsername("bob");
    }

    @Test
    void requirePermission_allowsMemberToReadMessages() {
        GroupParticipant participant = buildParticipant(group, actor, GroupRole.MEMBER);
        stubMembership(actor, participant);

        Group result = groupAuthorizationService.requirePermission(actor, 100L, GroupPermission.READ_MESSAGES);

        assertThat(result).isSameAs(group);
    }

    @Test
    void requirePermission_rejectsMemberManagingGroupDetails() {
        GroupParticipant participant = buildParticipant(group, actor, GroupRole.MEMBER);
        stubMembership(actor, participant);

        assertThatThrownBy(() -> groupAuthorizationService.requirePermission(actor, 100L, GroupPermission.MANAGE_GROUP_DETAILS))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You do not have permission to manage_group_details");
    }

    @Test
    void requirePermission_rejectsBannedUserBeforeMembershipLookup() {
        when(groupBanRepository.existsByGroup_IdAndUser_Id(100L, 1L)).thenReturn(true);

        assertThatThrownBy(() -> groupAuthorizationService.requirePermission(actor, 100L, GroupPermission.READ_MESSAGES))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You are banned from this group");

        verifyNoInteractions(groupParticipantRepository);
    }

    @Test
    void requireActivePermission_allowsActiveGroup() {
        GroupParticipant participant = buildParticipant(group, actor, GroupRole.MEMBER);
        stubMembership(actor, participant);

        Group result = groupAuthorizationService.requireActivePermission(actor, 100L, GroupPermission.READ_MESSAGES);

        assertThat(result).isSameAs(group);
    }

    @Test
    void requireActivePermission_rejectsArchivedGroup() {
        group.setArchivedAt(java.time.LocalDateTime.now());
        GroupParticipant participant = buildParticipant(group, actor, GroupRole.MEMBER);
        stubMembership(actor, participant);

        assertThatThrownBy(() -> groupAuthorizationService.requireActivePermission(actor, 100L, GroupPermission.READ_MESSAGES))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Group is archived");
    }

    @Test
    void requireCanManageTarget_rejectsCoLeaderManagingLeader() {
        GroupParticipant actorParticipant = buildParticipant(group, actor, GroupRole.CO_LEADER);
        GroupParticipant targetParticipant = buildParticipant(group, otherUser, GroupRole.LEADER);
        stubMembership(actor, actorParticipant);
        stubMembership(otherUser, targetParticipant);

        assertThatThrownBy(() -> groupAuthorizationService.requireCanManageTarget(
                actor, 100L, otherUser, GroupPermission.MANAGE_ROLES))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("The leader cannot be managed by this action");
    }

    @Test
    void requireCanManageTarget_allowsElderManagingMember() {
        GroupParticipant actorParticipant = buildParticipant(group, actor, GroupRole.ELDER);
        GroupParticipant targetParticipant = buildParticipant(group, otherUser, GroupRole.MEMBER);
        stubMembership(actor, actorParticipant);
        stubMembership(otherUser, targetParticipant);

        groupAuthorizationService.requireCanManageTarget(actor, 100L, otherUser, GroupPermission.KICK_MEMBERS);
    }

    @Test
    void requireCanEditMessage_allowsOwnerEditingOwnPublicTextMessage() {
        Message message = buildMessage(actor, null, MessageType.TEXT);

        groupAuthorizationService.requireCanEditMessage(actor, message);
    }

    @Test
    void requireCanEditMessage_allowsOwnerEditingOwnGroupTextMessageWhenStillMember() {
        Message message = buildMessage(actor, group, MessageType.TEXT);
        stubMembership(actor, buildParticipant(group, actor, GroupRole.MEMBER));

        groupAuthorizationService.requireCanEditMessage(actor, message);
    }

    @Test
    void requireCanEditMessage_rejectsOwnerEditingOwnGroupTextMessageAfterKick() {
        Message message = buildMessage(actor, group, MessageType.TEXT);
        when(groupBanRepository.existsByGroup_IdAndUser_Id(100L, 1L)).thenReturn(false);
        when(groupParticipantRepository.findByGroupIdAndUser(100L, actor)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupAuthorizationService.requireCanEditMessage(actor, message))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You are not a member of this group");
    }

    @Test
    void requireCanEditMessage_rejectsOwnerEditingOwnGroupTextMessageWhenBanned() {
        Message message = buildMessage(actor, group, MessageType.TEXT);
        when(groupBanRepository.existsByGroup_IdAndUser_Id(100L, 1L)).thenReturn(true);

        assertThatThrownBy(() -> groupAuthorizationService.requireCanEditMessage(actor, message))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You are banned from this group");

        verifyNoInteractions(groupParticipantRepository);
    }

    @Test
    void requireCanEditMessage_rejectsMediaMessages() {
        Message message = buildMessage(actor, group, MessageType.IMAGE);

        assertThatThrownBy(() -> groupAuthorizationService.requireCanEditMessage(actor, message))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Only text messages can be edited");
    }

    @Test
    void requireCanEditMessage_allowsLeaderEditingOtherUsersTextMessage() {
        Message message = buildMessage(otherUser, group, MessageType.TEXT);
        GroupParticipant actorParticipant = buildParticipant(group, actor, GroupRole.LEADER);
        stubMembership(actor, actorParticipant);

        groupAuthorizationService.requireCanEditMessage(actor, message);
    }

    @Test
    void requireCanDeleteMessage_rejectsDeletingOtherUsersPublicMessage() {
        Message message = buildMessage(otherUser, null, MessageType.TEXT);

        assertThatThrownBy(() -> groupAuthorizationService.requireCanDeleteMessage(actor, message))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You can only delete your own public messages");
    }

    @Test
    void requireCanDeleteMessage_rejectsOwnerDeletingOwnGroupMessageAfterKick() {
        Message message = buildMessage(actor, group, MessageType.TEXT);
        when(groupBanRepository.existsByGroup_IdAndUser_Id(100L, 1L)).thenReturn(false);
        when(groupParticipantRepository.findByGroupIdAndUser(100L, actor)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupAuthorizationService.requireCanDeleteMessage(actor, message))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You are not a member of this group");
    }

    @Test
    void requireCanDeleteMessage_rejectsOwnerDeletingOwnGroupMessageWhenBanned() {
        Message message = buildMessage(actor, group, MessageType.TEXT);
        when(groupBanRepository.existsByGroup_IdAndUser_Id(100L, 1L)).thenReturn(true);

        assertThatThrownBy(() -> groupAuthorizationService.requireCanDeleteMessage(actor, message))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You are banned from this group");

        verifyNoInteractions(groupParticipantRepository);
    }

    @Test
    void requireUserTopicAccess_rejectsDifferentUsername() {
        assertThatThrownBy(() -> groupAuthorizationService.requireUserTopicAccess(actor, "charlie"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You can only subscribe to your own user topic");
    }

    private void stubMembership(User user, GroupParticipant participant) {
        when(groupParticipantRepository.findByGroupIdAndUser(100L, user)).thenReturn(Optional.of(participant));
        when(groupBanRepository.existsByGroup_IdAndUser_Id(100L, user.getId())).thenReturn(false);
    }

    private GroupParticipant buildParticipant(Group participantGroup, User user, GroupRole role) {
        GroupParticipant participant = new GroupParticipant(participantGroup, user);
        participant.setRole(role);
        return participant;
    }

    private Message buildMessage(User messageUser, Group messageGroup, MessageType messageType) {
        Message message = new Message();
        message.setUser(messageUser);
        message.setGroup(messageGroup);
        message.setMessageType(messageType);
        message.setContent("hello");
        return message;
    }
}
