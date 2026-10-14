package pl.kingdomcraft.justbans.paper;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.plugin.Plugin;

import pl.kingdomcraft.justbans.common.database.Database;
import pl.kingdomcraft.justbans.common.database.DatabaseConfig;
import pl.kingdomcraft.justbans.common.database.Migrator;
import pl.kingdomcraft.justbans.common.messaging.HmacSigner;
import pl.kingdomcraft.justbans.common.messaging.OutboxPublisher;
import pl.kingdomcraft.justbans.common.messaging.RedisBus;
import pl.kingdomcraft.justbans.common.messaging.RedisConfig;
import pl.kingdomcraft.justbans.common.messaging.WebCommandHandler;
import pl.kingdomcraft.justbans.common.repository.PunishmentRepository;
import pl.kingdomcraft.justbans.common.repository.ReasonRepository;
import pl.kingdomcraft.justbans.common.repository.ServerRepository;
import pl.kingdomcraft.justbans.common.service.PunishmentService;
import pl.kingdomcraft.justbans.paper.catalog.ReasonCatalog;
import pl.kingdomcraft.justbans.paper.catalog.ServerCatalog;
import pl.kingdomcraft.justbans.paper.service.OnlineNames;
import pl.kingdomcraft.justbans.paper.service.PaperExemptions;
import pl.kingdomcraft.justbans.paper.service.PlayerResolver;
import pl.kingdomcraft.justbans.paper.settings.Settings;

/**
 * Everything that needs the database or Redis. It is built off the main thread by {@link JustBans} and replaced as a
 * whole on reload, so commands and listeners just ask the plugin for the current instance and treat null as "still
 * starting".
 */
public final class Core implements AutoCloseable {

	private static final String REASONS_UPDATED = "REASONS_UPDATED";

	private final Logger logger;
	private final Clock clock = Clock.systemUTC();
	private final String serverName;
	private final String serverGroup;
	private final String nodeId;
	private final DateTimeFormatter dateFormat;

	private final Database database;
	private final RedisBus bus;
	private final OutboxPublisher outbox;
	private final PunishmentService service;
	private final PlayerResolver resolver;
	private final OnlineNames onlineNames;
	private final ReasonCatalog reasons = new ReasonCatalog();
	private final ServerCatalog servers = new ServerCatalog();
	private final PunishmentRepository punishments = new PunishmentRepository();
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
		final Thread thread = new Thread(runnable, "JustBans-Background");
		thread.setDaemon(true);

		return thread;
	});

	private Core(Logger logger, String serverName, String serverGroup, String nodeId, DateTimeFormatter dateFormat, Database database, RedisBus bus, OutboxPublisher outbox,
			PunishmentService service) {
		this.logger = logger;
		this.serverName = serverName;
		this.serverGroup = serverGroup;
		this.nodeId = nodeId;
		this.dateFormat = dateFormat;
		this.database = database;
		this.bus = bus;
		this.outbox = outbox;
		this.service = service;
		this.resolver = new PlayerResolver(database);
		this.onlineNames = new OnlineNames(bus);
	}

	/**
	 * Connects to the database and Redis, migrates the schema, loads the catalogs and starts background work. Blocking.
	 *
	 * @throws IllegalArgumentException for unusable settings; the message tells the admin what to fix
	 * @throws Exception                if the database cannot be reached
	 */
	public static Core start(Plugin plugin) throws Exception {
		final Logger logger = plugin.getLogger();
		final String secret = Settings.Redis.WEB_SECRET;

		if (secret.length() < HmacSigner.MIN_SECRET_LENGTH)
			throw new IllegalArgumentException("Redis.Web_Secret in settings.yml must be at least " + HmacSigner.MIN_SECRET_LENGTH + " characters. Use the same value on every JustBans node and in the web panel.");

		final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern(Settings.Punishments.DATE_FORMAT_PATTERN).withZone(ZoneId.of(Settings.Punishments.TIME_ZONE));
		final String serverName = Settings.Server.NAME;
		final String nodeId = "paper-" + serverName;

		final Database database = new Database(new DatabaseConfig(Settings.Database.HOST, Settings.Database.PORT, Settings.Database.NAME, Settings.Database.USER, Settings.Database.PASSWORD,
				Settings.Database.POOL_SIZE, Settings.Database.CONNECTION_TIMEOUT_MILLIS, Map.of()));

		RedisBus bus = null;
		OutboxPublisher outbox = null;

		try {
			new Migrator(database, logger).migrate();

			bus = new RedisBus(new RedisConfig(Settings.Redis.HOST, Settings.Redis.PORT, emptyToNull(Settings.Redis.USER), emptyToNull(Settings.Redis.PASSWORD), Settings.Redis.SSL,
					Settings.Redis.DATABASE, Settings.Redis.CONNECT_TIMEOUT_MILLIS, Settings.Redis.PREFIX), new HmacSigner(secret), nodeId, Clock.systemUTC(), logger);

			outbox = new OutboxPublisher(database, bus, nodeId, Clock.systemUTC(), logger, Settings.Punishments.OUTBOX_INTERVAL_MILLIS);

			final PunishmentService service = new PunishmentService(database, nodeId, Clock.systemUTC(), PaperExemptions.create(plugin, Settings.Punishments.USE_WEIGHTS, logger));
			service.onEventsAppended(outbox::wake);

			final Core core = new Core(logger, serverName, Settings.Server.groupOrNull(), nodeId, dateFormat, database, bus, outbox, service);

			core.reloadReasons();
			core.refreshServers();

			if (!core.servers.hasServer(serverName))
				logger.warning("Server.Name '" + serverName + "' is not registered by the proxy yet (jb_servers). Server bans are stored by that name, so make sure it matches the name in velocity.toml exactly.");

			bus.onVerified(bus.channels().events(), event -> {
				if (REASONS_UPDATED.equals(event.type()))
					core.scheduler.execute(core::reloadReasonsQuietly);
			});

			if (Settings.WebCommands.ENABLED) {
				new WebCommandHandler(database, service, bus, nodeId, Clock.systemUTC(), logger, Duration.ofSeconds(Settings.WebCommands.MAX_SKEW_SECONDS)).start();
				logger.info("Web panel commands are enabled on this server");
			}

			bus.start();
			outbox.start();

			core.scheduler.scheduleWithFixedDelay(core.onlineNames::refresh, 1, 3, TimeUnit.SECONDS);
			core.scheduler.scheduleWithFixedDelay(core::refreshServersQuietly, 30, 30, TimeUnit.SECONDS);
			core.scheduler.scheduleWithFixedDelay(core::reloadReasonsQuietly, 5, 5, TimeUnit.MINUTES); // safety net for a missed REASONS_UPDATED

			return core;

		} catch (final Exception | Error ex) {
			if (outbox != null)
				outbox.close();

			if (bus != null)
				bus.close();

			database.close();

			throw ex;
		}
	}

	// ------------------------------------------------------------------------------------------
	// Catalog refreshing
	// ------------------------------------------------------------------------------------------

	/** Replaces the reason catalog with the enabled rows of {@code jb_reasons}. Blocking. */
	public void reloadReasons() throws SQLException {
		final ReasonRepository repository = new ReasonRepository();

		this.reasons.replace(this.database.query(connection -> repository.findAll(connection, false)));
		this.logger.fine("Loaded " + this.reasons.size() + " reasons");
	}

	private void reloadReasonsQuietly() {
		try {
			this.reloadReasons();
		} catch (final SQLException | RuntimeException ex) {
			this.logger.log(Level.WARNING, "Could not reload reasons, keeping the previous ones: " + ex.getMessage());
		}
	}

	private void refreshServers() throws SQLException {
		final ServerRepository repository = new ServerRepository();

		this.servers.replace(this.database.query(repository::findAll));
	}

	private void refreshServersQuietly() {
		try {
			this.refreshServers();
		} catch (final SQLException | RuntimeException ex) {
			this.logger.log(Level.FINE, "Could not refresh servers: " + ex.getMessage());
		}
	}

	// ------------------------------------------------------------------------------------------
	// Accessors
	// ------------------------------------------------------------------------------------------

	public Database database() {
		return this.database;
	}

	public PunishmentService service() {
		return this.service;
	}

	public PunishmentRepository punishments() {
		return this.punishments;
	}

	public ReasonCatalog reasons() {
		return this.reasons;
	}

	public ServerCatalog servers() {
		return this.servers;
	}

	public PlayerResolver resolver() {
		return this.resolver;
	}

	public OnlineNames onlineNames() {
		return this.onlineNames;
	}

	public Clock clock() {
		return this.clock;
	}

	public DateTimeFormatter dateFormat() {
		return this.dateFormat;
	}

	/** This server's name as the proxy knows it. */
	public String serverName() {
		return this.serverName;
	}

	/** Group this server belongs to, or null. */
	public String serverGroup() {
		return this.serverGroup;
	}

	public String nodeId() {
		return this.nodeId;
	}

	/** Logs a failed login check without flooding the console: one line per failure, stack trace only at FINE. */
	public void logGateFailure(String player, Exception ex) {
		this.logger.log(Level.WARNING, "Could not check bans for " + player + " at login: " + ex.getMessage());
		this.logger.log(Level.FINE, "Login check failure", ex);
	}

	@Override
	public void close() {
		this.scheduler.shutdownNow();

		try {
			this.outbox.drainOnce(); // best effort: get queued events out before going down
		} catch (final SQLException | RuntimeException ex) {
			this.logger.warning("Could not flush queued events on shutdown; they stay in the database for the next start: " + ex.getMessage());
		}

		this.outbox.close();
		this.bus.close();
		this.database.close();
	}

	private static String emptyToNull(String value) {
		return value == null || value.isEmpty() ? null : value;
	}
}
