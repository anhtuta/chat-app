package com.hello.chatapp;

import com.hello.chatapp.support.IsolatedH2DataSourceSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ChatAppApplicationTests {

	@DynamicPropertySource
	static void registerIsolatedDataSource(DynamicPropertyRegistry registry) {
		IsolatedH2DataSourceSupport.register(registry, ChatAppApplicationTests.class);
	}

	@Test
	void contextLoads() {
	}

}
