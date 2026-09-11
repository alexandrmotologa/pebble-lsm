package com.engine.pebble.domain.wal;

import java.util.List;

/**
 * Result of recovering records from a Write-Ahead Log.
 */
public record WalRecoveryResult(
        List<WalRecord> records,
        long maxSequenceNumber,
        long validByteOffset,
        boolean truncatedAtEof
) {}
