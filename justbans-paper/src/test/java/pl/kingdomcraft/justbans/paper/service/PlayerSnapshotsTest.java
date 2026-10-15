package pl.kingdomcraft.justbans.paper.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

class PlayerSnapshotsTest {

	private static final Logger LOGGER = Logger.getLogger("snapshots-test");

	static {
		LOGGER.setLevel(Level.OFF);
	}

	private static final UUID PLAYER = UUID.randomUUID();

	/** Executor that only runs work when the test says so, to control interleavings. */
	private static final class Manual implements java.util.concurrent.Executor {
		final Queue<Runnable> queue = new ArrayDeque<>();

		@Override
		public void execute(Runnable command) {
			queue.add(command);
		}

		void runOne() {
			queue.poll().run();
		}

		void runAll() {
			Runnable next;

			while ((next = queue.poll()) != null)
				next.run();
		}
	}

	private static final class TestClock extends Clock {
		Instant now = Instant.parse("2026-10-14T12:00:00Z");

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private static PlayerSnapshots.Snapshot empty(Instant at) {
		return new PlayerSnapshots.Snapshot(List.of(), List.of(), null, at);
	}

	@Test
	void peekNeverBlocksReturnsNullUntilLoadedAndLoadsOnce() {
		final Manual executor = new Manual();
		final AtomicInteger loads = new AtomicInteger();
		final TestClock clock = new TestClock();
		final PlayerSnapshots snapshots = new PlayerSnapshots((uuid, now) -> {
			loads.incrementAndGet();
			return empty(now);
		}, executor, clock, Duration.ofSeconds(60), LOGGER);

		assertNull(snapshots.peek(PLAYER));
		assertNull(snapshots.peek(PLAYER), "still loading, and asking again must not queue a second load");
		assertEquals(0, loads.get(), "nothing ran on the calling thread");
		assertEquals(1, executor.queue.size());

		executor.runAll();

		assertNotNull(snapshots.peek(PLAYER));
		assertEquals(1, loads.get());
		assertTrue(executor.queue.isEmpty());
	}

	@Test
	void staleEntriesAreServedWhileARefreshRunsInTheBackground() {
		final Manual executor = new Manual();
		final TestClock clock = new TestClock();
		final AtomicInteger loads = new AtomicInteger();
		final PlayerSnapshots snapshots = new PlayerSnapshots((uuid, now) -> {
			loads.incrementAndGet();
			return empty(now);
		}, executor, clock, Duration.ofSeconds(60), LOGGER);

		snapshots.refresh(PLAYER);
		executor.runAll();
		final PlayerSnapshots.Snapshot first = snapshots.peek(PLAYER);

		clock.now = clock.now.plusSeconds(61);

		assertSame(first, snapshots.peek(PLAYER), "the old data is still shown while the reload is pending");
		assertEquals(1, executor.queue.size());

		executor.runAll();

		assertEquals(2, loads.get());
		assertEquals(clock.now, snapshots.peek(PLAYER).loadedAt());
	}

	@Test
	void aChangeDuringALoadTriggersAnotherLoadSoNewDataIsNeverLost() {
		final Manual executor = new Manual();
		final AtomicReference<PlayerSnapshots> holder = new AtomicReference<>();
		final AtomicInteger databaseVersion = new AtomicInteger();

		final PlayerSnapshots snapshots = new PlayerSnapshots((uuid, now) -> {
			final int seen = databaseVersion.get(); // the load reads the database...

			if (seen == 0) {
				databaseVersion.set(1); // ...then a punishment is committed and its event arrives while the load is still running
				holder.get().refresh(PLAYER);
			}

			return new PlayerSnapshots.Snapshot(List.of(), List.of(), null, Instant.ofEpochSecond(seen));
		}, executor, new TestClock(), Duration.ofSeconds(60), LOGGER);
		holder.set(snapshots);

		snapshots.refresh(PLAYER);
		executor.runOne();

		assertEquals(0, snapshots.peek(PLAYER).loadedAt().getEpochSecond(), "the first load saw the old data");
		assertEquals(1, executor.queue.size(), "and the change that arrived meanwhile queued a repeat");

		executor.runOne();

		assertEquals(1, snapshots.peek(PLAYER).loadedAt().getEpochSecond(), "the repeat load picked up the change");
	}

	@Test
	void eventsOnlyRefreshPlayersThatAreTracked() {
		final Manual executor = new Manual();
		final PlayerSnapshots snapshots = new PlayerSnapshots((uuid, now) -> empty(now), executor, new TestClock(), Duration.ofSeconds(60), LOGGER);

		snapshots.refreshIfTracked(PLAYER);
		assertTrue(executor.queue.isEmpty(), "a stranger is not loaded");

		snapshots.refresh(PLAYER);
		executor.runAll();
		snapshots.refreshIfTracked(PLAYER);
		assertEquals(1, executor.queue.size());

		executor.runAll();
		snapshots.evict(PLAYER);
		snapshots.refreshIfTracked(PLAYER);
		assertTrue(executor.queue.isEmpty(), "evicted players are no longer tracked");
	}

	@Test
	void aFailingLoadKeepsThePreviousSnapshotAndCanBeRetried() {
		final Manual executor = new Manual();
		final TestClock clock = new TestClock();
		final AtomicInteger calls = new AtomicInteger();
		final PlayerSnapshots snapshots = new PlayerSnapshots((uuid, now) -> {
			if (calls.incrementAndGet() == 2)
				throw new SQLException("database is down");

			return empty(now);
		}, executor, clock, Duration.ofSeconds(60), LOGGER);

		snapshots.refresh(PLAYER);
		executor.runAll();
		final PlayerSnapshots.Snapshot good = snapshots.peek(PLAYER);

		snapshots.refresh(PLAYER);
		executor.runAll();

		assertSame(good, snapshots.peek(PLAYER));

		snapshots.refresh(PLAYER);
		executor.runAll();

		assertEquals(3, calls.get(), "a failure does not wedge the player in the loading state");
	}
}
