package portfolio.sketches;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Statistical checks: many seeded trials per setting, each compared with the theoretical standard
 * error {@code 1.04 / sqrt(m)}. Over 100 trials the measured RMSE itself varies by about 7%, so the
 * limit allows 25% above theory; the seeds are fixed, so the outcome is reproducible.
 */
class HyperLogLogAccuracyTest {
    private static final int TRIALS = 100;
    private static final int[] CHECKPOINTS = {1_000, 10_000, 100_000, 1_000_000};

    @ParameterizedTest(name = "p = {0}")
    @ValueSource(ints = {10, 12, 14})
    void rmseStaysWithinTheTheoreticalStandardErrorAndBiasNearZero(int p) {
        double[] sum = new double[CHECKPOINTS.length];
        double[] sq = new double[CHECKPOINTS.length];
        for (int t = 0; t < TRIALS; t++) {
            SplittableRandom r = new SplittableRandom(p * 1_000L + t);
            HyperLogLog h = new HyperLogLog(p);
            int next = 0;
            for (int n = 1; n <= CHECKPOINTS[CHECKPOINTS.length - 1]; n++) {
                h.addHash(r.nextLong());
                if (n == CHECKPOINTS[next]) {
                    double e = h.estimate() / n - 1;
                    sum[next] += e;
                    sq[next] += e * e;
                    next++;
                }
            }
        }
        double theory = 1.04 / Math.sqrt(1 << p);
        for (int i = 0; i < CHECKPOINTS.length; i++) {
            double rmse = Math.sqrt(sq[i] / TRIALS);
            double bias = sum[i] / TRIALS;
            assertTrue(rmse <= 1.25 * theory, "p=" + p + " n=" + CHECKPOINTS[i] + " RMSE " + rmse + " vs " + theory);
            // Three standard errors of the mean, plus 10% of theory so that near-exact sparse
            // estimates (RMSE close to 0) are not judged against a vanishing tolerance.
            assertTrue(
                    Math.abs(bias) <= 3 * rmse / Math.sqrt(TRIALS) + 0.1 * theory,
                    "p=" + p + " n=" + CHECKPOINTS[i] + " bias " + bias + " (RMSE " + rmse + ")");
        }
    }

    @ParameterizedTest(name = "p = {0}")
    @ValueSource(ints = {12, 14})
    void improvedEstimatorRemovesTheClassicErrorBumpNearTwoPointFiveM(int p) {
        // The classic estimator switches from linear counting to the raw estimate at 2.5m, where
        // both are poor; Ertl's estimator has no switch.
        int n = (int) (2.5 * (1 << p));
        double sqImproved = 0;
        double sqClassic = 0;
        for (int t = 0; t < TRIALS; t++) {
            SplittableRandom r = new SplittableRandom(77_000L + p * 1_000L + t);
            HyperLogLog h = HyperLogLog.dense(p);
            for (int i = 0; i < n; i++) {
                h.addHash(r.nextLong());
            }
            double e = h.estimate() / n - 1;
            double c = h.classicEstimate() / n - 1;
            sqImproved += e * e;
            sqClassic += c * c;
        }
        double improved = Math.sqrt(sqImproved / TRIALS);
        double classic = Math.sqrt(sqClassic / TRIALS);
        double theory = 1.04 / Math.sqrt(1 << p);
        assertTrue(improved <= 1.25 * theory, "improved RMSE " + improved + " vs theory " + theory);
        assertTrue(classic >= 1.5 * improved, "classic RMSE " + classic + " vs improved " + improved);
    }

    @ParameterizedTest(name = "p = {0}")
    @ValueSource(ints = {12, 14})
    void sparseModeIsMoreAccurateThanDenseForSmallSets(int p) {
        int n = (1 << p) / 16;
        double sqSparse = 0;
        double sqDense = 0;
        for (int t = 0; t < TRIALS; t++) {
            SplittableRandom r = new SplittableRandom(99_000L + p * 1_000L + t);
            HyperLogLog sparse = new HyperLogLog(p);
            HyperLogLog dense = HyperLogLog.dense(p);
            for (int i = 0; i < n; i++) {
                long h = r.nextLong();
                sparse.addHash(h);
                dense.addHash(h);
            }
            assertTrue(sparse.isSparse());
            double s = sparse.estimate() / n - 1;
            double d = dense.estimate() / n - 1;
            sqSparse += s * s;
            sqDense += d * d;
        }
        double sparseRmse = Math.sqrt(sqSparse / TRIALS);
        double denseRmse = Math.sqrt(sqDense / TRIALS);
        assertTrue(sparseRmse <= 0.001, "sparse RMSE " + sparseRmse);
        assertTrue(sparseRmse * 10 <= denseRmse, "sparse " + sparseRmse + " vs dense " + denseRmse);
    }
}
