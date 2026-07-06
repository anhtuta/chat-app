package com.hello.chatapp.repository;

import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupBan;
import com.hello.chatapp.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GroupBanRepository extends JpaRepository<GroupBan, Long> {
    boolean existsByGroupAndUser(Group group, User user);
    boolean existsByGroup_IdAndUser_Id(Long groupId, Long userId);
}
