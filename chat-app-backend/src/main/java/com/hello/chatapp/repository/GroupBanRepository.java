package com.hello.chatapp.repository;

import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupBan;
import com.hello.chatapp.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface GroupBanRepository extends JpaRepository<GroupBan, Long> {
    boolean existsByGroupAndUser(Group group, User user);

    boolean existsByGroup_IdAndUser_Id(Long groupId, Long userId);

    @Query("""
            SELECT gb FROM GroupBan gb
            WHERE gb.group.id = :groupId AND gb.user.id = :userId
            """)
    java.util.Optional<GroupBan> findByGroupIdAndUserId(@Param("groupId") Long groupId, @Param("userId") Long userId);
}
