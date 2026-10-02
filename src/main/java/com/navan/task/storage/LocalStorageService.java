package com.navan.task.storage;

import com.navan.task.common.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Local filesystem adapter. Default provider so the test suite and a plain
 * {@code ./mvnw spring-boot:run} work without any cloud credentials.
 */
@Component
@ConditionalOnProperty(name = "storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private final Path root;

    public LocalStorageService(@Value("${storage.local.dir:./data/receipts}") String dir) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage dir " + root, e);
        }
    }

    @Override
    public String store(String objectName, String contentType, byte[] content) {
        Path target = root.resolve(objectName);
        try {
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store " + objectName, e);
        }
        return objectName;
    }

    @Override
    public byte[] retrieve(String storageKey) {
        Path target = root.resolve(storageKey);
        if (!Files.exists(target)) {
            throw new NotFoundException("Stored object not found: " + storageKey);
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(root.resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + storageKey, e);
        }
    }
}
