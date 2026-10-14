package pl.kingdomcraft.justbans.paper.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.time.DurationParser;

class DurationMathTest {

	private static final Duration DAY = Duration.ofDays(1);
	private static final Duration HOUR = Duration.ofHours(1);

	@Test
	void addsAndSubtractsUnits() {
		assertEquals(Duration.ofDays(4), DurationMath.adjust(Duration.ofDays(3), DAY, 1));
		assertEquals(Duration.ofDays(8), DurationMath.adjust(Duration.ofDays(3), DAY, 5));
		assertEquals(Duration.ofDays(2), DurationMath.adjust(Duration.ofDays(3), DAY, -1));
		assertEquals(Duration.ofDays(1).plusHours(5), DurationMath.adjust(DAY, HOUR, 5));
	}

	@Test
	void neverGoesBelowOneHour() {
		assertEquals(DurationMath.MIN, DurationMath.adjust(Duration.ofDays(1), DAY, -1));
		assertEquals(DurationMath.MIN, DurationMath.adjust(Duration.ofDays(1), DAY, -50));
		assertEquals(DurationMath.MIN, DurationMath.adjust(HOUR, HOUR, -1));
	}

	@Test
	void neverExceedsTheGlobalMaximum() {
		assertEquals(DurationParser.MAX, DurationMath.adjust(Duration.ofDays(3640), DAY, 100));
		assertEquals(DurationParser.MAX, DurationMath.adjust(DurationParser.MAX, HOUR, 1));
	}

	@Test
	void startingPointIsSaneForAnyInput() {
		assertEquals(Duration.ofDays(30), DurationMath.startingPoint(Duration.ofDays(30)));
		assertEquals(DAY, DurationMath.startingPoint(null));
		assertEquals(DAY, DurationMath.startingPoint(DurationParser.PERMANENT));
		assertEquals(DAY, DurationMath.startingPoint(Duration.ofMinutes(5)));
		assertEquals(DAY, DurationMath.startingPoint(Duration.ZERO));
		assertEquals(DurationParser.MAX, DurationMath.startingPoint(Duration.ofDays(99_999)));
	}
}
