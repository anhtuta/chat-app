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
import com.hello.chatapp.repository.GroupBanRepository;
import com.hello.chatapp.repository.GroupJoinLinkRepository;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for membership mutations, including member-limit insertion-rule checks on add and join.
 */
@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class GroupMembershipServiceTest {

    @Mock
    private GroupAuthorizationService groupAuthorizationService;

    @Mock
    private GroupParticipantRepository groupParticipantRepository;

    @Mock
    private GroupBanRepository groupBanRepository;

    @Mock
    private GroupJoinLinkRepository groupJoinLinkRepository;

    @Mock
    private GroupRepository groupRepository;

    @Mock
    private SystemMessageService systemMessageService;

    @Mock
    private GroupMembershipRealtimePublisher membershipRealtimePublisher;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private GroupMembershipService groupMembershipService;

    private Group group;
    private User actor;
    private User targetUser;
    private Message systemMessage;

    @BeforeEach
    void setUp() {
        group = new Group();
        group.setId(100L);
        group.setName("Backend Team");
        group.setCreatedAt(LocalDateTime.now().minusDays(2));

        actor = new User();
        actor.setId(1L);
        actor.setUsername("alice");

        targetUser = new User();
        targetUser.setId(2L);
        targetUser.setUsername("bob");
        targetUser.setFullname("Bob Builder");

        systemMessage = new Message();
        systemMessage.setId(900L);
        systemMessage.setGroup(group);
        systemMessage.setUser(targetUser);
        systemMessage.setTimestamp(LocalDateTime.now());
    }

    private void stubMembershipRealtime(SystemEventType eventType, User subject, User eventActor) {
        when(systemMessageService.recordGroupEvent(group, subject, eventActor, eventType)).thenReturn(systemMessage);
    }

    @Test
    void listMembers_returnsPagedResultsAndNormalizesSearch() {
        GroupParticipant participant = new GroupParticipant(group, targetUser);
        participant.setRole(GroupRole.MEMBER);
        participant.setJoinedAt(LocalDateTime.now().minusDays(1));
        Page<GroupParticipant> page = new PageImpl<>(
                List.of(participant),
                PageRequest.of(0, 100),
                1);

        when(groupAuthorizationService.requireMember(actor, 100L)).thenReturn(participant);
        when(groupParticipantRepository.findByGroupIdWithUser(eq(100L), eq("%bob%"), any(Pageable.class)))
                .thenReturn(page);

        GroupMemberPageResponse response = groupMembershipService.listMembers(actor, 100L, "  bob  ", 0, 100);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getUsername()).isEqualTo("bob");
        assertThat(response.getPage()).isZero();
        assertThat(response.getSize()).isEqualTo(100);
        assertThat(response.getTotalElements()).isEqualTo(1);
        assertThat(response.isHasNext()).isFalse();
        verify(groupParticipantRepository).findByGroupIdWithUser(eq(100L), eq("%bob%"), any(Pageable.class));
    }

    @Test
    void listMembers_clampsPageSizeAndTreatsBlankSearchAsNull() {
        Page<GroupParticipant> emptyPage = Page.empty(PageRequest.of(0, 100));
        when(groupAuthorizationService.requireMember(actor, 100L)).thenReturn(new GroupParticipant(group, actor));
        when(groupParticipantRepository.findByGroupIdWithUser(eq(100L), isNull(), any(Pageable.class)))
                .thenReturn(emptyPage);

        GroupMemberPageResponse response = groupMembershipService.listMembers(actor, 100L, "   ", -1, 500);

        assertThat(response.getContent()).isEmpty();
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(groupParticipantRepository).findByGroupIdWithUser(eq(100L), isNull(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void listAddableUsers_requiresAddMembersNormalizesSearchAndCapsResults() {
        when(groupAuthorizationService.requireActivePermission(actor, 100L, GroupPermission.ADD_MEMBERS)).thenReturn(group);
        when(userRepository.findAddableUsersForGroup(eq(100L), eq("%bob%"), any(Pageable.class)))
                .thenReturn(List.of(targetUser));

        List<User> addableUsers = groupMembershipService.listAddableUsers(actor, 100L, "  bob  ");

        assertThat(addableUsers).containsExactly(targetUser);
        verify(groupAuthorizationService).requireActivePermission(actor, 100L, GroupPermission.ADD_MEMBERS);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findAddableUsersForGroup(eq(100L), eq("%bob%"), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(500);
    }

    @Test
    void listAddableUsers_treatsBlankSearchAsNull() {
        when(groupAuthorizationService.requireActivePermission(actor, 100L, GroupPermission.ADD_MEMBERS)).thenReturn(group);
        when(userRepository.findAddableUsersForGroup(eq(100L), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        List<User> addableUsers = groupMembershipService.listAddableUsers(actor, 100L, "   ");

        assertThat(addableUsers).isEmpty();
        verify(userRepository).findAddableUsersForGroup(eq(100L), isNull(), any(Pageable.class));
    }

    /**
     * Direct add inserts the target as {@code MEMBER} after locking the group and authorizing.
     */
    @Test
    void addMembers_createsMemberWithDefaultRole() {
        when(groupRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(group));
        when(groupAuthorizationService.requireActivePermission(actor, 100L, GroupPermission.ADD_MEMBERS)).thenReturn(group);
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetUser));
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.empty());
        when(groupParticipantRepository.save(any(GroupParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        stubMembershipRealtime(SystemEventType.USER_JOINED, targetUser, actor);

        List<GroupMemberResponse> responses = groupMembershipService.addMembers(actor, 100L, List.of(2L));

        InOrder order = inOrder(groupRepository, groupAuthorizationService);
        order.verify(groupRepository).findByIdForUpdate(100L);
        order.verify(groupAuthorizationService).requireActivePermission(actor, 100L, GroupPermission.ADD_MEMBERS);

        ArgumentCaptor<GroupParticipant> participantCaptor = ArgumentCaptor.forClass(GroupParticipant.class);
        verify(groupParticipantRepository).save(participantCaptor.capture());
        GroupParticipant savedParticipant = participantCaptor.getValue();

        assertThat(savedParticipant.getGroup()).isSameAs(group);
        assertThat(savedParticipant.getUser()).isSameAs(targetUser);
        assertThat(savedParticipant.getRole()).isEqualTo(GroupRole.MEMBER);

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().getUserId()).isEqualTo(2L);
        assertThat(responses.getFirst().getRole()).isEqualTo(GroupRole.MEMBER);
        verify(groupAuthorizationService).requireNotBanned(targetUser, 100L);
        verify(systemMessageService).recordGroupEvent(group, targetUser, actor, SystemEventType.USER_JOINED);
        verify(membershipRealtimePublisher).publishMembershipChange(
                group, systemMessage, SystemEventType.USER_JOINED.latestPreview(), null);
    }

    /**
     * One request can insert multiple distinct users, with a system event per added member.
     */
    @Test
    void addMembers_addsMultipleUsersInOneCall() {
        User thirdUser = new User();
        thirdUser.setId(3L);
        thirdUser.setUsername("carol");
        thirdUser.setFullname("Carol");

        when(groupRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(group));
        when(groupAuthorizationService.requireActivePermission(actor, 100L, GroupPermission.ADD_MEMBERS)).thenReturn(group);
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetUser));
        when(userRepository.findById(3L)).thenReturn(Optional.of(thirdUser));
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.empty());
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 3L)).thenReturn(Optional.empty());
        when(groupParticipantRepository.save(any(GroupParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(systemMessageService.recordGroupEvent(eq(group), any(User.class), eq(actor), eq(SystemEventType.USER_JOINED)))
                .thenReturn(systemMessage);

        List<GroupMemberResponse> responses = groupMembershipService.addMembers(actor, 100L, List.of(2L, 3L, 2L));

        assertThat(responses).extracting(GroupMemberResponse::getUserId).containsExactly(2L, 3L);
        verify(groupParticipantRepository, times(2)).save(any(GroupParticipant.class));
        verify(systemMessageService, times(2)).recordGroupEvent(eq(group), any(User.class), eq(actor), eq(SystemEventType.USER_JOINED));
    }

    /**
     * If one selected user is already a member, the whole batch is rejected and nobody is inserted.
     */
    @Test
    void addMembers_rejectsBatchWhenAnyUserIsAlreadyAMember() {
        when(groupRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(group));
        when(groupAuthorizationService.requireActivePermission(actor, 100L, GroupPermission.ADD_MEMBERS)).thenReturn(group);
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetUser));
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 2L))
                .thenReturn(Optional.of(new GroupParticipant(group, targetUser)));

        assertThatThrownBy(() -> groupMembershipService.addMembers(actor, 100L, List.of(2L)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("User is already a member of this group");

        verify(groupParticipantRepository, never()).save(any(GroupParticipant.class));
    }

    @Test
    void joinByToken_createsMemberWhenUserIsNotAlreadyInGroup() {
        GroupJoinLink joinLink = new GroupJoinLink();
        joinLink.setId(77L);
        joinLink.setGroup(group);
        joinLink.setCreatedBy(actor);
        joinLink.setCreatedAt(LocalDateTime.now().minusHours(1));
        joinLink.setExpiresAt(Instant.now().plusSeconds(3600));

        when(groupJoinLinkRepository.findByTokenHashWithGroup(anyString())).thenReturn(Optional.of(joinLink));
        when(groupRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(group));
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.empty());
        when(groupParticipantRepository.save(any(GroupParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        stubMembershipRealtime(SystemEventType.USER_JOINED, targetUser, targetUser);

        GroupMemberResponse response = groupMembershipService.joinByToken(targetUser, "join-token");

        ArgumentCaptor<GroupParticipant> participantCaptor = ArgumentCaptor.forClass(GroupParticipant.class);
        verify(groupParticipantRepository).save(participantCaptor.capture());
        GroupParticipant savedParticipant = participantCaptor.getValue();

        assertThat(savedParticipant.getGroup()).isSameAs(group);
        assertThat(savedParticipant.getUser()).isSameAs(targetUser);
        assertThat(savedParticipant.getRole()).isEqualTo(GroupRole.MEMBER);

        assertThat(response.getUserId()).isEqualTo(2L);
        assertThat(response.getRole()).isEqualTo(GroupRole.MEMBER);
        assertThat(response.getGroupId()).isEqualTo(100L);
        assertThat(response.getGroupName()).isEqualTo("Backend Team");
        InOrder lockThenRefreshThenAuth = inOrder(groupRepository, entityManager, groupAuthorizationService);
        lockThenRefreshThenAuth.verify(groupRepository).findByIdForUpdate(100L);
        lockThenRefreshThenAuth.verify(entityManager).refresh(joinLink);
        lockThenRefreshThenAuth.verify(groupAuthorizationService).requireNotBanned(targetUser, 100L);
        verify(groupAuthorizationService).requireNotBanned(targetUser, 100L);
        verify(systemMessageService).recordGroupEvent(group, targetUser, targetUser, SystemEventType.USER_JOINED);
        verify(membershipRealtimePublisher).publishMembershipChange(
                group, systemMessage, SystemEventType.USER_JOINED.latestPreview(), null);
    }

    @Test
    void joinByToken_revalidatesJoinLinkAfterGroupLock() {
        GroupJoinLink joinLink = new GroupJoinLink();
        joinLink.setId(77L);
        joinLink.setGroup(group);
        joinLink.setCreatedBy(actor);
        joinLink.setCreatedAt(LocalDateTime.now().minusHours(1));
        joinLink.setExpiresAt(Instant.now().plusSeconds(3600));

        when(groupJoinLinkRepository.findByTokenHashWithGroup(anyString())).thenReturn(Optional.of(joinLink));
        when(groupRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(group));
        doAnswer(invocation -> {
            joinLink.setRevokedAt(LocalDateTime.now());
            return null;
        }).when(entityManager).refresh(joinLink);

        assertThatThrownBy(() -> groupMembershipService.joinByToken(targetUser, "join-token"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Join link has been revoked");

        verify(entityManager).refresh(joinLink);
        verify(groupAuthorizationService, never()).requireNotBanned(targetUser, 100L);
        verify(groupParticipantRepository, never()).save(any(GroupParticipant.class));
    }

    /**
     * Direct add succeeds when a positive cap still has a free seat ({@code count < maxMembers}).
     */
    @Test
    void addMember_succeedsWhenCountIsBelowLimit() {
        group.setMaxMembers(2);
        when(groupRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(group));
        when(groupAuthorizationService.requireActivePermission(actor, 100L, GroupPermission.ADD_MEMBERS)).thenReturn(group);
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetUser));
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.empty());
        when(groupParticipantRepository.countByGroupId(100L)).thenReturn(1L);
        when(groupParticipantRepository.save(any(GroupParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        stubMembershipRealtime(SystemEventType.USER_JOINED, targetUser, actor);

        List<GroupMemberResponse> responses = groupMembershipService.addMembers(actor, 100L, List.of(2L));

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().getUserId()).isEqualTo(2L);
        verify(groupParticipantRepository).save(any(GroupParticipant.class));
    }

    /**
     * Direct add is rejected when the group is already at the cap; no participant row is saved.
     */
    @Test
    void addMember_rejectsWhenCountIsAtLimit() {
        group.setMaxMembers(2);
        when(groupRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(group));
        when(groupAuthorizationService.requireActivePermission(actor, 100L, GroupPermission.ADD_MEMBERS)).thenReturn(group);
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetUser));
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.empty());
        when(groupParticipantRepository.countByGroupId(100L)).thenReturn(2L);

        assertThatThrownBy(() -> groupMembershipService.addMembers(actor, 100L, List.of(2L)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Group member limit has been reached");

        verify(groupParticipantRepository, never()).save(any(GroupParticipant.class));
        verify(systemMessageService, never()).recordGroupEvent(any(), any(), any(), any());
    }

    /**
     * Direct add is rejected in the over-limit state after a leader lowered {@code maxMembers}.
     */
    @Test
    void addMember_rejectsWhenCountIsOverLimit() {
        group.setMaxMembers(2);
        when(groupRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(group));
        when(groupAuthorizationService.requireActivePermission(actor, 100L, GroupPermission.ADD_MEMBERS)).thenReturn(group);
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetUser));
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.empty());
        when(groupParticipantRepository.countByGroupId(100L)).thenReturn(5L);

        assertThatThrownBy(() -> groupMembershipService.addMembers(actor, 100L, List.of(2L)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Group member limit has been reached");

        verify(groupParticipantRepository, never()).save(any(GroupParticipant.class));
    }

    /**
     * A batch is rejected when remaining seats cannot cover every selected user; nobody is inserted.
     */
    @Test
    void addMembers_rejectsWhenBatchWouldExceedLimit() {
        User thirdUser = new User();
        thirdUser.setId(3L);
        thirdUser.setUsername("carol");
        group.setMaxMembers(2);
        when(groupRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(group));
        when(groupAuthorizationService.requireActivePermission(actor, 100L, GroupPermission.ADD_MEMBERS)).thenReturn(group);
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetUser));
        when(userRepository.findById(3L)).thenReturn(Optional.of(thirdUser));
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.empty());
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 3L)).thenReturn(Optional.empty());
        when(groupParticipantRepository.countByGroupId(100L)).thenReturn(1L);

        assertThatThrownBy(() -> groupMembershipService.addMembers(actor, 100L, List.of(2L, 3L)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Group member limit has been reached");

        verify(groupParticipantRepository, never()).save(any(GroupParticipant.class));
    }

    /**
     * Join by link succeeds when a positive cap still has a free seat.
     */
    @Test
    void joinByToken_succeedsWhenCountIsBelowLimit() {
        group.setMaxMembers(3);
        GroupJoinLink joinLink = validJoinLink();
        when(groupJoinLinkRepository.findByTokenHashWithGroup(anyString())).thenReturn(Optional.of(joinLink));
        when(groupRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(group));
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.empty());
        when(groupParticipantRepository.countByGroupId(100L)).thenReturn(2L);
        when(groupParticipantRepository.save(any(GroupParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        stubMembershipRealtime(SystemEventType.USER_JOINED, targetUser, targetUser);

        GroupMemberResponse response = groupMembershipService.joinByToken(targetUser, "join-token");

        assertThat(response.getUserId()).isEqualTo(2L);
        verify(groupParticipantRepository).save(any(GroupParticipant.class));
    }

    /**
     * Join by link is rejected when the group is full; no participant row is saved.
     */
    @Test
    void joinByToken_rejectsWhenCountIsAtLimit() {
        group.setMaxMembers(2);
        GroupJoinLink joinLink = validJoinLink();
        when(groupJoinLinkRepository.findByTokenHashWithGroup(anyString())).thenReturn(Optional.of(joinLink));
        when(groupRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(group));
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.empty());
        when(groupParticipantRepository.countByGroupId(100L)).thenReturn(2L);

        assertThatThrownBy(() -> groupMembershipService.joinByToken(targetUser, "join-token"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Group member limit has been reached");

        verify(groupParticipantRepository, never()).save(any(GroupParticipant.class));
        verify(systemMessageService, never()).recordGroupEvent(any(), any(), any(), any());
    }

    /**
     * An existing member using a join link stays idempotent even when the group is full or over-limit.
     */
    @Test
    void joinByToken_existingMemberSucceedsWhenGroupIsFull() {
        group.setMaxMembers(1);
        GroupJoinLink joinLink = validJoinLink();
        GroupParticipant existing = new GroupParticipant(group, targetUser);
        existing.setRole(GroupRole.MEMBER);
        when(groupJoinLinkRepository.findByTokenHashWithGroup(anyString())).thenReturn(Optional.of(joinLink));
        when(groupRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(group));
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.of(existing));

        GroupMemberResponse response = groupMembershipService.joinByToken(targetUser, "join-token");

        assertThat(response.getUserId()).isEqualTo(2L);
        verify(groupParticipantRepository, never()).countByGroupId(any());
        verify(groupParticipantRepository, never()).save(any(GroupParticipant.class));
        verify(systemMessageService, never()).recordGroupEvent(any(), any(), any(), any());
    }

    private GroupJoinLink validJoinLink() {
        GroupJoinLink joinLink = new GroupJoinLink();
        joinLink.setId(77L);
        joinLink.setGroup(group);
        joinLink.setCreatedBy(actor);
        joinLink.setCreatedAt(LocalDateTime.now().minusHours(1));
        joinLink.setExpiresAt(Instant.now().plusSeconds(3600));
        return joinLink;
    }

    @Test
    void listJoinLinks_requiresCreateJoinLinkAndOmitsToken() {
        GroupJoinLink joinLink = new GroupJoinLink();
        joinLink.setId(77L);
        joinLink.setGroup(group);
        joinLink.setCreatedBy(actor);
        joinLink.setCreatedAt(LocalDateTime.now().minusHours(1));
        joinLink.setExpiresAt(Instant.now().plusSeconds(86_400));

        when(groupAuthorizationService.requireActivePermission(actor, 100L, GroupPermission.CREATE_JOIN_LINK))
                .thenReturn(group);
        when(groupJoinLinkRepository.findByGroupIdWithCreator(100L)).thenReturn(List.of(joinLink));

        List<GroupJoinLinkResponse> links = groupMembershipService.listJoinLinks(actor, 100L);

        assertThat(links).hasSize(1);
        assertThat(links.get(0).getId()).isEqualTo(77L);
        assertThat(links.get(0).getToken()).isNull();
        assertThat(links.get(0).getCreatedByUsername()).isEqualTo("alice");
        verify(groupAuthorizationService).requireActivePermission(actor, 100L, GroupPermission.CREATE_JOIN_LINK);
    }

    @Test
    void banMember_removesExistingParticipantAndPersistsBan() {
        GroupParticipant targetParticipant = new GroupParticipant(group, targetUser);
        targetParticipant.setRole(GroupRole.MEMBER);

        when(groupRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(group));
        when(groupAuthorizationService.requireActivePermission(actor, 100L, GroupPermission.BAN_MEMBERS)).thenReturn(group);
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetUser));
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.of(targetParticipant));
        when(groupBanRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.empty());
        when(groupBanRepository.save(any(GroupBan.class))).thenAnswer(invocation -> invocation.getArgument(0));
        stubMembershipRealtime(SystemEventType.USER_BANNED, targetUser, actor);

        groupMembershipService.banMember(actor, 100L, 2L, "spam");

        InOrder order = inOrder(groupRepository, groupAuthorizationService);
        order.verify(groupRepository).findByIdForUpdate(100L);
        order.verify(groupAuthorizationService).requireActivePermission(actor, 100L, GroupPermission.BAN_MEMBERS);
        verify(groupAuthorizationService).requireCanManageTarget(actor, 100L, targetUser, GroupPermission.BAN_MEMBERS);
        verify(groupParticipantRepository).delete(targetParticipant);

        ArgumentCaptor<GroupBan> banCaptor = ArgumentCaptor.forClass(GroupBan.class);
        verify(groupBanRepository).save(banCaptor.capture());
        GroupBan savedBan = banCaptor.getValue();
        assertThat(savedBan.getGroup()).isSameAs(group);
        assertThat(savedBan.getUser()).isSameAs(targetUser);
        assertThat(savedBan.getBannedBy()).isSameAs(actor);
        assertThat(savedBan.getReason()).isEqualTo("spam");
        verify(systemMessageService).recordGroupEvent(group, targetUser, actor, SystemEventType.USER_BANNED);
        verify(membershipRealtimePublisher).publishMembershipChange(
                group, systemMessage, SystemEventType.USER_BANNED.latestPreview(), "bob");
    }

    @Test
    void listBans_requiresUnbanPermissionAndMapsResponses() {
        GroupBan ban = new GroupBan();
        ban.setGroup(group);
        ban.setUser(targetUser);
        ban.setBannedBy(actor);
        ban.setReason("spam");
        ban.setBannedAt(LocalDateTime.now().minusHours(1));

        when(groupAuthorizationService.requireActivePermission(actor, 100L, GroupPermission.UNBAN_MEMBERS))
                .thenReturn(group);
        when(groupBanRepository.findByGroupIdWithUsers(100L)).thenReturn(List.of(ban));

        List<GroupBanResponse> bans = groupMembershipService.listBans(actor, 100L);

        assertThat(bans).hasSize(1);
        assertThat(bans.get(0).getUserId()).isEqualTo(2L);
        assertThat(bans.get(0).getUsername()).isEqualTo("bob");
        assertThat(bans.get(0).getBannedByUsername()).isEqualTo("alice");
        assertThat(bans.get(0).getReason()).isEqualTo("spam");
        verify(groupAuthorizationService).requireActivePermission(actor, 100L, GroupPermission.UNBAN_MEMBERS);
    }

    @Test
    void transferLeadership_demotesCurrentLeaderBeforePromotingNewLeader() {
        GroupParticipant currentLeader = new GroupParticipant(group, actor);
        currentLeader.setRole(GroupRole.LEADER);

        GroupParticipant newLeader = new GroupParticipant(group, targetUser);
        newLeader.setRole(GroupRole.MEMBER);

        when(groupRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(group));
        when(groupAuthorizationService.requireMember(actor, 100L)).thenReturn(currentLeader);
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.of(newLeader));
        when(groupParticipantRepository.saveAndFlush(currentLeader)).thenReturn(currentLeader);
        when(groupParticipantRepository.save(newLeader)).thenReturn(newLeader);

        groupMembershipService.transferLeadership(actor, 100L, 2L);

        InOrder lockThenAuth = inOrder(groupRepository, groupAuthorizationService);
        lockThenAuth.verify(groupRepository).findByIdForUpdate(100L);
        lockThenAuth.verify(groupAuthorizationService).requireMember(actor, 100L);
        assertThat(currentLeader.getRole()).isEqualTo(GroupRole.MEMBER);
        assertThat(newLeader.getRole()).isEqualTo(GroupRole.LEADER);
        InOrder repositoryInOrder = inOrder(groupParticipantRepository);
        repositoryInOrder.verify(groupParticipantRepository).saveAndFlush(currentLeader);
        repositoryInOrder.verify(groupParticipantRepository).save(newLeader);
        verify(systemMessageService).recordGroupEvent(group, targetUser, actor, SystemEventType.LEADERSHIP_TRANSFERRED);
    }

    @Test
    void leaveGroup_archivesGroupWhenLastMemberLeaves() {
        GroupParticipant participant = new GroupParticipant(group, actor);
        participant.setRole(GroupRole.MEMBER);

        when(groupRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(group));
        when(groupAuthorizationService.requireMember(actor, 100L)).thenReturn(participant);
        when(groupParticipantRepository.countByGroupId(100L)).thenReturn(1L);
        when(systemMessageService.recordGroupEvent(group, actor, actor, SystemEventType.USER_LEFT)).thenReturn(systemMessage);
        when(systemMessageService.recordGroupEvent(group, actor, actor, SystemEventType.GROUP_ARCHIVED)).thenReturn(systemMessage);

        groupMembershipService.leaveGroup(actor, 100L);

        assertThat(group.getArchivedBy()).isSameAs(actor);
        assertThat(group.getArchiveReason()).isEqualTo("LAST_MEMBER_LEFT");
        assertThat(group.getArchivedAt()).isNotNull();
        verify(groupRepository).save(group);
        verify(groupParticipantRepository).delete(participant);
        verify(systemMessageService).recordGroupEvent(group, actor, actor, SystemEventType.USER_LEFT);
        verify(systemMessageService).recordGroupEvent(group, actor, actor, SystemEventType.GROUP_ARCHIVED);
        verify(membershipRealtimePublisher).publishMembershipChange(
                group, systemMessage, SystemEventType.USER_LEFT.latestPreview(), "alice");
        verify(membershipRealtimePublisher).publishMembershipChange(
                group, systemMessage, SystemEventType.GROUP_ARCHIVED.latestPreview(), null);
    }

    /**
     * Moderation must lock the actor’s participant row, not {@code groups}, so unrelated edits stay parallel.
     */
    @Test
    void lockActorParticipantForModeration_locksParticipantAndRejectsArchivedGroup() {
        GroupParticipant participant = new GroupParticipant(group, actor);
        when(groupParticipantRepository.findByGroupIdAndUserIdForUpdate(100L, 1L))
                .thenReturn(Optional.of(participant));

        assertThat(groupMembershipService.lockActorParticipantForModeration(100L, 1L)).isSameAs(participant);
        verify(groupRepository, never()).findByIdForUpdate(100L);

        group.setArchivedAt(LocalDateTime.now());
        assertThatThrownBy(() -> groupMembershipService.lockActorParticipantForModeration(100L, 1L))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Group is archived");
    }

    /**
     * A kicked user has no row to lock — fail before message auth.
     */
    @Test
    void lockActorParticipantForModeration_missingRow_isForbidden() {
        when(groupParticipantRepository.findByGroupIdAndUserIdForUpdate(100L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupMembershipService.lockActorParticipantForModeration(100L, 1L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You are not a member of this group");
    }
}
