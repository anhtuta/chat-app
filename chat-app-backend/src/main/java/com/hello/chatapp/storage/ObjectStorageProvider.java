package com.hello.chatapp.storage;

/**
 * Strategy interface for one object-storage provider implementation.
 * Used by media upload sessions to create presigned URLs and manage multipart uploads
 * without coupling callers to MinIO/S3 client APIs.
 */
public interface ObjectStorageProvider {

    /**
     * Returns static connection metadata for this provider (type, bucket, region, endpoint, path-style).
     */
    ObjectStorageProviderDescriptor describe();

    /**
     * Returns the provider type used for registry lookup and persistence on {@code media_uploads}.
     */
    ObjectStorageProviderType getType();

    /**
     * Builds a time-limited presigned PUT URL for a single-part object upload to {@code objectKey}.
     *
     * @param objectKey destination object key in the provider bucket
     * @return URL the client can PUT the full object bytes to
     */
    String buildUploadUrl(String objectKey);

    /**
     * Starts a multipart upload for {@code objectKey} and returns the provider upload id.
     * Callers should persist that id and pass it to later part-url / complete / abort calls.
     *
     * @param objectKey destination object key in the provider bucket
     * @return provider multipart upload id
     */
    String createMultipartUpload(String objectKey);

    /**
     * Builds a time-limited presigned PUT URL for one multipart part.
     * The URL embeds {@code multipartUploadId} and {@code partNumber} (SigV4 for MinIO/S3).
     *
     * @param objectKey destination object key in the provider bucket
     * @param multipartUploadId id returned by {@link #createMultipartUpload(String)}
     * @param partNumber 1-based part number
     * @return URL the client can PUT that part's bytes to
     */
    String buildMultipartUploadPartUrl(String objectKey, String multipartUploadId, int partNumber);

    /**
     * Assembles previously uploaded parts into the final object under {@code objectKey}.
     *
     * @param objectKey destination object key in the provider bucket
     * @param multipartUploadId id returned by {@link #createMultipartUpload(String)}
     * @param parts ordered part number + ETag pairs from successful part PUTs
     */
    void completeMultipartUpload(String objectKey, String multipartUploadId, java.util.List<ObjectStorageCompletedPart> parts);

    /**
     * Cancels an in-progress multipart upload and discards any uploaded parts for that upload id.
     *
     * @param objectKey destination object key in the provider bucket
     * @param multipartUploadId id returned by {@link #createMultipartUpload(String)}
     */
    void abortMultipartUpload(String objectKey, String multipartUploadId);

    /**
     * Builds a time-limited presigned GET URL for reading {@code objectKey}.
     *
     * @param objectKey object key in the provider bucket
     * @return URL clients can use to download or stream the object
     */
    String buildReadUrl(String objectKey);

    /**
     * Returns whether an object currently exists at {@code objectKey}.
     *
     * @param objectKey object key in the provider bucket
     * @return {@code true} if the object exists; {@code false} if missing
     */
    boolean objectExists(String objectKey);
}
