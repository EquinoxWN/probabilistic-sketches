package portfolio.sketches;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * MurmurHash3 x64 128-bit (Austin Appleby's public-domain reference, {@code MurmurHash3_x64_128}).
 *
 * <p>Every sketch in this library hashes its items with this function, so the same item always
 * lands in the same registers and counters. Strings are hashed as their UTF-8 bytes and a {@code
 * long} as its 8 little-endian bytes, which matches the reference C code byte for byte.
 */
public final class Murmur3 {
    /** Seed used by the sketches; changing it changes every register and counter position. */
    public static final int DEFAULT_SEED = 0;

    private static final long C1 = 0x87c37b91114253d5L;
    private static final long C2 = 0x4cf5ad432745937fL;
    private static final VarHandle LONG_LE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private Murmur3() {}

    /** The two 64-bit halves of one 128-bit hash. */
    public record Hash128(long h1, long h2) {}

    /** Hashes a byte range with the given seed. */
    public static Hash128 hash128(byte[] data, int offset, int length, int seed) {
        Objects.checkFromIndexSize(offset, length, data.length);
        long h1 = seed & 0xffffffffL;
        long h2 = h1;
        int blocks = length >>> 4;
        for (int i = 0; i < blocks; i++) {
            int at = offset + (i << 4);
            long k1 = (long) LONG_LE.get(data, at);
            long k2 = (long) LONG_LE.get(data, at + 8);
            h1 ^= mixK1(k1);
            h1 = Long.rotateLeft(h1, 27) + h2;
            h1 = h1 * 5 + 0x52dce729;
            h2 ^= mixK2(k2);
            h2 = Long.rotateLeft(h2, 31) + h1;
            h2 = h2 * 5 + 0x38495ab5;
        }
        int tail = offset + (blocks << 4);
        int rest = length & 15;
        long k1 = 0;
        long k2 = 0;
        for (int i = rest - 1; i >= 8; i--) {
            k2 ^= (data[tail + i] & 0xffL) << ((i - 8) << 3);
        }
        for (int i = Math.min(rest, 8) - 1; i >= 0; i--) {
            k1 ^= (data[tail + i] & 0xffL) << (i << 3);
        }
        if (rest > 8) {
            h2 ^= mixK2(k2);
        }
        if (rest > 0) {
            h1 ^= mixK1(k1);
        }
        return finish(h1, h2, length);
    }

    /** Hashes a whole byte array with the default seed. */
    public static Hash128 hash128(byte[] data) {
        return hash128(data, 0, data.length, DEFAULT_SEED);
    }

    /** Hashes a string's UTF-8 bytes with the default seed. */
    public static Hash128 hash128(CharSequence text) {
        return hash128(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Hashes a long as its 8 little-endian bytes, without allocating. */
    public static Hash128 hash128(long value, int seed) {
        long h1 = seed & 0xffffffffL;
        long h2 = h1;
        h1 ^= mixK1(value);
        return finish(h1, h2, 8);
    }

    /** Hashes a long with the default seed. */
    public static Hash128 hash128(long value) {
        return hash128(value, DEFAULT_SEED);
    }

    /** Scrambles the first 64-bit lane. */
    private static long mixK1(long k1) {
        return Long.rotateLeft(k1 * C1, 31) * C2;
    }

    /** Scrambles the second 64-bit lane. */
    private static long mixK2(long k2) {
        return Long.rotateLeft(k2 * C2, 33) * C1;
    }

    /** Final avalanche over both halves. */
    private static Hash128 finish(long h1, long h2, int length) {
        h1 ^= length;
        h2 ^= length;
        h1 += h2;
        h2 += h1;
        h1 = fmix64(h1);
        h2 = fmix64(h2);
        h1 += h2;
        h2 += h1;
        return new Hash128(h1, h2);
    }

    /** MurmurHash3 64-bit finalizer. */
    static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }
}
