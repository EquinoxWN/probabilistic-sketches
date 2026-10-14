package portfolio.sketches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class Murmur3Test {
    /** Little-endian bytes of a long. */
    static byte[] le(long v) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array();
    }

    @Test
    void emptyInputWithSeedZeroHashesToZero() {
        assertEquals(new Murmur3.Hash128(0, 0), Murmur3.hash128(new byte[0]));
    }

    @Test
    void matchesTheReferenceImplementationForHello() {
        // MurmurHash3_x64_128("hello", seed 0) = cbd8a7b341bd9b02 5b1e906a48ae1d19
        assertEquals(new Murmur3.Hash128(0xcbd8a7b341bd9b02L, 0x5b1e906a48ae1d19L), Murmur3.hash128("hello"));
    }

    @Test
    void matchesTheReferenceImplementationForALongerSentence() {
        // 43 bytes: two full 16-byte blocks and an 11-byte tail.
        assertEquals(
                new Murmur3.Hash128(0xe34bbc7bbc071b6cL, 0x7a433ca9c49a9347L),
                Murmur3.hash128("The quick brown fox jumps over the lazy dog"));
    }

    @Test
    void longHashEqualsTheHashOfItsLittleEndianBytes() {
        SplittableRandom r = new SplittableRandom(1);
        for (int i = 0; i < 10_000; i++) {
            long v = r.nextLong();
            assertEquals(Murmur3.hash128(le(v)), Murmur3.hash128(v));
        }
        assertEquals(Murmur3.hash128(le(0)), Murmur3.hash128(0L));
    }

    @Test
    void everyInputByteChangesTheHashForEveryLength() {
        SplittableRandom r = new SplittableRandom(2);
        for (int len = 1; len <= 48; len++) {
            byte[] data = new byte[len];
            r.nextBytes(data);
            Murmur3.Hash128 base = Murmur3.hash128(data);
            for (int i = 0; i < len; i++) {
                byte[] flipped = data.clone();
                flipped[i] ^= 1;
                assertNotEquals(base, Murmur3.hash128(flipped), "length " + len + ", byte " + i);
            }
        }
    }

    @Test
    void seedAndLengthChangeTheHash() {
        byte[] data = "visitor".getBytes(StandardCharsets.UTF_8);
        assertNotEquals(Murmur3.hash128(data, 0, data.length, 0), Murmur3.hash128(data, 0, data.length, 1));
        assertNotEquals(Murmur3.hash128(new byte[0]), Murmur3.hash128(new byte[1]));
    }

    @Test
    void offsetAndLengthHashOnlyThatRange() {
        byte[] data = "xxhello-worldyy".getBytes(StandardCharsets.UTF_8);
        byte[] middle = Arrays.copyOfRange(data, 2, 13);
        assertEquals(Murmur3.hash128(middle), Murmur3.hash128(data, 2, 11, Murmur3.DEFAULT_SEED));
        assertThrows(IndexOutOfBoundsException.class, () -> Murmur3.hash128(data, 10, 10, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> Murmur3.hash128(data, -1, 2, 0));
    }

    @Test
    void topBitsOfSequentialKeysAreUniform() {
        // Chi-square over 1,024 buckets of the top 10 bits; df = 1023, so the mean is 1023 and the
        // standard deviation about 45. Both halves are checked because Count-Min uses both.
        int buckets = 1024;
        int n = 1_000_000;
        long[] c1 = new long[buckets];
        long[] c2 = new long[buckets];
        for (long i = 0; i < n; i++) {
            Murmur3.Hash128 h = Murmur3.hash128(i);
            c1[(int) (h.h1() >>> 54)]++;
            c2[(int) (h.h2() >>> 54)]++;
        }
        assertTrue(chiSquare(c1, n) < 1023 + 5 * 45, "h1 chi-square " + chiSquare(c1, n));
        assertTrue(chiSquare(c2, n) < 1023 + 5 * 45, "h2 chi-square " + chiSquare(c2, n));
    }

    /** Pearson chi-square statistic against a uniform distribution. */
    private static double chiSquare(long[] counts, long n) {
        double expected = (double) n / counts.length;
        double sum = 0;
        for (long c : counts) {
            sum += (c - expected) * (c - expected) / expected;
        }
        return sum;
    }
}
