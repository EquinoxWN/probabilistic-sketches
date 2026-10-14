package portfolio.sketches.cli;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Writes a synthetic web access log in Common Log Format. Client addresses follow a Zipf
 * distribution over a fixed pool, so a few clients send most requests, as in real traffic. The
 * same arguments always produce the same file.
 */
final class LogGenerator {
    /** Largest number of lines one call may write. */
    static final long MAX_LINES = 100_000_000L;

    private static final String[] PATHS = {
        "/", "/index.html", "/products", "/products/42", "/cart", "/checkout", "/api/items", "/login", "/static/app.js"
    };
    private static final int[] STATUS = {200, 200, 200, 200, 200, 200, 304, 301, 404, 500};
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);
    private static final long START_EPOCH_SECOND =
            Instant.parse("2026-10-07T00:00:00Z").getEpochSecond();
    private static final int LINES_PER_SECOND = 50;

    private LogGenerator() {}

    /** Writes {@code lines} log lines drawn from {@code clients} addresses; refuses to overwrite a file. */
    static void generate(Path out, long lines, int clients, double zipf, long seed) throws IOException {
        if (lines < 0 || lines > MAX_LINES) {
            throw new IllegalArgumentException("lines must be between 0 and " + MAX_LINES);
        }
        ZipfSampler sampler = new ZipfSampler(clients, zipf);
        SplittableRandom random = new SplittableRandom(seed);
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            String stamp = "";
            long stampSecond = -1;
            StringBuilder sb = new StringBuilder(128);
            for (long i = 0; i < lines; i++) {
                long second = START_EPOCH_SECOND + i / LINES_PER_SECOND;
                if (second != stampSecond) {
                    stamp = TIME.format(Instant.ofEpochSecond(second));
                    stampSecond = second;
                }
                sb.setLength(0);
                appendAddress(sb, sampler.sample(random));
                sb.append(" - - [").append(stamp).append("] \"GET ");
                sb.append(PATHS[random.nextInt(PATHS.length)]).append(" HTTP/1.1\" ");
                sb.append(STATUS[random.nextInt(STATUS.length)]).append(' ');
                sb.append(200 + random.nextInt(50_000)).append('\n');
                w.append(sb);
            }
        }
    }

    /** Maps a client rank to a distinct IPv4 address (an odd multiplier is a bijection on 32 bits). */
    static void appendAddress(StringBuilder sb, int rank) {
        int x = rank * 0x9E3779B1 + 0x7F4A7C15;
        sb.append(x >>> 24)
                .append('.')
                .append((x >>> 16) & 0xff)
                .append('.')
                .append((x >>> 8) & 0xff)
                .append('.')
                .append(x & 0xff);
    }
}
