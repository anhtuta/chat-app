package com.hello.chatapp.service;

import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.dto.GroupResponse;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.BadRequestException;
import com.hello.chatapp.exception.ForbiddenException;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.MessageRepository;
import com.hello.chatapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for group create/update, including {@code maxMembers} persistence and initial-membership checks.
 */
@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    @Mock
    private GroupRepository groupRepository;

    @Mock
    private GroupParticipantRepository groupParticipantRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private GroupAuthorizationService groupAuthorizationService;

    @Mock
    private SystemMessageService systemMessageService;

    @InjectMocks
    private GroupService groupService;

    private User creator;
    private User member;
    private Group group;

    @BeforeEach
    void setUp() {
        creator = new User();
        creator.setId(1L);
        creator.setUsername("alice");
        creator.setFullname("Alice");

        member = new User();
        member.setId(2L);
        member.setUsername("bob");
        member.setFullname("Bob");

        group = new Group("Backend Team", creator);
        group.setId(100L);
        group.setDescription("Core backend work");
        group.setCreatedAt(LocalDateTime.now().minusDays(1));
    }

    /**
     * Create returns the persisted name, description, leader role, and leader permissions.
     */
    @Test
    void createGroup_returnsDescriptionRoleAndPermissions() {
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> {
            Group savedGroup = invocation.getArgument(0);
            savedGroup.setId(100L);
            return savedGroup;
        });
        when(groupRepository.findByIdWithCreator(100L)).thenReturn(Optional.of(group));
        when(groupAuthorizationService.getPermissions(GroupRole.LEADER))
                .thenReturn(List.of(GroupPermission.READ_MESSAGES, GroupPermission.MANAGE_GROUP_DETAILS));

        GroupResponse response = groupService.createGroup(
                "  Backend Team  ", "  Core backend work  ", creator, List.of(), null);

        assertThat(response.getName()).isEqualTo("Backend Team");
        assertThat(response.getDescription()).isEqualTo("Core backend work");
        assertThat(response.getCurrentUserRole()).isEqualTo(GroupRole.LEADER);
        assertThat(response.getCurrentUserPermissions())
                .containsExactly(GroupPermission.READ_MESSAGES, GroupPermission.MANAGE_GROUP_DETAILS);
    }

    /**
     * Create always inserts the creator as {@code LEADER}.
     */
    @Test
    void createGroup_savesCreatorAsLeader() {
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> {
            Group savedGroup = invocation.getArgument(0);
            savedGroup.setId(100L);
            return savedGroup;
        });
        when(groupRepository.findByIdWithCreator(100L)).thenReturn(Optional.of(group));
        when(groupAuthorizationService.getPermissions(GroupRole.LEADER)).thenReturn(List.of());

        groupService.createGroup("Backend Team", null, creator, List.of(), null);

        ArgumentCaptor<GroupParticipant> participantCaptor = ArgumentCaptor.forClass(GroupParticipant.class);
        verify(groupParticipantRepository).save(participantCaptor.capture());
        GroupParticipant savedCreator = participantCaptor.getValue();

        assertThat(savedCreator.getUser()).isSameAs(creator);
        assertThat(savedCreator.getRole()).isEqualTo(GroupRole.LEADER);
    }

    @Test
    void getUserGroups_returnsRoleAndUnreadWithoutPermissions() {
        GroupParticipant participant = new GroupParticipant(group, member);
        participant.setRole(GroupRole.CO_LEADER);

        when(groupParticipantRepository.findByUser(member)).thenReturn(List.of(participant));
        when(messageRepository.findUnreadCountRowsByUserId(2L)).thenReturn(List.of());

        List<GroupResponse> responses = groupService.getUserGroups(member);

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().getId()).isEqualTo(100L);
        assertThat(responses.getFirst().getCurrentUserRole()).isEqualTo(GroupRole.CO_LEADER);
        assertThat(responses.getFirst().getCurrentUserPermissions()).isEmpty();
        assertThat(responses.getFirst().getUnreadCount()).isZero();
        verify(groupAuthorizationService, never()).getPermissions(any(GroupParticipant.class));
        verify(groupAuthorizationService, never()).getPermissions(any(GroupRole.class));
    }

    @Test
    void getGroupDetails_returnsRoleAndPermissionsWithoutUnreadLookup() {
        GroupParticipant participant = new GroupParticipant(group, member);
        participant.setRole(GroupRole.CO_LEADER);

        when(groupAuthorizationService.requireMember(member, 100L)).thenReturn(participant);
        when(groupAuthorizationService.getPermissions(participant))
                .thenReturn(List.of(GroupPermission.READ_MESSAGES, GroupPermission.MANAGE_GROUP_DETAILS));

        GroupResponse response = groupService.getGroupDetails(member, 100L);

        assertThat(response.getId()).isEqualTo(100L);
        assertThat(response.getDescription()).isEqualTo("Core backend work");
        assertThat(response.getCurrentUserRole()).isEqualTo(GroupRole.CO_LEADER);
        assertThat(response.getCurrentUserPermissions())
                .containsExactly(GroupPermission.READ_MESSAGES, GroupPermission.MANAGE_GROUP_DETAILS);
        assertThat(response.getUnreadCount()).isZero();
        verify(messageRepository, never()).findUnreadCountRowsByUserId(any());
    }

    /**
     * Create stores {@code null} maxMembers as unlimited.
     */
    @Test
    void createGroup_nullMaxMembers_persistsUnlimited() {
        ArgumentCaptor<Group> groupCaptor = ArgumentCaptor.forClass(Group.class);
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> {
            Group savedGroup = invocation.getArgument(0);
            savedGroup.setId(100L);
            return savedGroup;
        });
        when(groupRepository.findByIdWithCreator(100L)).thenReturn(Optional.of(group));
        when(groupAuthorizationService.getPermissions(GroupRole.LEADER)).thenReturn(List.of());

        groupService.createGroup("Backend Team", null, creator, List.of(), null);

        verify(groupRepository).save(groupCaptor.capture());
        assertThat(groupCaptor.getValue().getMaxMembers()).isNull();
    }

    /**
     * Create stores {@code 0} maxMembers as unlimited (not a cap of zero).
     */
    @Test
    void createGroup_zeroMaxMembers_persistsUnlimited() {
        ArgumentCaptor<Group> groupCaptor = ArgumentCaptor.forClass(Group.class);
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> {
            Group savedGroup = invocation.getArgument(0);
            savedGroup.setId(100L);
            return savedGroup;
        });
        when(groupRepository.findByIdWithCreator(100L)).thenReturn(Optional.of(group));
        when(groupAuthorizationService.getPermissions(GroupRole.LEADER)).thenReturn(List.of());

        groupService.createGroup("Backend Team", null, creator, List.of(), 0);

        verify(groupRepository).save(groupCaptor.capture());
        assertThat(groupCaptor.getValue().getMaxMembers()).isZero();
    }

    /**
     * Create persists a positive maxMembers cap.
     */
    @Test
    void createGroup_positiveMaxMembers_isPersisted() {
        ArgumentCaptor<Group> groupCaptor = ArgumentCaptor.forClass(Group.class);
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> {
            Group savedGroup = invocation.getArgument(0);
            savedGroup.setId(100L);
            return savedGroup;
        });
        when(groupRepository.findByIdWithCreator(100L)).thenReturn(Optional.of(group));
        when(groupAuthorizationService.getPermissions(GroupRole.LEADER)).thenReturn(List.of());

        groupService.createGroup("Backend Team", null, creator, List.of(), 100);

        verify(groupRepository).save(groupCaptor.capture());
        assertThat(groupCaptor.getValue().getMaxMembers()).isEqualTo(100);
    }

    /**
     * Create rejects negatives before any group or participant row is saved.
     */
    @Test
    void createGroup_negativeMaxMembers_isRejectedBeforePersistence() {
        assertThatThrownBy(() -> groupService.createGroup("Backend Team", null, creator, List.of(), -1))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("maxMembers must not be negative");

        verify(groupRepository, never()).save(any());
        verify(groupParticipantRepository, never()).save(any());
    }

    /**
     * Create rejects when distinct creator plus invitees exceed a positive limit, with no partial inserts.
     */
    @Test
    void createGroup_initialMembershipAboveLimit_isRejectedWithoutInserts() {
        assertThatThrownBy(() -> groupService.createGroup("Backend Team", null, creator, List.of(2L, 3L), 2))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Initial membership exceeds the group member limit");

        verify(groupRepository, never()).save(any());
        verify(groupParticipantRepository, never()).save(any());
        verify(userRepository, never()).findById(any());
    }

    /**
     * Create succeeds when distinct initial membership equals the positive limit.
     */
    @Test
    void createGroup_initialMembershipEqualToLimit_succeeds() {
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> {
            Group savedGroup = invocation.getArgument(0);
            savedGroup.setId(100L);
            return savedGroup;
        });
        when(groupRepository.findByIdWithCreator(100L)).thenReturn(Optional.of(group));
        when(groupAuthorizationService.getPermissions(GroupRole.LEADER)).thenReturn(List.of());
        when(userRepository.findById(2L)).thenReturn(Optional.of(member));
        when(groupParticipantRepository.existsByGroupAndUser(any(Group.class), any(User.class))).thenReturn(false);

        groupService.createGroup("Backend Team", null, creator, List.of(2L), 2);

        verify(groupRepository).save(any(Group.class));
        verify(groupParticipantRepository, times(2)).save(any(GroupParticipant.class));
    }

    /**
     * Name/description PATCH still works when maxMembers is omitted.
     */
    @Test
    void updateGroupDetails_updatesNameAndClearsBlankDescription() {
        when(groupAuthorizationService.requireActivePermission(member, 100L, GroupPermission.MANAGE_GROUP_DETAILS))
                .thenReturn(group);
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(groupRepository.findByIdWithCreator(100L)).thenReturn(Optional.of(group));

        GroupResponse response = groupService.updateGroupDetails(member, 100L, "  API Team  ", "   ", null, false);

        assertThat(group.getName()).isEqualTo("API Team");
        assertThat(group.getDescription()).isNull();
        assertThat(response.getName()).isEqualTo("API Team");
        assertThat(response.getDescription()).isNull();
        assertThat(response.getCreatedByUsername()).isEqualTo("alice");
        assertThat(response.getCurrentUserRole()).isNull();
        assertThat(response.getCurrentUserPermissions()).isEmpty();
        assertThat(response.getUnreadCount()).isZero();
        verify(systemMessageService).recordGroupEvent(group, member, member, SystemEventType.GROUP_NAME_UPDATED);
        verify(systemMessageService).recordGroupEvent(group, member, member, SystemEventType.GROUP_DESCRIPTION_UPDATED);
        verify(groupRepository).findByIdWithCreator(100L);
    }

    /**
     * Users without {@code MANAGE_GROUP_DETAILS} cannot update group details or maxMembers.
     */
    @Test
    void updateGroupDetails_noPermission_throwsForbiddenException() {
        when(groupAuthorizationService.requireActivePermission(member, 100L, GroupPermission.MANAGE_GROUP_DETAILS))
                .thenThrow(new ForbiddenException("You do not have permission to manage group details"));

        assertThatThrownBy(() -> groupService.updateGroupDetails(
                        member, 100L, "new name", "new description", null, false))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You do not have permission to manage group details");

        verify(groupRepository, never()).save(any());
        verify(systemMessageService, never()).recordGroupEvent(any(), any(), any(), any());
    }

    /**
     * Omitted maxMembers on PATCH leaves the stored cap unchanged.
     */
    @Test
    void updateGroupDetails_omittedMaxMembers_leavesLimitUnchanged() {
        group.setMaxMembers(100);
        when(groupAuthorizationService.requireActivePermission(member, 100L, GroupPermission.MANAGE_GROUP_DETAILS))
                .thenReturn(group);
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(groupRepository.findByIdWithCreator(100L)).thenReturn(Optional.of(group));

        groupService.updateGroupDetails(member, 100L, "API Team", null, null, false);

        assertThat(group.getMaxMembers()).isEqualTo(100);
        verify(systemMessageService).recordGroupEvent(group, member, member, SystemEventType.GROUP_NAME_UPDATED);
    }

    /**
     * Explicit JSON null stores unlimited.
     */
    @Test
    void updateGroupDetails_explicitNullMaxMembers_setsUnlimited() {
        group.setMaxMembers(100);
        when(groupAuthorizationService.requireActivePermission(member, 100L, GroupPermission.MANAGE_GROUP_DETAILS))
                .thenReturn(group);
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(groupRepository.findByIdWithCreator(100L)).thenReturn(Optional.of(group));

        GroupResponse response = groupService.updateGroupDetails(member, 100L, null, null, null, true);

        assertThat(group.getMaxMembers()).isNull();
        assertThat(response.getMaxMembers()).isNull();
        verify(systemMessageService, never()).recordGroupEvent(any(), any(), any(), any());
    }

    /**
     * Explicit 0 stores unlimited.
     */
    @Test
    void updateGroupDetails_zeroMaxMembers_setsUnlimited() {
        group.setMaxMembers(100);
        when(groupAuthorizationService.requireActivePermission(member, 100L, GroupPermission.MANAGE_GROUP_DETAILS))
                .thenReturn(group);
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(groupRepository.findByIdWithCreator(100L)).thenReturn(Optional.of(group));

        groupService.updateGroupDetails(member, 100L, null, null, 0, true);

        assertThat(group.getMaxMembers()).isZero();
        verify(systemMessageService, never()).recordGroupEvent(any(), any(), any(), any());
    }

    /**
     * A positive PATCH value sets the cap.
     */
    @Test
    void updateGroupDetails_positiveMaxMembers_setsLimit() {
        when(groupAuthorizationService.requireActivePermission(member, 100L, GroupPermission.MANAGE_GROUP_DETAILS))
                .thenReturn(group);
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(groupRepository.findByIdWithCreator(100L)).thenReturn(Optional.of(group));

        groupService.updateGroupDetails(member, 100L, null, null, 50, true);

        assertThat(group.getMaxMembers()).isEqualTo(50);
    }

    /**
     * Lowering maxMembers below current membership is allowed and does not remove members.
     */
    @Test
    void updateGroupDetails_loweringBelowCurrentCount_keepsMembers() {
        group.setMaxMembers(100);
        when(groupAuthorizationService.requireActivePermission(member, 100L, GroupPermission.MANAGE_GROUP_DETAILS))
                .thenReturn(group);
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(groupRepository.findByIdWithCreator(100L)).thenReturn(Optional.of(group));

        groupService.updateGroupDetails(member, 100L, null, null, 1, true);

        assertThat(group.getMaxMembers()).isEqualTo(1);
        verify(groupParticipantRepository, never()).save(any());
        verify(systemMessageService, never()).recordGroupEvent(any(), any(), any(), any());
    }

    /**
     * Service still rejects negatives even if Bean Validation is skipped.
     */
    @Test
    void updateGroupDetails_negativeMaxMembers_isRejectedBeforePersistence() {
        when(groupAuthorizationService.requireActivePermission(member, 100L, GroupPermission.MANAGE_GROUP_DETAILS))
                .thenReturn(group);

        assertThatThrownBy(() -> groupService.updateGroupDetails(member, 100L, null, null, -3, true))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("maxMembers must not be negative");

        verify(groupRepository, never()).save(any());
    }
}
