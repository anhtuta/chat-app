package com.hello.chatapp.storage;

import com.hello.chatapp.config.MediaStorageProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the AWS SDK v2 S3 provider implementation against a disposable S3-compatible container.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ObjectStorageProviderIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(S3ObjectStorageProviderIntegrationTest.class);

    private static final String ACCESS_KEY = "rustfslocal";
    private static final String SECRET_KEY = "rustfslocalsecret";
    private static final String BUCKET = "chat-media";
    private static final String CONTAINER_NAME = "chat-app-s3-provider-test";
    private static final String STORAGE_IMAGE =
            "rustfs/rustfs@sha256:1803faef57627e2d9c2e7d89d655d712ddded5389040054987163043fecb6a3c";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private int s3Port;
    private S3ObjectStorageProvider provider;

    /**
     * Starts the disposable S3-compatible container and initializes the provider under test.
     */
    @BeforeAll
    void setUp() throws Exception {
        stopContainerIfPresent();
        runCommand(List.of(
                "docker", "run", "-d", "--rm",
                "--name", CONTAINER_NAME,
                "-p", "127.0.0.1::9000",
                "-e", "RUSTFS_ACCESS_KEY=" + ACCESS_KEY,
                "-e", "RUSTFS_SECRET_KEY=" + SECRET_KEY,
                "-e", "RUSTFS_ADDRESS=:9000",
                STORAGE_IMAGE,
                "/data"));
        s3Port = resolveMappedPort("9000/tcp");
        logger.info("Started S3 provider test container {} on {}", CONTAINER_NAME, endpoint());
        awaitHealth();

        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setProvider(ObjectStorageProviderType.S3);
        properties.getS3().setAccessKey(ACCESS_KEY);
        properties.getS3().setSecretKey(SECRET_KEY);
        properties.getS3().setBucket(BUCKET);
        properties.getS3().setRegion("us-east-1");
        properties.getS3().setEndpoint(endpoint());
        properties.getS3().setPathStyleAccess(true);
        provider = new S3ObjectStorageProvider(properties);
        provider.ensureBucketExistsWhenActive();
    }

    /**
     * Removes the disposable provider test container after the integration test suite finishes.
     */
    @AfterAll
    void tearDown() throws Exception {
        stopContainerIfPresent();
    }

    /**
     * Verifies real presigned upload/read URLs plus object existence and deletion.
     */
    @Test
    void presignedUrlsAndObjectLifecycle_workAgainstS3CompatibleContainer() throws Exception {
        String objectKey = "provider/" + UUID.randomUUID() + "/sample.txt";
        byte[] body = "aws-sdk-provider-contract".getBytes(StandardCharsets.UTF_8);

        HttpRequest uploadRequest = HttpRequest.newBuilder()
                .uri(URI.create(provider.buildUploadUrl(objectKey)))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<Void> uploadResponse = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.discarding());

        assertThat(uploadResponse.statusCode()).isEqualTo(200);
        assertThat(provider.objectExists(objectKey)).isTrue();

        HttpRequest readRequest = HttpRequest.newBuilder()
                .uri(URI.create(provider.buildReadUrl(objectKey)))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<byte[]> readResponse = httpClient.send(readRequest, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(readResponse.statusCode()).isEqualTo(200);
        assertThat(readResponse.body()).isEqualTo(body);

        provider.deleteObject(objectKey);

        assertThat(provider.objectExists(objectKey)).isFalse();
    }

    /**
     * Verifies multipart initiate/presigned-part/complete through the AWS SDK v2 provider.
     */
    @Test
    void multipartLifecycle_completesFinalObject() throws Exception {
        String objectKey = "provider/" + UUID.randomUUID() + "/multipart.bin";
        byte[] partOne = repeatedBytes(5 * 1024 * 1024, (byte) 'X');
        byte[] partTwo = repeatedBytes(1024 * 1024, (byte) 'Y');
        byte[] expected = new byte[partOne.length + partTwo.length];
        System.arraycopy(partOne, 0, expected, 0, partOne.length);
        System.arraycopy(partTwo, 0, expected, partOne.length, partTwo.length);

        String uploadId = provider.createMultipartUpload(objectKey);

        HttpResponse<Void> uploadPartOne = uploadPart(provider.buildMultipartUploadPartUrl(objectKey, uploadId, 1), partOne);
        HttpResponse<Void> uploadPartTwo = uploadPart(provider.buildMultipartUploadPartUrl(objectKey, uploadId, 2), partTwo);

        assertThat(uploadPartOne.statusCode()).isEqualTo(200);
        assertThat(uploadPartTwo.statusCode()).isEqualTo(200);

        provider.completeMultipartUpload(
                objectKey,
                uploadId,
                List.of(
                        new ObjectStorageCompletedPart(1, responseEtag(uploadPartOne)),
                        new ObjectStorageCompletedPart(2, responseEtag(uploadPartTwo))));

        assertThat(provider.objectExists(objectKey)).isTrue();

        HttpRequest readRequest = HttpRequest.newBuilder()
                .uri(URI.create(provider.buildReadUrl(objectKey)))
                .GET()
                .timeout(Duration.ofSeconds(20))
                .build();

        HttpResponse<byte[]> readResponse = httpClient.send(readRequest, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(readResponse.statusCode()).isEqualTo(200);
        assertThat(readResponse.body()).isEqualTo(expected);
    }

    /**
     * Verifies multipart abort leaves no finalized object behind.
     */
    @Test
    void multipartAbort_discardsUnfinishedObject() throws Exception {
        String objectKey = "provider/" + UUID.randomUUID() + "/aborted.bin";
        byte[] part = repeatedBytes(5 * 1024 * 1024, (byte) 'Z');

        String uploadId = provider.createMultipartUpload(objectKey);
        HttpResponse<Void> uploadResponse = uploadPart(provider.buildMultipartUploadPartUrl(objectKey, uploadId, 1), part);

        assertThat(uploadResponse.statusCode()).isEqualTo(200);

        provider.abortMultipartUpload(objectKey, uploadId);

        assertThat(provider.objectExists(objectKey)).isFalse();
    }

    /**
     * Returns the disposable S3 endpoint used by the provider under test.
     *
     * @return local endpoint URL
     */
    private String endpoint() {
        return "http://127.0.0.1:" + s3Port;
    }

    /**
     * Uploads one multipart part to the provided presigned URL.
     *
     * @param url presigned upload-part URL
     * @param body bytes to upload
     * @return upload response
     */
    private HttpResponse<Void> uploadPart(String url, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(20))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    /**
     * Returns the ETag header from one successful upload response.
     *
     * @param response successful upload response
     * @return returned ETag header value
     */
    private String responseEtag(HttpResponse<Void> response) {
        return response.headers()
                .firstValue("etag")
                .orElseThrow(() -> new IllegalStateException("Multipart upload response did not include ETag"));
    }

    /**
     * Builds a repeated byte array for multipart part bodies.
     *
     * @param length desired byte count
     * @param value repeated byte value
     * @return filled byte array
     */
    private byte[] repeatedBytes(int length, byte value) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = value;
        }
        return bytes;
    }

    /**
     * Waits until the disposable storage container reports healthy.
     */
    private void awaitHealth() throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint() + "/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            try {
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // Poll until timeout.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Timed out waiting for S3 provider test container health");
    }

    /**
     * Resolves one dynamically assigned Docker host port.
     *
     * @param containerPort port name such as {@code 9000/tcp}
     * @return mapped host port
     */
    private int resolveMappedPort(String containerPort) throws Exception {
        String output = runCommand(List.of("docker", "port", CONTAINER_NAME, containerPort)).trim();
        String port = output.substring(output.lastIndexOf(':') + 1);
        return Integer.parseInt(port);
    }

    /**
     * Removes the disposable container if it already exists.
     */
    private void stopContainerIfPresent() throws Exception {
        try {
            runCommand(List.of("docker", "rm", "-f", CONTAINER_NAME));
        } catch (IllegalStateException ignored) {
            // Container was already absent.
        }
    }

    /**
     * Runs one external command and returns captured standard output.
     *
     * @param command command and arguments
     * @return stdout text
     */
    private String runCommand(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        int exitCode = process.waitFor();
        String text = output.toString(StandardCharsets.UTF_8);
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed (" + String.join(" ", command) + "): " + text);
        }
        return text;
    }
}
