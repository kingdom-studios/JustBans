package pl.kingdomcraft.justbans.common.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DurationParserTest {

	@Test
	void parsesSingleUnits() {
		assertEquals(Duration.ofSeconds(45), DurationParser.parse("45s"));
		assertEquals(Duration.ofMinutes(90), DurationParser.parse("90m"));
		assertEquals(Duration.ofHours(5), DurationParser.parse("5h"));
		assertEquals(Duration.ofDays(30), DurationParser.parse("30d"));
		assertEquals(Duration.ofDays(14), DurationParser.parse("2w"));
		assertEquals(Duration.ofDays(60), DurationParser.parse("2mo"));
		assertEquals(Duration.ofDays(365), DurationParser.parse("1y"));
	}

	@Test
	void distinguishesMinutesFromMonths() {
		assertEquals(Duration.ofMinutes(1), DurationParser.parse("1m"));
		assertEquals(Duration.ofDays(30), DurationParser.parse("1mo"));
	}

	@Test
	void parsesCompoundDurations() {
		assertEquals(Duration.ofDays(1).plusHours(12), DurationParser.parse("1d12h"));
		assertEquals(Duration.ofDays(17).plusHours(2).plusMinutes(3), DurationParser.parse("2w 3d 2h 3m"));
	}

	@Test
	void isCaseInsensitiveAndTrims() {
		assertEquals(Duration.ofDays(7), DurationParser.parse("  7D "));
		assertEquals(Duration.ofHours(36), DurationParser.parse("1D12H"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "perm", "PERM", "permanent", " Permanent " })
	void parsesPermanent(String input) {
		final Duration duration = DurationParser.parse(input);

		assertTrue(DurationParser.isPermanent(duration));
		assertNull(DurationParser.expiry(Instant.EPOCH, duration));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", "d", "30", "30x", "-5d", "5d-", "1.5h", "abc", "5 d", "0s", "0d0h", "perm1", "11y", "99999999999999999999d" })
	void rejectsInvalidInput(String input) {
		assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(input));
		assertNull(DurationParser.tryParse(input));
	}

	@Test
	void rejectsNull() {
		assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(null));
	}

	@Test
	void acceptsExactlyTenYearsButNotMore() {
		assertEquals(Duration.ofDays(3650), DurationParser.parse("3650d"));
		assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("3651d"));
	}

	@Test
	void computesExpiry() {
		final Instant now = Instant.parse("2026-10-14T12:00:00Z");

		assertEquals(now.plusSeconds(3600), DurationParser.expiry(now, Duration.ofHours(1)));
		assertFalse(DurationParser.isPermanent(Duration.ofDays(1)));
	}

	@Test
	void formatsDurations() {
		assertEquals("30d", DurationParser.format(Duration.ofDays(30)));
		assertEquals("1d 12h", DurationParser.format(Duration.ofHours(36)));
		assertEquals("1h 30m", DurationParser.format(Duration.ofMinutes(90)));
		assertEquals("1d 1h 1m 1s", DurationParser.format(Duration.ofSeconds(86_400 + 3_600 + 60 + 1)));
		assertEquals("45s", DurationParser.format(Duration.ofSeconds(45)));
		assertEquals("0s", DurationParser.format(Duration.ZERO));
		assertEquals("permanent", DurationParser.format(DurationParser.PERMANENT));
		assertEquals("permanent", DurationParser.format(null));
	}

	@Test
	void formatRoundTripsThroughParse() {
		final Duration original = Duration.ofDays(3).plusHours(4).plusMinutes(5);

		assertEquals(original, DurationParser.parse(DurationParser.format(original).replace(" ", "")));
	}
}
