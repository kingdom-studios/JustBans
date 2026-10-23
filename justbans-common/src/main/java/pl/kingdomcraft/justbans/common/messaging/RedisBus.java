package pl.kingdomcraft.justbans.common.messaging;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonObject;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.params.SetParams;

/**
 * Redis Pub/Sub transport. Redis is only a nudge: delivery is at-most-once, so nothing here is authoritative;
 * the database (and the outbox) is.
 *
 * <p>A dedicated daemon thread keeps one subscription to the events, commands and command-results channels and
 * reconnects with exponential backoff. Received messages are parsed, signature-checked, de-duplicated by envelope id
 * and handed to listeners one at a time, in arrival order, on a separate dispatch thread so a slow listener never
 * stalls the socket. Publishing uses a {@link JedisPool}.
 */
public final class RedisBus implements AutoCloseable {

	/** Largest accepted or published message, in characters. */
	public static final int MAX_MESSAGE_LENGTH = 16 * 1024;

	private static final long INITIAL_BACKOFF_MS = 1_000;
	private static final long MAX_BACKOFF_MS = 30_000;
	private static final long PING_INTERVAL_SECONDS = 15;
	private static final int SUBSCRIBER_SOCKET_TIMEOUT_MS = 45_000;
	private static final int MIN_READ_TIMEOUT_MS = 1_000;
	private static final int MAX_READ_TIMEOUT_MS = 3_000;

	/**
	 * A received message.
	 *
	 * @param signatureValid false if the signature is missing or wrong. Listeners that need to react to bad
	 *                       signatures can inspect this; use {@link #onVerified} to ignore them.
	 */
	public record Delivery(String channel, Envelope envelope, boolean signatureValid) {
	}

	@FunctionalInterface
	public interface Listener {
		void onMessage(Delivery delivery);
	}

	/** Called from the subscriber thread; keep implementations fast. */
	public interface ConnectionListener {
		void onConnected();

		void onDisconnected();
	}

	private final RedisConfig config;
	private final Channels channels;
	private final HmacSigner signer;
	private final String nodeId;
	private final Clock clock;
	private final Logger logger;

	private final JedisPool publishPool;
	private final JedisClientConfig subscriberConfig;

	private final Map<String, List<Listener>> listeners = new ConcurrentHashMap<>();
	private final List<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();
	private final Cache<String, Boolean> seen = Caffeine.newBuilder().maximumSize(20_000).expireAfterWrite(Duration.ofMinutes(10)).build();

	private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(daemon("JustBans-Redis-Dispatch"));
	private final ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor(daemon("JustBans-Redis-Ping"));
	private final AtomicInteger subscribedChannels = new AtomicInteger();

	private volatile BooleanSupplier traceEnabled = () -> false;
	private volatile Consumer<String> traceSink = line -> {
	};

	private volatile boolean running;
	private volatile boolean connected;
	private volatile Subscription subscription;
	private Thread subscriberThread;

	public RedisBus(RedisConfig config, HmacSigner signer, String nodeId, Clock clock, Logger logger) {
		this.config = config;
		this.channels = new Channels(config.prefix());
		this.signer = signer;
		this.nodeId = nodeId;
		this.clock = clock;
		this.logger = logger;

		final DefaultJedisClientConfig.Builder client = DefaultJedisClientConfig.builder()
				.user(blankToNull(config.user()))
				.password(blankToNull(config.password()))
				.ssl(config.ssl())
				.database(config.database())
				.connectionTimeoutMillis(config.connectTimeoutMs())
				.socketTimeoutMillis(readTimeoutMs(config));

		final HostAndPort address = new HostAndPort(config.host(), config.port());
		final JedisPoolConfig poolConfig = new JedisPoolConfig();

		poolConfig.setMaxTotal(8);
		poolConfig.setMaxIdle(4);
		poolConfig.setMinIdle(0);

		// No validation by the pool. A PING per borrow (or per idle connection) makes every dead connection cost one full read
		// timeout, one after the other, and logs a stack trace for each. Instead execute() recovers from a dead connection
		// itself: it drops all idle connections at once and retries once on a fresh one. Idle connections are still closed
		// by the pool's own eviction after a minute, so a stale one rarely survives long.
		poolConfig.setTestOnBorrow(false);
		poolConfig.setTestWhileIdle(false);

		this.publishPool = new JedisPool(poolConfig, address, client.build());

		// The subscription socket sits idle between messages, so it gets a long read timeout and is kept alive by
		// PING; a silent dead connection then surfaces as a timeout instead of hanging forever.
		this.subscriberConfig = DefaultJedisClientConfig.builder()
				.user(blankToNull(config.user()))
				.password(blankToNull(config.password()))
				.ssl(config.ssl())
				.database(config.database())
				.connectionTimeoutMillis(config.connectTimeoutMs())
				.socketTimeoutMillis(SUBSCRIBER_SOCKET_TIMEOUT_MS)
				.build();
	}

	public Channels channels() {
		return this.channels;
	}

	/**
	 * Optional diagnostics: while {@code enabled} returns true every published and received message is described to
	 * {@code sink} (type, node, seq, size, signature result; never the payload). Both are called from I/O threads, so they
	 * must be fast and thread-safe.
	 */
	public void setTrace(BooleanSupplier enabled, Consumer<String> sink) {
		this.traceEnabled = enabled;
		this.traceSink = sink;
	}

	private void trace(Supplier<String> line) {
		if (this.traceEnabled.getAsBoolean())
			this.traceSink.accept(line.get());
	}

	public String nodeId() {
		return this.nodeId;
	}

	/** Whether the subscriber is currently subscribed to all channels. */
	public boolean isConnected() {
		return this.connected;
	}

	// ------------------------------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------------------------------

	/** Starts the subscriber thread. Returns immediately; connection problems are retried in the background. */
	public synchronized void start() {
		if (this.running)
			return;

		this.running = true;
		this.subscriberThread = new Thread(this::subscribeLoop, "JustBans-Redis-Subscriber");
		this.subscriberThread.setDaemon(true);
		this.subscriberThread.start();

		this.pinger.scheduleWithFixedDelay(this::ping, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	@Override
	public synchronized void close() {
		this.running = false;

		final Subscription current = this.subscription;

		if (current != null)
			try {
				current.unsubscribe();
			} catch (final RuntimeException ignored) {
				// the connection is going away anyway
			}

		if (this.subscriberThread != null) {
			this.subscriberThread.interrupt();

			try {
				this.subscriberThread.join(2_000);
			} catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}

		this.pinger.shutdownNow();
		this.dispatcher.shutdown();

		try {
			this.dispatcher.awaitTermination(2, TimeUnit.SECONDS);
		} catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();
		}

		this.publishPool.close();
	}

	// ------------------------------------------------------------------------------------------
	// Listening
	// ------------------------------------------------------------------------------------------

	/** Registers a listener for a channel; it receives every parsed message, including ones with a bad signature. */
	public void listen(String channel, Listener listener) {
		this.listeners.computeIfAbsent(channel, key -> new CopyOnWriteArrayList<>()).add(listener);
	}

	/** Registers a listener that only receives messages whose signature verified. */
	public void onVerified(String channel, java.util.function.Consumer<Envelope> listener) {
		this.listen(channel, delivery -> {
			if (delivery.signatureValid())
				listener.accept(delivery.envelope());
		});
	}

	public void addConnectionListener(ConnectionListener listener) {
		this.connectionListeners.add(listener);
	}

	// ------------------------------------------------------------------------------------------
	// Publishing
	// ------------------------------------------------------------------------------------------

	/** Builds an unsigned envelope from this node. */
	public Envelope newEnvelope(String type, Long seq, JsonObject payload) {
		return Envelope.create(type, this.nodeId, seq, payload, this.clock);
	}

	/**
	 * How long a command on the shared pool may wait for Redis. Commands here are tiny (PUBLISH, HSET, SET NX, HGETALL of a player
	 * list), so even a slow link answers in milliseconds; a longer wait only means a dead connection takes longer to notice.
	 */
	private static int readTimeoutMs(RedisConfig config) {
		return Math.max(MIN_READ_TIMEOUT_MS, Math.min(config.connectTimeoutMs(), MAX_READ_TIMEOUT_MS));
	}

	/** One Redis operation on a pooled connection. */
	@FunctionalInterface
	private interface RedisCall<T> {
		T call(Jedis jedis);
	}

	/**
	 * Runs the call on a pooled connection and turns every failure into a {@link BusException}.
	 *
	 * <p>A pooled connection can be dead without the pool knowing: a NAT or firewall dropped it after being idle, Redis restarted,
	 * the network blinked. The first command on it then fails with a connection error (after the read timeout). When that happens
	 * on a connection that was already in the pool, every other idle connection is probably dead for the same reason, so all of
	 * them are discarded and the call is retried once on a fresh connection. All operations here are safe to repeat: events
	 * carry a stable id and are de-duplicated by every receiver, hash writes are idempotent, and the sweeper lock tolerates a
	 * repeated request (the worst case is that one sweep round is skipped). If there was no idle connection to blame, the
	 * failure is a genuine outage and is reported straight away instead of waiting out a second timeout.
	 */
	private <T> T execute(String what, RedisCall<T> call) {
		final boolean pooled = this.publishPool.getNumIdle() > 0;

		try {
			return this.attempt(call);

		} catch (final JedisConnectionException first) {
			if (!pooled)
				throw new BusException(what + ": " + first.getMessage(), first);

			this.trace(() -> what + ": a pooled connection was dead (" + first.getMessage() + "), dropping idle connections and retrying once");
			this.logger.log(Level.FINE, "Dropping idle Redis connections after: " + first.getMessage());

			try {
				this.publishPool.clear();

				return this.attempt(call);

			} catch (final RuntimeException second) {
				throw new BusException(what + ": " + second.getMessage(), second);
			}

		} catch (final RuntimeException ex) {
			throw new BusException(what + ": " + ex.getMessage(), ex);
		}
	}

	private <T> T attempt(RedisCall<T> call) {
		try (Jedis jedis = this.publishPool.getResource()) {
			return call.call(jedis);
		}
	}

	/**
	 * Signs and publishes the envelope.
	 *
	 * @return number of subscribers that received it (0 is not an error: the database is the source of truth)
	 * @throws BusException if Redis is unreachable or the message is too large
	 */
	public long publish(String channel, Envelope envelope) {
		final String json = this.signer.sign(envelope).toJson();

		if (json.length() > MAX_MESSAGE_LENGTH)
			throw new BusException("Message of type " + envelope.type() + " is " + json.length() + " characters, over the " + MAX_MESSAGE_LENGTH + " limit");

		try {
			final long receivers = this.execute("Could not publish to " + channel, jedis -> jedis.publish(channel, json));

			this.trace(() -> "published " + envelope.type() + " seq=" + envelope.seq() + " (" + json.length() + " chars) on " + channel + " to " + receivers + " subscriber(s)");

			return receivers;

		} catch (final BusException ex) {
			this.trace(() -> "publish of " + envelope.type() + " on " + channel + " failed: " + ex.getMessage());

			throw ex;
		}
	}

	/**
	 * Tries to take a short-lived lock with {@code SET key owner NX PX ttl}. Whoever gets {@code true} holds the lock until
	 * the TTL passes; there is deliberately no release, so the lock also rate-limits the work it guards across nodes. The
	 * lock only reduces duplicate work, correctness must never depend on it.
	 *
	 * @return true if this call took the lock, false if another holder has it
	 * @throws BusException if Redis is unreachable
	 */
	public boolean tryLock(String key, String owner, long ttlMillis) {
		if (ttlMillis <= 0)
			throw new IllegalArgumentException("ttlMillis must be positive");

		final boolean acquired = this.execute("Could not take lock " + key, jedis -> "OK".equals(jedis.set(key, owner, SetParams.setParams().nx().px(ttlMillis))));

		this.trace(() -> "lock " + key + (acquired ? " acquired" : " held by another node") + " (ttl " + ttlMillis + " ms)");

		return acquired;
	}

	// ------------------------------------------------------------------------------------------
	// Hash helpers (the "online players" directory)
	// ------------------------------------------------------------------------------------------

	/** @throws BusException if Redis is unreachable */
	public void hashPut(String key, String field, String value) {
		this.execute("Could not write " + key, jedis -> jedis.hset(key, field, value));
	}

	/** @return the value, or null if the field is absent
	 * @throws BusException if Redis is unreachable */
	public String hashGet(String key, String field) {
		return this.execute("Could not read " + key, jedis -> jedis.hget(key, field));
	}

	/** @throws BusException if Redis is unreachable */
	public void hashRemove(String key, String field) {
		this.execute("Could not write " + key, jedis -> jedis.hdel(key, field));
	}

	/** Atomically replaces the whole hash (an empty map just deletes it). */
	public void hashReplace(String key, Map<String, String> entries) {
		this.execute("Could not write " + key, jedis -> {
			final redis.clients.jedis.Transaction transaction = jedis.multi();

			transaction.del(key);

			if (!entries.isEmpty())
				transaction.hset(key, entries);

			transaction.exec();

			return null;
		});
	}

	/** @throws BusException if Redis is unreachable */
	public Map<String, String> hashGetAll(String key) {
		return this.execute("Could not read " + key, jedis -> jedis.hgetAll(key));
	}

	// ------------------------------------------------------------------------------------------
	// Subscriber thread
	// ------------------------------------------------------------------------------------------

	private void subscribeLoop() {
		long backoff = INITIAL_BACKOFF_MS;

		while (this.running) {
			final Subscription current = new Subscription();

			try (Jedis jedis = new Jedis(new HostAndPort(this.config.host(), this.config.port()), this.subscriberConfig)) {
				this.subscription = current;
				this.subscribedChannels.set(0);

				jedis.subscribe(current, this.channels.all()); // blocks until unsubscribed or the connection drops

			} catch (final RuntimeException ex) {
				if (this.running)
					this.logger.log(Level.WARNING, "Redis subscription lost: " + ex.getMessage());

			} finally {
				this.subscription = null;
				this.setConnected(false);
			}

			if (!this.running)
				break;

			if (current.everSubscribed)
				backoff = INITIAL_BACKOFF_MS;

			final long sleep = backoff + ThreadLocalRandom.current().nextLong(backoff / 4 + 1);
			this.logger.info("Reconnecting to Redis in " + sleep + " ms");

			try {
				Thread.sleep(sleep);
			} catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}

			backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
		}
	}

	private void ping() {
		final Subscription current = this.subscription;

		if (current != null && current.isSubscribed())
			try {
				current.ping();
			} catch (final RuntimeException ex) {
				// a dead socket makes the blocked subscribe() throw, which triggers the reconnect
			}
	}

	private void setConnected(boolean value) {
		if (this.connected == value)
			return;

		this.connected = value;

		for (final ConnectionListener listener : this.connectionListeners)
			try {
				if (value)
					listener.onConnected();
				else
					listener.onDisconnected();
			} catch (final RuntimeException ex) {
				this.logger.log(Level.WARNING, "Redis connection listener failed", ex);
			}
	}

	private final class Subscription extends JedisPubSub {

		volatile boolean everSubscribed;

		@Override
		public void onSubscribe(String channel, int subscribedChannels) {
			if (subscribedChannels >= RedisBus.this.channels.all().length) {
				this.everSubscribed = true;
				RedisBus.this.logger.info("Subscribed to Redis channels " + String.join(", ", RedisBus.this.channels.all()));
				RedisBus.this.setConnected(true);
			}
		}

		@Override
		public void onMessage(String channel, String message) {
			try {
				RedisBus.this.dispatcher.execute(() -> RedisBus.this.handle(channel, message));
			} catch (final RuntimeException rejected) {
				// shutting down
			}
		}
	}

	// ------------------------------------------------------------------------------------------
	// Receiving
	// ------------------------------------------------------------------------------------------

	private void handle(String channel, String message) {
		if (message.length() > MAX_MESSAGE_LENGTH) {
			this.logger.warning("Dropped oversized Redis message on " + channel + " (" + message.length() + " characters)");
			return;
		}

		final Envelope envelope;

		try {
			envelope = Envelope.fromJson(message);
		} catch (final Envelope.InvalidEnvelopeException ex) {
			this.logger.warning("Dropped malformed Redis message on " + channel + ": " + ex.getMessage());
			return;
		}

		final boolean valid = this.signer.verify(envelope);

		this.trace(() -> "received " + envelope.type() + " seq=" + envelope.seq() + " from " + envelope.node() + " on " + channel + " (" + message.length() + " chars, signature " + (valid ? "ok" : "INVALID") + ")");

		if (!valid)
			this.logger.warning("Redis message " + envelope.id() + " (" + envelope.type() + ") from '" + envelope.node() + "' on " + channel
					+ " has an invalid signature; check that web-secret matches on every node");
		else if (this.seen.asMap().putIfAbsent(envelope.id(), Boolean.TRUE) != null)
			return; // duplicate delivery of an already handled message; only authentic ids are remembered so forged ids cannot suppress real ones

		final List<Listener> targets = this.listeners.get(channel);

		if (targets == null)
			return;

		final Delivery delivery = new Delivery(channel, envelope, valid);

		for (final Listener listener : targets)
			try {
				listener.onMessage(delivery);
			} catch (final RuntimeException ex) {
				this.logger.log(Level.SEVERE, "Redis listener failed for " + envelope.type() + " on " + channel, ex);
			}
	}

	private static String blankToNull(String value) {
		return value == null || value.isEmpty() ? null : value;
	}

	private static java.util.concurrent.ThreadFactory daemon(String name) {
		return runnable -> {
			final Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);

			return thread;
		};
	}
}
