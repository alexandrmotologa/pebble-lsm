package com.engine.pebble.domain.model;

/**
 * Entry operation type stored in Write-Ahead Log and SSTable blocks.
 */
public enum EntryType {
    PUT((byte) 0x01),
    DELETE((byte) 0x02); // Tombstone indicating key deletion

    private final byte code;

    EntryType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static EntryType fromCode(byte code) {
        return switch (code) {
            case 0x01 -> PUT;
            case 0x02 -> DELETE;
            default -> throw new IllegalArgumentException("Unknown EntryType code: " + code);
        };
    }
}
