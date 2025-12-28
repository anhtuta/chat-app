package com.hello.seed;

public class SeedConstants {

    // User seeding constants
    public static final String USER_PASSWORD = "$2a$10$bDDVkus07dWTgkNBPxWczupezjEU7p5YlTbwfGTpTOC6UiSqF1GCW";
    public static final int USER_COUNT = 1000;

    // Group seeding constants
    public static final int GROUP_COUNT = 100;

    // Database connection details
    public static final String DB_URL = "jdbc:postgresql://localhost:5434/chatdb";
    public static final String DB_USER = "postgres";
    public static final String DB_PASSWORD = "5555";

    private SeedConstants() {
        // Utility class - prevent instantiation
    }
}

