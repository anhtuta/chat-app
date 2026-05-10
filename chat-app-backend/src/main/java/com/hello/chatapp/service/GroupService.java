package com.hello.chatapp.service;

import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupParticipantRepository groupParticipantRepository;
    private final UserRepository userRepository;

    public GroupService(GroupRepository groupRepository,
            GroupParticipantRepository groupParticipantRepository,
            UserRepository userRepository) {
        this.groupRepository = groupRepository;
        this.groupParticipantRepository = groupParticipantRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Group createGroup(String name, User creator, List<Long> participantIds) {
        // Create the group
        Group group = new Group(name, creator);
        group = groupRepository.save(group);

        // Add creator as participant
        GroupParticipant creatorParticipant = new GroupParticipant(group, creator);
        groupParticipantRepository.save(creatorParticipant);

        // Add other participants
        for (Long userId : participantIds) {
            // Skip if trying to add creator again
            if (userId.equals(creator.getId())) {
                continue;
            }

            User participant = userRepository.findById(userId)
                    .orElseThrow(() -> new NotFoundException("User with id " + userId + " not found"));

            // Check if already a participant
            if (!groupParticipantRepository.existsByGroupAndUser(group, participant)) {
                GroupParticipant groupParticipant = new GroupParticipant(group, participant);
                groupParticipantRepository.save(groupParticipant);
            }
        }

        // Fetch group with creator to avoid LazyInitializationException
        return groupRepository.findByIdWithCreator(group.getId()).orElse(group);
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public List<Group> getGroupsByUser(User user) {
        List<GroupParticipant> participants = groupParticipantRepository.findByUser(user);
        return participants.stream()
                .map(GroupParticipant::getGroup)
                .collect(Collectors.toList());
    }
}
