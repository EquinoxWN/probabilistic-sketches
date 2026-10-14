package portfolio.sketches;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class HyperLogLogTest {
    @Test
    void rejectsPrecisionOutsideFourToEighteen() {
        assertThrows(IllegalArgumentException.class, () -> new HyperLogLog(3));
        assertThrows(IllegalArgumentException.class, () -> new HyperLogLog(19));
        assertThrows(IllegalArgumentException.class, () -> HyperLogLog.dense(0));
        assertEquals(16, new HyperLogLog(4).registerCount());
        assertEquals(1 << 18, HyperLogLog.dense(18).registerCount());
        assertEquals(14, new HyperLogLog().precision());
    }

    @Test
    void emptySketchEstimatesZero() {
        assertEquals(0.0, new HyperLogLog(14).estimate());
        assertEquals(0.0, HyperLogLog.dense(14).estimate());
        assertEquals(0L, HyperLogLog.dense(4).cardinality());
    }

    @Test
    void addingTheSameItemsAgainChangesNothing() {
        HyperLogLog once = new HyperLogLog(12);
        HyperLogLog twice = new HyperLogLog(12);
        for (int i = 0; i < 20_000; i++) {
            once.add("user-" + i);
            twice.add("user-" + i);
            twice.add("user-" + i);
        }
        for (int i = 0; i < 20_000; i++) {
            twice.add("user-" + i);
        }
        assertEquals(once.estimate(), twice.estimate());
        assertArrayEquals(once.registersSnapshot(), twice.registersSnapshot());
    }

    @Test
    void registerKeepsTheLeadingZeroRunPlusOne() {
        int p = 4;
        HyperLogLog h = HyperLogLog.dense(p);
        // Register 3; the 60 remaining bits start with five zeros and a one: run 6.
        h.addHash((3L << 60) | (1L << (59 - 5)));
        // Register 5; the remaining bits are all zero: the maximum, 64 - p + 1 = 61.
        h.addHash(5L << 60);
        // Register 3 again with a shorter run (2) must not lower the register.
        h.addHash((3L << 60) | (1L << 58));
        byte[] r = h.registersSnapshot();
        assertEquals(6, r[3]);
        assertEquals(61, r[5]);
        assertEquals(0, r[0]);
    }

    @Test
    void startsSparseAndTurnsDenseOnceSparseWouldUseMoreMemory() {
        for (int p = HyperLogLog.MIN_PRECISION; p <= HyperLogLog.MAX_PRECISION; p++) {
            HyperLogLog h = new HyperLogLog(p);
            SplittableRandom r = new SplittableRandom(p);
            assertTrue(h.isSparse());
            boolean wasDense = false;
            for (int i = 0; i < 2 * h.registerCount(); i++) {
                h.addHash(r.nextLong());
                assertTrue(h.memoryBytes() <= h.registerCount(), "p=" + p + " memory above the dense size");
                if (wasDense) {
                    assertFalse(h.isSparse(), "p=" + p + " went back to sparse");
                }
                wasDense |= !h.isSparse();
            }
            assertTrue(wasDense, "p=" + p + " never turned dense");
            assertEquals(h.registerCount(), h.memoryBytes());
        }
    }

    @Test
    void sparseToDenseConversionGivesExactlyTheDenseRegisters() {
        for (int p = HyperLogLog.MIN_PRECISION; p <= HyperLogLog.MAX_PRECISION; p++) {
            HyperLogLog adaptive = new HyperLogLog(p);
            HyperLogLog dense = HyperLogLog.dense(p);
            SplittableRandom r = new SplittableRandom(100 + p);
            int n = 3 * (1 << p);
            int every = Math.max(50, (1 << p) / 64);
            for (int i = 1; i <= n; i++) {
                long h = r.nextLong();
                // Also exercise hashes whose low 39 bits are zero (longest possible fine run).
                if (i % 997 == 0) {
                    h &= ~((1L << 39) - 1);
                }
                adaptive.addHash(h);
                dense.addHash(h);
                if (i % every == 0 || i == n) {
                    assertArrayEquals(dense.registersSnapshot(), adaptive.registersSnapshot(), "p=" + p + ", n=" + i);
                }
            }
            assertFalse(adaptive.isSparse());
            assertEquals(dense.estimate(), adaptive.estimate(), "p=" + p);
        }
    }

    @Test
    void smallSetsAreCountedAlmostExactlyWhileSparse() {
        SplittableRandom r = new SplittableRandom(7);
        for (int trial = 0; trial < 50; trial++) {
            HyperLogLog h = new HyperLogLog(14);
            for (int n = 1; n <= 3000; n++) {
                h.addHash(r.nextLong());
                if (n % 100 == 0 || n < 20) {
                    assertTrue(h.isSparse(), "still sparse at " + n);
                    assertTrue(Math.abs(h.cardinality() - n) <= 2, "n=" + n + " estimate " + h.cardinality());
                }
            }
        }
    }

    @Test
    void addOverloadsHashTheirInputConsistently() {
        HyperLogLog a = HyperLogLog.dense(10);
        HyperLogLog b = HyperLogLog.dense(10);
        a.add("visitor-42");
        b.addHash(Murmur3.hash128("visitor-42").h1());
        a.add(123_456_789L);
        b.add(Murmur3Test.le(123_456_789L));
        a.add(new byte[] {1, 2, 3});
        b.addHash(Murmur3.hash128(new byte[] {1, 2, 3}).h1());
        assertArrayEquals(a.registersSnapshot(), b.registersSnapshot());
    }

    @Test
    void realStringItemsLandWithinThreeStandardErrors() {
        for (int p : new int[] {10, 14, 16}) {
            HyperLogLog h = new HyperLogLog(p);
            int n = 200_000;
            for (int i = 0; i < n; i++) {
                h.add("203.0." + (i >>> 8) + "." + (i & 0xff) + "/user-" + i);
            }
            double error = h.estimate() / n - 1;
            assertTrue(
                    Math.abs(error) <= 3 * h.relativeStandardError(),
                    "p=" + p + " error " + error + " vs sigma " + h.relativeStandardError());
        }
    }

    @Test
    void sigmaAndTauMatchTheirSeriesDefinitions() {
        for (double x : new double[] {0.0, 0.1, 0.3, 0.5, 0.7, 0.9, 0.99}) {
            double sigma = x;
            for (int k = 1; k < 64; k++) {
                sigma += Math.pow(x, Math.pow(2, k)) * Math.pow(2, k - 1);
            }
            assertEquals(sigma, HyperLogLog.sigma(x), 1e-12 * Math.max(1, sigma), "sigma(" + x + ")");
            double tau = 1 - x;
            for (int k = 1; k < 64; k++) {
                double d = 1 - Math.pow(x, Math.pow(2, -k));
                tau -= d * d * Math.pow(2, -k);
            }
            assertEquals(x == 0 ? 0 : tau / 3, HyperLogLog.tau(x), 1e-12, "tau(" + x + ")");
        }
        assertEquals(Double.POSITIVE_INFINITY, HyperLogLog.sigma(1.0));
        assertEquals(0.0, HyperLogLog.tau(1.0));
    }
}
