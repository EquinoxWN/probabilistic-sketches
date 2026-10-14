package portfolio.sketches;

/**
 * Count-Min Sketch: approximate per-item counts in a fixed {@code depth x width} table of counters.
 *
 * <p>Each item is hashed to one counter in every row, and an update adds to those counters. Other
 * items can share a counter, so a counter only ever over-counts; the smallest of an item's counters
 * is its estimate. With {@code width = ceil(e / epsilon)} and {@code depth = ceil(ln(1 / delta))}
 * (Cormode and Muthukrishnan, 2005), for every item:
 *
 * <ul>
 *   <li>{@code estimate >= trueCount}, always;
 *   <li>{@code estimate <= trueCount + epsilon * N} with probability at least {@code 1 - delta},
 *       where {@code N} is {@link #totalCount()}, the sum of all updates.
 * </ul>
 *
 * <p>Row positions come from one 128-bit MurmurHash3 per item, split into two 64-bit halves and
 * combined as {@code h1 + i * h2} for row {@code i} (Kirsch-Mitzenmacher double hashing), so an
 * update costs one hash however deep the table is.
 *
 * <p><b>Conservative update</b> (Estan and Varghese, 2002) is optional: an update raises only the
 * counters that would otherwise fall below the item's new estimate. Every counter stays at or below
 * the value plain updates would give it, so estimates are never worse and still never under-count.
 * It requires non-negative updates, which this class enforces for both modes.
 *
 * <p>{@link #halve()} divides every counter by two, the aging step a TinyLFU admission filter runs
 * periodically so that old popularity fades. Instances are not thread-safe; merging is planned for
 * M3.
 */
public final class CountMinSketch {
    /** Upper limit on {@code depth * width}, 16,777,216 counters (128 MiB), to stop accidental huge tables. */
    public static final long MAX_COUNTERS = 1L << 24;
    /** Upper limit on depth; 32 rows already give {@code delta < 1e-13}. */
    public static final int MAX_DEPTH = 32;

    private final int depth;
    private final int width;
    private final boolean conservative;
    private final long[] counters;
    private long total;

    /** Creates a sketch with explicit dimensions. */
    public CountMinSketch(int depth, int width, boolean conservative) {
        if (depth < 1 || depth > MAX_DEPTH) {
            throw new IllegalArgumentException("depth must be between 1 and " + MAX_DEPTH + ", got " + depth);
        }
        if (width < 1) {
            throw new IllegalArgumentException("width must be positive, got " + width);
        }
        if ((long) depth * width > MAX_COUNTERS) {
            throw new IllegalArgumentException(
                    "depth * width must be at most " + MAX_COUNTERS + ", got " + (long) depth * width);
        }
        this.depth = depth;
        this.width = width;
        this.conservative = conservative;
        this.counters = new long[depth * width];
    }

    /** Creates the smallest sketch whose over-count is at most {@code epsilon * N} with probability {@code 1 - delta}. */
    public static CountMinSketch withErrorBounds(double epsilon, double delta, boolean conservative) {
        if (!(epsilon > 0 && epsilon < 1)) {
            throw new IllegalArgumentException("epsilon must be in (0, 1), got " + epsilon);
        }
        if (!(delta > 0 && delta < 1)) {
            throw new IllegalArgumentException("delta must be in (0, 1), got " + delta);
        }
        double width = Math.ceil(Math.E / epsilon);
        double depth = Math.ceil(Math.log(1.0 / delta));
        if (width > Integer.MAX_VALUE || depth > MAX_DEPTH) {
            throw new IllegalArgumentException("epsilon or delta is too small for this sketch");
        }
        return new CountMinSketch(Math.max(1, (int) depth), (int) width, conservative);
    }

    /** Adds one occurrence of a string (hashed as UTF-8) and returns its new estimate. */
    public long add(CharSequence item) {
        return add(item, 1);
    }

    /** Adds {@code count >= 0} occurrences of a string and returns its new estimate. */
    public long add(CharSequence item, long count) {
        Murmur3.Hash128 h = Murmur3.hash128(item);
        return addHash(h.h1(), h.h2(), count);
    }

    /** Adds {@code count >= 0} occurrences of a byte array and returns its new estimate. */
    public long add(byte[] item, long count) {
        Murmur3.Hash128 h = Murmur3.hash128(item);
        return addHash(h.h1(), h.h2(), count);
    }

    /** Adds one occurrence of a long key and returns its new estimate. */
    public long add(long item) {
        return add(item, 1);
    }

    /** Adds {@code count >= 0} occurrences of a long key and returns its new estimate. */
    public long add(long item, long count) {
        Murmur3.Hash128 h = Murmur3.hash128(item);
        return addHash(h.h1(), h.h2(), count);
    }

    /** Estimated count of a string; never below the true count. */
    public long estimate(CharSequence item) {
        Murmur3.Hash128 h = Murmur3.hash128(item);
        return estimateHash(h.h1(), h.h2());
    }

    /** Estimated count of a byte array; never below the true count. */
    public long estimate(byte[] item) {
        Murmur3.Hash128 h = Murmur3.hash128(item);
        return estimateHash(h.h1(), h.h2());
    }

    /** Estimated count of a long key; never below the true count. */
    public long estimate(long item) {
        Murmur3.Hash128 h = Murmur3.hash128(item);
        return estimateHash(h.h1(), h.h2());
    }

    /** Sum of all counts added (N in the error bound). */
    public long totalCount() {
        return total;
    }

    /** Error factor: with probability {@code 1 - delta}, over-count is at most {@code epsilon * N}; equals {@code e / width}. */
    public double epsilon() {
        return Math.E / width;
    }

    /** Failure probability of the bound for one item, {@code e^-depth}. */
    public double delta() {
        return Math.exp(-depth);
    }

    /** The over-count bound {@code ceil(epsilon * N)} for the current total. */
    public long overcountBound() {
        return (long) Math.ceil(epsilon() * total);
    }

    /** Number of rows. */
    public int depth() {
        return depth;
    }

    /** Counters per row. */
    public int width() {
        return width;
    }

    /** True when updates use the conservative rule. */
    public boolean isConservative() {
        return conservative;
    }

    /** Bytes held by the counter table. */
    public long memoryBytes() {
        return 8L * counters.length;
    }

    /** Halves every counter and the total (rounding down), so older counts fade. */
    public void halve() {
        for (int i = 0; i < counters.length; i++) {
            counters[i] >>>= 1;
        }
        total >>>= 1;
    }

    /** Applies an update for a pre-hashed item. */
    private long addHash(long h1, long h2, long count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative, got " + count);
        }
        total = Math.addExact(total, count);
        if (conservative) {
            long target = estimateHash(h1, h2) + count;
            for (int row = 0; row < depth; row++) {
                int cell = cell(row, h1, h2);
                if (counters[cell] < target) {
                    counters[cell] = target;
                }
            }
            return target;
        }
        long min = Long.MAX_VALUE;
        for (int row = 0; row < depth; row++) {
            int cell = cell(row, h1, h2);
            long value = counters[cell] + count;
            counters[cell] = value;
            min = Math.min(min, value);
        }
        return min;
    }

    /** Minimum over the item's counters. */
    private long estimateHash(long h1, long h2) {
        long min = Long.MAX_VALUE;
        for (int row = 0; row < depth; row++) {
            min = Math.min(min, counters[cell(row, h1, h2)]);
        }
        return min;
    }

    /** Flat index of the item's counter in one row, using the high bits of {@code h1 + row * h2}. */
    private int cell(int row, long h1, long h2) {
        long g = h1 + row * h2;
        return row * width + (int) Math.unsignedMultiplyHigh(g, width);
    }
}
