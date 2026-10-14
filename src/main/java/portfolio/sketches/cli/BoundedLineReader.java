package portfolio.sketches.cli;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;

/** Reads lines without ever holding more than a fixed number of characters of one line. */
final class BoundedLineReader implements Closeable {
    /** Returned for a line longer than the limit; the rest of that line is skipped. */
    static final String OVERSIZED = new String("<oversized>");

    private final Reader in;
    private final int maxChars;
    private final char[] buf = new char[1 << 16];
    private final StringBuilder line = new StringBuilder();
    private int pos;
    private int end;

    /** Wraps a reader with a per-line character limit. */
    BoundedLineReader(Reader in, int maxChars) {
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        this.in = in;
        this.maxChars = maxChars;
    }

    /** Next line without its terminator, {@link #OVERSIZED}, or null at end of input. */
    String readLine() throws IOException {
        line.setLength(0);
        boolean oversized = false;
        boolean any = false;
        while (true) {
            if (pos == end) {
                end = in.read(buf, 0, buf.length);
                pos = 0;
                if (end <= 0) {
                    end = 0;
                    if (!any) {
                        return null;
                    }
                    break;
                }
            }
            any = true;
            char c = buf[pos++];
            if (c == '\n') {
                break;
            }
            if (!oversized) {
                if (line.length() > maxChars) {
                    oversized = true;
                    line.setLength(0);
                } else {
                    line.append(c);
                }
            }
        }
        int len = line.length();
        if (len > 0 && line.charAt(len - 1) == '\r') {
            line.setLength(--len);
        }
        return oversized || len > maxChars ? OVERSIZED : line.toString();
    }

    /** Closes the underlying reader. */
    @Override
    public void close() throws IOException {
        in.close();
    }
}
