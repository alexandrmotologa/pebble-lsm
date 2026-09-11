package com.engine.pebble.domain.wal;

/**
 * Storage synchronization policy for Write-Ahead Log appends.
 */
public enum SyncPolicy {
    /**
     * Forces disk sync (fsync) after every write operation, guaranteeing zero data loss.
     */
    ALWAYS,

    /**
     * Relies on OS page cache flushing, providing higher throughput.
     */
    BUFFERED
}
