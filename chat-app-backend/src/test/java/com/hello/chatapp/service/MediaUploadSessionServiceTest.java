package com.hello.chatapp.service;

import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;
import com.hello.chatapp.config.MediaStorageProperties;
import com.hello.chatapp.dto.CompleteMediaAttachmentRequest;
import com.hello.chatapp.dto.CompleteMediaMessageRequest;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.dto.MessageResponseMapper;
import com.hello.chatapp.entity.ChatScope;
import com.hello.chatapp.entity.MediaUpload;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageType;
import com.hello.chatapp.entity.UploadSessionStatus;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.BadRequestException;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private GroupRepository groupRepository;

    @Mock
    private GroupParticipantRepository groupParticipantRepository;

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
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private CustomRabbitMQBrokerHandler rabbitMQBrokerHandler;

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
    void completeUploadSession_mediaMessage_enqueuesProcessingOnlyAfterCommit(MessageType messageType) {
        stubSuccessfulCompletion(messageType);

        mediaUploadSessionService.completeUploadSession(
                user,
                UPLOAD_SESSION_ID,
                completionRequest(ATTACHMENT_ID));

        verify(mediaProcessingService, never()).enqueueProcessing(anyLong());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        triggerAfterCommit();

        verify(mediaProcessingService).enqueueProcessing(MESSAGE_ID);
    }

    @Test
    void completeUploadSession_imageMessage_doesNotEnqueueOnRollback() {
        stubSuccessfulCompletion(MessageType.IMAGE);

        mediaUploadSessionService.completeUploadSession(
                user,
                UPLOAD_SESSION_ID,
                completionRequest(ATTACHMENT_ID));

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(mediaProcessingService, never()).enqueueProcessing(anyLong());
    }

    @Test
    void completeUploadSession_audioMessage_doesNotScheduleAsyncProcessing() {
        stubSuccessfulCompletion(MessageType.AUDIO);

        mediaUploadSessionService.completeUploadSession(
                user,
                UPLOAD_SESSION_ID,
                completionRequest(ATTACHMENT_ID));

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(mediaProcessingService, never()).enqueueProcessing(anyLong());
    }

    @Test
    void completeUploadSession_rejectsEtagMismatch() {
        stubUploadSessionLookup(MessageType.IMAGE);
        stubStoredEtagVerification(Optional.of("\"different-etag\""));

        assertThatThrownBy(() -> mediaUploadSessionService.completeUploadSession(
                user,
                UPLOAD_SESSION_ID,
                completionRequest(ATTACHMENT_ID)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("etag does not match");
    }

    @Test
    void completeUploadSession_rejectsUnavailableClientEtag() {
        stubUploadSessionLookup(MessageType.IMAGE);

        assertThatThrownBy(() -> mediaUploadSessionService.completeUploadSession(
                user,
                UPLOAD_SESSION_ID,
                new CompleteMediaMessageRequest(List.of(
                        new CompleteMediaAttachmentRequest(ATTACHMENT_ID, "etag-unavailable", null)))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("etag is unavailable");
    }

    @Test
    void completeUploadSession_rejectsMissingStoredObject() {
        stubUploadSessionLookup(MessageType.IMAGE);
        stubStoredEtagVerification(Optional.empty());

        assertThatThrownBy(() -> mediaUploadSessionService.completeUploadSession(
                user,
                UPLOAD_SESSION_ID,
                completionRequest(ATTACHMENT_ID)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not found in storage");
    }

    private void stubUploadSessionLookup(MessageType messageType) {
        when(mediaUploadRepository.findByUploadSessionIdOrderByIdAsc(UPLOAD_SESSION_ID))
                .thenReturn(List.of(buildUpload(messageType)));
        when(mediaStorageProperties.getMultipartThresholdBytes()).thenReturn(10L * 1024 * 1024);
    }

    private void stubStoredEtagVerification(Optional<String> storageEtag) {
        when(objectStorageProviderRegistry.getProvider(ObjectStorageProviderType.MINIO))
                .thenReturn(objectStorageProvider);
        when(objectStorageProvider.supportsStoredEtagVerification()).thenReturn(true);
        when(objectStorageProvider.findObjectEtag(uploadObjectKey())).thenReturn(storageEtag);
    }

    private void stubSuccessfulCompletion(MessageType messageType) {
        stubSuccessfulCompletion(messageType, "\"etag-1\"");
    }

    private void stubSuccessfulCompletion(MessageType messageType, String storageEtag) {
        Message savedMessage = buildMessage(messageType);

        stubUploadSessionLookup(messageType);
        stubStoredEtagVerification(Optional.of(storageEtag));
        when(messageService.savePublicMediaMessage(eq(user), eq(messageType), anyList()))
                .thenReturn(savedMessage);
        when(messageResponseMapper.toResponse(savedMessage)).thenReturn(MessageResponse.builder().id(MESSAGE_ID).build());
    }

    private MediaUpload buildUpload(MessageType messageType) {
        MediaUpload upload = new MediaUpload();
        upload.setId(1L);
        upload.setUploadId(ATTACHMENT_ID);
        upload.setUser(user);
        upload.setChatScope(ChatScope.PUBLIC);
        upload.setGroup(null);
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

    private Message buildMessage(MessageType messageType) {
        Message message = new Message();
        message.setId(MESSAGE_ID);
        message.setUser(user);
        message.setGroup(null);
        message.setMessageType(messageType);
        message.setTimestamp(LocalDateTime.now());
        return message;
    }

    private CompleteMediaMessageRequest completionRequest(String attachmentId) {
        return new CompleteMediaMessageRequest(List.of(
                new CompleteMediaAttachmentRequest(attachmentId, "\"etag-1\"", null)));
    }

    private String uploadObjectKey() {
        return "media/7/image/object.jpg";
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
