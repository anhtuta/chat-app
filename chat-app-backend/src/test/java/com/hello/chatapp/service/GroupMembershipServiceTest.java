package com.hello.chatapp.service;

import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.dto.GroupBanResponse;
import com.hello.chatapp.dto.GroupMemberPageResponse;
import com.hello.chatapp.dto.GroupMemberResponse;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupBan;
import com.hello.chatapp.entity.GroupJoinLink;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.repository.GroupBanRepository;
import com.hello.chatapp.repository.GroupJoinLinkRepository;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.UserRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private UserRepository userRepository;

    @InjectMocks
    private GroupMembershipService groupMembershipService;

    private Group group;
    private User actor;
    private User targetUser;

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

    @Test
    void addMember_createsMemberWithDefaultRole() {
        when(groupAuthorizationService.requireActivePermission(actor, 100L, GroupPermission.ADD_MEMBERS)).thenReturn(group);
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetUser));
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.empty());
        when(groupParticipantRepository.save(any(GroupParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GroupMemberResponse response = groupMembershipService.addMember(actor, 100L, 2L);

        ArgumentCaptor<GroupParticipant> participantCaptor = ArgumentCaptor.forClass(GroupParticipant.class);
        verify(groupParticipantRepository).save(participantCaptor.capture());
        GroupParticipant savedParticipant = participantCaptor.getValue();

        assertThat(savedParticipant.getGroup()).isSameAs(group);
        assertThat(savedParticipant.getUser()).isSameAs(targetUser);
        assertThat(savedParticipant.getRole()).isEqualTo(GroupRole.MEMBER);

        assertThat(response.getUserId()).isEqualTo(2L);
        assertThat(response.getRole()).isEqualTo(GroupRole.MEMBER);
        verify(groupAuthorizationService).requireNotBanned(targetUser, 100L);
        verify(systemMessageService).recordGroupEvent(group, targetUser, actor, SystemEventType.USER_JOINED);
    }

    @Test
    void joinByToken_createsMemberWhenUserIsNotAlreadyInGroup() {
        GroupJoinLink joinLink = new GroupJoinLink();
        joinLink.setId(77L);
        joinLink.setGroup(group);
        joinLink.setCreatedBy(actor);
        joinLink.setCreatedAt(LocalDateTime.now().minusHours(1));
        joinLink.setExpiresAt(LocalDateTime.now().plusHours(1));

        when(groupJoinLinkRepository.findByTokenHashWithGroup(anyString())).thenReturn(Optional.of(joinLink));
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.empty());
        when(groupParticipantRepository.save(any(GroupParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GroupMemberResponse response = groupMembershipService.joinByToken(targetUser, "join-token");

        ArgumentCaptor<GroupParticipant> participantCaptor = ArgumentCaptor.forClass(GroupParticipant.class);
        verify(groupParticipantRepository).save(participantCaptor.capture());
        GroupParticipant savedParticipant = participantCaptor.getValue();

        assertThat(savedParticipant.getGroup()).isSameAs(group);
        assertThat(savedParticipant.getUser()).isSameAs(targetUser);
        assertThat(savedParticipant.getRole()).isEqualTo(GroupRole.MEMBER);

        assertThat(response.getUserId()).isEqualTo(2L);
        assertThat(response.getRole()).isEqualTo(GroupRole.MEMBER);
        verify(groupAuthorizationService).requireNotBanned(targetUser, 100L);
        verify(systemMessageService).recordGroupEvent(group, targetUser, targetUser, SystemEventType.USER_JOINED);
    }

    @Test
    void banMember_removesExistingParticipantAndPersistsBan() {
        GroupParticipant targetParticipant = new GroupParticipant(group, targetUser);
        targetParticipant.setRole(GroupRole.MEMBER);

        when(groupAuthorizationService.requireActivePermission(actor, 100L, GroupPermission.BAN_MEMBERS)).thenReturn(group);
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetUser));
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.of(targetParticipant));
        when(groupBanRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.empty());
        when(groupBanRepository.save(any(GroupBan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        groupMembershipService.banMember(actor, 100L, 2L, "spam");

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

        when(groupAuthorizationService.requireMember(actor, 100L)).thenReturn(currentLeader);
        when(groupParticipantRepository.findByGroupIdAndUserId(100L, 2L)).thenReturn(Optional.of(newLeader));
        when(groupParticipantRepository.saveAndFlush(currentLeader)).thenReturn(currentLeader);
        when(groupParticipantRepository.save(newLeader)).thenReturn(newLeader);

        groupMembershipService.transferLeadership(actor, 100L, 2L);

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

        when(groupAuthorizationService.requireMember(actor, 100L)).thenReturn(participant);
        when(groupParticipantRepository.countByGroupId(100L)).thenReturn(1L);

        groupMembershipService.leaveGroup(actor, 100L);

        assertThat(group.getArchivedBy()).isSameAs(actor);
        assertThat(group.getArchiveReason()).isEqualTo("LAST_MEMBER_LEFT");
        assertThat(group.getArchivedAt()).isNotNull();
        verify(groupRepository).save(group);
        verify(groupParticipantRepository).delete(participant);
        verify(systemMessageService).recordGroupEvent(group, actor, actor, SystemEventType.USER_LEFT);
        verify(systemMessageService).recordGroupEvent(group, actor, actor, SystemEventType.GROUP_ARCHIVED);
    }
}
