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
				.socketTimeoutMillis(config.connectTimeoutMs());

		final HostAndPort address = new HostAndPort(config.host(), config.port());
		final JedisPoolConfig poolConfig = new JedisPoolConfig();

		poolConfig.setMaxTotal(8);
		poolConfig.setMaxIdle(4);
		poolConfig.setMinIdle(0);
		poolConfig.setTestOnBorrow(true);

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
	 * Signs and publishes the envelope.
	 *
	 * @return number of subscribers that received it (0 is not an error: the database is the source of truth)
	 * @throws BusException if Redis is unreachable or the message is too large
	 */
	public long publish(String channel, Envelope envelope) {
		final String json = this.signer.sign(envelope).toJson();

		if (json.length() > MAX_MESSAGE_LENGTH)
			throw new BusException("Message of type " + envelope.type() + " is " + json.length() + " characters, over the " + MAX_MESSAGE_LENGTH + " limit");

		try (Jedis jedis = this.publishPool.getResource()) {
			return jedis.publish(channel, json);

		} catch (final RuntimeException ex) {
			throw new BusException("Could not publish to " + channel + ": " + ex.getMessage(), ex);
		}
	}

	// ------------------------------------------------------------------------------------------
	// Hash helpers (the "online players" directory)
	// ------------------------------------------------------------------------------------------

	/** @throws BusException if Redis is unreachable */
	public void hashPut(String key, String field, String value) {
		try (Jedis jedis = this.publishPool.getResource()) {
			jedis.hset(key, field, value);

		} catch (final RuntimeException ex) {
			throw new BusException("Could not write " + key + ": " + ex.getMessage(), ex);
		}
	}

	/** @throws BusException if Redis is unreachable */
	public void hashRemove(String key, String field) {
		try (Jedis jedis = this.publishPool.getResource()) {
			jedis.hdel(key, field);

		} catch (final RuntimeException ex) {
			throw new BusException("Could not write " + key + ": " + ex.getMessage(), ex);
		}
	}

	/** Atomically replaces the whole hash (an empty map just deletes it). */
	public void hashReplace(String key, Map<String, String> entries) {
		try (Jedis jedis = this.publishPool.getResource()) {
			final redis.clients.jedis.Transaction transaction = jedis.multi();

			transaction.del(key);

			if (!entries.isEmpty())
				transaction.hset(key, entries);

			transaction.exec();

		} catch (final RuntimeException ex) {
			throw new BusException("Could not write " + key + ": " + ex.getMessage(), ex);
		}
	}

	public Map<String, String> hashGetAll(String key) {
		try (Jedis jedis = this.publishPool.getResource()) {
			return jedis.hgetAll(key);

		} catch (final RuntimeException ex) {
			throw new BusException("Could not read " + key + ": " + ex.getMessage(), ex);
		}
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
