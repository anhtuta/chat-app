package com.hello.seed;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

public class GroupSeeder {

    public static void main(String[] args) {
        System.out.println("Starting to seed " + SeedConstants.GROUP_COUNT + " groups with all " + SeedConstants.USER_COUNT +
                " users as participants...");

        try (Connection connection =
                DriverManager.getConnection(SeedConstants.DB_URL, SeedConstants.DB_USER, SeedConstants.DB_PASSWORD)) {
            seedGroups(connection);
            System.out.println("Successfully seeded " + SeedConstants.GROUP_COUNT + " groups (Group 1 to Group " +
                    SeedConstants.GROUP_COUNT + ")");
        } catch (SQLException e) {
            System.err.println("Error seeding groups: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void seedGroups(Connection connection) throws SQLException {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());

        // First, verify that user with id=1 exists (we'll use it as created_by)
        String checkUserSql = "SELECT id FROM users WHERE id = 1";
        try (PreparedStatement checkUserStmt = connection.prepareStatement(checkUserSql);
                ResultSet rs = checkUserStmt.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("User with id=1 does not exist. Please run UserSeeder first.");
            }
        }

        // Insert groups
        String checkGroupSql = "SELECT COUNT(*) FROM groups WHERE name = ?";
        String insertGroupSql = "INSERT INTO groups (name, created_by, created_at) VALUES (?, ?, ?) RETURNING id";

        int groupsInserted = 0;
        int groupsSkipped = 0;
        int totalParticipantsInserted = 0;

        try (PreparedStatement checkGroupStmt = connection.prepareStatement(checkGroupSql);
                PreparedStatement insertGroupStmt = connection.prepareStatement(insertGroupSql)) {

            for (int i = 1; i <= SeedConstants.GROUP_COUNT; i++) {
                String groupName = "Group " + i;

                // Check if group already exists
                checkGroupStmt.setString(1, groupName);
                try (ResultSet rs = checkGroupStmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        System.out.println("Group '" + groupName + "' already exists, skipping...");
                        groupsSkipped++;
                        continue;
                    }
                }

                // Insert new group
                insertGroupStmt.setString(1, groupName);
                insertGroupStmt.setLong(2, 1); // created_by = user id 1
                insertGroupStmt.setTimestamp(3, now);

                try (ResultSet rs = insertGroupStmt.executeQuery()) {
                    if (rs.next()) {
                        long groupId = rs.getLong(1);
                        System.out.println("Created group: " + groupName + " (id=" + groupId + ")");

                        // Insert all users as participants for this group
                        int participantsInserted = insertGroupParticipants(connection, groupId, now);
                        totalParticipantsInserted += participantsInserted;
                        groupsInserted++;

                        if (i % 10 == 0) {
                            System.out.println("Progress: " + i + " groups processed...");
                        }
                    }
                }
            }
        }

        System.out.println("\nSummary:");
        System.out.println("Groups inserted: " + groupsInserted);
        System.out.println("Groups skipped: " + groupsSkipped);
        System.out.println("Total participants inserted: " + totalParticipantsInserted);
    }

    private static int insertGroupParticipants(Connection connection, long groupId, Timestamp joinedAt) throws SQLException {
        String checkParticipantSql = "SELECT COUNT(*) FROM group_participants WHERE group_id = ? AND user_id = ?";
        String insertParticipantSql = "INSERT INTO group_participants (group_id, user_id, joined_at) VALUES (?, ?, ?)";

        int insertedCount = 0;
        int skippedCount = 0;

        try (PreparedStatement checkStmt = connection.prepareStatement(checkParticipantSql);
                PreparedStatement insertStmt = connection.prepareStatement(insertParticipantSql)) {

            for (int userId = 1; userId <= SeedConstants.USER_COUNT; userId++) {
                // Check if participant already exists
                checkStmt.setLong(1, groupId);
                checkStmt.setLong(2, userId);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        skippedCount++;
                        continue;
                    }
                }

                // Insert participant
                insertStmt.setLong(1, groupId);
                insertStmt.setLong(2, userId);
                insertStmt.setTimestamp(3, joinedAt);
                insertStmt.addBatch();
                insertedCount++;

                // Execute batch every 500 records for better performance
                if (userId % 500 == 0) {
                    insertStmt.executeBatch();
                }
            }

            // Execute remaining batch
            if (insertedCount % 500 != 0) {
                insertStmt.executeBatch();
            }
        }

        if (skippedCount > 0) {
            System.out.println("  Skipped " + skippedCount + " existing participants for group id=" + groupId);
        }

        return insertedCount;
    }
}

