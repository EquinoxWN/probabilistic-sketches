package portfolio.sketches.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Minimal command-line parser: positional arguments, {@code --name value} options and boolean flags. */
final class Args {
    private final List<String> positional = new ArrayList<>();
    private final Map<String, String> options = new HashMap<>();
    private final Set<String> flags = new HashSet<>();

    /** Parses arguments, accepting only the named options and flags. */
    Args(List<String> args, Set<String> allowedOptions, Set<String> allowedFlags) {
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.startsWith("--")) {
                String name = a.substring(2);
                if (allowedFlags.contains(name)) {
                    flags.add(name);
                } else if (allowedOptions.contains(name)) {
                    if (i + 1 >= args.size()) {
                        throw new UsageException("--" + name + " needs a value");
                    }
                    if (options.put(name, args.get(++i)) != null) {
                        throw new UsageException("--" + name + " given twice");
                    }
                } else {
                    throw new UsageException("unknown option --" + name);
                }
            } else {
                positional.add(a);
            }
        }
    }

    /** Positional arguments in order. */
    List<String> positional() {
        return positional;
    }

    /** True when the flag was given. */
    boolean flag(String name) {
        return flags.contains(name);
    }

    /** Option value or null. */
    String option(String name) {
        return options.get(name);
    }

    /** Integer option within [min, max], or the default. */
    long longOption(String name, long fallback, long min, long max) {
        String v = options.get(name);
        if (v == null) {
            return fallback;
        }
        long value;
        try {
            value = Long.parseLong(v.replace("_", ""));
        } catch (NumberFormatException e) {
            throw new UsageException("--" + name + " must be a whole number, got '" + v + "'");
        }
        if (value < min || value > max) {
            throw new UsageException("--" + name + " must be between " + min + " and " + max + ", got " + value);
        }
        return value;
    }

    /** Decimal option strictly inside (min, max], or the default. */
    double doubleOption(String name, double fallback, double minExclusive, double maxInclusive) {
        String v = options.get(name);
        if (v == null) {
            return fallback;
        }
        double value;
        try {
            value = Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new UsageException("--" + name + " must be a number, got '" + v + "'");
        }
        if (!(value > minExclusive && value <= maxInclusive)) {
            throw new UsageException("--" + name + " must be greater than " + minExclusive + " and at most "
                    + maxInclusive + ", got " + v);
        }
        return value;
    }

    /** Bad command-line input; Main prints it with the usage text and exits with 2. */
    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception with a message for the user. */
        UsageException(String message) {
            super(message);
        }
    }
}
