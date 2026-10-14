package portfolio.sketches.cli;

import java.util.Arrays;
import java.util.SplittableRandom;

/** Draws ranks 0..n-1 with probability proportional to {@code 1 / (rank + 1)^s}, the skew of real traffic. */
final class ZipfSampler {
    /** Largest supported number of ranks (the table takes 8 bytes per rank). */
    static final int MAX_RANKS = 5_000_000;

    private final double[] cdf;

    /** Builds the cumulative table for {@code n} ranks and exponent {@code s}. */
    ZipfSampler(int n, double s) {
        if (n < 1 || n > MAX_RANKS) {
            throw new IllegalArgumentException("ranks must be between 1 and " + MAX_RANKS + ", got " + n);
        }
        if (!(s > 0 && s <= 3)) {
            throw new IllegalArgumentException("exponent must be in (0, 3], got " + s);
        }
        cdf = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += Math.pow(i + 1, -s);
            cdf[i] = sum;
        }
        for (int i = 0; i < n; i++) {
            cdf[i] /= sum;
        }
        cdf[n - 1] = 1.0;
    }

    /** Draws one rank. */
    int sample(SplittableRandom random) {
        int i = Arrays.binarySearch(cdf, random.nextDouble());
        return i >= 0 ? i : -i - 1;
    }

    /** Number of ranks. */
    int size() {
        return cdf.length;
    }
}
