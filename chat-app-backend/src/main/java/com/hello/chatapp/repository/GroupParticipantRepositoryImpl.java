package com.hello.chatapp.repository;

import com.hello.chatapp.constant.GroupRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Native multi-row insert for {@code group_participants}. Hibernate {@code IDENTITY} ids cannot
 * JDBC-batch {@code persist}, so one statement is used instead of N {@code save} calls.
 */
public class GroupParticipantRepositoryImpl implements GroupParticipantBulkInsertRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Builds {@code INSERT ... VALUES (...), (...)} so Hibernate IDENTITY does not force N persist calls.
     */
    @Override
    public int insertMembers(Long groupId, List<Long> userIds, LocalDateTime joinedAt) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder(
                "INSERT INTO group_participants (group_id, user_id, joined_at, role) VALUES ");
        for (int i = 0; i < userIds.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(?, ?, ?, ?)");
        }
        Query query = entityManager.createNativeQuery(sql.toString());
        Timestamp joinedAtTimestamp = Timestamp.valueOf(joinedAt);
        int parameterIndex = 1;
        for (Long userId : userIds) {
            query.setParameter(parameterIndex++, groupId);
            query.setParameter(parameterIndex++, userId);
            query.setParameter(parameterIndex++, joinedAtTimestamp);
            query.setParameter(parameterIndex++, GroupRole.MEMBER.name());
        }
        return query.executeUpdate();
    }
}
