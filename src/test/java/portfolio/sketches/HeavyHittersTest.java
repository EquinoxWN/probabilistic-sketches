package portfolio.sketches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class HeavyHittersTest {
    /** A sketch wide enough that a handful of items never collide in every row. */
    private static CountMinSketch wide() {
        return CountMinSketch.withErrorBounds(0.0001, 0.001, false);
    }

    /** Cumulative Zipf table for {@code n} ranks with exponent {@code s}. */
    private static double[] zipfCdf(int n, double s) {
        double[] cdf = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += Math.pow(i + 1, -s);
            cdf[i] = sum;
        }
        for (int i = 0; i < n; i++) {
            cdf[i] /= sum;
        }
        return cdf;
    }

    /** Draws one Zipf rank. */
    private static int draw(double[] cdf, SplittableRandom r) {
        int i = java.util.Arrays.binarySearch(cdf, r.nextDouble());
        return Math.min(cdf.length - 1, i >= 0 ? i : -i - 1);
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new HeavyHitters(0, wide()));
        assertThrows(IllegalArgumentException.class, () -> new HeavyHitters(HeavyHitters.MAX_K + 1, wide()));
        assertThrows(NullPointerException.class, () -> new HeavyHitters(3, null));
        HeavyHitters h = new HeavyHitters(3, wide());
        assertThrows(NullPointerException.class, () -> h.add(null));
        assertThrows(IllegalArgumentException.class, () -> h.aboveFraction(0));
        assertThrows(IllegalArgumentException.class, () -> h.aboveFraction(1.5));
    }

    @Test
    void fewerDistinctItemsThanKAreAllReportedExactlyInOrder() {
        HeavyHitters h = new HeavyHitters(10, wide());
        h.add("b", 5);
        h.add("a", 7);
        h.add("c", 5);
        h.add("b");
        List<HeavyHitters.Entry> top = h.top();
        assertEquals(
                List.of("a", "b", "c"),
                top.stream().map(HeavyHitters.Entry::item).toList());
        assertEquals(
                List.of(7L, 6L, 5L),
                top.stream().map(HeavyHitters.Entry::estimate).toList());
        assertEquals(3, h.size());
        assertEquals(10, h.capacity());
    }

    @Test
    void heapKeepsAtMostKItemsAndEvictsTheSmallest() {
        HeavyHitters h = new HeavyHitters(2, wide());
        h.add("x", 10);
        h.add("y", 3);
        h.add("z", 2);
        assertEquals(Set.of("x", "y"), items(h.top()));
        h.add("z", 2);
        assertEquals(Set.of("x", "z"), items(h.top()));
        assertEquals(2, h.size());
    }

    @Test
    void anEvictedItemComesBackWhenItOutgrowsTheMinimum() {
        HeavyHitters h = new HeavyHitters(1, wide());
        h.add("early", 5);
        h.add("late", 3);
        assertEquals(Set.of("early"), items(h.top()));
        h.add("late", 3);
        assertEquals(List.of(new HeavyHitters.Entry("late", 6, 6 - h.sketch().overcountBound())), h.top());
    }

    @Test
    void everyItemAbovePhiTimesNIsReportedOnZipfStreams() {
        double epsilon = 0.001;
        double phi = 0.01;
        int k = (int) Math.ceil(1 / (phi - epsilon));
        double[] cdf = zipfCdf(50_000, 1.1);
        for (int trial = 0; trial < 5; trial++) {
            SplittableRandom r = new SplittableRandom(trial);
            HeavyHitters h = new HeavyHitters(k, CountMinSketch.withErrorBounds(epsilon, 0.01, true));
            long[] truth = new long[cdf.length];
            int n = 200_000;
            for (int i = 0; i < n; i++) {
                int rank = draw(cdf, r);
                truth[rank]++;
                h.add("item-" + trial + "-" + rank);
            }
            List<HeavyHitters.Entry> reported = h.aboveFraction(phi);
            Set<String> names = items(reported);
            for (int rank = 0; rank < truth.length; rank++) {
                if (truth[rank] >= phi * n) {
                    assertTrue(names.contains("item-" + trial + "-" + rank), "missed rank " + rank);
                }
            }
            for (HeavyHitters.Entry e : reported) {
                long t = truth[Integer.parseInt(e.item().substring(e.item().lastIndexOf('-') + 1))];
                assertTrue(e.lowerBound() <= t && t <= e.estimate(), e + " true " + t);
            }
        }
    }

    @Test
    void topTenMatchesTheExactTopTenOnZipfStreams() {
        double[] cdf = zipfCdf(100_000, 1.0);
        for (int trial = 0; trial < 5; trial++) {
            SplittableRandom r = new SplittableRandom(50 + trial);
            HeavyHitters h = new HeavyHitters(10, CountMinSketch.withErrorBounds(0.001, 0.01, true));
            long[] truth = new long[cdf.length];
            for (int i = 0; i < 300_000; i++) {
                int rank = draw(cdf, r);
                truth[rank]++;
                h.add(Integer.toString(rank));
            }
            Set<String> exact = new HashSet<>();
            Integer[] order = new Integer[truth.length];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            java.util.Arrays.sort(order, (a, b) -> Long.compare(truth[b], truth[a]));
            for (int i = 0; i < 10; i++) {
                exact.add(Integer.toString(order[i]));
            }
            assertEquals(exact, items(h.top()), "trial " + trial);
        }
    }

    /** Item names of entries. */
    private static Set<String> items(List<HeavyHitters.Entry> entries) {
        Set<String> out = new HashSet<>();
        entries.forEach(e -> out.add(e.item()));
        return out;
    }
}
