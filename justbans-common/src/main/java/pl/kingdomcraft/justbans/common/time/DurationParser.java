package pl.kingdomcraft.justbans.common.time;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and formats punishment durations the same way on every platform.
 *
 * <p>Accepted input: one or more {@code <number><unit>} groups, optionally separated by spaces,
 * e.g. {@code 30d}, {@code 1d12h}, {@code 90m}, {@code 2w 3d}; or {@code perm} / {@code permanent}.
 * Units: {@code s}, {@code m} (minutes), {@code h}, {@code d}, {@code w}, {@code mo} (30 days), {@code y} (365 days).
 */
public final class DurationParser {

	/** Sentinel returned for permanent durations. Compare with {@link #isPermanent(Duration)}. */
	public static final Duration PERMANENT = Duration.ofSeconds(Long.MAX_VALUE);

	/** Longest finite duration accepted. */
	public static final Duration MAX = Duration.ofDays(365L * 10);

	private static final Pattern GROUP = Pattern.compile("(\\d+)(mo|y|w|d|h|m|s)");
	private static final Pattern WHOLE = Pattern.compile("(?:\\d+(?:mo|y|w|d|h|m|s)\\s*)+");

	private DurationParser() {
	}

	public static boolean isPermanent(Duration duration) {
		return duration == null || PERMANENT.equals(duration);
	}

	/**
	 * @return the duration, or {@link #PERMANENT}
	 * @throws IllegalArgumentException if the input is blank, malformed, zero or longer than {@link #MAX}
	 */
	public static Duration parse(String input) {
		if (input == null || input.isBlank())
			throw new IllegalArgumentException("Duration is empty");

		final String text = input.trim().toLowerCase(Locale.ROOT);

		if (text.equals("perm") || text.equals("permanent"))
			return PERMANENT;

		if (!WHOLE.matcher(text).matches())
			throw new IllegalArgumentException("Invalid duration '" + input + "', expected e.g. 30d, 1d12h, 90m or perm");

		Duration total = Duration.ZERO;
		final Matcher matcher = GROUP.matcher(text);

		try {
			while (matcher.find()) {
				final long amount = Long.parseLong(matcher.group(1));
				total = total.plus(unit(matcher.group(2)).multipliedBy(amount));

				if (total.compareTo(MAX) > 0)
					throw new IllegalArgumentException("Duration '" + input + "' is longer than the maximum of 10 years");
			}
		} catch (final ArithmeticException | NumberFormatException ex) {
			throw new IllegalArgumentException("Duration '" + input + "' is too large");
		}

		if (total.isZero())
			throw new IllegalArgumentException("Duration must be greater than zero");

		return total;
	}

	/** Like {@link #parse(String)} but returns null instead of throwing. */
	public static Duration tryParse(String input) {
		try {
			return parse(input);
		} catch (final IllegalArgumentException ex) {
			return null;
		}
	}

	/**
	 * Expiry instant for a duration starting now, or null if permanent.
	 */
	public static Instant expiry(Instant now, Duration duration) {
		return isPermanent(duration) ? null : now.plus(duration);
	}

	/**
	 * Formats as e.g. {@code 1d 12h 30m}. Seconds are only shown when nothing larger is present
	 * or they are non-zero; sub-second precision is dropped.
	 */
	public static String format(Duration duration) {
		if (isPermanent(duration))
			return "permanent";

		long seconds = duration.getSeconds();

		if (seconds <= 0)
			return "0s";

		final long days = seconds / 86_400;
		seconds %= 86_400;
		final long hours = seconds / 3_600;
		seconds %= 3_600;
		final long minutes = seconds / 60;
		seconds %= 60;

		final List<String> parts = new ArrayList<>();

		if (days > 0)
			parts.add(days + "d");
		if (hours > 0)
			parts.add(hours + "h");
		if (minutes > 0)
			parts.add(minutes + "m");
		if (seconds > 0)
			parts.add(seconds + "s");

		return String.join(" ", parts);
	}

	private static Duration unit(String suffix) {
		return switch (suffix) {
			case "s" -> Duration.ofSeconds(1);
			case "m" -> Duration.ofMinutes(1);
			case "h" -> Duration.ofHours(1);
			case "d" -> Duration.ofDays(1);
			case "w" -> Duration.ofDays(7);
			case "mo" -> Duration.ofDays(30);
			case "y" -> Duration.ofDays(365);
			default -> throw new IllegalStateException("Unknown unit " + suffix);
		};
	}
}
