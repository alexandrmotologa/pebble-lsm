package com.engine.pebble.domain.batch;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.model.EntryType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Collection of atomic put and delete mutations applied together.
 * Guarantees ACID all-or-nothing execution across multiple keys.
 */
public class WriteBatch {

    private final List<BatchOperation> operations = new ArrayList<>();
    private long approximateBytes = 0;

    public WriteBatch put(ByteSlice key, ByteSlice value) {
        return put(key, value, 0L);
    }

    public WriteBatch put(ByteSlice key, ByteSlice value, long expiresAtTimestamp) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        operations.add(new BatchOperation(EntryType.PUT, key, value, expiresAtTimestamp));
        approximateBytes += (key.length() + value.length() + 32);
        return this;
    }

    public WriteBatch put(ByteSlice key, ByteSlice value, Duration ttl) {
        long expiresAt = (ttl != null && !ttl.isZero() && !ttl.isNegative())
                ? System.currentTimeMillis() + ttl.toMillis()
                : 0L;
        return put(key, value, expiresAt);
    }

    public WriteBatch put(byte[] key, byte[] value) {
        return put(ByteSlice.of(key), ByteSlice.of(value), 0L);
    }

    public WriteBatch putString(String key, String value) {
        return put(
                ByteSlice.of(key.getBytes(StandardCharsets.UTF_8)),
                ByteSlice.of(value.getBytes(StandardCharsets.UTF_8)),
                0L
        );
    }

    public WriteBatch delete(ByteSlice key) {
        Objects.requireNonNull(key, "key must not be null");
        operations.add(new BatchOperation(EntryType.DELETE, key, ByteSlice.EMPTY, 0L));
        approximateBytes += (key.length() + 32);
        return this;
    }

    public WriteBatch delete(byte[] key) {
        return delete(ByteSlice.of(key));
    }

    public WriteBatch deleteString(String key) {
        return delete(ByteSlice.of(key.getBytes(StandardCharsets.UTF_8)));
    }

    public List<BatchOperation> operations() {
        return Collections.unmodifiableList(operations);
    }

    public int size() {
        return operations.size();
    }

    public boolean isEmpty() {
        return operations.isEmpty();
    }

    public long approximateBytes() {
        return approximateBytes;
    }

    public void clear() {
        operations.clear();
        approximateBytes = 0;
    }
}
