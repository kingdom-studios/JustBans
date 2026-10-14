package pl.kingdomcraft.justbans.common.messaging;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import pl.kingdomcraft.justbans.common.database.Database;
import pl.kingdomcraft.justbans.common.domain.EventRecord;
import pl.kingdomcraft.justbans.common.repository.EventRepository;

/**
 * Transactional-outbox relay: publishes this node's unpublished {@code jb_events} rows to Redis in order and
 * marks them published.
 *
 * <p>If Redis is down the rows simply pile up and are sent, oldest first, once it returns. Delivery is
 * at-least-once: a crash between publishing and marking re-sends the event, which is harmless because the envelope
 * id is derived from the event id, so {@link RedisBus} and the consumers drop the duplicate. Publishing stops at the
 * first failure so consumers never see events out of order.
 */
public final class OutboxPublisher implements AutoCloseable {

	/** Event envelopes get a stable id so re-publishing an event is recognisable as a duplicate. */
	public static String envelopeIdFor(long seq) {
		return UUID.nameUUIDFromBytes(("justbans-event-" + seq).getBytes(StandardCharsets.UTF_8)).toString();
	}

	private static final int BATCH_SIZE = 100;

	private final Database database;
	private final RedisBus bus;
	private final String nodeId;
	private final Clock clock;
	private final Logger logger;
	private final long intervalMs;
	private final EventRepository events = new EventRepository();

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		final Thread thread = new Thread(runnable, "JustBans-Outbox");
		thread.setDaemon(true);

		return thread;
	});
	private final AtomicBoolean wakePending = new AtomicBoolean();
	private final AtomicBoolean failing = new AtomicBoolean();

	/**
	 * @param intervalMs how often to retry when nothing wakes the publisher (safety net; 2000 is a good value)
	 */
	public OutboxPublisher(Database database, RedisBus bus, String nodeId, Clock clock, Logger logger, long intervalMs) {
		this.database = database;
		this.bus = bus;
		this.nodeId = nodeId;
		this.clock = clock;
		this.logger = logger;
		this.intervalMs = intervalMs;
	}

	public void start() {
		this.executor.scheduleWithFixedDelay(this::drainSafely, 0, this.intervalMs, TimeUnit.MILLISECONDS);
	}

	/** Requests a prompt drain, e.g. right after a punishment committed. Cheap and non-blocking. */
	public void wake() {
		if (this.wakePending.compareAndSet(false, true))
			try {
				this.executor.execute(() -> {
					this.wakePending.set(false);
					this.drainSafely();
				});
			} catch (final RuntimeException rejected) {
				// closed
			}
	}

	@Override
	public void close() {
		this.executor.shutdownNow();
	}

	private void drainSafely() {
		try {
			this.drainOnce();
		} catch (final RuntimeException | SQLException ex) {
			this.logger.log(Level.WARNING, "Outbox drain failed, will retry: " + ex.getMessage());
		}
	}

	/**
	 * Publishes everything currently pending. Serialized, so concurrent calls cannot reorder events.
	 *
	 * @return the number of events published
	 */
	public synchronized int drainOnce() throws SQLException {
		int published = 0;

		while (true) {
			final List<EventRecord> batch = this.database.query(connection -> this.events.unpublished(connection, this.nodeId, BATCH_SIZE));

			if (batch.isEmpty())
				break;

			for (final EventRecord event : batch) {
				try {
					this.bus.publish(this.bus.channels().events(), this.toEnvelope(event));

				} catch (final BusException ex) {
					if (this.failing.compareAndSet(false, true))
						this.logger.warning("Redis unavailable; events will queue in the database until it returns (" + ex.getMessage() + ")");

					return published;
				}

				this.database.query(connection -> {
					this.events.markPublished(connection, event.id(), this.clock.instant());
					return null;
				});

				published++;
			}
		}

		if (this.failing.compareAndSet(true, false))
			this.logger.info("Redis is reachable again; outbox drained");

		return published;
	}

	private Envelope toEnvelope(EventRecord event) {
		JsonObject payload;

		try {
			payload = JsonParser.parseString(event.payload()).getAsJsonObject();
		} catch (final JsonParseException | IllegalStateException ex) {
			this.logger.warning("Event " + event.id() + " has an unreadable payload; publishing it empty");
			payload = new JsonObject();
		}

		return new Envelope(Envelope.VERSION, envelopeIdFor(event.id()), event.type().name(), event.createdAt().toEpochMilli(), event.originNode(), event.id(), payload, null);
	}
}
