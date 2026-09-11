package com.engine.pebble.domain.model;

/**
 * Point-in-time snapshot representing a consistent view of the database.
 * Any writes occurring after this snapshot's sequence number are hidden.
 */
public record Snapshot(long sequenceNumber, long createdAtTimestamp) implements AutoCloseable {

    public Snapshot(long sequenceNumber) {
        this(sequenceNumber, System.currentTimeMillis());
    }

    @Override
    public void close() {
        // No-op by default; can be used for tracking active snapshots
    }
}
