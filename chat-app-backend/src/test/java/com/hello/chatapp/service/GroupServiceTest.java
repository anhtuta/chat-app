package com.hello.chatapp.service;

import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.dto.GroupResponse;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.User;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

        GroupResponse response = groupService.createGroup("  Backend Team  ", "  Core backend work  ", creator, List.of());

        assertThat(response.getName()).isEqualTo("Backend Team");
        assertThat(response.getDescription()).isEqualTo("Core backend work");
        assertThat(response.getCurrentUserRole()).isEqualTo(GroupRole.LEADER);
        assertThat(response.getCurrentUserPermissions())
                .containsExactly(GroupPermission.READ_MESSAGES, GroupPermission.MANAGE_GROUP_DETAILS);
    }

    @Test
    void createGroup_savesCreatorAsLeader() {
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> {
            Group savedGroup = invocation.getArgument(0);
            savedGroup.setId(100L);
            return savedGroup;
        });
        when(groupRepository.findByIdWithCreator(100L)).thenReturn(Optional.of(group));
        when(groupAuthorizationService.getPermissions(GroupRole.LEADER)).thenReturn(List.of());

        groupService.createGroup("Backend Team", null, creator, List.of());

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

    @Test
    void updateGroupDetails_updatesNameAndClearsBlankDescription() {
        when(groupAuthorizationService.requireActivePermission(member, 100L, GroupPermission.MANAGE_GROUP_DETAILS))
                .thenReturn(group);
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(groupRepository.findByIdWithCreator(100L)).thenReturn(Optional.of(group));

        GroupResponse response = groupService.updateGroupDetails(member, 100L, "  API Team  ", "   ");

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

    @Test
    void updateGroupDetails_noPermission_throwsForbiddenException() {
        when(groupAuthorizationService.requireActivePermission(member, 100L, GroupPermission.MANAGE_GROUP_DETAILS))
                .thenThrow(new ForbiddenException("You do not have permission to manage group details"));

        assertThatThrownBy(() -> groupService.updateGroupDetails(member, 100L, "new name", "new description"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You do not have permission to manage group details");

        verify(groupRepository, never()).save(any());
        verify(systemMessageService, never()).recordGroupEvent(any(), any(), any(), any());
    }
}
