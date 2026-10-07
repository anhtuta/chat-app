package com.hello.chatapp.dto;

import com.hello.chatapp.config.MediaStorageProperties;
import com.hello.chatapp.constant.MediaScanStatus;
import com.hello.chatapp.constant.MediaStatus;
import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.model.SystemEventPayload;
import com.hello.chatapp.storage.ObjectStorageCompletedPart;
import com.hello.chatapp.storage.ObjectStorageProvider;
import com.hello.chatapp.storage.ObjectStorageProviderDescriptor;
import com.hello.chatapp.storage.ObjectStorageProviderRegistry;
import com.hello.chatapp.storage.ObjectStorageProviderType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Maps {@link Message} rows to API responses, including structured system-event fields.
 */
class MessageResponseMapperTest {

    /**
     * Attachment payloads include a storage-backed content URL.
     */
    @Test
    void toResponse_addsContentUrlForAttachments() {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setProvider(ObjectStorageProviderType.S3);

        MessageResponseMapper mapper = mapperWithStorage(properties);

        User user = new User("alice", "secret", "Alice");
        user.setId(1L);

        Message message = new Message();
        message.setId(10L);
        message.setUser(user);
        message.setMessageType(MessageType.IMAGE);

        MessageMedia attachment = new MessageMedia();
        attachment.setId(100L);
        attachment.setAttachmentOrder(0);
        attachment.setStorageProvider(ObjectStorageProviderType.S3);
        attachment.setBucket("chat-media");
        attachment.setObjectKey("media/1/photo.png");
        attachment.setOriginalFilename("photo.png");
        attachment.setDeclaredMimeType("image/png");
        attachment.setSizeBytes(1234L);
        attachment.setStatus(MediaStatus.MEDIA_READY);
        attachment.setScanStatus(MediaScanStatus.SCAN_PASSED);
        message.addAttachment(attachment);

        MessageResponse response = mapper.toResponse(message);

        assertThat(response).isNotNull();
        assertThat(response.getAttachments()).hasSize(1);
        assertThat(response.getAttachments().getFirst().getContentUrl()).isNotBlank();
        assertThat(response.getAttachments().getFirst().getDownloadUrl()).isNotBlank();
        assertThat(response.getAttachments().getFirst().getContentUrl()).contains("chat-media/media/1/photo.png");
    }

    /**
     * Video attachments expose the canonical playback/download contract needed by the frontend player.
     */
    @Test
    void toResponse_mapsVideoPlaybackContractFields() {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setProvider(ObjectStorageProviderType.S3);

        MessageResponseMapper mapper = mapperWithStorage(properties);

        User user = new User("alice", "secret", "Alice");
        user.setId(1L);

        Message message = new Message();
        message.setId(11L);
        message.setUser(user);
        message.setMessageType(MessageType.VIDEO);

        MessageMedia attachment = new MessageMedia();
        attachment.setId(101L);
        attachment.setAttachmentOrder(0);
        attachment.setStorageProvider(ObjectStorageProviderType.S3);
        attachment.setBucket("chat-media");
        attachment.setObjectKey("media/1/demo.transcoded.mp4");
        attachment.setOriginalFilename("demo.mov");
        attachment.setDeclaredMimeType("video/quicktime");
        attachment.setDetectedMimeType("video/mp4");
        attachment.setSizeBytes(4321L);
        attachment.setWidth(1920);
        attachment.setHeight(1080);
        attachment.setDurationMs(12_345L);
        attachment.setStatus(MediaStatus.MEDIA_READY);
        attachment.setScanStatus(MediaScanStatus.SCAN_PASSED);
        attachment.setThumbnailObjectKey("media/1/demo.thumbnail.jpg");
        attachment.setTranscodedObjectKey("media/1/demo.transcoded.mp4");
        attachment.setRendition480pObjectKey("media/1/demo.480p.mp4");
        attachment.setRendition480pSizeBytes(2_000L);
        message.addAttachment(attachment);

        MessageResponse response = mapper.toResponse(message);

        assertThat(response).isNotNull();
        assertThat(response.getAttachments()).hasSize(1);
        MessageAttachmentResponse mapped = response.getAttachments().getFirst();
        assertThat(mapped.getDurationMs()).isEqualTo(12_345L);
        assertThat(mapped.getWidth()).isEqualTo(1920);
        assertThat(mapped.getHeight()).isEqualTo(1080);
        assertThat(mapped.getPlaybackUrl()).isEqualTo(mapped.getTranscodedUrl());
        assertThat(mapped.getDownloadUrl()).isEqualTo(mapped.getContentUrl());
        assertThat(mapped.getPosterUrl()).isEqualTo(mapped.getThumbnailUrl());
        assertThat(mapped.getVideoSources()).hasSize(2);
        assertThat(mapped.getVideoSources().getFirst().role()).isEqualTo("CANONICAL");
        assertThat(mapped.getVideoSources().get(1).role()).isEqualTo("MOBILE");
        assertThat(mapped.getVideoSources().get(1).height()).isEqualTo(480);
        assertThat(mapped.getVideoSources().get(1).sizeBytes()).isEqualTo(2_000L);
    }

    /**
     * SYSTEM rows expose event type plus actor (updatedBy) separately from the subject user.
     */
    @Test
    void toResponse_mapsSystemEventMetadata() {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setProvider(ObjectStorageProviderType.S3);

        MessageResponseMapper mapper = mapperWithStorage(properties);

        User actor = new User("alice", "secret", "Alice");
        actor.setId(1L);

        User subject = new User("bob", "secret", "Bob");
        subject.setId(2L);

        Message message = new Message();
        message.setId(12L);
        message.setUser(subject);
        message.setUpdatedBy(actor);
        message.setMessageType(MessageType.SYSTEM);
        message.setContent(SystemEventType.USER_LEFT.name());

        MessageResponse response = mapper.toResponse(message);

        assertThat(response).isNotNull();
        assertThat(response.getSystemEventType()).isEqualTo(SystemEventType.USER_LEFT);
        assertThat(response.getSystemEventActor()).isNotNull();
        assertThat(response.getSystemEventActor().getUsername()).isEqualTo("alice");
        assertThat(response.getUser()).isNotNull();
        assertThat(response.getUser().getUsername()).isEqualTo("bob");
    }

    /**
     * Batch add-member events expose every added display name on {@code systemEventPayload}.
     */
    @Test
    void toResponse_mapsSystemEventPayloadSubjectNames() {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setProvider(ObjectStorageProviderType.S3);

        MessageResponseMapper mapper = mapperWithStorage(properties);

        User actor = new User("alice", "secret", "Alice");
        User subject = new User("bob", "secret", "Bob");
        Message message = new Message();
        message.setId(13L);
        message.setUser(subject);
        message.setUpdatedBy(actor);
        message.setMessageType(MessageType.SYSTEM);
        message.setContent(SystemEventType.USER_JOINED.name());
        message.setSystemEventPayload(SystemEventPayload.ofSubjectNames(List.of("Bob", "Carol")));

        MessageResponse response = mapper.toResponse(message);

        assertThat(response.getSystemEventPayload()).isNotNull();
        assertThat(response.getSystemEventPayload().getSubjectNames()).containsExactly("Bob", "Carol");
    }

    /**
     * Freshness keys advance for attachment processing updates, not just message edits.
     */
    @Test
    void toResponse_usesAttachmentUpdatesForFreshnessKey() {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setProvider(ObjectStorageProviderType.S3);

        MessageResponseMapper mapper = mapperWithStorage(properties);

        Instant messageTimestamp = Instant.parse("2026-09-24T11:00:00Z");
        Instant attachmentUpdatedAt = messageTimestamp.plusSeconds(2 * 60L);

        User user = new User("alice", "secret", "Alice");
        user.setId(1L);

        Message message = new Message();
        message.setId(14L);
        message.setUser(user);
        message.setMessageType(MessageType.VIDEO);
        message.setTimestamp(messageTimestamp);

        MessageMedia attachment = new MessageMedia();
        attachment.setId(102L);
        attachment.setAttachmentOrder(0);
        attachment.setStorageProvider(ObjectStorageProviderType.S3);
        attachment.setBucket("chat-media");
        attachment.setObjectKey("media/1/demo.mp4");
        attachment.setOriginalFilename("demo.mp4");
        attachment.setDeclaredMimeType("video/mp4");
        attachment.setSizeBytes(3_210L);
        attachment.setStatus(MediaStatus.MEDIA_READY);
        attachment.setScanStatus(MediaScanStatus.SCAN_PASSED);
        attachment.setUpdatedAt(attachmentUpdatedAt);
        message.addAttachment(attachment);

        MessageResponse response = mapper.toResponse(message);

        assertThat(response.getFreshnessKey()).isEqualTo(attachmentUpdatedAt.toString());
    }

    /**
     * Creates a mapper backed by a simple always-present S3 stub so these tests remain pure mapping checks.
     *
     * @param properties active media storage properties
     * @return mapper configured with a deterministic storage provider
     */
    private MessageResponseMapper mapperWithStorage(MediaStorageProperties properties) {
        return new MessageResponseMapper(new ObjectStorageProviderRegistry(
                List.of(new StubS3Provider()),
                properties));
    }

    /**
     * Minimal S3 provider stub for mapper-only tests.
     */
    private static final class StubS3Provider implements ObjectStorageProvider {

        /**
         * Returns deterministic descriptor metadata for stubbed mapper tests.
         *
         * @return fixed provider descriptor
         */
        @Override
        public ObjectStorageProviderDescriptor describe() {
            return new ObjectStorageProviderDescriptor(
                    ObjectStorageProviderType.S3,
                    "chat-media",
                    "ap-southeast-1",
                    "https://storage.example.test",
                    true,
                    true);
        }

        /**
         * Returns the provider type represented by this stub.
         *
         * @return {@link ObjectStorageProviderType#S3}
         */
        @Override
        public ObjectStorageProviderType getType() {
            return ObjectStorageProviderType.S3;
        }

        /**
         * Returns a deterministic upload URL for the given object key.
         *
         * @param objectKey destination object key
         * @return stable fake upload URL
         */
        @Override
        public String buildUploadUrl(String objectKey) {
            return "https://storage.example.test/chat-media/" + objectKey + "?upload=1";
        }

        /**
         * Multipart uploads are not exercised in mapper-only tests.
         *
         * @param objectKey destination object key
         * @return fixed upload id
         */
        @Override
        public String createMultipartUpload(String objectKey) {
            return "stub-upload-id";
        }

        /**
         * Returns a deterministic upload-part URL for mapper-only tests.
         *
         * @param objectKey destination object key
         * @param multipartUploadId ignored stub value
         * @param partNumber multipart part number
         * @return stable fake upload-part URL
         */
        @Override
        public String buildMultipartUploadPartUrl(String objectKey, String multipartUploadId, int partNumber) {
            return "https://storage.example.test/chat-media/" + objectKey
                    + "?uploadId=" + multipartUploadId + "&partNumber=" + partNumber;
        }

        /**
         * Multipart completion is not exercised in mapper-only tests.
         *
         * @param objectKey destination object key
         * @param multipartUploadId ignored stub value
         * @param parts ignored stub parts
         */
        @Override
        public void completeMultipartUpload(String objectKey, String multipartUploadId, List<ObjectStorageCompletedPart> parts) {
            // No-op in mapper tests.
        }

        /**
         * Multipart abort is not exercised in mapper-only tests.
         *
         * @param objectKey destination object key
         * @param multipartUploadId ignored stub value
         */
        @Override
        public void abortMultipartUpload(String objectKey, String multipartUploadId) {
            // No-op in mapper tests.
        }

        /**
         * Returns a deterministic read URL for the given object key.
         *
         * @param objectKey object key to expose
         * @return stable fake read URL
         */
        @Override
        public String buildReadUrl(String objectKey) {
            return "https://storage.example.test/chat-media/" + objectKey + "?read=1";
        }

        /**
         * Pretends every requested object exists so mapping logic can populate derived URLs.
         *
         * @param objectKey ignored stub key
         * @return always {@code true}
         */
        @Override
        public boolean objectExists(String objectKey) {
            return true;
        }

        /**
         * Deletion is not exercised in mapper-only tests.
         *
         * @param objectKey ignored stub key
         */
        @Override
        public void deleteObject(String objectKey) {
            // No-op in mapper tests.
        }
    }
}
