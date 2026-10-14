package pl.kingdomcraft.justbans.common.render;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Human-readable "time left" for ban screens, e.g. {@code 6d 3h}. Rounds up to the next second so a ban with
 * 200 ms left never reads "0s", and shows at most {@code maxUnits} units starting from the largest non-zero one.
 */
public final class RemainingTimeFormatter {

	/** Singular and plural text for one unit, including any leading space (e.g. {@code " day"}, {@code " days"}). */
	public record Unit(String singular, String plural) {

		public static Unit same(String text) {
			return new Unit(text, text);
		}

		String of(long amount) {
			return amount + (amount == 1 ? this.singular : this.plural);
		}
	}

	public record Labels(Unit day, Unit hour, Unit minute, Unit second, String permanent, String expired) {

		public static final Labels SHORT_EN = new Labels(Unit.same("d"), Unit.same("h"), Unit.same("m"), Unit.same("s"), "never", "expired");

		public static final Labels LONG_EN = new Labels(new Unit(" day", " days"), new Unit(" hour", " hours"), new Unit(" minute", " minutes"),
				new Unit(" second", " seconds"), "never", "expired");
	}

	private final Labels labels;
	private final int maxUnits;

	public RemainingTimeFormatter(Labels labels, int maxUnits) {
		if (maxUnits < 1)
			throw new IllegalArgumentException("maxUnits must be at least 1");

		this.labels = labels;
		this.maxUnits = maxUnits;
	}

	/** Short English, two units: {@code 6d 3h}. */
	public static RemainingTimeFormatter defaults() {
		return new RemainingTimeFormatter(Labels.SHORT_EN, 2);
	}

	/**
	 * @param expiresAt null means permanent
	 */
	public String format(Instant now, Instant expiresAt) {
		if (expiresAt == null)
			return this.labels.permanent();

		if (!expiresAt.isAfter(now))
			return this.labels.expired();

		return this.format(Duration.between(now, expiresAt));
	}

	/** Formats a positive duration; a zero or negative one reads as expired. */
	public String format(Duration remaining) {
		if (remaining.isNegative() || remaining.isZero())
			return this.labels.expired();

		long seconds = remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0);

		final long days = seconds / 86_400;
		seconds %= 86_400;
		final long hours = seconds / 3_600;
		seconds %= 3_600;
		final long minutes = seconds / 60;
		seconds %= 60;

		final List<String> parts = new ArrayList<>();
		this.add(parts, days, this.labels.day());
		this.add(parts, hours, this.labels.hour());
		this.add(parts, minutes, this.labels.minute());
		this.add(parts, seconds, this.labels.second());

		return String.join(" ", parts.subList(0, Math.min(this.maxUnits, parts.size())));
	}

	private void add(List<String> parts, long amount, Unit unit) {
		// Once the largest unit has started, keep zero units inside the window ("1d 0h" would be misleading to drop).
		if (amount > 0 || !parts.isEmpty())
			parts.add(unit.of(amount));
	}
}
