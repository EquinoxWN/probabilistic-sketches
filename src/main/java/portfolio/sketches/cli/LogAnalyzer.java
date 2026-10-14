package portfolio.sketches.cli;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import portfolio.sketches.CountMinSketch;
import portfolio.sketches.HeavyHitters;
import portfolio.sketches.HyperLogLog;

/**
 * Counts unique clients (HyperLogLog) and top talkers (Count-Min Sketch with a top-k heap) in a
 * web access log whose first field is the client address. With {@code exact} it also counts
 * everything exactly in a hash map and prints how far each sketch answer is from the truth.
 */
final class LogAnalyzer {
    /** Longest line accepted; longer lines are skipped and counted. */
    static final int MAX_LINE_CHARS = 8192;
    /** Longest client field accepted (a full IPv6 address with an embedded IPv4 part is 45). */
    static final int MAX_CLIENT_CHARS = 45;
    /** Most distinct clients {@code --exact} will hold in memory (the sketches have no such limit). */
    static final int MAX_EXACT_KEYS = 5_000_000;

    /** Sketch settings for one run. */
    record Settings(int precision, double epsilon, double delta, int top, boolean conservative, boolean exact) {}

    /** What one run measured, for tests and the summary line. */
    record Report(
            long lines,
            long skipped,
            long uniqueEstimate,
            long uniqueExact,
            int topRecall,
            int topInRange,
            int topCompared,
            boolean withinThreeSigma) {}

    private LogAnalyzer() {}

    /** Reads the log once, feeding every valid client into the sketches, and prints the results. */
    static Report analyze(Path log, Settings s, PrintStream out) throws IOException {
        HyperLogLog hll = new HyperLogLog(s.precision());
        CountMinSketch cms = CountMinSketch.withErrorBounds(s.epsilon(), s.delta(), s.conservative());
        HeavyHitters heavy = new HeavyHitters(s.top(), cms);
        Map<String, long[]> exact = s.exact() ? new HashMap<>() : null;
        long lines = 0;
        long skipped = 0;
        var decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (BoundedLineReader reader =
                new BoundedLineReader(new InputStreamReader(Files.newInputStream(log), decoder), MAX_LINE_CHARS)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines++;
                String client = line == BoundedLineReader.OVERSIZED ? null : clientOf(line);
                if (client == null) {
                    skipped++;
                    continue;
                }
                hll.add(client);
                heavy.add(client);
                if (exact != null) {
                    exact.computeIfAbsent(client, k -> new long[1])[0]++;
                    if (exact.size() > MAX_EXACT_KEYS) {
                        throw new IllegalArgumentException("--exact stops above " + MAX_EXACT_KEYS
                                + " distinct clients; run without --exact to use the sketches alone");
                    }
                }
            }
        }
        return print(log, s, out, lines, skipped, hll, heavy, exact);
    }

    /** First space-separated field if it looks like an IPv4 or IPv6 address, else null. */
    static String clientOf(String line) {
        int end = line.indexOf(' ');
        if (end < 0) {
            end = line.length();
        }
        if (end == 0 || end > MAX_CLIENT_CHARS) {
            return null;
        }
        for (int i = 0; i < end; i++) {
            char c = line.charAt(i);
            boolean ok =
                    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '.' || c == ':';
            if (!ok) {
                return null;
            }
        }
        return line.substring(0, end);
    }

    /** Prints the human-readable report and the one-line RESULT summary. */
    private static Report print(
            Path log,
            Settings s,
            PrintStream out,
            long lines,
            long skipped,
            HyperLogLog hll,
            HeavyHitters heavy,
            Map<String, long[]> exact) {
        CountMinSketch cms = heavy.sketch();
        long estimate = hll.cardinality();
        out.printf(Locale.ROOT, "input           %s%n", log.getFileName());
        out.printf(
                Locale.ROOT,
                "lines           %,d read, %,d skipped (not starting with an address, or over %,d characters)%n%n",
                lines,
                skipped,
                MAX_LINE_CHARS);
        out.printf(
                Locale.ROOT,
                "unique clients  HyperLogLog p=%d, %s, %,d bytes, standard error %.2f%%%n",
                hll.precision(),
                hll.isSparse() ? "sparse" : "dense",
                hll.memoryBytes(),
                100 * hll.relativeStandardError());
        out.printf(Locale.ROOT, "  estimate      %,d%n", estimate);
        long uniqueExact = -1;
        boolean withinThreeSigma = false;
        if (exact != null) {
            uniqueExact = exact.size();
            double error = uniqueExact == 0 ? 0 : (estimate - uniqueExact) / (double) uniqueExact;
            double sigmas = error / hll.relativeStandardError();
            withinThreeSigma = Math.abs(sigmas) <= 3;
            out.printf(
                    Locale.ROOT,
                    "  exact         %,d (error %+.3f%%, %+.2f standard errors)%n",
                    uniqueExact,
                    100 * error,
                    sigmas);
        }
        out.printf(
                Locale.ROOT,
                "%ntop %d clients  Count-Min %d x %,d, %,d bytes, epsilon %.4f, delta %.4f, %s updates%n",
                s.top(),
                cms.depth(),
                cms.width(),
                cms.memoryBytes(),
                cms.epsilon(),
                cms.delta(),
                cms.isConservative() ? "conservative" : "plain");
        out.printf(
                Locale.ROOT, "  N = %,d, over-count bound epsilon*N = %,d%n", cms.totalCount(), cms.overcountBound());
        List<HeavyHitters.Entry> top = heavy.top();
        int width = "client".length();
        for (HeavyHitters.Entry e : top) {
            width = Math.max(width, e.item().length());
        }
        String row = "  %-4s %-" + width + "s %12s %25s%s%n";
        out.printf(
                Locale.ROOT,
                row,
                "#",
                "client",
                "estimate",
                "likely range",
                exact != null ? String.format(Locale.ROOT, " %12s", "exact") : "");
        int inRange = 0;
        for (int i = 0; i < top.size(); i++) {
            HeavyHitters.Entry e = top.get(i);
            String range = String.format(Locale.ROOT, "%,d - %,d", e.lowerBound(), e.estimate());
            String truth = "";
            if (exact != null) {
                long t = exact.get(e.item())[0];
                if (t >= e.lowerBound() && t <= e.estimate()) {
                    inRange++;
                }
                truth = String.format(Locale.ROOT, " %,12d", t);
            }
            out.printf(
                    Locale.ROOT, row, i + 1, e.item(), String.format(Locale.ROOT, "%,d", e.estimate()), range, truth);
        }
        int recall = 0;
        if (exact != null) {
            Set<String> reported = new HashSet<>();
            top.forEach(e -> reported.add(e.item()));
            for (String item : exactTop(exact, s.top())) {
                if (reported.contains(item)) {
                    recall++;
                }
            }
            int compared = Math.min(s.top(), exact.size());
            out.printf(
                    Locale.ROOT,
                    "%nRESULT unique_error=%+.3f%% within_3_sigma=%s top%d_recall=%d/%d counts_in_range=%d/%d%n",
                    uniqueExact == 0 ? 0.0 : 100.0 * (estimate - uniqueExact) / uniqueExact,
                    withinThreeSigma ? "yes" : "no",
                    s.top(),
                    recall,
                    compared,
                    inRange,
                    top.size());
        }
        return new Report(
                lines,
                skipped,
                estimate,
                uniqueExact,
                recall,
                inRange,
                exact == null ? 0 : Math.min(s.top(), exact.size()),
                withinThreeSigma);
    }

    /** The true top-k items, ties broken by item so the answer is deterministic. */
    private static List<String> exactTop(Map<String, long[]> exact, int k) {
        List<Map.Entry<String, long[]>> all = new ArrayList<>(exact.entrySet());
        all.sort((a, b) -> {
            int c = Long.compare(b.getValue()[0], a.getValue()[0]);
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        List<String> out = new ArrayList<>(k);
        for (int i = 0; i < Math.min(k, all.size()); i++) {
            out.add(all.get(i).getKey());
        }
        return out;
    }
}
