package com.hello.chatapp.storage;

import com.hello.chatapp.config.MediaStorageProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.BucketLocationConstraint;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * S3-compatible provider implementation backed by AWS SDK v2.
 */
@Component
public class S3ObjectStorageProvider implements ObjectStorageProvider {

    private static final Logger logger = LoggerFactory.getLogger(S3ObjectStorageProvider.class);

    private final MediaStorageProperties mediaStorageProperties;

    public S3ObjectStorageProvider(MediaStorageProperties mediaStorageProperties) {
        this.mediaStorageProperties = mediaStorageProperties;
    }

    /**
     * Ensures the configured bucket exists when S3 is the active provider.
     */
    @PostConstruct
    public void ensureBucketExistsWhenActive() {
        if (mediaStorageProperties.getProvider() != ObjectStorageProviderType.S3) {
            return;
        }

        MediaStorageProperties.S3 s3 = mediaStorageProperties.getS3();
        try (S3Client s3Client = buildS3Client()) {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(s3.getBucket()).build());
        } catch (NoSuchBucketException e) {
            createBucket(s3);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                createBucket(s3);
                return;
            }
            throw new IllegalStateException("Failed to verify S3 bucket existence", e);
        } catch (SdkException e) {
            throw new IllegalStateException("Failed to verify S3 bucket existence", e);
        }
    }

    /**
     * Returns resolved connection metadata for the configured S3-compatible provider.
     *
     * @return storage provider descriptor used by the application
     */
    @Override
    public ObjectStorageProviderDescriptor describe() {
        MediaStorageProperties.S3 s3 = mediaStorageProperties.getS3();
        return new ObjectStorageProviderDescriptor(
                getType(),
                s3.getBucket(),
                s3.getRegion(),
                s3.getEndpoint(),
                s3.isPathStyleAccess(),
                true);
    }

    /**
     * Returns the provider type for AWS S3 and S3-compatible endpoints.
     *
     * @return {@link ObjectStorageProviderType#S3}
     */
    @Override
    public ObjectStorageProviderType getType() {
        return ObjectStorageProviderType.S3;
    }

    /**
     * Builds a presigned single-part upload URL for one object key.
     *
     * @param objectKey destination object key
     * @return presigned PUT URL
     */
    @Override
    public String buildUploadUrl(String objectKey) {
        MediaStorageProperties.S3 s3 = mediaStorageProperties.getS3();
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(s3.getBucket())
                .key(objectKey)
                .build();
        try (S3Presigner presigner = buildPresigner()) {
            return presigner.presignPutObject(PutObjectPresignRequest.builder()
                            .signatureDuration(Duration.ofMinutes(mediaStorageProperties.getUploadUrlTtlMinutes()))
                            .putObjectRequest(request)
                            .build())
                    .url()
                    .toString();
        }
    }

    /**
     * Starts one multipart upload and returns the provider upload id.
     *
     * @param objectKey destination object key
     * @return multipart upload id
     */
    @Override
    public String createMultipartUpload(String objectKey) {
        MediaStorageProperties.S3 s3 = mediaStorageProperties.getS3();
        try (S3Client s3Client = buildS3Client()) {
            return s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                            .bucket(s3.getBucket())
                            .key(objectKey)
                            .build())
                    .uploadId();
        }
    }

    /**
     * Builds a presigned upload-part URL for one multipart part.
     *
     * @param objectKey destination object key
     * @param multipartUploadId provider upload id
     * @param partNumber one-based multipart part number
     * @return presigned upload-part URL
     */
    @Override
    public String buildMultipartUploadPartUrl(String objectKey, String multipartUploadId, int partNumber) {
        MediaStorageProperties.S3 s3 = mediaStorageProperties.getS3();
        UploadPartRequest request = UploadPartRequest.builder()
                .bucket(s3.getBucket())
                .key(objectKey)
                .uploadId(multipartUploadId)
                .partNumber(partNumber)
                .build();
        try (S3Presigner presigner = buildPresigner()) {
            return presigner.presignUploadPart(UploadPartPresignRequest.builder()
                            .signatureDuration(Duration.ofMinutes(mediaStorageProperties.getUploadUrlTtlMinutes()))
                            .uploadPartRequest(request)
                            .build())
                    .url()
                    .toString();
        }
    }

    /**
     * Completes a multipart upload using ordered part metadata captured during uploads.
     *
     * @param objectKey destination object key
     * @param multipartUploadId provider upload id
     * @param parts ordered part metadata
     */
    @Override
    public void completeMultipartUpload(
            String objectKey,
            String multipartUploadId,
            List<ObjectStorageCompletedPart> parts) {
        MediaStorageProperties.S3 s3 = mediaStorageProperties.getS3();
        List<CompletedPart> completedParts = parts.stream()
                .sorted(Comparator.comparingInt(ObjectStorageCompletedPart::partNumber))
                .map(part -> CompletedPart.builder()
                        .partNumber(part.partNumber())
                        .eTag(normalizeEtag(part.etag()))
                        .build())
                .toList();
        CompletedMultipartUpload completedUpload = CompletedMultipartUpload.builder()
                .parts(completedParts)
                .build();
        try (S3Client s3Client = buildS3Client()) {
            s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(s3.getBucket())
                    .key(objectKey)
                    .uploadId(multipartUploadId)
                    .multipartUpload(completedUpload)
                    .build());
        }
    }

    /**
     * Aborts a multipart upload so uploaded parts are discarded.
     *
     * @param objectKey destination object key
     * @param multipartUploadId provider upload id
     */
    @Override
    public void abortMultipartUpload(String objectKey, String multipartUploadId) {
        MediaStorageProperties.S3 s3 = mediaStorageProperties.getS3();
        try (S3Client s3Client = buildS3Client()) {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(s3.getBucket())
                    .key(objectKey)
                    .uploadId(multipartUploadId)
                    .build());
        }
    }

    /**
     * Builds a presigned read URL for one object key.
     *
     * @param objectKey object key to download or stream
     * @return presigned GET URL
     */
    @Override
    public String buildReadUrl(String objectKey) {
        MediaStorageProperties.S3 s3 = mediaStorageProperties.getS3();
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(s3.getBucket())
                .key(objectKey)
                .build();
        try (S3Presigner presigner = buildPresigner()) {
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(Duration.ofMinutes(mediaStorageProperties.getReadUrlTtlMinutes()))
                            .getObjectRequest(request)
                            .build())
                    .url()
                    .toString();
        }
    }

    /**
     * Returns whether the configured bucket currently contains the object key.
     *
     * @param objectKey object key to inspect
     * @return {@code true} when the object exists
     */
    @Override
    public boolean objectExists(String objectKey) {
        MediaStorageProperties.S3 s3 = mediaStorageProperties.getS3();
        try (S3Client s3Client = buildS3Client()) {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(s3.getBucket())
                    .key(objectKey)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new IllegalStateException("Failed to verify object existence in S3", e);
        }
    }

    /**
     * Deletes one object from the configured bucket.
     *
     * @param objectKey object key that should be removed
     */
    @Override
    public void deleteObject(String objectKey) {
        MediaStorageProperties.S3 s3 = mediaStorageProperties.getS3();
        try (S3Client s3Client = buildS3Client()) {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(s3.getBucket())
                    .key(objectKey)
                    .build());
        }
    }

    /**
     * Creates the configured bucket after a missing-bucket check when S3 is active.
     *
     * @param s3 resolved S3 configuration
     */
    private void createBucket(MediaStorageProperties.S3 s3) {
        try (S3Client s3Client = buildS3Client()) {
            CreateBucketRequest.Builder request = CreateBucketRequest.builder()
                    .bucket(s3.getBucket());
            if ((s3.getEndpoint() == null || s3.getEndpoint().isBlank()) && !"us-east-1".equals(s3.getRegion())) {
                request.createBucketConfiguration(CreateBucketConfiguration.builder()
                        .locationConstraint(BucketLocationConstraint.fromValue(s3.getRegion()))
                        .build());
            }
            s3Client.createBucket(request.build());
            logger.info("Created S3 bucket {}", s3.getBucket());
        } catch (S3Exception e) {
            String errorCode = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
            if ("BucketAlreadyOwnedByYou".equals(errorCode) || "BucketAlreadyExists".equals(errorCode)) {
                return;
            }
            throw new IllegalStateException("Failed to create S3 bucket", e);
        }
    }

    /**
     * Builds a synchronous S3 client using the configured endpoint, region, credentials, and path style.
     *
     * @return configured S3 client
     */
    private S3Client buildS3Client() {
        var builder = S3Client.builder()
                .region(resolveRegion())
                .credentialsProvider(resolveCredentialsProvider())
                .serviceConfiguration(resolveS3Configuration());
        URI endpoint = resolveEndpointOverride();
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        return builder.build();
    }

    /**
     * Builds an S3 presigner with the same endpoint and signing settings as the synchronous client.
     *
     * @return configured S3 presigner
     */
    private S3Presigner buildPresigner() {
        var builder = S3Presigner.builder()
                .region(resolveRegion())
                .credentialsProvider(resolveCredentialsProvider())
                .serviceConfiguration(resolveS3Configuration());
        URI endpoint = resolveEndpointOverride();
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        return builder.build();
    }

    /**
     * Returns the configured S3 region.
     *
     * @return AWS SDK region object
     */
    private Region resolveRegion() {
        return Region.of(mediaStorageProperties.getS3().getRegion());
    }

    /**
     * Returns the configured static access-key credential provider.
     *
     * @return static credential provider
     */
    private StaticCredentialsProvider resolveCredentialsProvider() {
        MediaStorageProperties.S3 s3 = mediaStorageProperties.getS3();
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                s3.getAccessKey(),
                s3.getSecretKey()));
    }

    /**
     * Returns the endpoint override when the configuration targets a custom S3-compatible service.
     *
     * @return custom endpoint URI or {@code null} for the AWS default endpoint resolution
     */
    private URI resolveEndpointOverride() {
        String endpoint = mediaStorageProperties.getS3().getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        return URI.create(endpoint);
    }

    /**
     * Returns the S3-specific client configuration, including path-style access when required.
     *
     * @return configured AWS SDK S3 settings
     */
    private S3Configuration resolveS3Configuration() {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(mediaStorageProperties.getS3().isPathStyleAccess())
                .build();
    }

    /**
     * Removes surrounding double quotes from one multipart ETag when callers captured the raw header value.
     *
     * @param etag raw provider ETag value
     * @return normalized ETag without surrounding quotes
     */
    private String normalizeEtag(String etag) {
        if (etag == null) {
            return null;
        }
        if (etag.length() >= 2 && etag.startsWith("\"") && etag.endsWith("\"")) {
            return etag.substring(1, etag.length() - 1);
        }
        return etag;
    }
}
