package com.hello.chatapp.repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Bulk insert of {@code group_participants} rows as a single multi-value {@code INSERT}.
 */
public interface GroupParticipantBulkInsertRepository {

    /**
     * Inserts each user as {@code MEMBER} in one SQL statement.
     *
     * @return number of rows inserted
     */
    int insertMembers(Long groupId, List<Long> userIds, LocalDateTime joinedAt);
}
