package com.hello.seed;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

public class UserSeeder {

    public static void main(String[] args) {
        System.out.println("Starting to seed " + SeedConstants.USER_COUNT + " users...");

        try (Connection connection =
                DriverManager.getConnection(SeedConstants.DB_URL, SeedConstants.DB_USER, SeedConstants.DB_PASSWORD)) {
            seedUsers(connection);
            System.out.println(
                    "Successfully seeded " + SeedConstants.USER_COUNT + " users (u1 to u" + SeedConstants.USER_COUNT + ")");
        } catch (SQLException e) {
            System.err.println("Error seeding users: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void seedUsers(Connection connection) throws SQLException {
        String checkSql = "SELECT COUNT(*) FROM users WHERE username = ?";
        String insertSql = "INSERT INTO users (username, password, fullname, created_at) VALUES (?, ?, ?, ?)";

        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int insertedCount = 0;
        int skippedCount = 0;

        try (PreparedStatement checkStmt = connection.prepareStatement(checkSql);
                PreparedStatement insertStmt = connection.prepareStatement(insertSql)) {

            for (int i = 1; i <= SeedConstants.USER_COUNT; i++) {
                String username = "u" + i;

                // Check if user already exists
                checkStmt.setString(1, username);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        System.out.println("User " + username + " already exists, skipping...");
                        skippedCount++;
                        continue;
                    }
                }

                // Insert new user
                insertStmt.setString(1, username);
                insertStmt.setString(2, SeedConstants.USER_PASSWORD);
                insertStmt.setString(3, username); // fullname defaults to username
                insertStmt.setTimestamp(4, now);
                insertStmt.addBatch();
                insertedCount++;

                // Execute batch every 100 records for better performance
                if (i % 100 == 0) {
                    insertStmt.executeBatch();
                    System.out.println("Inserted batch: " + i + " users processed...");
                }
            }

            // Execute remaining batch
            if (insertedCount % 100 != 0) {
                insertStmt.executeBatch();
            }

            System.out.println("Inserted: " + insertedCount + " users, Skipped: " + skippedCount + " users");
        }
    }
}

