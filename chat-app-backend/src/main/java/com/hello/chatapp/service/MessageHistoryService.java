package com.hello.chatapp.service;

import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.dto.MessageResponseMapper;
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
    private final MessageResponseMapper messageResponseMapper;

    public MessageHistoryService(MessageRepository messageRepository, MessageResponseMapper messageResponseMapper) {
        this.messageRepository = messageRepository;
        this.messageResponseMapper = messageResponseMapper;
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> getPublicMessages() {
        // TODO Add pagination
        List<Long> messageIds = messageRepository.findAllPublicMessageIds();
        return toResponsesWithMedia(messageIds, false);
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> getGroupMessages(
            Group group,
            LocalDateTime beforeTimestamp,
            Long beforeId,
            int size) {
        boolean hasCursor = beforeTimestamp != null && beforeId != null;
        List<Long> messageIds = hasCursor
                ? messageRepository.findGroupMessageIdsBeforeCursor(group, beforeTimestamp, beforeId, PageRequest.of(0, size))
                : messageRepository.findLatestGroupMessageIds(group, PageRequest.of(0, size));
        return toResponsesWithMedia(messageIds, true);
    }

    private List<MessageResponse> toResponsesWithMedia(List<Long> idsInQueryOrder, boolean ascendingOutput) {
        if (idsInQueryOrder.isEmpty()) {
            return Collections.emptyList();
        }

        // Why don't we use a single query (with JOIN FETCH) to fetch all messages with media?
        // Vì 1 message có thể có nhiều media attachments, do đó query đầu tiên chỉ lấy message để phân trang trước,
        // sau đó từ list message đó mới lấy các media của từng message đc!
        Map<Long, Message> messagesById = new LinkedHashMap<>();
        for (Message hydratedMessage : messageRepository.findWithMediaByIdIn(idsInQueryOrder)) {
            messagesById.put(hydratedMessage.getId(), hydratedMessage);
        }

        List<MessageResponse> responses = idsInQueryOrder.stream()
                .map(messagesById::get)
                .filter(java.util.Objects::nonNull)
                .map(messageResponseMapper::toResponse)
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
