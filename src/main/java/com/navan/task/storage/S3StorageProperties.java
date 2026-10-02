package com.navan.task.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the Flocii S3-compatible object store.
 * Bound from {@code storage.s3.*}. Secrets come from env vars, never committed.
 */
@ConfigurationProperties(prefix = "storage.s3")
public class S3StorageProperties {

    /** Flocii S3 endpoint, e.g. https://s3.flocii.com */
    private String endpoint;
    private String region = "us-east-1";
    private String bucket;
    private String accessKey;
    private String secretKey;
    /** Multipart part size in bytes. S3 requires >= 5 MiB except the final part. */
    private long partSizeBytes = 5L * 1024 * 1024;
    /** Use path-style access (most S3-compatible providers need this). */
    private boolean pathStyleAccess = true;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public long getPartSizeBytes() {
        return partSizeBytes;
    }

    public void setPartSizeBytes(long partSizeBytes) {
        this.partSizeBytes = partSizeBytes;
    }

    public boolean isPathStyleAccess() {
        return pathStyleAccess;
    }

    public void setPathStyleAccess(boolean pathStyleAccess) {
        this.pathStyleAccess = pathStyleAccess;
    }
}
