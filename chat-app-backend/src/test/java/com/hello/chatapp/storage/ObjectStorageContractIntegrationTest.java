package com.hello.chatapp.storage;

import io.minio.AbortMultipartUploadArgs;
import io.minio.BucketExistsArgs;
import io.minio.CompleteMultipartUploadArgs;
import io.minio.CreateMultipartUploadArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.MinioAsyncClient;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketCorsArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.CORSConfiguration;
import io.minio.messages.Part;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the first executable object-storage contract slice against a disposable
 * S3-compatible container.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObjectStorageContractIntegrationTest {

    private static final String ACCESS_KEY = "rustfslocal";
    private static final String SECRET_KEY = "rustfslocalsecret";
    private static final String BUCKET = "chat-media";
    private static final String CONTAINER_NAME = "chat-app-storage-contract-test";
    private static final String STORAGE_IMAGE =
            "rustfs/rustfs@sha256:1803faef57627e2d9c2e7d89d655d712ddded5389040054987163043fecb6a3c";
    private static final Logger logger = LoggerFactory.getLogger(ObjectStorageContractIntegrationTest.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private int s3Port;
    private int consolePort;

    /**
     * Starts a disposable S3-compatible container through the local Docker CLI and
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
                "-e", "RUSTFS_ACCESS_KEY=" + ACCESS_KEY,
                "-e", "RUSTFS_SECRET_KEY=" + SECRET_KEY,
                "-e", "RUSTFS_ADDRESS=:9000",
                "-e", "RUSTFS_CONSOLE_ADDRESS=:9001",
                "-e", "RUSTFS_CONSOLE_ENABLE=true",
                STORAGE_IMAGE,
                "/data"));
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
     * Verifies the disposable container exposes the same liveness endpoint
     * used by local Compose health checks.
     */
    @Test
    void healthEndpoint_returnsOk() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint() + "/health"))
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
     * Verifies presigned reads honor HTTP range requests used by video-like partial playback.
     */
    @Test
    void presignedGet_honorsRangeRequests() throws Exception {
        String objectKey = "contract/" + UUID.randomUUID() + "/range.bin";
        byte[] body = repeatedAlphabetBytes(8192);
        int start = 1024;
        int endInclusive = 4095;
        byte[] expected = new byte[endInclusive - start + 1];
        System.arraycopy(body, start, expected, 0, expected.length);

        HttpRequest uploadRequest = HttpRequest.newBuilder()
                .uri(URI.create(presignedPutUrl(objectKey)))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<Void> uploadResponse = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.discarding());
        assertThat(uploadResponse.statusCode()).isEqualTo(200);

        HttpRequest rangeRequest = HttpRequest.newBuilder()
                .uri(URI.create(presignedGetUrl(objectKey)))
                .header("Range", "bytes=" + start + "-" + endInclusive)
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<byte[]> rangeResponse = httpClient.send(rangeRequest, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(rangeResponse.statusCode()).isEqualTo(206);
        assertThat(rangeResponse.headers().firstValue("Content-Range"))
                .hasValue("bytes " + start + "-" + endInclusive + "/" + body.length);
        assertThat(rangeResponse.body()).isEqualTo(expected);
    }

    /**
     * Verifies browser-style cross-origin requests succeed only after bucket CORS rules are configured.
     */
    @Test
    @Disabled("Current Phase 0 bridge image returns 501 NotImplemented for bucket CORS configuration; re-enable after local storage moves to an engine with CORS support.")
    void browserOriginCors_allowsPreflightPutAndCrossOriginGet() throws Exception {
        String origin = "http://localhost:3000";
        String objectKey = "contract/" + UUID.randomUUID() + "/cors.txt";
        byte[] body = "cors-contract-body".getBytes(StandardCharsets.UTF_8);

        ensureBucketCorsConfigured(origin);

        String uploadUrl = presignedPutUrl(objectKey);
        HttpRequest preflightRequest = HttpRequest.newBuilder()
                .uri(URI.create(uploadUrl))
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<Void> preflightResponse = httpClient.send(preflightRequest, HttpResponse.BodyHandlers.discarding());

        assertThat(preflightResponse.statusCode()).isIn(200, 204);
        assertThat(preflightResponse.headers().firstValue("Access-Control-Allow-Origin")).hasValue(origin);
        assertThat(preflightResponse.headers().firstValue("Access-Control-Allow-Methods"))
                .hasValueSatisfying(value -> assertThat(value).contains("PUT"));
        assertThat(preflightResponse.headers().firstValue("Access-Control-Allow-Headers"))
                .hasValueSatisfying(value -> assertThat(value.toLowerCase()).contains("content-type"));

        HttpRequest uploadRequest = HttpRequest.newBuilder()
                .uri(URI.create(uploadUrl))
                .header("Origin", origin)
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<Void> uploadResponse = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.discarding());

        assertThat(uploadResponse.statusCode()).isEqualTo(200);
        assertThat(uploadResponse.headers().firstValue("Access-Control-Allow-Origin")).hasValue(origin);

        HttpRequest readRequest = HttpRequest.newBuilder()
                .uri(URI.create(presignedGetUrl(objectKey)))
                .header("Origin", origin)
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<byte[]> readResponse = httpClient.send(readRequest, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(readResponse.statusCode()).isEqualTo(200);
        assertThat(readResponse.headers().firstValue("Access-Control-Allow-Origin")).hasValue(origin);
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
     * Verifies multipart upload create, per-part presigned PUT, completion, and final readback.
     */
    @Test
    void multipartUpload_completeRoundTripsObjectBytes() throws Exception {
        String objectKey = "contract/" + UUID.randomUUID() + "/multipart.bin";
        byte[] partOne = repeatedBytes(5 * 1024 * 1024, (byte) 'A');
        byte[] partTwo = repeatedBytes(1024 * 1024, (byte) 'B');
        byte[] expected = new byte[partOne.length + partTwo.length];
        System.arraycopy(partOne, 0, expected, 0, partOne.length);
        System.arraycopy(partTwo, 0, expected, partOne.length, partTwo.length);

        String uploadId = startMultipartUpload(objectKey);

        HttpResponse<Void> partOneResponse = uploadMultipartPart(objectKey, uploadId, 1, partOne);
        HttpResponse<Void> partTwoResponse = uploadMultipartPart(objectKey, uploadId, 2, partTwo);

        assertThat(partOneResponse.statusCode()).isEqualTo(200);
        assertThat(partTwoResponse.statusCode()).isEqualTo(200);

        completeMultipartUpload(
                objectKey,
                uploadId,
                new Part(1, responseEtag(partOneResponse)),
                new Part(2, responseEtag(partTwoResponse)));

        StatObjectResponse stat = minioClient().statObject(StatObjectArgs.builder()
                .bucket(BUCKET)
                .object(objectKey)
                .build());

        assertThat(stat.size()).isEqualTo(expected.length);
        assertThat(stat.etag()).contains("-");

        HttpRequest readRequest = HttpRequest.newBuilder()
                .uri(URI.create(presignedGetUrl(objectKey)))
                .GET()
                .timeout(Duration.ofSeconds(20))
                .build();

        HttpResponse<byte[]> readResponse = httpClient.send(readRequest, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(readResponse.statusCode()).isEqualTo(200);
        assertThat(readResponse.body()).isEqualTo(expected);
    }

    /**
     * Verifies aborting a multipart upload leaves no finalized object behind.
     */
    @Test
    void multipartUpload_abortLeavesNoFinalObject() throws Exception {
        String objectKey = "contract/" + UUID.randomUUID() + "/aborted.bin";
        byte[] partOne = repeatedBytes(5 * 1024 * 1024, (byte) 'C');

        String uploadId = startMultipartUpload(objectKey);
        HttpResponse<Void> uploadResponse = uploadMultipartPart(objectKey, uploadId, 1, partOne);
        assertThat(uploadResponse.statusCode()).isEqualTo(200);

        abortMultipartUpload(objectKey, uploadId);

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
     * Starts a multipart upload and returns the provider upload id.
     *
     * @param objectKey destination key for the final object
     * @return provider-issued multipart upload id
     */
    private String startMultipartUpload(String objectKey) throws Exception {
        ensureBucketExists(minioClient());
        return minioAsyncClient().createMultipartUpload(CreateMultipartUploadArgs.builder()
                .bucket(BUCKET)
                .object(objectKey)
                .build())
                .join()
                .result()
                .uploadId();
    }

    /**
     * Uploads one multipart part through a presigned URL.
     *
     * @param objectKey multipart object key
     * @param uploadId provider multipart upload id
     * @param partNumber one-based multipart part number
     * @param body bytes to upload for this part
     * @return HTTP response from the upload-part request
     */
    private HttpResponse<Void> uploadMultipartPart(String objectKey, String uploadId, int partNumber, byte[] body)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(presignedMultipartPartUrl(objectKey, uploadId, partNumber)))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(20))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    /**
     * Builds a presigned upload-part URL for one multipart part.
     *
     * @param objectKey multipart object key
     * @param uploadId provider multipart upload id
     * @param partNumber one-based multipart part number
     * @return presigned part PUT URL
     */
    private String presignedMultipartPartUrl(String objectKey, String uploadId, int partNumber) throws Exception {
        return minioClient().getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Http.Method.PUT)
                .bucket(BUCKET)
                .object(objectKey)
                .expiry(15 * 60)
                .extraQueryParams(Map.of(
                        "uploadId", uploadId,
                        "partNumber", String.valueOf(partNumber)))
                .build());
    }

    /**
     * Completes a multipart upload using the uploaded part numbers and ETags.
     *
     * @param objectKey multipart object key
     * @param uploadId provider multipart upload id
     * @param parts ordered parts returned by successful upload-part calls
     */
    private void completeMultipartUpload(String objectKey, String uploadId, Part... parts) throws Exception {
        minioAsyncClient().completeMultipartUpload(CompleteMultipartUploadArgs.builder()
                .bucket(BUCKET)
                .object(objectKey)
                .uploadId(uploadId)
                .parts(parts)
                .build())
                .join();
    }

    /**
     * Aborts a multipart upload so uploaded parts do not become a finalized object.
     *
     * @param objectKey multipart object key
     * @param uploadId provider multipart upload id
     */
    private void abortMultipartUpload(String objectKey, String uploadId) throws Exception {
        minioAsyncClient().abortMultipartUpload(AbortMultipartUploadArgs.builder()
                .bucket(BUCKET)
                .object(objectKey)
                .uploadId(uploadId)
                .build())
                .join();
    }

    /**
     * Returns the ETag header from one successful multipart part response.
     *
     * @param response upload-part HTTP response
     * @return provider-generated ETag header value
     */
    private String responseEtag(HttpResponse<Void> response) {
        return response.headers()
                .firstValue("etag")
                .orElseThrow(() -> new IllegalStateException("Multipart upload response did not include ETag"));
    }

    /**
     * Builds a byte array filled with one repeated value.
     *
     * @param length desired byte count
     * @param value byte value repeated through the array
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
     * Builds deterministic test content with repeating ASCII letters so range assertions stay readable.
     *
     * @param length desired byte count
     * @return byte array cycling through {@code a-z}
     */
    private byte[] repeatedAlphabetBytes(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ('a' + (i % 26));
        }
        return bytes;
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
     * Configures a minimal bucket CORS policy for browser upload and readback checks.
     *
     * @param origin browser origin allowed to access presigned object URLs
     */
    private void ensureBucketCorsConfigured(String origin) throws Exception {
        ensureBucketExists(minioClient());
        minioClient().setBucketCors(SetBucketCorsArgs.builder()
                .bucket(BUCKET)
                .config(new CORSConfiguration(List.of(
                        new CORSConfiguration.CORSRule(
                                List.of("content-type"),
                                List.of("GET", "PUT", "HEAD"),
                                List.of(origin),
                                List.of("Accept-Ranges", "Content-Length", "Content-Range", "ETag"),
                                "contract-cors",
                                3600))))
                .build());
    }

    /**
     * Creates an async MinIO client for multipart lifecycle operations against the disposable container.
     *
     * @return initialized async client
     */
    private MinioAsyncClient minioAsyncClient() {
        return MinioAsyncClient.builder()
                .endpoint(endpoint())
                .credentials(ACCESS_KEY, SECRET_KEY)
                .build();
    }

    /**
     * Waits until the storage server reports the expected liveness endpoint.
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
