package pl.kingdomcraft.justbans.common.service;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import pl.kingdomcraft.justbans.common.messaging.BusException;
import pl.kingdomcraft.justbans.common.messaging.RedisBus;

/**
 * Periodically announces expired bans and warns ({@code PUNISHMENT_EXPIRED}) from exactly one node at a time.
 *
 * <p>Every node runs a sweeper. On each tick a node tries {@code SET <prefix>lock:expiry-sweeper <nodeId> NX PX <ttl>}; only
 * the node that gets the lock sweeps, the others skip the tick. The TTL is a little shorter than the interval and the lock
 * is never released, so across all nodes the sweep runs about once per interval, and a crashed holder simply stops
 * renewing it. Redis being down means no sweep (events could not be published anyway) and the backlog is announced
 * after it returns.
 *
 * <p>The lock is only an optimisation: {@link PunishmentService#sweepExpiredBlocking} claims each expiry with a conditional
 * update, so even two sweepers racing (lock expired mid-sweep, clock skew) cannot emit an event twice.
 */
public final class ExpirySweeper implements AutoCloseable {

	/** Rows announced per transaction; a bigger backlog is worked off in several rounds. */
	static final int BATCH_SIZE = 200;

	/** Safety stop so one tick cannot run forever after a very long outage. */
	static final int MAX_ROUNDS = 50;

	private final PunishmentService service;
	private final RedisBus bus;
	private final String nodeId;
	private final Duration interval;
	private final Logger logger;

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		final Thread thread = new Thread(runnable, "JustBans-ExpirySweeper");
		thread.setDaemon(true);

		return thread;
	});

	private volatile boolean redisWarned;
	private volatile Consumer<String> trace = line -> {
	};

	public ExpirySweeper(PunishmentService service, RedisBus bus, String nodeId, Duration interval, Logger logger) {
		if (interval.compareTo(Duration.ofSeconds(1)) < 0)
			throw new IllegalArgumentException("Sweep interval must be at least one second");

		this.service = service;
		this.bus = bus;
		this.nodeId = nodeId;
		this.interval = interval;
		this.logger = logger;
	}

	/** Name of the Redis key that elects the sweeping node. */
	public static String lockKey(RedisBus bus) {
		return bus.channels().prefix() + "lock:expiry-sweeper";
	}

	/** Optional diagnostics: receives one line per sweep attempt. Called on the sweeper thread. */
	public void setTrace(Consumer<String> trace) {
		this.trace = trace;
	}

	public void start() {
		// A random first delay keeps nodes that boot together from all contending in the same millisecond.
		final long initial = 2_000 + ThreadLocalRandom.current().nextLong(3_000);

		this.executor.scheduleWithFixedDelay(this::tickSafely, initial, this.interval.toMillis(), TimeUnit.MILLISECONDS);
	}

	@Override
	public void close() {
		this.executor.shutdownNow();
	}

	private void tickSafely() {
		try {
			this.tick();
		} catch (final RuntimeException | SQLException ex) {
			this.logger.log(Level.WARNING, "Expiry sweep failed, will retry next interval: " + ex.getMessage());
			this.logger.log(Level.FINE, "Expiry sweep failure", ex);
		}
	}

	/**
	 * One sweep attempt: elect, then announce everything due.
	 *
	 * @return the number of expiry events appended by this node, 0 if another node holds the lock
	 */
	public int tick() throws SQLException {
		final long ttl = Math.max(500, this.interval.toMillis() * 9 / 10);

		try {
			if (!this.bus.tryLock(lockKey(this.bus), this.nodeId, ttl)) {
				this.trace.accept("another node holds the sweep lock, skipping");
				return 0;
			}

			this.redisWarned = false;

		} catch (final BusException ex) {
			if (!this.redisWarned) {
				this.redisWarned = true;
				this.logger.warning("Redis is unavailable, expired punishments will be announced once it returns (" + ex.getMessage() + ")");
			}

			return 0;
		}

		int total = 0;

		for (int round = 0; round < MAX_ROUNDS; round++) {
			final int announced = this.service.sweepExpiredBlocking(BATCH_SIZE);
			total += announced;

			if (announced < BATCH_SIZE)
				break;
		}

		this.trace.accept("sweep announced " + total + " expired punishment(s)");

		if (total > 0)
			this.logger.fine("Announced " + total + " expired punishment(s)");

		return total;
	}
}
