package com.hello.chatapp.support;

import org.springframework.test.context.DynamicPropertyRegistry;

public final class IsolatedH2DataSourceSupport {

    private static final String H2_OPTIONS = ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false;LOCK_TIMEOUT=15000";

    private IsolatedH2DataSourceSupport() {
    }

    public static void register(DynamicPropertyRegistry registry, Class<?> testClass) {
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:" + testClass.getSimpleName() + H2_OPTIONS);
    }
}
