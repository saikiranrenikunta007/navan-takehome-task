package com.navan.task.storage;

/**
 * Storage abstraction for receipt file bytes.
 *
 * SOLID notes:
 * - Interface Segregation / Dependency Inversion: callers depend on this
 *   interface, never on a concrete S3 or filesystem class.
 * - Open/Closed: add a new backend by adding an adapter, without touching callers.
 * Adapters are selected at runtime by the {@code storage.provider} property.
 */
public interface StorageService {

    /**
     * Persist file bytes and return an opaque storage key used to fetch/delete later.
     *
     * @param objectName logical name (we use the receipt content hash) to make the key stable
     * @param contentType MIME type, may be null
     * @param content     raw file bytes
     * @return opaque storage key
     */
    String store(String objectName, String contentType, byte[] content);

    /** Fetch previously stored bytes by key. */
    byte[] retrieve(String storageKey);

    /** Delete bytes by key. No-op if already absent. */
    void delete(String storageKey);
}
