package portfolio.sketches.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Generates access logs, analyzes them through the command line, and checks every answer. */
class EndToEndTest {
    @TempDir
    Path dir;

    /** Result of one CLI run. */
    private record Run(int code, String out, String err) {}

    /** Runs the CLI in-process and captures its output. */
    private static Run cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(
                Arrays.asList(args),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void sketchAnswersOnAGeneratedLogStayInsideTheirBounds() throws IOException {
        Path log = dir.resolve("access.log");
        Run gen = cli("generate", "--out", log.toString(), "--lines", "300000", "--clients", "60000", "--seed", "7");
        assertEquals(0, gen.code(), gen.err());
        LogAnalyzer.Report report = LogAnalyzer.analyze(
                log,
                new LogAnalyzer.Settings(14, 0.001, 0.01, 10, true, true),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals(300_000, report.lines());
        assertEquals(0, report.skipped());
        assertTrue(report.withinThreeSigma(), report.toString());
        assertEquals(10, report.topRecall());
        assertEquals(10, report.topInRange());
        double error = Math.abs(report.uniqueEstimate() - report.uniqueExact()) / (double) report.uniqueExact();
        assertTrue(error <= 3 * 1.04 / Math.sqrt(1 << 14), "unique error " + error);
    }

    @Test
    void analyzePrintsTheSummaryLineCiReads() throws IOException {
        Path log = dir.resolve("small.log");
        assertEquals(
                0,
                cli("generate", "--out", log.toString(), "--lines", "20000", "--clients", "3000")
                        .code());
        Run run = cli("analyze", log.toString(), "--exact", "--top", "5");
        assertEquals(0, run.code(), run.err());
        assertTrue(run.out().contains("HyperLogLog p=14, sparse"), run.out());
        assertTrue(run.out().contains("conservative updates"), run.out());
        assertTrue(run.out().contains("RESULT unique_error="), run.out());
        assertTrue(run.out().contains("within_3_sigma=yes top5_recall=5/5 counts_in_range=5/5"), run.out());
        Run plain = cli("analyze", log.toString(), "--plain", "--precision", "10");
        assertEquals(0, plain.code(), plain.err());
        assertTrue(plain.out().contains("plain updates"), plain.out());
        assertFalse(plain.out().contains("RESULT"), "RESULT needs --exact");
    }

    @Test
    void generationIsDeterministicPerSeed() throws IOException {
        Path a = dir.resolve("a.log");
        Path b = dir.resolve("b.log");
        Path c = dir.resolve("c.log");
        cli("generate", "--out", a.toString(), "--lines", "5000", "--seed", "11");
        cli("generate", "--out", b.toString(), "--lines", "5000", "--seed", "11");
        cli("generate", "--out", c.toString(), "--lines", "5000", "--seed", "12");
        assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b));
        assertFalse(Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(c)));
        assertEquals(5000, Files.readAllLines(a).size());
    }

    @Test
    void malformedOversizedAndInvalidUtf8LinesAreSkippedAndCounted() throws IOException {
        Path log = dir.resolve("messy.log");
        byte[] badUtf8 = {(byte) 0xC3, (byte) 0x28, ' ', 'x', '\n'};
        try (var out = Files.newOutputStream(log)) {
            out.write(("10.0.0.1 - - ok\n"
                            + "not-an-address - - x\n"
                            + "\n"
                            + "10.0.0.2 " + "y".repeat(LogAnalyzer.MAX_LINE_CHARS) + "\n"
                            + "10.0.0.1 - - again\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(badUtf8);
            out.write("2001:db8::7".getBytes(StandardCharsets.UTF_8));
        }
        LogAnalyzer.Report r = LogAnalyzer.analyze(
                log,
                new LogAnalyzer.Settings(12, 0.01, 0.01, 3, true, true),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals(7, r.lines());
        assertEquals(4, r.skipped());
        assertEquals(2, r.uniqueExact());
        assertEquals(2, r.uniqueEstimate());
        assertEquals(2, r.topRecall());
    }

    @Test
    void usageErrorsExitWithTwoAndFileErrorsWithOne() throws IOException {
        assertEquals(2, cli().code());
        assertEquals(2, cli("frobnicate").code());
        assertEquals(2, cli("analyze").code());
        assertEquals(2, cli("analyze", "a.log", "b.log").code());
        assertEquals(2, cli("analyze", "a.log", "--precision", "30").code());
        assertEquals(2, cli("analyze", "a.log", "--epsilon", "0").code());
        assertEquals(2, cli("generate", "--lines", "10").code());
        assertEquals(2, cli("generate", "--out", "x.log", "--clients", "0").code());
        assertEquals(2, cli("bench", "--trials", "0").code());
        assertEquals(0, cli("help").code());
        Run missing = cli("analyze", dir.resolve("missing.log").toString());
        assertEquals(1, missing.code());
        assertTrue(missing.err().contains("no such file"), missing.err());
        Path existing = dir.resolve("exists.log");
        Files.writeString(existing, "keep me\n");
        Run overwrite = cli("generate", "--out", existing.toString(), "--lines", "10");
        assertEquals(1, overwrite.code());
        assertTrue(overwrite.err().contains("already exists"), overwrite.err());
        assertEquals(List.of("keep me"), Files.readAllLines(existing));
    }
}
