package com.hello.chatapp.service;

import com.hello.chatapp.config.MediaStorageProperties;
import com.hello.chatapp.constant.ChatScope;
import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.constant.MediaScanStatus;
import com.hello.chatapp.constant.MediaStatus;
import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.constant.UploadSessionStatus;
import com.hello.chatapp.dto.CompleteMediaAttachmentRequest;
import com.hello.chatapp.dto.CompleteMediaMessageRequest;
import com.hello.chatapp.dto.CompletedMultipartPartRequest;
import com.hello.chatapp.dto.MessageResponseMapper;
import com.hello.chatapp.dto.MultipartPartResponse;
import com.hello.chatapp.dto.PrepareMediaAttachmentRequest;
import com.hello.chatapp.dto.PrepareMediaMessageRequest;
import com.hello.chatapp.dto.PrepareMediaMessageResponse;
import com.hello.chatapp.dto.PreparedMediaAttachmentResponse;
import com.hello.chatapp.dto.RequestMultipartPartUrlsRequest;
import com.hello.chatapp.dto.RequestMultipartPartUrlsResponse;
import com.hello.chatapp.dto.UploadStrategy;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.MediaUpload;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.exception.BadRequestException;
import com.hello.chatapp.exception.ForbiddenException;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.repository.MediaUploadRepository;
import com.hello.chatapp.storage.ObjectStorageProvider;
import com.hello.chatapp.storage.ObjectStorageCompletedPart;
import com.hello.chatapp.storage.ObjectStorageProviderDescriptor;
import com.hello.chatapp.storage.ObjectStorageProviderRegistry;
import com.hello.chatapp.util.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(MediaUploadSessionService.class);

    private final MediaUploadRepository mediaUploadRepository;
    private final GroupAuthorizationService groupAuthorizationService;
    private final MediaStorageProperties mediaStorageProperties;
    private final ObjectStorageProviderRegistry objectStorageProviderRegistry;
    private final MalwareScanService malwareScanService;
    private final MessageService messageService;
    private final MediaProcessingService mediaProcessingService;
    private final MessageResponseMapper messageResponseMapper;
    private final RealtimeMessageDeliveryService realtimeMessageDeliveryService;

    public MediaUploadSessionService(
            MediaUploadRepository mediaUploadRepository,
            GroupAuthorizationService groupAuthorizationService,
            MediaStorageProperties mediaStorageProperties,
            ObjectStorageProviderRegistry objectStorageProviderRegistry,
            MalwareScanService malwareScanService,
            MessageService messageService,
            MediaProcessingService mediaProcessingService,
            MessageResponseMapper messageResponseMapper,
            RealtimeMessageDeliveryService realtimeMessageDeliveryService) {
        this.mediaUploadRepository = mediaUploadRepository;
        this.groupAuthorizationService = groupAuthorizationService;
        this.mediaStorageProperties = mediaStorageProperties;
        this.objectStorageProviderRegistry = objectStorageProviderRegistry;
        this.malwareScanService = malwareScanService;
        this.messageService = messageService;
        this.mediaProcessingService = mediaProcessingService;
        this.messageResponseMapper = messageResponseMapper;
        this.realtimeMessageDeliveryService = realtimeMessageDeliveryService;
    }

    @Transactional
    public PrepareMediaMessageResponse prepareUploadSession(User user, PrepareMediaMessageRequest request) {
        logger.debug("Prepare upload session for user {} with groupId {}", user.getUsername(), request.getGroupId());
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
        logger.debug("Request multipart part urls for user {} with uploadSessionId {} and attachmentId {}",
                user.getUsername(), uploadSessionId, attachmentId);
        MediaUpload mediaUpload = mediaUploadRepository.findByUploadSessionIdAndUploadId(uploadSessionId, attachmentId)
                .orElseThrow(() -> new NotFoundException("Upload attachment not found"));

        ensureUploadBelongsToUser(mediaUpload, user);
        ensureNotExpired(mediaUpload);
        if (resolveUploadStrategy(mediaUpload.getRequestedSizeBytes()) != UploadStrategy.MULTIPART) {
            throw new BadRequestException("Attachment does not use multipart upload");
        }

        Set<Integer> uniquePartNumbers = new LinkedHashSet<>(request.getPartNumbers());
        if (uniquePartNumbers.stream().anyMatch(partNumber -> partNumber == null || partNumber <= 0)) {
            throw new BadRequestException("All partNumbers must be positive");
        }

        ObjectStorageProvider provider = objectStorageProviderRegistry.getProvider(mediaUpload.getStorageProvider());
        ensureMultipartUploadInitialized(mediaUpload, provider);
        mediaUpload.setStatus(UploadSessionStatus.UPLOAD_IN_PROGRESS);
        // claimMultipartUploadId clears the persistence context; persist status + synced multipart id explicitly.
        mediaUploadRepository.save(mediaUpload);

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
        logger.debug("Complete upload session for user {} with uploadSessionId {}", user.getUsername(), uploadSessionId);
        List<MediaUpload> uploads = mediaUploadRepository.findByUploadSessionIdOrderByIdAsc(uploadSessionId);
        if (uploads.isEmpty()) {
            throw new NotFoundException("Upload session not found");
        }

        uploads.forEach(upload -> ensureUploadBelongsToUser(upload, user));
        MediaUpload firstUpload = uploads.getFirst();
        ensureNotExpired(firstUpload);

        // Validate that the attachmentIds in the request are unique.
        // This is to ensure that the same attachment is not uploaded multiple times.
        Map<String, CompleteMediaAttachmentRequest> requestByAttachmentId = new HashMap<>();
        for (CompleteMediaAttachmentRequest attachmentRequest : request.getAttachments()) {
            CompleteMediaAttachmentRequest previous =
                    requestByAttachmentId.put(attachmentRequest.getAttachmentId(), attachmentRequest);
            if (previous != null) {
                throw new BadRequestException("Duplicate attachmentId in completion request");
            }
        }

        // Validate that the number of attachments in the request matches the number of uploads.
        if (requestByAttachmentId.size() != uploads.size()) {
            throw new BadRequestException("Completion request must include every prepared attachment exactly once");
        }

        // Re-check SEND_MESSAGES at complete time: prepare can succeed, then kick/ban before finalize.
        // Intentionally no group FOR UPDATE here — membership mutations own that lock; media complete
        // only needs a fresh permission read (narrow residual race vs concurrent kick during this tx).
        // Details: check SEND_MESSAGES on group_participants, then write the message later. Kick/ban can commit in between.
        // Cái race condition này thực sự ko cần fix, nếu như user bị kick/ban khi đang complete thì cứ kệ thôi!
        Long preparedGroupId = firstUpload.getGroup() == null
                ? null
                : Objects.requireNonNull(firstUpload.getGroup().getId());
        validateScopeAndMembership(user, firstUpload.getChatScope(), preparedGroupId);

        for (MediaUpload upload : uploads) {
            CompleteMediaAttachmentRequest attachmentRequest = requestByAttachmentId.get(upload.getUploadId());
            if (attachmentRequest == null) {
                throw new BadRequestException("Missing completion metadata for attachment " + upload.getUploadId());
            }
            validateCompletionRequest(upload, attachmentRequest);
            finalizeAndVerifyUploadedObject(upload, attachmentRequest);
            malwareScanService.assertClean(upload);
            upload.setStatus(UploadSessionStatus.UPLOAD_COMPLETED);
        }

        Message message = persistFinalMessage(user, uploads);
        uploads.forEach(upload -> upload.setStatus(UploadSessionStatus.UPLOAD_SESSION_COMPLETED));

        MessageResponse response = Objects.requireNonNull(messageResponseMapper.toResponse(message));
        // Snapshot before deferring: do not publish STOMP/RabbitMQ while the tx can still roll back.
        Long groupId = message.getGroup() == null
                ? null
                : Objects.requireNonNull(message.getGroup().getId());
        schedulePublishFinalMessageAfterCommit(response, groupId);
        scheduleAsyncProcessingAfterCommit(message);
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

        return groupAuthorizationService.requireActivePermission(user, groupId, GroupPermission.SEND_MESSAGES);
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
            validateMultipartParts(request.getParts());
            return;
        }

        if (request.getEtag() == null || request.getEtag().isBlank()) {
            throw new BadRequestException("Single-part attachment requires etag");
        }
    }

    private void finalizeAndVerifyUploadedObject(MediaUpload upload, CompleteMediaAttachmentRequest request) {
        ObjectStorageProvider provider = objectStorageProviderRegistry.getProvider(upload.getStorageProvider());
        UploadStrategy uploadStrategy = resolveUploadStrategy(upload.getRequestedSizeBytes());
        if (uploadStrategy == UploadStrategy.MULTIPART) {
            provider.completeMultipartUpload(
                    upload.getObjectKey(),
                    Objects.requireNonNull(upload.getMultipartUploadId()),
                    toStorageCompletedParts(Objects.requireNonNull(request.getParts())));
        }

        if (!provider.objectExists(upload.getObjectKey())) {
            throw new BadRequestException("Uploaded object not found in storage for attachment " + upload.getUploadId());
        }
    }

    /**
     * Ensures {@code mediaUpload} has a provider multipart upload id, using CAS so concurrent first
     * {@code /parts} calls cannot persist different ids. Losers abort their provider create and reload
     * the winner's id (no create/CAS retry loop). See {@code docs/31_MEDIA_MULTIPART_UPLOAD_ID_INIT_RACE.md}.
     *
     * @param mediaUpload prepared multipart attachment row (multipart id synced onto this instance)
     * @param provider storage provider for create/abort
     */
    private void ensureMultipartUploadInitialized(MediaUpload mediaUpload, ObjectStorageProvider provider) {
        // Early return if the multipart upload id is already set.
        if (mediaUpload.getMultipartUploadId() != null && !mediaUpload.getMultipartUploadId().isBlank()) {
            return;
        }

        Long uploadRowId = Objects.requireNonNull(mediaUpload.getId(), "media upload id");
        String candidateId = provider.createMultipartUpload(mediaUpload.getObjectKey());

        // CAS: try to update multipartUploadId to the candidateId (multiple requests can do this concurrently).
        int claimed = mediaUploadRepository.claimMultipartUploadId(
                uploadRowId,
                candidateId,
                UploadSessionStatus.UPLOAD_INITIATED);

        // Updated multipartUploadId successfully (won the CAS race), update the multipartUploadId on the entity.
        if (claimed == 1) {
            mediaUpload.setMultipartUploadId(candidateId);
            return;
        }

        // Lost the CAS race (another request won the race and set the multipartUploadId), abort the provider multipart upload.
        provider.abortMultipartUpload(mediaUpload.getObjectKey(), candidateId);

        MediaUpload reloaded = mediaUploadRepository.findById(uploadRowId)
                .orElseThrow(() -> new NotFoundException("Upload attachment not found"));
        String winningId = reloaded.getMultipartUploadId();

        // This should never happen.
        if (winningId == null || winningId.isBlank()) {
            throw new IllegalStateException(
                    "Multipart upload id missing after lost CAS for attachment " + mediaUpload.getUploadId());
        }
        mediaUpload.setMultipartUploadId(winningId);
    }

    private void validateMultipartParts(List<CompletedMultipartPartRequest> parts) {
        Set<Integer> partNumbers = new LinkedHashSet<>();
        for (CompletedMultipartPartRequest part : parts) {
            if (part.getPartNumber() == null || part.getPartNumber() <= 0) {
                throw new BadRequestException("All multipart part numbers must be positive");
            }
            if (part.getEtag() == null || part.getEtag().isBlank()) {
                throw new BadRequestException("All multipart parts must include etag");
            }
            if (!partNumbers.add(part.getPartNumber())) {
                throw new BadRequestException("Duplicate multipart partNumber " + part.getPartNumber());
            }
        }
    }

    private List<ObjectStorageCompletedPart> toStorageCompletedParts(List<CompletedMultipartPartRequest> parts) {
        return parts.stream()
                .sorted((left, right) -> Integer.compare(left.getPartNumber(), right.getPartNumber()))
                .map(part -> new ObjectStorageCompletedPart(part.getPartNumber(), part.getEtag()))
                .toList();
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

    private void schedulePublishFinalMessageAfterCommit(MessageResponse response, Long groupId) {
        MessageResponse snapshot = Objects.requireNonNull(response);
        AfterCommit.run(
                () -> publishFinalMessage(snapshot, groupId),
                "Failed to publish final media message after commit for messageId=" + snapshot.getId());
    }

    private void publishFinalMessage(MessageResponse response, Long groupId) {
        if (groupId == null) {
            realtimeMessageDeliveryService.publishToPublic(response);
        } else {
            realtimeMessageDeliveryService.publishToGroup(groupId, response);
        }
    }

    private void scheduleAsyncProcessingAfterCommit(Message message) {
        Long messageId = Objects.requireNonNull(message.getId());
        MessageType messageType = message.getMessageType();
        if (messageType != MessageType.IMAGE && messageType != MessageType.VIDEO) {
            return;
        }
        AfterCommit.run(
                () -> mediaProcessingService.enqueueProcessing(messageId),
                "Failed to enqueue media processing after commit for messageId=" + messageId);
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
