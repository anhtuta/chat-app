package com.hello.chatapp.service;

import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RealtimeMessageDeliveryServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private CustomRabbitMQBrokerHandler rabbitMQBrokerHandler;

    private RealtimeMessageDeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        deliveryService = new RealtimeMessageDeliveryService(messagingTemplate, rabbitMQBrokerHandler);
    }

    @Test
    void publish_sendsToLocalBrokerThenRabbitMq() {
        Object payload = new Object();

        deliveryService.publish("/topic/group.1", payload);

        verify(messagingTemplate).convertAndSend("/topic/group.1", payload);
        verify(rabbitMQBrokerHandler).publishToRabbitMQ("/topic/group.1", payload);
    }

    @Test
    void publishToGroup_usesGroupDestination() {
        Object payload = new Object();

        deliveryService.publishToGroup(42L, payload);

        verify(messagingTemplate).convertAndSend("/topic/group.42", payload);
        verify(rabbitMQBrokerHandler).publishToRabbitMQ("/topic/group.42", payload);
    }

    @Test
    void publishToPublic_usesPublicDestination() {
        Object payload = new Object();

        deliveryService.publishToPublic(payload);

        verify(messagingTemplate).convertAndSend("/topic/public", payload);
        verify(rabbitMQBrokerHandler).publishToRabbitMQ("/topic/public", payload);
    }
}
