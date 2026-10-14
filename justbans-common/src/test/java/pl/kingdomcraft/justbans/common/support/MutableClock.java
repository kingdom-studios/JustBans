package pl.kingdomcraft.justbans.common.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock that only moves when told to. */
public final class MutableClock extends Clock {

	private volatile Instant now;

	public MutableClock(Instant start) {
		this.now = start;
	}

	public void advance(Duration duration) {
		this.now = this.now.plus(duration);
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return this.now;
	}
}
