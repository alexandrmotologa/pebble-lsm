package com.engine.pebble.domain.wal;

import java.io.IOException;

/**
 * Thrown when unrecoverable corruption is detected inside a Write-Ahead Log.
 */
public class CorruptedWalException extends IOException {
    private static final long serialVersionUID = 1L;

    public CorruptedWalException(String message) {
        super(message);
    }

    public CorruptedWalException(String message, Throwable cause) {
        super(message, cause);
    }
}
