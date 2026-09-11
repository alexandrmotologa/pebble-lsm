package com.engine.pebble.domain.filter;

import com.engine.pebble.common.ByteSlice;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BloomFilterTest {

    @Test
    void testZeroFalseNegatives() {
        int count = 10_000;
        BloomFilter filter = new BloomFilter(count, 0.01);

        for (int i = 0; i < count; i++) {
            filter.add(ByteSlice.of("key:" + i));
        }

        // Bloom filters must never produce false negatives
        for (int i = 0; i < count; i++) {
            assertThat(filter.mightContain(ByteSlice.of("key:" + i))).isTrue();
        }
    }

    @Test
    void testLowFalsePositiveRate() {
        int count = 20_000;
        BloomFilter filter = new BloomFilter(count, 0.01);

        for (int i = 0; i < count; i++) {
            filter.add(ByteSlice.of("present:" + i));
        }

        int falsePositives = 0;
        int queryCount = 20_000;
        for (int i = 0; i < queryCount; i++) {
            if (filter.mightContain(ByteSlice.of("absent:" + i))) {
                falsePositives++;
            }
        }

        double fpRate = (double) falsePositives / queryCount;
        // False positive rate should be close to 1% (and definitely under 3%)
        assertThat(fpRate).isLessThan(0.03);
    }

    @Test
    void testSerializationRoundTrip() {
        BloomFilter original = new BloomFilter(1000, 0.01);
        for (int i = 0; i < 500; i++) {
            original.add(ByteSlice.of("item:" + i));
        }

        byte[] serialized = original.serialize();
        BloomFilter deserialized = BloomFilter.deserialize(serialized);

        assertThat(deserialized.numBits()).isEqualTo(original.numBits());
        assertThat(deserialized.k()).isEqualTo(original.k());

        for (int i = 0; i < 500; i++) {
            assertThat(deserialized.mightContain(ByteSlice.of("item:" + i))).isTrue();
        }
        assertThat(deserialized.mightContain(ByteSlice.of("missing:item"))).isFalse();
    }
}
