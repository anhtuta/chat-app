package com.hello.chatapp.repository;

import com.hello.chatapp.entity.GroupJoinLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupJoinLinkRepository extends JpaRepository<GroupJoinLink, Long> {

    @Query("""
            SELECT gjl FROM GroupJoinLink gjl
            JOIN FETCH gjl.group g
            JOIN FETCH g.createdBy
            WHERE gjl.tokenHash = :tokenHash
            """)
    Optional<GroupJoinLink> findByTokenHashWithGroup(@Param("tokenHash") String tokenHash);

    @Query("""
            SELECT gjl FROM GroupJoinLink gjl
            JOIN FETCH gjl.group
            WHERE gjl.id = :joinLinkId AND gjl.group.id = :groupId
            """)
    Optional<GroupJoinLink> findByIdAndGroupId(
            @Param("joinLinkId") Long joinLinkId,
            @Param("groupId") Long groupId);

    @Query("""
            SELECT gjl FROM GroupJoinLink gjl
            JOIN FETCH gjl.createdBy
            WHERE gjl.group.id = :groupId
            ORDER BY gjl.createdAt DESC, gjl.id DESC
            """)
    List<GroupJoinLink> findByGroupIdWithCreator(@Param("groupId") Long groupId);
}
