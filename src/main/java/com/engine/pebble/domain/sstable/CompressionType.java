package com.engine.pebble.domain.sstable;

/**
 * Supported compression algorithms for SSTable 4KB data blocks.
 */
public enum CompressionType {
    NONE((byte) 0),
    LZ4((byte) 1);

    private final byte code;

    CompressionType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static CompressionType fromCode(byte code) {
        return switch (code) {
            case 0 -> NONE;
            case 1 -> LZ4;
            default -> throw new IllegalArgumentException("Unknown CompressionType code: " + code);
        };
    }
}
