package portfolio.sketches.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class InputParsingTest {
    /** Reads every line of a string with the given limit. */
    private static List<String> lines(String text, int max) throws IOException {
        try (BoundedLineReader r = new BoundedLineReader(new StringReader(text), max)) {
            List<String> out = new java.util.ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                out.add(line == BoundedLineReader.OVERSIZED ? "<OVERSIZED>" : line);
            }
            return out;
        }
    }

    @Test
    void clientFieldAcceptsIpv4AndIpv6Only() {
        assertEquals("203.0.113.7", LogAnalyzer.clientOf("203.0.113.7 - - [x] \"GET / HTTP/1.1\" 200 1"));
        assertEquals("2001:db8::1", LogAnalyzer.clientOf("2001:db8::1 - - rest"));
        assertEquals("10.0.0.1", LogAnalyzer.clientOf("10.0.0.1"));
        assertNull(LogAnalyzer.clientOf(""));
        assertNull(LogAnalyzer.clientOf(" 10.0.0.1 leading space"));
        assertNull(LogAnalyzer.clientOf("host.example.com - - x"));
        assertNull(LogAnalyzer.clientOf("10.0.0.1;rm -rf / x"));
        assertNull(LogAnalyzer.clientOf("1".repeat(LogAnalyzer.MAX_CLIENT_CHARS + 1) + " x"));
        assertEquals("1".repeat(45), LogAnalyzer.clientOf("1".repeat(45) + " x"));
    }

    @Test
    void lineReaderHandlesTerminatorsAndAMissingFinalNewline() throws IOException {
        assertEquals(List.of("a", "b", "", "c"), lines("a\nb\r\n\nc", 10));
        assertEquals(List.of("a"), lines("a\n", 10));
        assertEquals(List.of(), lines("", 10));
        assertEquals(List.of(""), lines("\n", 10));
    }

    @Test
    void lineReaderSkipsOversizedLinesButKeepsReading() throws IOException {
        String max = "x".repeat(8);
        assertEquals(List.of(max, "<OVERSIZED>", "ok"), lines(max + "\n" + max + "y\nok\n", 8));
        assertEquals(List.of(max), lines(max + "\r\n", 8));
        assertEquals(List.of("<OVERSIZED>"), lines("z".repeat(1_000_000), 8));
        assertThrows(IllegalArgumentException.class, () -> new BoundedLineReader(new StringReader(""), 0));
    }

    @Test
    void lineReaderReturnsTheSentinelInstanceForOversizedLines() throws IOException {
        try (BoundedLineReader r = new BoundedLineReader(new StringReader("long line\n"), 3)) {
            assertSame(BoundedLineReader.OVERSIZED, r.readLine());
            assertNull(r.readLine());
        }
    }

    @Test
    void zipfSamplerFollowsItsDistribution() {
        ZipfSampler z = new ZipfSampler(1_000, 1.0);
        double h = 0;
        for (int i = 1; i <= 1_000; i++) {
            h += 1.0 / i;
        }
        int[] counts = new int[z.size()];
        SplittableRandom r = new SplittableRandom(3);
        int n = 500_000;
        for (int i = 0; i < n; i++) {
            counts[z.sample(r)]++;
        }
        for (int rank : new int[] {0, 1, 9}) {
            double expected = n / ((rank + 1) * h);
            assertEquals(expected, counts[rank], 5 * Math.sqrt(expected), "rank " + rank);
        }
        assertThrows(IllegalArgumentException.class, () -> new ZipfSampler(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ZipfSampler(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new ZipfSampler(ZipfSampler.MAX_RANKS + 1, 1));
    }

    @Test
    void addressesForDistinctRanksAreDistinct() {
        Set<String> seen = new java.util.HashSet<>();
        for (int rank = 0; rank < 100_000; rank++) {
            StringBuilder sb = new StringBuilder();
            LogGenerator.appendAddress(sb, rank);
            assertTrue(seen.add(sb.toString()), "duplicate address for rank " + rank);
            assertEquals(sb.toString(), LogAnalyzer.clientOf(sb + " - -"));
        }
    }

    @Test
    void argsRejectUnknownRepeatedAndOutOfRangeOptions() {
        Set<String> opts = Set.of("lines");
        assertThrows(Args.UsageException.class, () -> new Args(List.of("--nope"), opts, Set.of()));
        assertThrows(Args.UsageException.class, () -> new Args(List.of("--lines"), opts, Set.of()));
        assertThrows(
                Args.UsageException.class, () -> new Args(List.of("--lines", "1", "--lines", "2"), opts, Set.of()));
        Args a = new Args(List.of("--lines", "1_000", "file"), opts, Set.of());
        assertEquals(1000, a.longOption("lines", 5, 0, 10_000));
        assertEquals(List.of("file"), a.positional());
        assertThrows(Args.UsageException.class, () -> a.longOption("lines", 5, 0, 10));
        Args b = new Args(List.of("--lines", "ten"), opts, Set.of());
        assertThrows(Args.UsageException.class, () -> b.longOption("lines", 5, 0, 10));
        assertThrows(Args.UsageException.class, () -> b.doubleOption("lines", 5, 0, 10));
        Args c = new Args(List.of("--lines", "NaN"), opts, Set.of());
        assertThrows(Args.UsageException.class, () -> c.doubleOption("lines", 0.5, 0, 1));
    }
}
