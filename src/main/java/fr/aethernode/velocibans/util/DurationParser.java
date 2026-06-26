package fr.aethernode.velocibans.util;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses human-readable duration strings into {@link Duration}.
 *
 * <p>Supported units: {@code s} (seconds), {@code min}/{@code m} (minutes),
 * {@code h} (hours), {@code d} (days), {@code w} (weeks), {@code mo} (months ≈ 30 days).
 *
 * <p>Examples: {@code 7d}, {@code 2h}, {@code 30min}, {@code 1w}, {@code 1mo}, {@code 80s}.
 */
public final class DurationParser {

    // Order matters: "mo" and "min" must be tested before "m"
    private static final Pattern PATTERN =
            Pattern.compile("(\\d+)\\s*(mo|min|[smhdw])", Pattern.CASE_INSENSITIVE);

    private DurationParser() {}

    /**
     * @param input the raw string, e.g. {@code "7d"} or {@code "30min"}
     * @return a {@link Duration}, or {@code null} if parsing fails
     */
    public static Duration parse(String input) {
        if (input == null || input.isBlank()) return null;

        Matcher m = PATTERN.matcher(input.trim());
        if (!m.matches()) return null;

        long value = Long.parseLong(m.group(1));
        String unit = m.group(2).toLowerCase();

        return switch (unit) {
            case "s"   -> Duration.ofSeconds(value);
            case "m", "min" -> Duration.ofMinutes(value);
            case "h"   -> Duration.ofHours(value);
            case "d"   -> Duration.ofDays(value);
            case "w"   -> Duration.ofDays(value * 7);
            case "mo"  -> Duration.ofDays(value * 30);
            default    -> null;
        };
    }

    /** Formats a duration back to a short human-readable string for display. */
    public static String format(Duration duration) {
        if (duration == null) return "permanent";
        long totalSeconds = duration.getSeconds();
        if (totalSeconds <= 0) return "permanent";

        long months  = totalSeconds / (30L * 86400);
        long rem     = totalSeconds % (30L * 86400);
        long weeks   = rem / (7L * 86400);
        rem          = rem % (7L * 86400);
        long days    = rem / 86400;
        rem          = rem % 86400;
        long hours   = rem / 3600;
        rem          = rem % 3600;
        long minutes = rem / 60;
        long seconds = rem % 60;

        StringBuilder sb = new StringBuilder();
        if (months  > 0) sb.append(months).append("mo ");
        if (weeks   > 0) sb.append(weeks).append("w ");
        if (days    > 0) sb.append(days).append("d ");
        if (hours   > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("min ");
        if (seconds > 0) sb.append(seconds).append("s ");

        return sb.toString().trim();
    }
}
