package portfolio.sketches.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import portfolio.sketches.HyperLogLog;

/** Command-line entry point: {@code generate}, {@code analyze} and {@code bench}. */
public final class Main {
    /** Exit code for success. */
    static final int OK = 0;
    /** Exit code for an input or output error. */
    static final int IO_ERROR = 1;
    /** Exit code for bad command-line arguments. */
    static final int USAGE = 2;
    /** Exit code when the bench observes an error outside the bound it checks. */
    static final int BOUND_EXCEEDED = 3;
    /** Bench gate: worst HyperLogLog RMSE may exceed 1.04/sqrt(m) by at most this factor. */
    static final double HLL_RMSE_LIMIT = 1.25;

    static final String USAGE_TEXT = """
            usage:
              sketch generate --out FILE [--lines N] [--clients N] [--zipf S] [--seed N]
                  write a synthetic access log (Zipf-distributed client addresses); never overwrites
              sketch analyze FILE [--precision P] [--epsilon E] [--delta D] [--top K] [--plain] [--exact]
                  unique clients with HyperLogLog, top talkers with Count-Min Sketch + heap;
                  --exact also counts exactly and prints the error of every answer
              sketch bench [--trials N] [--cms-trials N] [--seed N]
                  observed error vs theoretical bounds over many seeded trials (Markdown tables);
                  exits with 3 if any observed error is outside its bound
            """;

    private Main() {}

    /** Runs the command and exits with its status. */
    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        System.exit(run(Arrays.asList(args), out, err));
    }

    /** Runs one command and returns the exit code (testable without exiting the JVM). */
    static int run(List<String> args, PrintStream out, PrintStream err) {
        if (args.isEmpty()) {
            err.print(USAGE_TEXT);
            return USAGE;
        }
        List<String> rest = args.subList(1, args.size());
        try {
            return switch (args.get(0)) {
                case "generate" -> generate(rest, out);
                case "analyze" -> analyze(rest, out);
                case "bench" -> bench(rest, out);
                case "help", "--help", "-h" -> {
                    out.print(USAGE_TEXT);
                    yield OK;
                }
                default -> throw new Args.UsageException("unknown command '" + args.get(0) + "'");
            };
        } catch (Args.UsageException | IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.print(USAGE_TEXT);
            return USAGE;
        } catch (FileAlreadyExistsException e) {
            err.println("error: " + e.getFile() + " already exists (generate never overwrites a file)");
            return IO_ERROR;
        } catch (NoSuchFileException e) {
            err.println("error: no such file: " + e.getFile());
            return IO_ERROR;
        } catch (IOException e) {
            err.println("error: " + e);
            return IO_ERROR;
        }
    }

    /** The {@code generate} command. */
    private static int generate(List<String> raw, PrintStream out) throws IOException {
        Args a = new Args(raw, Set.of("out", "lines", "clients", "zipf", "seed"), Set.of());
        if (!a.positional().isEmpty()) {
            throw new Args.UsageException("generate takes no positional arguments");
        }
        String file = a.option("out");
        if (file == null) {
            throw new Args.UsageException("generate needs --out FILE");
        }
        long lines = a.longOption("lines", 1_000_000, 0, LogGenerator.MAX_LINES);
        int clients = (int) a.longOption("clients", 200_000, 1, ZipfSampler.MAX_RANKS);
        double zipf = a.doubleOption("zipf", 1.1, 0, 3);
        long seed = a.longOption("seed", 42, Long.MIN_VALUE, Long.MAX_VALUE);
        Path path = Path.of(file);
        long started = System.nanoTime();
        LogGenerator.generate(path, lines, clients, zipf, seed);
        out.printf(
                Locale.ROOT,
                "wrote %,d lines from a pool of %,d clients (zipf %.2f, seed %d) to %s in %.1f s%n",
                lines,
                clients,
                zipf,
                seed,
                path,
                (System.nanoTime() - started) / 1e9);
        return OK;
    }

    /** The {@code analyze} command. */
    private static int analyze(List<String> raw, PrintStream out) throws IOException {
        Args a = new Args(raw, Set.of("precision", "epsilon", "delta", "top"), Set.of("plain", "exact"));
        if (a.positional().size() != 1) {
            throw new Args.UsageException("analyze needs exactly one log file");
        }
        LogAnalyzer.Settings s = new LogAnalyzer.Settings(
                (int) a.longOption(
                        "precision",
                        HyperLogLog.DEFAULT_PRECISION,
                        HyperLogLog.MIN_PRECISION,
                        HyperLogLog.MAX_PRECISION),
                a.doubleOption("epsilon", 0.001, 0, 0.5),
                a.doubleOption("delta", 0.01, 0, 0.5),
                (int) a.longOption("top", 10, 1, 1_000),
                !a.flag("plain"),
                a.flag("exact"));
        LogAnalyzer.analyze(Path.of(a.positional().get(0)), s, out);
        return OK;
    }

    /** The {@code bench} command. */
    private static int bench(List<String> raw, PrintStream out) {
        Args a = new Args(raw, Set.of("trials", "cms-trials", "seed"), Set.of());
        if (!a.positional().isEmpty()) {
            throw new Args.UsageException("bench takes no positional arguments");
        }
        AccuracyBench.Summary s = AccuracyBench.run(
                (int) a.longOption("trials", 200, 1, 100_000),
                (int) a.longOption("cms-trials", 10, 1, 10_000),
                a.longOption("seed", 1, 0, Long.MAX_VALUE / 2_000_000),
                out);
        boolean ok = s.worstHllRatio <= HLL_RMSE_LIMIT
                && s.worstOverBoundFraction <= AccuracyBench.sketchDelta()
                && s.underCounts == 0;
        out.println(ok ? "bounds: all observed errors inside their bounds" : "bounds: EXCEEDED");
        return ok ? OK : BOUND_EXCEEDED;
    }
}
