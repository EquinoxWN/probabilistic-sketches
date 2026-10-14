package portfolio.sketches;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Top-k most frequent items of a stream: a {@link CountMinSketch} counts every item, and an
 * indexed min-heap of at most {@code k} entries remembers the items with the largest estimates.
 *
 * <p>Memory is the sketch plus {@code k} items, independent of how many distinct items the stream
 * has. On every update the item's new estimate is compared with the smallest estimate in the heap,
 * so an item enters the heap as soon as it outgrows the current minimum. Because estimates never
 * under-count, an item whose true count is at least {@code phi * N} is still in the heap at the end
 * whenever {@code k >= 1 / (phi - epsilon)} and no lighter item is over-counted by more than
 * {@code epsilon * N} (each item stays within that bound with probability {@code 1 - delta}).
 *
 * <p>Reported entries carry the range {@code [estimate - ceil(epsilon * N), estimate]}, which holds
 * the true count with probability at least {@code 1 - delta}. Instances are not thread-safe.
 */
public final class HeavyHitters {
    /** Largest supported heap size. */
    public static final int MAX_K = 100_000;

    private final int k;
    private final CountMinSketch sketch;
    private final String[] items;
    private final long[] counts;
    private final Map<String, Integer> positions;
    private int size;

    /** One reported item with its estimate and the lower end of its likely range. */
    public record Entry(String item, long estimate, long lowerBound) {}

    /** Tracks the top {@code k} items using the given (fresh or shared) sketch. */
    public HeavyHitters(int k, CountMinSketch sketch) {
        if (k < 1 || k > MAX_K) {
            throw new IllegalArgumentException("k must be between 1 and " + MAX_K + ", got " + k);
        }
        this.k = k;
        this.sketch = Objects.requireNonNull(sketch, "sketch");
        this.items = new String[k];
        this.counts = new long[k];
        this.positions = HashMap.newHashMap(k);
    }

    /** Adds one occurrence of an item. */
    public void add(String item) {
        add(item, 1);
    }

    /** Adds {@code count >= 0} occurrences of an item and updates the heap. */
    public void add(String item, long count) {
        Objects.requireNonNull(item, "item");
        long estimate = sketch.add(item, count);
        Integer at = positions.get(item);
        if (at != null) {
            counts[at] = estimate;
            siftDown(at);
        } else if (size < k) {
            items[size] = item;
            counts[size] = estimate;
            positions.put(item, size);
            siftUp(size++);
        } else if (estimate > counts[0]) {
            positions.remove(items[0]);
            items[0] = item;
            counts[0] = estimate;
            positions.put(item, 0);
            siftDown(0);
        }
    }

    /** Current top items, largest estimate first (ties by item), with fresh estimates. */
    public List<Entry> top() {
        refresh();
        long bound = sketch.overcountBound();
        List<Entry> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(new Entry(items[i], counts[i], Math.max(0, counts[i] - bound)));
        }
        out.sort(Comparator.comparingLong(Entry::estimate).reversed().thenComparing(Entry::item));
        return out;
    }

    /** Top items whose estimate is at least {@code phi * N}. */
    public List<Entry> aboveFraction(double phi) {
        if (!(phi > 0 && phi <= 1)) {
            throw new IllegalArgumentException("phi must be in (0, 1], got " + phi);
        }
        double threshold = phi * sketch.totalCount();
        return top().stream().filter(e -> e.estimate() >= threshold).toList();
    }

    /** The underlying sketch, for estimates of items outside the heap. */
    public CountMinSketch sketch() {
        return sketch;
    }

    /** Heap capacity {@code k}. */
    public int capacity() {
        return k;
    }

    /** Number of items currently in the heap. */
    public int size() {
        return size;
    }

    /** Re-reads every heap entry's estimate and restores heap order. */
    private void refresh() {
        for (int i = 0; i < size; i++) {
            counts[i] = sketch.estimate(items[i]);
        }
        for (int i = size / 2 - 1; i >= 0; i--) {
            siftDown(i);
        }
    }

    /** Moves an entry up while it is smaller than its parent. */
    private void siftUp(int i) {
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (counts[i] >= counts[parent]) {
                return;
            }
            swap(i, parent);
            i = parent;
        }
    }

    /** Moves an entry down while a child is smaller. */
    private void siftDown(int i) {
        while (true) {
            int left = 2 * i + 1;
            if (left >= size) {
                return;
            }
            int right = left + 1;
            int smallest = right < size && counts[right] < counts[left] ? right : left;
            if (counts[i] <= counts[smallest]) {
                return;
            }
            swap(i, smallest);
            i = smallest;
        }
    }

    /** Swaps two heap slots and their index entries. */
    private void swap(int a, int b) {
        String item = items[a];
        items[a] = items[b];
        items[b] = item;
        long count = counts[a];
        counts[a] = counts[b];
        counts[b] = count;
        positions.put(items[a], a);
        positions.put(items[b], b);
    }
}
