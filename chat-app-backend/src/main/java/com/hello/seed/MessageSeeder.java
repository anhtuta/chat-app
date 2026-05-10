package com.hello.seed;

import com.github.javafaker.Faker;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Random;

public class MessageSeeder {

    private static final int GROUPS_TO_SEED = 10;
    private static final int MESSAGES_PER_GROUP = 1000;
    private static final Random random = new Random();
    private static final Faker faker = new Faker();

    public static void main(String[] args) {
        System.out.println("Starting to seed " + MESSAGES_PER_GROUP + " messages for each of the first " + GROUPS_TO_SEED + " groups...");

        try (Connection connection = DriverManager.getConnection(SeedConstants.DB_URL, SeedConstants.DB_USER, SeedConstants.DB_PASSWORD)) {
            seedMessages(connection);
            System.out.println("Successfully seeded messages for groups 1 to " + GROUPS_TO_SEED);
        } catch (SQLException e) {
            System.err.println("Error seeding messages: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void seedMessages(Connection connection) throws SQLException {
        String checkGroupSql = "SELECT id FROM groups WHERE id = ?";
        String insertMessageSql = "INSERT INTO messages (user_id, group_id, content, timestamp) VALUES (?, ?, ?, ?)";

        int totalMessagesInserted = 0;

        try (PreparedStatement checkGroupStmt = connection.prepareStatement(checkGroupSql);
             PreparedStatement insertMessageStmt = connection.prepareStatement(insertMessageSql)) {

            for (int groupId = 1; groupId <= GROUPS_TO_SEED; groupId++) {
                // Verify group exists
                checkGroupStmt.setLong(1, groupId);
                try (ResultSet rs = checkGroupStmt.executeQuery()) {
                    if (!rs.next()) {
                        System.out.println("Group with id=" + groupId + " does not exist, skipping...");
                        continue;
                    }
                }

                System.out.println("Seeding messages for Group " + groupId + "...");
                int messagesInserted = insertMessagesForGroup(connection, groupId, insertMessageStmt);
                totalMessagesInserted += messagesInserted;
                System.out.println("  Inserted " + messagesInserted + " messages for Group " + groupId);
            }
        }

        System.out.println("\nSummary:");
        System.out.println("Total messages inserted: " + totalMessagesInserted);
    }

    private static int insertMessagesForGroup(Connection connection, long groupId, PreparedStatement insertStmt) throws SQLException {
        int insertedCount = 0;
        LocalDateTime baseTime = LocalDateTime.now().minusDays(30); // Messages from last 30 days

        for (int i = 0; i < MESSAGES_PER_GROUP; i++) {
            // Random user ID from 1 to 1000
            long userId = random.nextInt(SeedConstants.USER_COUNT) + 1;

            // Generate meaningful message using Faker
            String content = generateMessage();

            // Random timestamp within last 30 days
            int daysAgo = random.nextInt(30);
            int hoursAgo = random.nextInt(24);
            int minutesAgo = random.nextInt(60);
            LocalDateTime messageTime = baseTime.plusDays(daysAgo).plusHours(hoursAgo).plusMinutes(minutesAgo);
            Timestamp timestamp = Timestamp.valueOf(messageTime);

            // Insert message
            insertStmt.setLong(1, userId);
            insertStmt.setLong(2, groupId);
            insertStmt.setString(3, content);
            insertStmt.setTimestamp(4, timestamp);
            insertStmt.addBatch();
            insertedCount++;

            // Execute batch every 200 records for better performance
            if (i % 200 == 0 && i > 0) {
                insertStmt.executeBatch();
            }
        }

        // Execute remaining batch
        if (insertedCount % 200 != 0) {
            insertStmt.executeBatch();
        }

        return insertedCount;
    }

    private static String generateMessage() {
        // Mix different types of messages for variety
        int messageType = random.nextInt(10);

        return switch (messageType) {
            case 0 -> faker.lorem().sentence(random.nextInt(5) + 3);
            case 1 -> faker.harryPotter().quote();
            case 2 -> faker.chuckNorris().fact();
            case 3 -> faker.howIMetYourMother().quote();
            case 4 -> faker.friends().quote();
            case 5 -> faker.gameOfThrones().quote();
            case 6 -> faker.rickAndMorty().quote();
            case 7 -> faker.witcher().quote();
            case 8 -> faker.backToTheFuture().quote();
            default -> faker.lorem().paragraph(random.nextInt(2) + 1);
        };
    }
}

