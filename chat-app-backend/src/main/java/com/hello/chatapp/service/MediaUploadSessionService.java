package com.hello.chatapp.service;

import com.hello.chatapp.config.MediaStorageProperties;
import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;
import com.hello.chatapp.dto.CompleteMediaAttachmentRequest;
import com.hello.chatapp.dto.CompleteMediaMessageRequest;
import com.hello.chatapp.dto.MultipartPartResponse;
import com.hello.chatapp.dto.PrepareMediaAttachmentRequest;
import com.hello.chatapp.dto.PrepareMediaMessageRequest;
import com.hello.chatapp.dto.PrepareMediaMessageResponse;
import com.hello.chatapp.dto.PreparedMediaAttachmentResponse;
import com.hello.chatapp.dto.RequestMultipartPartUrlsRequest;
import com.hello.chatapp.dto.RequestMultipartPartUrlsResponse;
import com.hello.chatapp.dto.UploadStrategy;
import com.hello.chatapp.entity.ChatScope;
import com.hello.chatapp.entity.MediaScanStatus;
import com.hello.chatapp.entity.MediaStatus;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.MediaUpload;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.entity.MessageType;
import com.hello.chatapp.entity.UploadSessionStatus;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.exception.BadRequestException;
import com.hello.chatapp.exception.ForbiddenException;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.MediaUploadRepository;
import com.hello.chatapp.storage.ObjectStorageProvider;
import com.hello.chatapp.storage.ObjectStorageProviderDescriptor;
import com.hello.chatapp.storage.ObjectStorageProviderRegistry;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class MediaUploadSessionService {

    private final MediaUploadRepository mediaUploadRepository;
    private final GroupRepository groupRepository;
    private final GroupParticipantRepository groupParticipantRepository;
    private final MediaStorageProperties mediaStorageProperties;
    private final ObjectStorageProviderRegistry objectStorageProviderRegistry;
    private final MalwareScanService malwareScanService;
    private final MessageService messageService;
    private final MediaProcessingService mediaProcessingService;
    private final SimpMessagingTemplate messagingTemplate;
    private final CustomRabbitMQBrokerHandler rabbitMQBrokerHandler;

    public MediaUploadSessionService(
            MediaUploadRepository mediaUploadRepository,
            GroupRepository groupRepository,
            GroupParticipantRepository groupParticipantRepository,
            MediaStorageProperties mediaStorageProperties,
            ObjectStorageProviderRegistry objectStorageProviderRegistry,
            MalwareScanService malwareScanService,
            MessageService messageService,
            MediaProcessingService mediaProcessingService,
            SimpMessagingTemplate messagingTemplate,
            CustomRabbitMQBrokerHandler rabbitMQBrokerHandler) {
        this.mediaUploadRepository = mediaUploadRepository;
        this.groupRepository = groupRepository;
        this.groupParticipantRepository = groupParticipantRepository;
        this.mediaStorageProperties = mediaStorageProperties;
        this.objectStorageProviderRegistry = objectStorageProviderRegistry;
        this.malwareScanService = malwareScanService;
        this.messageService = messageService;
        this.mediaProcessingService = mediaProcessingService;
        this.messagingTemplate = messagingTemplate;
        this.rabbitMQBrokerHandler = rabbitMQBrokerHandler;
    }

    @Transactional
    public PrepareMediaMessageResponse prepareUploadSession(User user, PrepareMediaMessageRequest request) {
        Group group = validateScopeAndMembership(user, request.getChatScope(), request.getGroupId());
        validateMessageType(request.getMessageType());
        validateAttachmentCount(request.getMessageType(), request.getAttachments());

        String uploadSessionId = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(mediaStorageProperties.getUploadSessionTtlMinutes());

        ObjectStorageProvider provider = objectStorageProviderRegistry.getActiveProvider();
        ObjectStorageProviderDescriptor descriptor = provider.describe();
        long maxSizeBytes = resolveMaxSizeBytes(request.getMessageType());
        int maxAttachmentCount = resolveMaxAttachmentCount(request.getMessageType());

        List<PreparedMediaAttachmentResponse> attachments = request.getAttachments().stream()
                .map(attachment -> createUploadRecord(
                        user,
                        group,
                        request.getChatScope(),
                        request.getMessageType(),
                        uploadSessionId,
                        expiresAt,
                        descriptor,
                        provider,
                        attachment,
                        maxSizeBytes))
                .toList();

        return PrepareMediaMessageResponse.builder()
                .uploadSessionId(uploadSessionId)
                .messageType(request.getMessageType())
                .chatScope(request.getChatScope())
                .expiresAt(expiresAt)
                .retentionDays(mediaStorageProperties.getRetentionDays())
                .limits(PrepareMediaMessageResponse.Limits.builder()
                        .maxSizeBytes(maxSizeBytes)
                        .maxAttachmentCount(maxAttachmentCount)
                        .build())
                .attachments(attachments)
                .build();
    }

    @Transactional
    public RequestMultipartPartUrlsResponse requestMultipartPartUrls(
            User user,
            String uploadSessionId,
            String attachmentId,
            RequestMultipartPartUrlsRequest request) {
        MediaUpload mediaUpload = mediaUploadRepository.findByUploadSessionIdAndUploadId(uploadSessionId, attachmentId)
                .orElseThrow(() -> new NotFoundException("Upload attachment not found"));

        ensureUploadBelongsToUser(mediaUpload, user);
        if (resolveUploadStrategy(mediaUpload.getRequestedSizeBytes()) != UploadStrategy.MULTIPART) {
            throw new BadRequestException("Attachment does not use multipart upload");
        }

        Set<Integer> uniquePartNumbers = new LinkedHashSet<>(request.getPartNumbers());
        if (uniquePartNumbers.stream().anyMatch(partNumber -> partNumber == null || partNumber <= 0)) {
            throw new BadRequestException("All partNumbers must be positive");
        }

        if (mediaUpload.getMultipartUploadId() == null || mediaUpload.getMultipartUploadId().isBlank()) {
            mediaUpload.setMultipartUploadId(UUID.randomUUID().toString());
        }
        mediaUpload.setStatus(UploadSessionStatus.UPLOAD_IN_PROGRESS);

        ObjectStorageProvider provider = objectStorageProviderRegistry.getActiveProvider();
        List<MultipartPartResponse> parts = uniquePartNumbers.stream()
                .map(partNumber -> MultipartPartResponse.builder()
                        .partNumber(partNumber)
                        .presignedUrl(provider.buildMultipartUploadPartUrl(
                                mediaUpload.getObjectKey(),
                                Objects.requireNonNull(mediaUpload.getMultipartUploadId()),
                                partNumber))
                        .build())
                .toList();

        return RequestMultipartPartUrlsResponse.builder()
                .multipartUploadId(mediaUpload.getMultipartUploadId())
                .parts(parts)
                .build();
    }

    @Transactional
    public MessageResponse completeUploadSession(User user, String uploadSessionId, CompleteMediaMessageRequest request) {
        List<MediaUpload> uploads = mediaUploadRepository.findByUploadSessionIdOrderByIdAsc(uploadSessionId);
        if (uploads.isEmpty()) {
            throw new NotFoundException("Upload session not found");
        }

        uploads.forEach(upload -> ensureUploadBelongsToUser(upload, user));
        MediaUpload firstUpload = uploads.getFirst();
        ensureNotExpired(firstUpload);

        Map<String, CompleteMediaAttachmentRequest> requestByAttachmentId = new HashMap<>();
        for (CompleteMediaAttachmentRequest attachmentRequest : request.getAttachments()) {
            CompleteMediaAttachmentRequest previous =
                    requestByAttachmentId.put(attachmentRequest.getAttachmentId(), attachmentRequest);
            if (previous != null) {
                throw new BadRequestException("Duplicate attachmentId in completion request");
            }
        }

        if (requestByAttachmentId.size() != uploads.size()) {
            throw new BadRequestException("Completion request must include every prepared attachment exactly once");
        }

        for (MediaUpload upload : uploads) {
            CompleteMediaAttachmentRequest attachmentRequest = requestByAttachmentId.get(upload.getUploadId());
            if (attachmentRequest == null) {
                throw new BadRequestException("Missing completion metadata for attachment " + upload.getUploadId());
            }
            validateCompletionRequest(upload, attachmentRequest);
            // TODO: Replace completion-metadata checks with provider-backed object existence verification.
            malwareScanService.assertClean(upload);
            upload.setStatus(UploadSessionStatus.UPLOAD_COMPLETED);
        }

        Message message = persistFinalMessage(user, uploads);
        uploads.forEach(upload -> upload.setStatus(UploadSessionStatus.UPLOAD_SESSION_COMPLETED));

        MessageResponse response = Objects.requireNonNull(MessageResponse.fromMessage(message));
        publishFinalMessage(response, message);
        enqueueAsyncProcessingIfNeeded(message);
        return response;
    }

    private PreparedMediaAttachmentResponse createUploadRecord(
            User user,
            Group group,
            ChatScope chatScope,
            MessageType messageType,
            String uploadSessionId,
            LocalDateTime expiresAt,
            ObjectStorageProviderDescriptor descriptor,
            ObjectStorageProvider provider,
            PrepareMediaAttachmentRequest attachment,
            long maxSizeBytes) {
        validateAttachmentSize(attachment, maxSizeBytes);

        String uploadId = UUID.randomUUID().toString();
        String objectKey = buildObjectKey(user, messageType, attachment.getFilename());
        UploadStrategy uploadStrategy = resolveUploadStrategy(attachment.getSizeBytes());

        MediaUpload mediaUpload = new MediaUpload();
        mediaUpload.setUploadId(uploadId);
        mediaUpload.setUser(user);
        mediaUpload.setChatScope(chatScope);
        mediaUpload.setGroup(group);
        mediaUpload.setUploadSessionId(uploadSessionId);
        mediaUpload.setRequestedMessageType(messageType);
        mediaUpload.setRequestedFilename(attachment.getFilename());
        mediaUpload.setRequestedSizeBytes(attachment.getSizeBytes());
        mediaUpload.setRequestedMimeType(attachment.getMimeType());
        mediaUpload.setStorageProvider(descriptor.type());
        mediaUpload.setBucket(descriptor.bucket());
        mediaUpload.setObjectKey(objectKey);
        mediaUpload.setStatus(UploadSessionStatus.UPLOAD_INITIATED);
        mediaUpload.setExpiresAt(expiresAt);
        mediaUploadRepository.save(mediaUpload);

        return PreparedMediaAttachmentResponse.builder()
                .attachmentId(uploadId)
                .objectKey(objectKey)
                .uploadStrategy(uploadStrategy)
                .presignedUrl(uploadStrategy == UploadStrategy.SINGLE_PART ? provider.buildUploadUrl(objectKey) : null)
                .multipartUploadId(mediaUpload.getMultipartUploadId())
                .recommendedPartSize(uploadStrategy == UploadStrategy.MULTIPART
                        ? mediaStorageProperties.getMultipartThresholdBytes()
                        : null)
                .completeBy(expiresAt)
                .build();
    }

    private Group validateScopeAndMembership(User user, ChatScope chatScope, Long groupId) {
        if (chatScope == ChatScope.PUBLIC) {
            if (groupId != null) {
                throw new BadRequestException("groupId must be null for PUBLIC chat scope");
            }
            return null;
        }

        if (groupId == null) {
            throw new BadRequestException("groupId is required for GROUP chat scope");
        }

        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group with id " + groupId + " not found"));
        if (!groupParticipantRepository.existsByGroupAndUser(group, user)) {
            throw new ForbiddenException("You are not a member of this group");
        }
        return group;
    }

    private void validateMessageType(MessageType messageType) {
        if (messageType == MessageType.TEXT || messageType == MessageType.SYSTEM) {
            throw new BadRequestException("Upload sessions support only media message types");
        }
    }

    private void validateAttachmentCount(MessageType messageType, List<PrepareMediaAttachmentRequest> attachments) {
        int count = attachments.size();
        if (messageType == MessageType.IMAGE) {
            if (count < 1 || count > mediaStorageProperties.getMaxImageCount()) {
                throw new BadRequestException("Image messages must contain between 1 and " +
                        mediaStorageProperties.getMaxImageCount() + " attachments");
            }
            return;
        }

        if (count != 1) {
            throw new BadRequestException(messageType + " messages must contain exactly one attachment");
        }
    }

    private void validateAttachmentSize(PrepareMediaAttachmentRequest attachment, long maxSizeBytes) {
        if (attachment.getSizeBytes() == null || attachment.getSizeBytes() <= 0) {
            throw new BadRequestException("Attachment sizeBytes must be positive");
        }
        if (attachment.getSizeBytes() > maxSizeBytes) {
            throw new BadRequestException("Attachment exceeds max allowed size for this media type");
        }
    }

    private void ensureUploadBelongsToUser(MediaUpload mediaUpload, User user) {
        Long uploadUserId = Objects.requireNonNull(mediaUpload.getUser().getId());
        Long currentUserId = Objects.requireNonNull(user.getId());
        if (!uploadUserId.equals(currentUserId)) {
            throw new ForbiddenException("Upload does not belong to the current user");
        }
    }

    private void ensureNotExpired(MediaUpload mediaUpload) {
        if (mediaUpload.getExpiresAt() != null && mediaUpload.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("Upload session has expired");
        }
    }

    private void validateCompletionRequest(MediaUpload upload, CompleteMediaAttachmentRequest request) {
        UploadStrategy uploadStrategy = resolveUploadStrategy(upload.getRequestedSizeBytes());
        if (uploadStrategy == UploadStrategy.MULTIPART) {
            if (request.getParts() == null || request.getParts().isEmpty()) {
                throw new BadRequestException("Multipart attachment requires completed parts metadata");
            }
            if (upload.getMultipartUploadId() == null || upload.getMultipartUploadId().isBlank()) {
                throw new BadRequestException("Multipart upload was not initialized for attachment " + upload.getUploadId());
            }
            return;
        }

        if (request.getEtag() == null || request.getEtag().isBlank()) {
            throw new BadRequestException("Single-part attachment requires etag");
        }
    }

    private Message persistFinalMessage(User user, List<MediaUpload> uploads) {
        MediaUpload firstUpload = uploads.getFirst();
        MessageType messageType = firstUpload.getRequestedMessageType();
        Map<Long, Integer> attachmentOrderByUploadId = new HashMap<>();
        for (int index = 0; index < uploads.size(); index++) {
            attachmentOrderByUploadId.put(uploads.get(index).getId(), index);
        }

        List<MessageMedia> attachments = uploads.stream()
                .map(upload -> toMessageMedia(upload, attachmentOrderByUploadId))
                .toList();

        return firstUpload.getChatScope() == ChatScope.GROUP
                ? messageService.saveGroupMediaMessage(
                        Objects.requireNonNull(firstUpload.getGroup()),
                        user,
                        messageType,
                        attachments)
                : messageService.savePublicMediaMessage(user, messageType, attachments);
    }

    private MessageMedia toMessageMedia(MediaUpload upload, Map<Long, Integer> attachmentOrderByUploadId) {
        MessageMedia media = new MessageMedia();
        media.setAttachmentOrder(attachmentOrderByUploadId.getOrDefault(upload.getId(), 0));
        media.setStorageProvider(upload.getStorageProvider());
        media.setBucket(upload.getBucket());
        media.setObjectKey(upload.getObjectKey());
        media.setOriginalFilename(upload.getRequestedFilename());
        media.setDeclaredMimeType(upload.getRequestedMimeType());
        media.setSizeBytes(upload.getRequestedSizeBytes());
        media.setScanStatus(MediaScanStatus.SCAN_PASSED);
        media.setStatus(resolveInitialMediaStatus(upload.getRequestedMessageType()));
        return media;
    }

    private MediaStatus resolveInitialMediaStatus(MessageType messageType) {
        return switch (messageType) {
            case IMAGE, VIDEO -> MediaStatus.PROCESSING_PENDING;
            case AUDIO, FILE -> MediaStatus.MEDIA_READY;
            default -> MediaStatus.MEDIA_READY;
        };
    }

    private void publishFinalMessage(MessageResponse response, Message message) {
        String destination = message.getGroup() == null
                ? "/topic/public"
                : "/topic/group." + Objects.requireNonNull(message.getGroup().getId());
        MessageResponse nonNullResponse = Objects.requireNonNull(response);
        messagingTemplate.convertAndSend(destination, nonNullResponse);
        rabbitMQBrokerHandler.publishToRabbitMQ(destination, nonNullResponse);
    }

    private void enqueueAsyncProcessingIfNeeded(Message message) {
        MessageType messageType = message.getMessageType();
        if (messageType == MessageType.IMAGE || messageType == MessageType.VIDEO) {
            mediaProcessingService.enqueueProcessing(Objects.requireNonNull(message.getId()));
        }
    }

    private long resolveMaxSizeBytes(MessageType messageType) {
        return switch (messageType) {
            case IMAGE -> mediaStorageProperties.getMaxSize().getImageBytes();
            case AUDIO -> mediaStorageProperties.getMaxSize().getAudioBytes();
            case VIDEO -> mediaStorageProperties.getMaxSize().getVideoBytes();
            case FILE -> mediaStorageProperties.getMaxSize().getFileBytes();
            default -> throw new BadRequestException("Unsupported media type for upload session");
        };
    }

    private int resolveMaxAttachmentCount(MessageType messageType) {
        return messageType == MessageType.IMAGE ? mediaStorageProperties.getMaxImageCount() : 1;
    }

    private UploadStrategy resolveUploadStrategy(long sizeBytes) {
        return sizeBytes > mediaStorageProperties.getMultipartThresholdBytes()
                ? UploadStrategy.MULTIPART
                : UploadStrategy.SINGLE_PART;
    }

    private String buildObjectKey(User user, MessageType messageType, String filename) {
        String safeFilename = filename == null ? "upload.bin" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
        Long userId = Objects.requireNonNull(user.getId());
        return "media/" + userId + "/" + messageType.name().toLowerCase() + "/" + UUID.randomUUID() + "-" + safeFilename;
    }
}
