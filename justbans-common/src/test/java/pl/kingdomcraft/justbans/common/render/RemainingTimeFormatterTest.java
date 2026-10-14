package pl.kingdomcraft.justbans.common.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class RemainingTimeFormatterTest {

	private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
	private final RemainingTimeFormatter formatter = RemainingTimeFormatter.defaults();

	@Test
	void permanentAndExpired() {
		assertEquals("never", formatter.format(NOW, null));
		assertEquals("expired", formatter.format(NOW, NOW));
		assertEquals("expired", formatter.format(NOW, NOW.minusSeconds(5)));
		assertEquals("expired", formatter.format(Duration.ZERO));
		assertEquals("expired", formatter.format(Duration.ofSeconds(-3)));
	}

	@Test
	void showsTheTwoLargestUnits() {
		assertEquals("6d 3h", formatter.format(NOW, NOW.plus(Duration.ofDays(6).plusHours(3).plusMinutes(59))));
		assertEquals("1h 30m", formatter.format(Duration.ofMinutes(90).plusSeconds(20)));
		assertEquals("59s", formatter.format(Duration.ofSeconds(59)));
		assertEquals("2m 5s", formatter.format(Duration.ofSeconds(125)));
	}

	@Test
	void keepsZeroUnitsInsideTheWindow() {
		assertEquals("1d 0h", formatter.format(Duration.ofDays(1).plusMinutes(5)));
		assertEquals("2h 0m", formatter.format(Duration.ofHours(2).plusSeconds(10)));
	}

	@Test
	void roundsPartialSecondsUp() {
		assertEquals("1s", formatter.format(Duration.ofMillis(200)));
		assertEquals("1m 0s", formatter.format(Duration.ofSeconds(59, 500_000_000)));
	}

	@Test
	void maxUnitsIsConfigurable() {
		final RemainingTimeFormatter all = new RemainingTimeFormatter(RemainingTimeFormatter.Labels.SHORT_EN, 4);
		final RemainingTimeFormatter one = new RemainingTimeFormatter(RemainingTimeFormatter.Labels.SHORT_EN, 1);
		final Duration d = Duration.ofDays(2).plusHours(3).plusMinutes(4).plusSeconds(5);

		assertEquals("2d 3h 4m 5s", all.format(d));
		assertEquals("2d", one.format(d));
		assertThrows(IllegalArgumentException.class, () -> new RemainingTimeFormatter(RemainingTimeFormatter.Labels.SHORT_EN, 0));
	}

	@Test
	void longLabelsPluralise() {
		final RemainingTimeFormatter longForm = new RemainingTimeFormatter(RemainingTimeFormatter.Labels.LONG_EN, 2);

		assertEquals("1 day 1 hour", longForm.format(Duration.ofHours(25)));
		assertEquals("2 days 3 hours", longForm.format(Duration.ofHours(51)));
		assertEquals("1 minute 1 second", longForm.format(Duration.ofSeconds(61)));
	}

	@Test
	void labelsCanBeLocalised() {
		final RemainingTimeFormatter polish = new RemainingTimeFormatter(new RemainingTimeFormatter.Labels(RemainingTimeFormatter.Unit.same(" dni"),
				RemainingTimeFormatter.Unit.same(" godz."), RemainingTimeFormatter.Unit.same(" min"), RemainingTimeFormatter.Unit.same(" s"), "na zawsze", "wygasł"), 2);

		assertEquals("3 dni 2 godz.", polish.format(Duration.ofDays(3).plusHours(2)));
		assertEquals("na zawsze", polish.format(NOW, null));
		assertEquals("wygasł", polish.format(Duration.ZERO));
	}
}
