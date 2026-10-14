package portfolio.sketches.cli;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;
import portfolio.sketches.CountMinSketch;
import portfolio.sketches.HeavyHitters;
import portfolio.sketches.HyperLogLog;

/**
 * Measures observed error against the theoretical bounds over many seeded trials and prints
 * Markdown tables (the numbers in docs/results/m1.md come from this class).
 */
final class AccuracyBench {
    private static final int[] PRECISIONS = {10, 12, 14};
    private static final int[] CHECKPOINTS = {100, 1_000, 5_000, 10_000, 20_000, 50_000, 100_000, 1_000_000};
    private static final double[] EPSILONS = {0.01, 0.001};
    private static final double[] SKEWS = {0.8, 1.2};
    private static final double DELTA = 0.01;
    private static final int CMS_ITEMS = 100_000;
    private static final int CMS_UPDATES = 1_000_000;
    private static final int TOP = 20;

    /** Worst cases across all rows, printed as the RESULT line that CI checks. */
    static final class Summary {
        double worstHllRatio;
        double worstClassicRatio;
        double worstOverBoundFraction;
        long underCounts;
    }

    private AccuracyBench() {}

    /** Actual per-item failure probability of the benchmarked sketches, {@code e^-depth} (below 1%). */
    static double sketchDelta() {
        return CountMinSketch.withErrorBounds(EPSILONS[0], DELTA, false).delta();
    }

    /** Runs both experiments, prints their tables and a RESULT line, and returns the worst cases. */
    static Summary run(int hllTrials, int cmsTrials, long seed, PrintStream out) {
        long started = System.nanoTime();
        Summary summary = new Summary();
        hyperLogLog(hllTrials, seed, out, summary);
        countMin(cmsTrials, seed, out, summary);
        out.printf(Locale.ROOT, "%nbench finished in %.1f s%n", (System.nanoTime() - started) / 1e9);
        out.printf(
                Locale.ROOT,
                "RESULT hll_worst_rmse_vs_theory=%.3f classic_worst_rmse_vs_theory=%.3f"
                        + " cms_worst_fraction_over_bound=%.5f%% delta=%.3f%% cms_under_counts=%d%n",
                summary.worstHllRatio,
                summary.worstClassicRatio,
                100 * summary.worstOverBoundFraction,
                100 * sketchDelta(),
                summary.underCounts);
        return summary;
    }

    /** Relative error of HyperLogLog at several cardinalities, per estimator. */
    static void hyperLogLog(int trials, long seed, PrintStream out, Summary summary) {
        out.printf(
                Locale.ROOT,
                "## HyperLogLog: relative error over %d trials per row (random 64-bit hashes, seed %d)%n%n",
                trials,
                seed);
        out.println(
                "| p | registers | n | bytes used | form | bias | RMSE | RMSE dense (Ertl) | RMSE classic | 1.04/sqrt(m) |");
        out.println("|---|---|---|---|---|---|---|---|---|---|");
        for (int p : PRECISIONS) {
            int c = CHECKPOINTS.length;
            double[] sum = new double[c];
            double[] sq = new double[c];
            double[] sqDense = new double[c];
            double[] sqClassic = new double[c];
            long[] bytes = new long[c];
            boolean[] sparseAtCheckpoint = new boolean[c];
            for (int t = 0; t < trials; t++) {
                SplittableRandom random = new SplittableRandom(seed * 1_000_003L + p * 10_007L + t);
                HyperLogLog adaptive = new HyperLogLog(p);
                HyperLogLog dense = HyperLogLog.dense(p);
                int next = 0;
                for (int n = 1; n <= CHECKPOINTS[c - 1]; n++) {
                    long h = random.nextLong();
                    adaptive.addHash(h);
                    dense.addHash(h);
                    if (n == CHECKPOINTS[next]) {
                        double e = adaptive.estimate() / n - 1;
                        double d = dense.estimate() / n - 1;
                        double k = dense.classicEstimate() / n - 1;
                        sum[next] += e;
                        sq[next] += e * e;
                        sqDense[next] += d * d;
                        sqClassic[next] += k * k;
                        bytes[next] = adaptive.memoryBytes();
                        sparseAtCheckpoint[next] = adaptive.isSparse();
                        next++;
                    }
                }
            }
            double theory = 1.04 / Math.sqrt(1 << p);
            for (int i = 0; i < c; i++) {
                summary.worstHllRatio = Math.max(summary.worstHllRatio, Math.sqrt(sq[i] / trials) / theory);
                summary.worstClassicRatio =
                        Math.max(summary.worstClassicRatio, Math.sqrt(sqClassic[i] / trials) / theory);
                out.printf(
                        Locale.ROOT,
                        "| %d | %,d | %,d | %,d | %s | %+.3f%% | %.3f%% | %.3f%% | %.3f%% | %.3f%% |%n",
                        p,
                        1 << p,
                        CHECKPOINTS[i],
                        bytes[i],
                        sparseAtCheckpoint[i] ? "sparse" : "dense",
                        100 * sum[i] / trials,
                        100 * Math.sqrt(sq[i] / trials),
                        100 * Math.sqrt(sqDense[i] / trials),
                        100 * Math.sqrt(sqClassic[i] / trials),
                        100 * theory);
            }
        }
    }

    /** Over-count of Count-Min Sketch against epsilon*N, and top-k recall, on Zipf streams. */
    static void countMin(int trials, long seed, PrintStream out, Summary summary) {
        out.printf(
                Locale.ROOT,
                "%n## Count-Min Sketch: %,d updates over %,d items, delta %.2f, %d trials per row (seed %d)%n%n",
                CMS_UPDATES,
                CMS_ITEMS,
                DELTA,
                trials,
                seed);
        out.println("| skew | epsilon | update | depth x width | bytes | epsilon*N | max over-count | mean over-count"
                + " | items over epsilon*N | under-counts | top-" + TOP + " recall |");
        out.println("|---|---|---|---|---|---|---|---|---|---|---|");
        for (double skew : SKEWS) {
            ZipfSampler zipf = new ZipfSampler(CMS_ITEMS, skew);
            for (double epsilon : EPSILONS) {
                for (boolean conservative : new boolean[] {false, true}) {
                    countMinRow(zipf, skew, epsilon, conservative, trials, seed, out, summary);
                }
            }
        }
    }

    /** One table row: the same seeded streams for every configuration. */
    private static void countMinRow(
            ZipfSampler zipf,
            double skew,
            double epsilon,
            boolean conservative,
            int trials,
            long seed,
            PrintStream out,
            Summary summary) {
        long maxOver = 0;
        double overSum = 0;
        long items = 0;
        long overBound = 0;
        long under = 0;
        long recalled = 0;
        CountMinSketch shape = CountMinSketch.withErrorBounds(epsilon, DELTA, conservative);
        for (int t = 0; t < trials; t++) {
            SplittableRandom random = new SplittableRandom(seed * 7_919L + t);
            long salt = random.nextLong();
            HeavyHitters heavy = new HeavyHitters(TOP, CountMinSketch.withErrorBounds(epsilon, DELTA, conservative));
            long[] truth = new long[CMS_ITEMS];
            for (int i = 0; i < CMS_UPDATES; i++) {
                int rank = zipf.sample(random);
                truth[rank]++;
                heavy.add(key(rank, salt));
            }
            CountMinSketch cms = heavy.sketch();
            long bound = cms.overcountBound();
            for (int r = 0; r < CMS_ITEMS; r++) {
                if (truth[r] == 0) {
                    continue;
                }
                long over = cms.estimate(key(r, salt)) - truth[r];
                items++;
                overSum += over;
                maxOver = Math.max(maxOver, over);
                if (over > bound) {
                    overBound++;
                }
                if (over < 0) {
                    under++;
                }
            }
            Set<String> reported = new HashSet<>();
            heavy.top().forEach(e -> reported.add(e.item()));
            for (String item : exactTop(truth, salt)) {
                if (reported.contains(item)) {
                    recalled++;
                }
            }
        }
        summary.worstOverBoundFraction = Math.max(summary.worstOverBoundFraction, (double) overBound / items);
        summary.underCounts += under;
        out.printf(
                Locale.ROOT,
                "| %.1f | %.3f | %s | %d x %,d | %,d | %,d | %,d | %.1f | %d of %,d (%.4f%%) | %d | %.1f%% |%n",
                skew,
                epsilon,
                conservative ? "conservative" : "plain",
                shape.depth(),
                shape.width(),
                shape.memoryBytes(),
                (long) Math.ceil(shape.epsilon() * CMS_UPDATES),
                maxOver,
                overSum / items,
                overBound,
                items,
                100.0 * overBound / items,
                under,
                100.0 * recalled / ((long) trials * TOP));
    }

    /** Distinct string key for a rank, different per trial. */
    private static String key(int rank, long salt) {
        return Long.toHexString(rank * 0x9E3779B97F4A7C15L + salt);
    }

    /** The true top items by count, ties broken by rank. */
    private static List<String> exactTop(long[] truth, long salt) {
        List<Integer> ranks = new ArrayList<>();
        for (int r = 0; r < truth.length; r++) {
            ranks.add(r);
        }
        ranks.sort((a, b) -> truth[a] != truth[b] ? Long.compare(truth[b], truth[a]) : Integer.compare(a, b));
        List<String> out = new ArrayList<>(TOP);
        for (int i = 0; i < TOP; i++) {
            out.add(key(ranks.get(i), salt));
        }
        return out;
    }
}
