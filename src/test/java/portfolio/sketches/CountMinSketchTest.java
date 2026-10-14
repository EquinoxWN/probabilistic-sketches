package portfolio.sketches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class CountMinSketchTest {
    @Test
    void dimensionsFollowEpsilonAndDelta() {
        CountMinSketch a = CountMinSketch.withErrorBounds(0.001, 0.01, false);
        assertEquals(5, a.depth());
        assertEquals(2719, a.width());
        assertEquals(5L * 2719 * 8, a.memoryBytes());
        assertTrue(a.epsilon() <= 0.001 && a.delta() <= 0.01);
        CountMinSketch b = CountMinSketch.withErrorBounds(0.01, 0.01, true);
        assertEquals(272, b.width());
        assertTrue(b.isConservative());
        CountMinSketch c = CountMinSketch.withErrorBounds(0.1, 0.5, false);
        assertEquals(1, c.depth());
        assertEquals(28, c.width());
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new CountMinSketch(0, 10, false));
        assertThrows(IllegalArgumentException.class, () -> new CountMinSketch(33, 10, false));
        assertThrows(IllegalArgumentException.class, () -> new CountMinSketch(4, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new CountMinSketch(2, 1 << 24, false));
        assertThrows(IllegalArgumentException.class, () -> CountMinSketch.withErrorBounds(0, 0.1, false));
        assertThrows(IllegalArgumentException.class, () -> CountMinSketch.withErrorBounds(1, 0.1, false));
        assertThrows(IllegalArgumentException.class, () -> CountMinSketch.withErrorBounds(Double.NaN, 0.1, false));
        assertThrows(IllegalArgumentException.class, () -> CountMinSketch.withErrorBounds(0.1, 0, false));
        assertThrows(IllegalArgumentException.class, () -> CountMinSketch.withErrorBounds(0.1, 1, false));
        assertThrows(IllegalArgumentException.class, () -> CountMinSketch.withErrorBounds(1e-9, 0.1, false));
        CountMinSketch s = new CountMinSketch(2, 8, false);
        assertThrows(IllegalArgumentException.class, () -> s.add("x", -1));
        assertEquals(0, s.totalCount());
    }

    @Test
    void totalCountOverflowIsRejectedBeforeAnyCounterChanges() {
        CountMinSketch s = new CountMinSketch(3, 16, false);
        s.add("a", Long.MAX_VALUE);
        long before = s.estimate("b");
        assertThrows(ArithmeticException.class, () -> s.add("b", 1));
        assertEquals(before, s.estimate("b"));
        assertEquals(Long.MAX_VALUE, s.totalCount());
    }

    @Test
    void aSingleItemIsCountedExactly() {
        for (boolean conservative : new boolean[] {false, true}) {
            CountMinSketch s = CountMinSketch.withErrorBounds(0.01, 0.01, conservative);
            assertEquals(0, s.estimate("only"));
            assertEquals(1, s.add("only"));
            assertEquals(6, s.add("only", 5));
            assertEquals(6, s.add("only", 0));
            assertEquals(6, s.estimate("only"));
            assertEquals(6, s.totalCount());
        }
    }

    @Test
    void neverUnderCountsInEitherUpdateMode() {
        for (boolean conservative : new boolean[] {false, true}) {
            for (int trial = 0; trial < 5; trial++) {
                SplittableRandom r = new SplittableRandom(trial);
                CountMinSketch s = new CountMinSketch(4, 64, conservative);
                long[] truth = new long[5_000];
                for (int i = 0; i < 50_000; i++) {
                    int item = r.nextInt(truth.length);
                    long count = 1 + r.nextInt(3);
                    truth[item] += count;
                    long reported = s.add(item, count);
                    assertTrue(reported >= truth[item]);
                }
                for (int item = 0; item < truth.length; item++) {
                    assertTrue(s.estimate(item) >= truth[item], "item " + item + " under-counted");
                }
            }
        }
    }

    @Test
    void overCountAboveEpsilonNHappensForAtMostDeltaOfItems() {
        // depth 1: delta = 1/e (Markov's inequality); depth 3: delta = e^-3.
        for (int depth : new int[] {1, 3}) {
            int width = 272;
            long over = 0;
            long items = 0;
            for (int trial = 0; trial < 20; trial++) {
                SplittableRandom r = new SplittableRandom(1_000 + trial);
                CountMinSketch s = new CountMinSketch(depth, width, false);
                long[] truth = new long[20_000];
                for (int i = 0; i < 100_000; i++) {
                    int item = r.nextInt(truth.length);
                    truth[item]++;
                    s.add(item + 1_000_000L * trial);
                }
                long bound = s.overcountBound();
                for (int item = 0; item < truth.length; item++) {
                    if (truth[item] > 0) {
                        items++;
                        if (s.estimate(item + 1_000_000L * trial) - truth[item] > bound) {
                            over++;
                        }
                    }
                }
            }
            double fraction = (double) over / items;
            double delta = Math.exp(-depth);
            assertTrue(
                    fraction <= delta, "depth " + depth + ": " + fraction + " of items over the bound, delta " + delta);
        }
    }

    @Test
    void meanOverCountOfOneRowMatchesTheExpectedCollisionMass() {
        // With one row of width w, an item's counter also holds each other unit with probability
        // 1/w, so the expected over-count of item x is (N - count(x)) / w.
        int width = 1_000;
        int distinct = 50_000;
        CountMinSketch s = new CountMinSketch(1, width, false);
        for (int i = 0; i < distinct; i++) {
            s.add("key-" + i, 1 + (i % 4));
        }
        long n = s.totalCount();
        double observed = 0;
        double expected = 0;
        for (int i = 0; i < distinct; i++) {
            long truth = 1 + (i % 4);
            observed += s.estimate("key-" + i) - truth;
            expected += (double) (n - truth) / width;
        }
        assertEquals(1.0, observed / expected, 0.03);
    }

    @Test
    void conservativeUpdateNeverExceedsPlainEstimates() {
        SplittableRandom r = new SplittableRandom(5);
        CountMinSketch plain = new CountMinSketch(4, 128, false);
        CountMinSketch conservative = new CountMinSketch(4, 128, true);
        long[] truth = new long[10_000];
        for (int i = 0; i < 200_000; i++) {
            int item = (int) Math.min(truth.length - 1, Math.abs(r.nextGaussian()) * 1_500);
            truth[item]++;
            plain.add(item);
            conservative.add(item);
        }
        long plainError = 0;
        long conservativeError = 0;
        for (int item = 0; item < truth.length; item++) {
            long c = conservative.estimate(item);
            long p = plain.estimate(item);
            assertTrue(c >= truth[item] && c <= p, "item " + item);
            plainError += p - truth[item];
            conservativeError += c - truth[item];
        }
        assertTrue(conservativeError < plainError, conservativeError + " vs " + plainError);
    }

    @Test
    void halvingAgesCountsWithoutUnderCountingTheHalvedTruth() {
        SplittableRandom r = new SplittableRandom(9);
        CountMinSketch s = new CountMinSketch(4, 256, false);
        long[] truth = new long[2_000];
        for (int i = 0; i < 40_000; i++) {
            int item = r.nextInt(truth.length);
            truth[item]++;
            s.add(item);
        }
        s.halve();
        assertEquals(20_000, s.totalCount());
        for (int item = 0; item < truth.length; item++) {
            assertTrue(s.estimate(item) >= truth[item] / 2, "item " + item);
        }
        CountMinSketch one = new CountMinSketch(2, 16, true);
        one.add("hot", 7);
        one.halve();
        assertEquals(3, one.estimate("hot"));
    }

    @Test
    void itemOverloadsAgreeOnTheSameBytes() {
        CountMinSketch s = new CountMinSketch(3, 50, false);
        s.add(42L, 3);
        assertEquals(3, s.estimate(Murmur3Test.le(42L)));
        s.add("é-visitor", 2);
        assertEquals(2, s.estimate("é-visitor".getBytes(StandardCharsets.UTF_8)));
        s.add(new byte[] {9}, 4);
        assertEquals(4, s.estimate(new byte[] {9}));
        assertFalse(s.isConservative());
    }
}
