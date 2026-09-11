package com.engine.pebble.domain.sstable;

/**
 * Handle representing the file offset and byte length of a data block.
 */
public record BlockHandle(long offset, int size) {}
