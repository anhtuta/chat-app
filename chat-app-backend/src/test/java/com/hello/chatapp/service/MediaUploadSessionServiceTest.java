package com.hello.chatapp.service;

import com.hello.chatapp.config.MediaStorageProperties;
import com.hello.chatapp.constant.ChatScope;
import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.constant.UploadSessionStatus;
import com.hello.chatapp.dto.CompleteMediaAttachmentRequest;
import com.hello.chatapp.dto.CompleteMediaMessageRequest;
import com.hello.chatapp.dto.CompletedMultipartPartRequest;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.dto.MessageResponseMapper;
import com.hello.chatapp.dto.RequestMultipartPartUrlsRequest;
import com.hello.chatapp.dto.RequestMultipartPartUrlsResponse;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.MediaUpload;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.ForbiddenException;
import com.hello.chatapp.repository.MediaUploadRepository;
import com.hello.chatapp.storage.ObjectStorageProvider;
import com.hello.chatapp.storage.ObjectStorageProviderRegistry;
import com.hello.chatapp.storage.ObjectStorageProviderType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class MediaUploadSessionServiceTest {

    private static final String UPLOAD_SESSION_ID = "session-1";
    private static final String ATTACHMENT_ID = "attachment-1";
    private static final long MESSAGE_ID = 42L;

    @Mock
    private MediaUploadRepository mediaUploadRepository;

    @Mock
    private GroupAuthorizationService groupAuthorizationService;

    @Mock
    private MediaStorageProperties mediaStorageProperties;

    @Mock
    private ObjectStorageProviderRegistry objectStorageProviderRegistry;

    @Mock
    private MalwareScanService malwareScanService;

    @Mock
    private MessageService messageService;

    @Mock
    private MediaProcessingService mediaProcessingService;

    @Mock
    private MessageResponseMapper messageResponseMapper;

    @Mock
    private RealtimeMessageDeliveryService realtimeMessageDeliveryService;

    @Mock
    private ObjectStorageProvider objectStorageProvider;

    @InjectMocks
    private MediaUploadSessionService mediaUploadSessionService;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(7L);
        user.setUsername("alice");

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    @ParameterizedTest
    @EnumSource(value = MessageType.class, names = {"IMAGE", "VIDEO"})
    void completeUploadSession_mediaMessage_publishesAndEnqueuesOnlyAfterCommit(MessageType messageType) {
        MessageResponse response = stubSuccessfulCompletion(messageType);

        mediaUploadSessionService.completeUploadSession(
                user,
                UPLOAD_SESSION_ID,
                completionRequest(ATTACHMENT_ID));

        verify(realtimeMessageDeliveryService, never()).publishToPublic(any());
        verify(mediaProcessingService, never()).enqueueProcessing(anyLong());
        // publish + enqueue processing
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(2);

        triggerAfterCommit();

        verify(realtimeMessageDeliveryService).publishToPublic(response);
        verify(mediaProcessingService).enqueueProcessing(MESSAGE_ID);
    }

    @Test
    void completeUploadSession_imageMessage_doesNotPublishOrEnqueueOnRollback() {
        stubSuccessfulCompletion(MessageType.IMAGE);

        mediaUploadSessionService.completeUploadSession(
                user,
                UPLOAD_SESSION_ID,
                completionRequest(ATTACHMENT_ID));

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(2);
        triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(realtimeMessageDeliveryService, never()).publishToPublic(any());
        verify(mediaProcessingService, never()).enqueueProcessing(anyLong());
    }

    @Test
    void completeUploadSession_audioMessage_publishesAfterCommitWithoutAsyncProcessing() {
        MessageResponse response = stubSuccessfulCompletion(MessageType.AUDIO);

        mediaUploadSessionService.completeUploadSession(
                user,
                UPLOAD_SESSION_ID,
                completionRequest(ATTACHMENT_ID));

        verify(realtimeMessageDeliveryService, never()).publishToPublic(any());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        verify(mediaProcessingService, never()).enqueueProcessing(anyLong());

        triggerAfterCommit();

        verify(realtimeMessageDeliveryService).publishToPublic(response);
        verify(mediaProcessingService, never()).enqueueProcessing(anyLong());
    }

    @Test
    void completeUploadSession_groupMessage_rechecksSendMessagesBeforePersist() {
        Group group = new Group();
        group.setId(100L);
        MessageResponse response = stubSuccessfulGroupCompletion(MessageType.IMAGE, group);

        mediaUploadSessionService.completeUploadSession(
                user,
                UPLOAD_SESSION_ID,
                completionRequest(ATTACHMENT_ID));

        verify(groupAuthorizationService).requireActivePermission(user, 100L, GroupPermission.SEND_MESSAGES);
        verify(messageService).saveGroupMediaMessage(eq(group), eq(user), eq(MessageType.IMAGE), anyList());

        triggerAfterCommit();

        verify(realtimeMessageDeliveryService).publishToGroup(100L, response);
    }

    @Test
    void completeUploadSession_groupMessage_rejectsWhenSendMessagesRevoked() {
        Group group = new Group();
        group.setId(100L);
        MediaUpload upload = buildUpload(MessageType.IMAGE, ChatScope.GROUP, group);

        when(mediaUploadRepository.findByUploadSessionIdOrderByIdAsc(UPLOAD_SESSION_ID))
                .thenReturn(List.of(upload));
        when(mediaStorageProperties.getMultipartThresholdBytes()).thenReturn(10L * 1024 * 1024);
        when(objectStorageProviderRegistry.getProvider(ObjectStorageProviderType.MINIO))
                .thenReturn(objectStorageProvider);
        when(objectStorageProvider.objectExists(anyString())).thenReturn(true);
        when(groupAuthorizationService.requireActivePermission(user, 100L, GroupPermission.SEND_MESSAGES))
                .thenThrow(new ForbiddenException("You do not have permission to send messages in this group"));

        assertThatThrownBy(() -> mediaUploadSessionService.completeUploadSession(
                        user,
                        UPLOAD_SESSION_ID,
                        completionRequest(ATTACHMENT_ID)))
                .isInstanceOf(ForbiddenException.class);

        verify(messageService, never()).saveGroupMediaMessage(any(), any(), any(), anyList());
        verify(realtimeMessageDeliveryService, never()).publishToGroup(anyLong(), any());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void requestMultipartPartUrls_initializesProviderMultipartUploadId() {
        MediaUpload upload = buildUpload(MessageType.VIDEO, ChatScope.PUBLIC, null);
        upload.setRequestedSizeBytes(20L * 1024 * 1024);

        when(mediaUploadRepository.findByUploadSessionIdAndUploadId(UPLOAD_SESSION_ID, ATTACHMENT_ID))
                .thenReturn(java.util.Optional.of(upload));
        when(mediaStorageProperties.getMultipartThresholdBytes()).thenReturn(10L * 1024 * 1024);
        when(objectStorageProviderRegistry.getProvider(ObjectStorageProviderType.MINIO))
                .thenReturn(objectStorageProvider);
        when(objectStorageProvider.createMultipartUpload(upload.getObjectKey()))
                .thenReturn("provider-upload-id");
        when(objectStorageProvider.buildMultipartUploadPartUrl(upload.getObjectKey(), "provider-upload-id", 1))
                .thenReturn("part-1-url");
        when(objectStorageProvider.buildMultipartUploadPartUrl(upload.getObjectKey(), "provider-upload-id", 2))
                .thenReturn("part-2-url");

        RequestMultipartPartUrlsResponse response = mediaUploadSessionService.requestMultipartPartUrls(
                user,
                UPLOAD_SESSION_ID,
                ATTACHMENT_ID,
                new RequestMultipartPartUrlsRequest(List.of(1, 2)));

        assertThat(response.getMultipartUploadId()).isEqualTo("provider-upload-id");
        assertThat(response.getParts()).extracting("partNumber").containsExactly(1, 2);
        assertThat(upload.getMultipartUploadId()).isEqualTo("provider-upload-id");
        assertThat(upload.getStatus()).isEqualTo(UploadSessionStatus.UPLOAD_IN_PROGRESS);
        verify(objectStorageProvider).createMultipartUpload(upload.getObjectKey());
    }

    @Test
    void completeUploadSession_multipartAttachment_completesProviderUploadBeforePersistingMessage() {
        MediaUpload upload = buildUpload(MessageType.VIDEO, ChatScope.PUBLIC, null);
        upload.setRequestedSizeBytes(20L * 1024 * 1024);
        upload.setMultipartUploadId("provider-upload-id");
        Message savedMessage = buildMessage(MessageType.VIDEO, null);
        MessageResponse response = MessageResponse.builder().id(MESSAGE_ID).build();

        when(mediaUploadRepository.findByUploadSessionIdOrderByIdAsc(UPLOAD_SESSION_ID))
                .thenReturn(List.of(upload));
        when(mediaStorageProperties.getMultipartThresholdBytes()).thenReturn(10L * 1024 * 1024);
        when(objectStorageProviderRegistry.getProvider(ObjectStorageProviderType.MINIO))
                .thenReturn(objectStorageProvider);
        when(objectStorageProvider.objectExists(upload.getObjectKey())).thenReturn(true);
        when(messageService.savePublicMediaMessage(eq(user), eq(MessageType.VIDEO), anyList()))
                .thenReturn(savedMessage);
        when(messageResponseMapper.toResponse(savedMessage)).thenReturn(response);

        mediaUploadSessionService.completeUploadSession(
                user,
                UPLOAD_SESSION_ID,
                new CompleteMediaMessageRequest(List.of(new CompleteMediaAttachmentRequest(
                        ATTACHMENT_ID,
                        null,
                        List.of(
                                new CompletedMultipartPartRequest(2, "\"etag-2\""),
                                new CompletedMultipartPartRequest(1, "\"etag-1\""))))));

        verify(objectStorageProvider).completeMultipartUpload(eq(upload.getObjectKey()), eq("provider-upload-id"), anyList());
        verify(objectStorageProvider).objectExists(upload.getObjectKey());
        verify(messageService).savePublicMediaMessage(eq(user), eq(MessageType.VIDEO), anyList());
        assertThat(upload.getStatus()).isEqualTo(UploadSessionStatus.UPLOAD_SESSION_COMPLETED);
    }

    private MessageResponse stubSuccessfulCompletion(MessageType messageType) {
        MediaUpload upload = buildUpload(messageType, ChatScope.PUBLIC, null);
        Message savedMessage = buildMessage(messageType, null);
        MessageResponse response = MessageResponse.builder().id(MESSAGE_ID).build();

        when(mediaUploadRepository.findByUploadSessionIdOrderByIdAsc(UPLOAD_SESSION_ID))
                .thenReturn(List.of(upload));
        when(mediaStorageProperties.getMultipartThresholdBytes()).thenReturn(10L * 1024 * 1024);
        when(objectStorageProviderRegistry.getProvider(ObjectStorageProviderType.MINIO))
                .thenReturn(objectStorageProvider);
        when(objectStorageProvider.objectExists(anyString())).thenReturn(true);
        when(messageService.savePublicMediaMessage(eq(user), eq(messageType), anyList()))
                .thenReturn(savedMessage);
        when(messageResponseMapper.toResponse(savedMessage)).thenReturn(response);
        return response;
    }

    private MessageResponse stubSuccessfulGroupCompletion(MessageType messageType, Group group) {
        MediaUpload upload = buildUpload(messageType, ChatScope.GROUP, group);
        Message savedMessage = buildMessage(messageType, group);
        MessageResponse response = MessageResponse.builder().id(MESSAGE_ID).build();

        when(mediaUploadRepository.findByUploadSessionIdOrderByIdAsc(UPLOAD_SESSION_ID))
                .thenReturn(List.of(upload));
        when(mediaStorageProperties.getMultipartThresholdBytes()).thenReturn(10L * 1024 * 1024);
        when(objectStorageProviderRegistry.getProvider(ObjectStorageProviderType.MINIO))
                .thenReturn(objectStorageProvider);
        when(objectStorageProvider.objectExists(anyString())).thenReturn(true);
        when(groupAuthorizationService.requireActivePermission(user, group.getId(), GroupPermission.SEND_MESSAGES))
                .thenReturn(group);
        when(messageService.saveGroupMediaMessage(eq(group), eq(user), eq(messageType), anyList()))
                .thenReturn(savedMessage);
        when(messageResponseMapper.toResponse(savedMessage)).thenReturn(response);
        return response;
    }

    private MediaUpload buildUpload(MessageType messageType, ChatScope chatScope, Group group) {
        MediaUpload upload = new MediaUpload();
        upload.setId(1L);
        upload.setUploadId(ATTACHMENT_ID);
        upload.setUser(user);
        upload.setChatScope(chatScope);
        upload.setGroup(group);
        upload.setUploadSessionId(UPLOAD_SESSION_ID);
        upload.setRequestedMessageType(messageType);
        upload.setRequestedFilename("photo.jpg");
        upload.setRequestedSizeBytes(1024L);
        upload.setRequestedMimeType("image/jpeg");
        upload.setStorageProvider(ObjectStorageProviderType.MINIO);
        upload.setBucket("media-bucket");
        upload.setObjectKey("media/7/image/object.jpg");
        upload.setStatus(UploadSessionStatus.UPLOAD_INITIATED);
        upload.setExpiresAt(LocalDateTime.now().plusHours(1));
        return upload;
    }

    private Message buildMessage(MessageType messageType, Group group) {
        Message message = new Message();
        message.setId(MESSAGE_ID);
        message.setUser(user);
        message.setGroup(group);
        message.setMessageType(messageType);
        message.setTimestamp(LocalDateTime.now());
        return message;
    }

    private CompleteMediaMessageRequest completionRequest(String attachmentId) {
        return new CompleteMediaMessageRequest(List.of(
                new CompleteMediaAttachmentRequest(attachmentId, "\"etag-1\"", null)));
    }

    private void triggerAfterCommit() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
    }

    private void triggerAfterCompletion(int status) {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(status);
        }
    }
}
