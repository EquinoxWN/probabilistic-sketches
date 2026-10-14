package portfolio.sketches;

import java.util.Arrays;

/**
 * HyperLogLog distinct-count sketch with a sparse mode for small sets.
 *
 * <p>Each item is hashed to 64 bits. The first {@code p} bits pick one of {@code m = 2^p}
 * registers; the register keeps the longest run of leading zeros (plus one) seen in the remaining
 * bits. A long run is rare, so the registers together say how many distinct hashes were seen. The
 * relative standard error is about {@code 1.04 / sqrt(m)}: 0.81% for the default {@code p = 14},
 * which needs 16,384 bytes no matter how many items are added.
 *
 * <p>A new sketch starts <b>sparse</b>: it stores one 4-byte entry per occupied register of a
 * much finer 2^25-register sketch and estimates with linear counting, which is close to exact for
 * small sets. When the sparse entries would need more memory than the dense registers, the sketch
 * converts itself to the dense form. The conversion produces exactly the registers that dense
 * insertion of the same items would have produced, so switching never adds error of its own.
 *
 * <p>The dense estimate uses Ertl's improved estimator (O. Ertl, "New cardinality estimation
 * algorithms for HyperLogLog sketches", 2017), which needs no empirical bias tables and has no
 * bias bump between the small and large ranges. {@link #classicEstimate()} keeps the original
 * Flajolet et al. estimator for comparison.
 *
 * <p>Instances are not thread-safe. Merging sketches is planned for M3.
 */
public final class HyperLogLog {
    /** Smallest supported precision (16 registers). */
    public static final int MIN_PRECISION = 4;
    /** Largest supported precision (262,144 registers, 256 KiB dense). */
    public static final int MAX_PRECISION = 18;
    /** Default precision: 16,384 registers, 0.81% standard error. */
    public static final int DEFAULT_PRECISION = 14;

    /** Index bits of the fine-grained sketch that sparse entries describe. */
    static final int SPARSE_PRECISION = 25;

    private static final int RHO_BITS = 6;
    private static final int RHO_MASK = (1 << RHO_BITS) - 1;
    private static final double ALPHA_INF = 1.0 / (2.0 * Math.log(2.0));

    private final int p;
    private final int m;
    private final int q;
    private final int bufferCapacity;

    private byte[] registers;
    private int[] sparse;
    private int sparseSize;
    private int[] buffer;
    private int bufferSize;

    /** Creates a sketch that starts sparse and turns dense when that saves memory. */
    public HyperLogLog(int precision) {
        this(precision, true);
    }

    /** Creates a sketch with the default precision of 14. */
    public HyperLogLog() {
        this(DEFAULT_PRECISION);
    }

    /** Creates a sketch that starts sparse or dense. */
    private HyperLogLog(int precision, boolean startSparse) {
        if (precision < MIN_PRECISION || precision > MAX_PRECISION) {
            throw new IllegalArgumentException(
                    "precision must be between " + MIN_PRECISION + " and " + MAX_PRECISION + ", got " + precision);
        }
        this.p = precision;
        this.m = 1 << precision;
        this.q = Long.SIZE - precision;
        this.bufferCapacity = Math.max(1, m / 32);
        if (startSparse) {
            this.sparse = new int[0];
            this.buffer = new int[bufferCapacity];
        } else {
            this.registers = new byte[m];
        }
    }

    /** Creates a sketch that is dense from the start (for comparisons and benchmarks). */
    public static HyperLogLog dense(int precision) {
        return new HyperLogLog(precision, false);
    }

    /** Adds a string, hashed as UTF-8. */
    public void add(CharSequence item) {
        addHash(Murmur3.hash128(item).h1());
    }

    /** Adds a byte array. */
    public void add(byte[] item) {
        addHash(Murmur3.hash128(item).h1());
    }

    /** Adds a long, hashed as its 8 little-endian bytes. */
    public void add(long item) {
        addHash(Murmur3.hash128(item).h1());
    }

    /** Adds an already-hashed item; the hash must be uniformly distributed over 64 bits. */
    public void addHash(long hash) {
        if (registers != null) {
            int index = (int) (hash >>> q);
            long rest = hash << p;
            int rho = rest == 0 ? q + 1 : Long.numberOfLeadingZeros(rest) + 1;
            if (rho > registers[index]) {
                registers[index] = (byte) rho;
            }
            return;
        }
        buffer[bufferSize++] = encodeSparse(hash);
        if (bufferSize == bufferCapacity) {
            flushBuffer();
        }
    }

    /** Estimated number of distinct items added. */
    public double estimate() {
        if (registers == null) {
            flushBuffer();
            if (registers == null) {
                return linearCounting(1L << SPARSE_PRECISION, (1L << SPARSE_PRECISION) - sparseSize);
            }
        }
        return improvedEstimate(histogram());
    }

    /** Estimate rounded to the nearest whole number. */
    public long cardinality() {
        return Math.round(estimate());
    }

    /** The original HyperLogLog estimator with the linear-counting switch at 2.5m (for comparison). */
    public double classicEstimate() {
        int[] c = denseCopyHistogram();
        double sum = 0;
        for (int k = 0; k < c.length; k++) {
            sum += c[k] * Math.scalb(1.0, -k);
        }
        double raw = alpha(m) * m * (double) m / sum;
        if (raw <= 2.5 * m && c[0] > 0) {
            return linearCounting(m, c[0]);
        }
        return raw;
    }

    /** Relative standard error of the dense estimate, {@code 1.04 / sqrt(m)}. */
    public double relativeStandardError() {
        return 1.04 / Math.sqrt(m);
    }

    /** Precision {@code p}; the dense form has {@code 2^p} registers. */
    public int precision() {
        return p;
    }

    /** Number of dense registers, {@code 2^p}. */
    public int registerCount() {
        return m;
    }

    /** True while the sketch still uses the sparse representation. */
    public boolean isSparse() {
        return registers == null;
    }

    /** Bytes held by the sketch's arrays (registers, or sparse entries plus the insert buffer). */
    public long memoryBytes() {
        if (registers != null) {
            return registers.length;
        }
        return 4L * (sparse.length + buffer.length);
    }

    /** Copy of the dense registers, converting a sparse sketch's view without changing it. */
    byte[] registersSnapshot() {
        if (registers != null) {
            return registers.clone();
        }
        flushBuffer();
        if (registers != null) {
            return registers.clone();
        }
        byte[] out = new byte[m];
        for (int i = 0; i < sparseSize; i++) {
            applySparseEntry(out, sparse[i]);
        }
        return out;
    }

    /** Packs the fine index and its leading-zero run into one non-negative int. */
    private static int encodeSparse(long hash) {
        int index = (int) (hash >>> (Long.SIZE - SPARSE_PRECISION));
        long rest = hash << SPARSE_PRECISION;
        int rho = rest == 0 ? Long.SIZE - SPARSE_PRECISION + 1 : Long.numberOfLeadingZeros(rest) + 1;
        return (index << RHO_BITS) | rho;
    }

    /** Sorts the buffer into the sparse list, keeping the largest run per fine index. */
    private void flushBuffer() {
        if (registers != null || bufferSize == 0) {
            return;
        }
        Arrays.sort(buffer, 0, bufferSize);
        int[] merged = new int[sparseSize + bufferSize];
        int a = 0;
        int b = 0;
        int n = 0;
        while (a < sparseSize || b < bufferSize) {
            int next;
            if (b == bufferSize || (a < sparseSize && sparse[a] <= buffer[b])) {
                next = sparse[a++];
            } else {
                next = buffer[b++];
            }
            if (n > 0 && (merged[n - 1] >>> RHO_BITS) == (next >>> RHO_BITS)) {
                merged[n - 1] = Math.max(merged[n - 1], next);
            } else {
                merged[n++] = next;
            }
        }
        bufferSize = 0;
        sparse = n == merged.length ? merged : Arrays.copyOf(merged, n);
        sparseSize = n;
        if (4L * (sparseSize + bufferCapacity) > m) {
            toDense();
        }
    }

    /** Replaces the sparse list with dense registers. */
    private void toDense() {
        byte[] dense = new byte[m];
        for (int i = 0; i < sparseSize; i++) {
            applySparseEntry(dense, sparse[i]);
        }
        registers = dense;
        sparse = null;
        buffer = null;
        sparseSize = 0;
    }

    /** Folds one sparse entry into dense registers exactly as dense insertion would. */
    private void applySparseEntry(byte[] dense, int entry) {
        int fineIndex = entry >>> RHO_BITS;
        int fineRho = entry & RHO_MASK;
        int extraBits = SPARSE_PRECISION - p;
        int index = fineIndex >>> extraBits;
        int low = fineIndex & ((1 << extraBits) - 1);
        int rho;
        if (low != 0) {
            rho = Integer.numberOfLeadingZeros(low << (Integer.SIZE - extraBits)) + 1;
        } else {
            rho = extraBits + fineRho;
        }
        if (rho > dense[index]) {
            dense[index] = (byte) rho;
        }
    }

    /** Counts registers per value 0..q+1. */
    private int[] histogram() {
        int[] c = new int[q + 2];
        for (byte r : registers) {
            c[r]++;
        }
        return c;
    }

    /** Histogram of the dense registers, also for a sketch that is still sparse. */
    private int[] denseCopyHistogram() {
        int[] c = new int[q + 2];
        for (byte r : registersSnapshot()) {
            c[r]++;
        }
        return c;
    }

    /** Ertl's improved raw estimator over a register histogram. */
    private double improvedEstimate(int[] c) {
        double z = m * tau(1.0 - (double) c[q + 1] / m);
        for (int k = q; k >= 1; k--) {
            z = 0.5 * (z + c[k]);
        }
        z += m * sigma((double) c[0] / m);
        return ALPHA_INF * m * (double) m / z;
    }

    /** Ertl's sigma function, {@code x + sum_k x^(2^k) 2^(k-1)}. */
    static double sigma(double x) {
        if (x == 1.0) {
            return Double.POSITIVE_INFINITY;
        }
        double y = 1.0;
        double z = x;
        double previous;
        do {
            x *= x;
            previous = z;
            z += x * y;
            y += y;
        } while (previous != z);
        return z;
    }

    /** Ertl's tau function, {@code (1 - x - sum_k (1 - x^(2^-k))^2 2^-k) / 3}. */
    static double tau(double x) {
        if (x == 0.0 || x == 1.0) {
            return 0.0;
        }
        double y = 1.0;
        double z = 1.0 - x;
        double previous;
        do {
            x = Math.sqrt(x);
            previous = z;
            y *= 0.5;
            double d = 1.0 - x;
            z -= d * d * y;
        } while (previous != z);
        return z / 3.0;
    }

    /** Linear counting: {@code m ln(m / empty)}. */
    private static double linearCounting(long buckets, long empty) {
        return buckets * Math.log((double) buckets / empty);
    }

    /** Bias constant of the classic estimator. */
    private static double alpha(int m) {
        return switch (m) {
            case 16 -> 0.673;
            case 32 -> 0.697;
            case 64 -> 0.709;
            default -> 0.7213 / (1.0 + 1.079 / m);
        };
    }
}
