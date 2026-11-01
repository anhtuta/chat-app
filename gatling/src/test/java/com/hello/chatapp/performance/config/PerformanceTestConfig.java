package com.hello.chatapp.performance.config;

/**
 * Centralized environment-based settings for the first Gatling skeleton.
 *
 * <p>The goal is to keep the initial simulation readable while making the
 * target URL and a few workload values easy to override without editing code.
 */
public final class PerformanceTestConfig {

    private static final String DEFAULT_BASE_URL = "http://localhost:9010";
    private static final int DEFAULT_SMOKE_USERS = 1;
    private static final long DEFAULT_PAUSE_MILLIS = 1000L;

    private PerformanceTestConfig() {
    }

    /**
     * Returns the backend base URL for HTTP simulations.
     */
    public static String baseUrl() {
        return envOrDefault("GATLING_BASE_URL", DEFAULT_BASE_URL);
    }

    /**
     * Returns the number of virtual users for the smoke run.
     */
    public static int smokeUsers() {
        return intEnvOrDefault("GATLING_SMOKE_USERS", DEFAULT_SMOKE_USERS);
    }

    /**
     * Returns the pause duration between smoke-test requests in milliseconds.
     */
    public static long pauseMillis() {
        return longEnvOrDefault("GATLING_PAUSE_MILLIS", DEFAULT_PAUSE_MILLIS);
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    private static int intEnvOrDefault(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

    private static long longEnvOrDefault(String key, long defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(value);
    }
}
