package com.hello.chatapp.repository;

import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageType;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.support.IsolatedH2DataSourceSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@DirtiesContext
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.show-sql=true",
        "logging.level.org.hibernate.SQL=DEBUG"
})
class MessageRepositoryFindWithMediaSqlTest {

    @DynamicPropertySource
    static void registerIsolatedDataSource(DynamicPropertyRegistry registry) {
        IsolatedH2DataSourceSupport.register(registry, MessageRepositoryFindWithMediaSqlTest.class);
    }

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void findWithMediaById_loadsMessage() {
        User user = userRepository.saveAndFlush(new User("u", "secret", "User"));
        Message message = new Message(user, "hello");
        message.setMessageType(MessageType.TEXT);
        message = messageRepository.saveAndFlush(message);

        assertThat(messageRepository.findWithMediaById(message.getId())).isPresent();
    }
}
