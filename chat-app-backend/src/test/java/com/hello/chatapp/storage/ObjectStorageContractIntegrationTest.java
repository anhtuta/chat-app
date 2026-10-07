package com.hello.chatapp.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the first executable object-storage contract slice against a disposable
 * MinIO-compatible container.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObjectStorageContractIntegrationTest {

    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";
    private static final String BUCKET = "chat-media";
    private static final String CONTAINER_NAME = "chat-app-storage-contract-test";
    private static final String BRIDGE_IMAGE =
            "cgr.dev/chainguard/minio@sha256:e7ca559d9f7c0b5f24f5f669bb92f40f3ca88d56273b808bf3a7c116c17d2ffa";
    private static final Logger logger = LoggerFactory.getLogger(ObjectStorageContractIntegrationTest.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private int s3Port;
    private int consolePort;

    /**
     * Starts a disposable MinIO-compatible container through the local Docker CLI and
     * waits for the S3 health endpoint used by the application.
     */
    @BeforeAll
    void startDisposableStorage() throws Exception {
        stopContainerIfPresent();
        runCommand(List.of(
                "docker", "run", "-d", "--rm",
                "--name", CONTAINER_NAME,
                "-p", "127.0.0.1::9000",
                "-p", "127.0.0.1::9001",
                "-e", "MINIO_ROOT_USER=" + ACCESS_KEY,
                "-e", "MINIO_ROOT_PASSWORD=" + SECRET_KEY,
                BRIDGE_IMAGE,
                "server", "/data", "--console-address", ":9001"));
        s3Port = resolveMappedPort("9000/tcp");
        consolePort = resolveMappedPort("9001/tcp");
        logger.info("Started disposable storage container {} with S3 endpoint {} and console http://127.0.0.1:{}",
                CONTAINER_NAME, endpoint(), consolePort);
        awaitHealth();
    }

    /**
     * Removes the disposable storage container after the contract slice completes.
     */
    @AfterAll
    void stopDisposableStorage() throws Exception {
        stopContainerIfPresent();
    }

    /**
     * Verifies the disposable container exposes the same MinIO-style liveness endpoint
     * used by local Compose health checks.
     */
    @Test
    void healthEndpoint_returnsOk() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint() + "/minio/health/live"))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    /**
     * Verifies the current single-part workflow: presigned PUT upload, metadata visibility,
     * and presigned GET readback.
     */
    @Test
    void presignedSinglePartUpload_roundTripsObjectBytesAndMetadata() throws Exception {
        String objectKey = "contract/" + UUID.randomUUID() + "/sample.txt";
        byte[] body = "phase-1-contract-upload".getBytes(StandardCharsets.UTF_8);

        HttpRequest uploadRequest = HttpRequest.newBuilder()
                .uri(URI.create(presignedPutUrl(objectKey)))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<Void> uploadResponse = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.discarding());

        assertThat(uploadResponse.statusCode()).isEqualTo(200);

        StatObjectResponse stat = minioClient().statObject(StatObjectArgs.builder()
                .bucket(BUCKET)
                .object(objectKey)
                .build());

        assertThat(stat.size()).isEqualTo(body.length);
        assertThat(stat.etag()).isNotBlank();

        HttpRequest readRequest = HttpRequest.newBuilder()
                .uri(URI.create(presignedGetUrl(objectKey)))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<byte[]> readResponse = httpClient.send(readRequest, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(readResponse.statusCode()).isEqualTo(200);
        assertThat(readResponse.body()).isEqualTo(body);
    }

    /**
     * Verifies cleanup removes objects so callers can distinguish present data from missing keys.
     */
    @Test
    void deleteObject_removesUploadedObject() throws Exception {
        String objectKey = "contract/" + UUID.randomUUID() + "/cleanup.txt";
        byte[] body = "delete-me".getBytes(StandardCharsets.UTF_8);

        HttpRequest uploadRequest = HttpRequest.newBuilder()
                .uri(URI.create(presignedPutUrl(objectKey)))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<Void> uploadResponse = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.discarding());
        assertThat(uploadResponse.statusCode()).isEqualTo(200);

        minioClient().removeObject(RemoveObjectArgs.builder()
                .bucket(BUCKET)
                .object(objectKey)
                .build());

        assertThatThrownBy(() -> minioClient().statObject(StatObjectArgs.builder()
                .bucket(BUCKET)
                .object(objectKey)
                .build()))
                .isInstanceOf(ErrorResponseException.class)
                .satisfies(error -> assertThat(((ErrorResponseException) error).errorResponse().code())
                        .isIn("NoSuchKey", "NoSuchObject"));
    }

    /**
     * Returns the S3 endpoint exposed by the disposable storage container.
     *
     * @return HTTP endpoint for S3-compatible requests
     */
    private String endpoint() {
        return "http://127.0.0.1:" + s3Port;
    }

    /**
     * Creates a bucket-scoped MinIO client configured for path-style access against the test container.
     *
     * @return initialized client with the contract bucket created
     */
    private MinioClient minioClient() throws Exception {
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint())
                .credentials(ACCESS_KEY, SECRET_KEY)
                .build();
        client.disableVirtualStyleEndpoint();
        ensureBucketExists(client);
        return client;
    }

    /**
     * Returns a presigned single-part upload URL for {@code objectKey}.
     *
     * @param objectKey destination key inside the contract bucket
     * @return presigned PUT URL
     */
    private String presignedPutUrl(String objectKey) throws Exception {
        return minioClient().getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Http.Method.PUT)
                .bucket(BUCKET)
                .object(objectKey)
                .expiry(15 * 60)
                .build());
    }

    /**
     * Returns a presigned read URL for {@code objectKey}.
     *
     * @param objectKey uploaded key to read back
     * @return presigned GET URL
     */
    private String presignedGetUrl(String objectKey) throws Exception {
        return minioClient().getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Http.Method.GET)
                .bucket(BUCKET)
                .object(objectKey)
                .expiry(15 * 60)
                .build());
    }

    /**
     * Creates the contract bucket on first use so each test can run independently.
     *
     * @param client client bound to the disposable storage container
     */
    private void ensureBucketExists(MinioClient client) throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    /**
     * Waits until the storage server reports the expected liveness endpoint.
     */
    private void awaitHealth() throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint() + "/minio/health/live"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            try {
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // Poll until the timeout window expires.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Timed out waiting for disposable storage health endpoint");
    }

    /**
     * Resolves the dynamically published host port for one container port.
     *
     * @param containerPort port identifier such as {@code 9000/tcp}
     * @return host port mapped by Docker
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
     * @return trimmed stdout
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
