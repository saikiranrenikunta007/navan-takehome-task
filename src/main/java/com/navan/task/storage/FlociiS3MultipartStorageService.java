package com.navan.task.storage;

import com.navan.task.common.NotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Flocii (S3-compatible) adapter using the S3 multipart upload protocol.
 *
 * Multipart upload is required per project guidelines. We create an upload,
 * push the bytes in parts of {@code partSizeBytes} (S3 requires >= 5 MiB per
 * part except the last), then complete it. On any failure we abort so no
 * partial object lingers.
 */
@Component
@ConditionalOnProperty(name = "storage.provider", havingValue = "s3")
public class FlociiS3MultipartStorageService implements StorageService {

    private final S3Client s3;
    private final S3StorageProperties props;

    public FlociiS3MultipartStorageService(S3Client s3, S3StorageProperties props) {
        this.s3 = s3;
        this.props = props;
    }

    @Override
    public String store(String objectName, String contentType, byte[] content) {
        String bucket = props.getBucket();
        CreateMultipartUploadResponse created = s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                        .bucket(bucket)
                        .key(objectName)
                        .contentType(contentType)
                        .build());
        String uploadId = created.uploadId();
        try {
            List<CompletedPart> completedParts = new ArrayList<>();
            int partNumber = 1;
            int offset = 0;
            int partSize = (int) props.getPartSizeBytes();
            while (offset < content.length) {
                int end = Math.min(offset + partSize, content.length);
                byte[] chunk = Arrays.copyOfRange(content, offset, end);
                UploadPartResponse part = s3.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(bucket)
                                .key(objectName)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .build(),
                        RequestBody.fromBytes(chunk));
                completedParts.add(CompletedPart.builder()
                        .partNumber(partNumber)
                        .eTag(part.eTag())
                        .build());
                offset = end;
                partNumber++;
            }
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(objectName)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder()
                            .parts(completedParts)
                            .build())
                    .build());
            return objectName;
        } catch (RuntimeException e) {
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(objectName).uploadId(uploadId));
            throw e;
        }
    }

    @Override
    public byte[] retrieve(String storageKey) {
        try {
            ResponseBytes<?> bytes = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(storageKey)
                    .build());
            return bytes.asByteArray();
        } catch (NoSuchKeyException e) {
            throw new NotFoundException("Stored object not found: " + storageKey);
        }
    }

    @Override
    public void delete(String storageKey) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(props.getBucket())
                .key(storageKey)
                .build());
    }
}
