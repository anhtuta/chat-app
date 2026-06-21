package com.hello.chatapp.service;

import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.repository.MessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MessageHistoryService {

    private final MessageRepository messageRepository;

    public MessageHistoryService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> getPublicMessages() {
        // TODO Add pagination
        List<Message> baseMessages = messageRepository.findAllPublicMessages();
        return toResponsesWithMedia(baseMessages, false);
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> getGroupMessages(
            Group group,
            LocalDateTime beforeTimestamp,
            Long beforeId,
            int size) {
        boolean hasCursor = beforeTimestamp != null && beforeId != null;
        List<Message> baseMessages = hasCursor
                ? messageRepository.findGroupMessagesBeforeCursor(group, beforeTimestamp, beforeId, PageRequest.of(0, size))
                : messageRepository.findLatestGroupMessages(group, PageRequest.of(0, size));
        return toResponsesWithMedia(baseMessages, true);
    }

    private List<MessageResponse> toResponsesWithMedia(List<Message> baseMessages, boolean ascendingOutput) {
        if (baseMessages.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> idsInQueryOrder = baseMessages.stream()
                .map(Message::getId)
                .toList();

        // TODO why don't we use a single query (with JOIN FETCH) to fetch all messages with media?
        Map<Long, Message> messagesById = new LinkedHashMap<>();
        for (Message hydratedMessage : messageRepository.findWithMediaByIdIn(idsInQueryOrder)) {
            messagesById.put(hydratedMessage.getId(), hydratedMessage);
        }

        List<MessageResponse> responses = idsInQueryOrder.stream()
                .map(messagesById::get)
                .filter(java.util.Objects::nonNull)
                .map(MessageResponse::fromMessage)
                .toList();

        // Keep the response in ascending order so UI can prepend older pages safely.
        if (ascendingOutput) {
            java.util.ArrayList<MessageResponse> ascending = new java.util.ArrayList<>(responses);
            Collections.reverse(ascending);
            return ascending;
        }
        return responses;
    }
}
