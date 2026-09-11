package com.engine.pebble.domain.sstable;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.model.EntryType;
import com.engine.pebble.domain.model.ValueEntry;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * In-memory representation of an immutable 4KB SSTable data block.
 * Records include key, value, sequence number, operation type, and TTL expiration.
 */
public final class DataBlock implements Iterable<DataBlock.Record> {

    public record Record(
            ByteSlice key,
            ByteSlice value,
            long sequenceNumber,
            EntryType type,
            long expiresAtTimestamp
    ) {
        public Record(ByteSlice key, ByteSlice value, long sequenceNumber, EntryType type) {
            this(key, value, sequenceNumber, type, 0L);
        }

        public ValueEntry toValueEntry() {
            return new ValueEntry(value, sequenceNumber, type, expiresAtTimestamp);
        }

        public boolean isExpired(long now) {
            return expiresAtTimestamp > 0 && now >= expiresAtTimestamp;
        }
    }

    private final List<Record> records;

    public DataBlock(List<Record> records) {
        this.records = Collections.unmodifiableList(new ArrayList<>(records));
    }

    public List<Record> records() {
        return records;
    }

    public int size() {
        return records.size();
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    /**
     * Binary searches for the exact key inside this data block matching maxSequenceNumber.
     */
    public ValueEntry search(ByteSlice targetKey, long maxSequenceNumber, long now) {
        int low = 0;
        int high = records.size() - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            Record record = records.get(mid);
            int cmp = record.key().compareTo(targetKey);

            if (cmp < 0) {
                low = mid + 1;
            } else if (cmp > 0) {
                high = mid - 1;
            } else {
                // Key found, check sequence number visibility and expiration
                if (record.sequenceNumber() <= maxSequenceNumber) {
                    if (record.isExpired(now)) {
                        return null; // Expired key
                    }
                    return record.toValueEntry();
                }
                return null;
            }
        }

        return null;
    }

    public ValueEntry search(ByteSlice targetKey) {
        return search(targetKey, Long.MAX_VALUE, System.currentTimeMillis());
    }

    public byte[] serialize() {
        int totalBytes = 4; // number of records
        for (Record r : records) {
            int keyLen = r.key().length();
            int valLen = (r.value() != null) ? r.value().length() : 0;
            // 4 (keyLen) + key + 4 (valLen) + val + 8 (seq) + 8 (expiresAt) + 1 (type)
            totalBytes += (4 + keyLen + 4 + valLen + 8 + 8 + 1);
        }

        ByteBuffer buffer = ByteBuffer.allocate(totalBytes);
        buffer.putInt(records.size());
        for (Record r : records) {
            int keyLen = r.key().length();
            buffer.putInt(keyLen);
            buffer.put(r.key().rawArray(), r.key().offset(), keyLen);

            int valLen = (r.value() != null) ? r.value().length() : 0;
            buffer.putInt(valLen);
            if (valLen > 0) {
                buffer.put(r.value().rawArray(), r.value().offset(), valLen);
            }

            buffer.putLong(r.sequenceNumber());
            buffer.putLong(r.expiresAtTimestamp());
            buffer.put(r.type().code());
        }

        return buffer.array();
    }

    public static DataBlock deserialize(byte[] bytes) {
        return deserialize(ByteBuffer.wrap(bytes));
    }

    public static DataBlock deserialize(ByteBuffer buffer) {
        int recordCount = buffer.getInt();
        List<Record> list = new ArrayList<>(recordCount);

        for (int i = 0; i < recordCount; i++) {
            int keyLen = buffer.getInt();
            byte[] keyBytes = new byte[keyLen];
            buffer.get(keyBytes);

            int valLen = buffer.getInt();
            byte[] valBytes = (valLen > 0) ? new byte[valLen] : new byte[0];
            if (valLen > 0) {
                buffer.get(valBytes);
            }

            long seq = buffer.getLong();
            long expiresAt = buffer.getLong();
            byte typeCode = buffer.get();

            ByteSlice key = ByteSlice.of(keyBytes);
            ByteSlice val = ByteSlice.of(valBytes);
            EntryType type = EntryType.fromCode(typeCode);

            list.add(new Record(key, val, seq, type, expiresAt));
        }

        return new DataBlock(list);
    }

    @Override
    public Iterator<Record> iterator() {
        return records.iterator();
    }
}
