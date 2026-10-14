package pl.kingdomcraft.justbans.common.database;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Applies the versioned SQL files listed in {@code db/mariadb/migrations.list}.
 *
 * <p>Each line of the list is {@code <version> <file name>}. Migrations run inside a
 * {@code GET_LOCK}, so whichever node starts first migrates and the rest wait, then see nothing to do.
 * A node whose bundled migrations are older than the schema in the database refuses to start.
 */
public final class Migrator {

	private static final String RESOURCE_DIR = "db/mariadb/";
	private static final String LOCK_NAME = "justbans_migrate";
	private static final int LOCK_TIMEOUT_SECONDS = 30;

	private final Database database;
	private final Logger logger;

	public Migrator(Database database, Logger logger) {
		this.database = database;
		this.logger = logger;
	}

	/**
	 * @return the schema version after migrating
	 */
	public int migrate() throws SQLException {
		final List<Migration> migrations = loadMigrations();
		final int latest = migrations.isEmpty() ? 0 : migrations.get(migrations.size() - 1).version();

		try (Connection connection = this.database.getConnection()) {
			acquireLock(connection);

			try {
				ensureVersionTable(connection);

				final int current = currentVersion(connection);

				if (current > latest)
					throw new IllegalStateException("Database schema is version " + current + " but this build only knows up to " + latest + ". Update JustBans.");

				for (final Migration migration : migrations)
					if (migration.version() > current)
						apply(connection, migration);

				return latest;

			} finally {
				releaseLock(connection);
			}
		}
	}

	private void apply(Connection connection, Migration migration) throws SQLException {
		this.logger.info("Applying database migration V" + migration.version() + " (" + migration.description() + ")");

		// DDL statements auto-commit in MariaDB, so there is no transaction to wrap them in.
		try (Statement statement = connection.createStatement()) {
			for (final String sql : splitStatements(migration.sql()))
				statement.execute(sql);
		}

		try (PreparedStatement insert = connection.prepareStatement("INSERT INTO jb_schema_version (version, description) VALUES (?, ?)")) {
			insert.setInt(1, migration.version());
			insert.setString(2, migration.description());
			insert.executeUpdate();
		}
	}

	private static void ensureVersionTable(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE IF NOT EXISTS jb_schema_version ("
					+ "version INT NOT NULL PRIMARY KEY, "
					+ "description VARCHAR(200) NOT NULL, "
					+ "applied_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3))");
		}
	}

	private static int currentVersion(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM jb_schema_version")) {
			result.next();

			return result.getInt(1);
		}
	}

	private static void acquireLock(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
			statement.setString(1, LOCK_NAME);
			statement.setInt(2, LOCK_TIMEOUT_SECONDS);

			try (ResultSet result = statement.executeQuery()) {
				if (!result.next() || result.getInt(1) != 1)
					throw new SQLException("Could not acquire migration lock within " + LOCK_TIMEOUT_SECONDS + "s");
			}
		}
	}

	private static void releaseLock(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
			statement.setString(1, LOCK_NAME);
			statement.execute();
		}
	}

	/**
	 * Splits a script on semicolons that end a line, dropping {@code --} comment lines.
	 * Sufficient for plain DDL; does not support stored routines.
	 */
	static List<String> splitStatements(String script) {
		final List<String> statements = new ArrayList<>();
		final StringBuilder current = new StringBuilder();

		for (final String line : script.split("\\R")) {
			final String trimmed = line.trim();

			if (trimmed.isEmpty() || trimmed.startsWith("--"))
				continue;

			current.append(line).append('\n');

			if (trimmed.endsWith(";")) {
				final String sql = current.toString().trim();

				statements.add(sql.substring(0, sql.length() - 1));
				current.setLength(0);
			}
		}

		if (!current.toString().isBlank())
			statements.add(current.toString().trim());

		return statements;
	}

	static List<Migration> loadMigrations() {
		final List<Migration> migrations = new ArrayList<>();

		for (final String line : readResource("migrations.list").split("\\R")) {
			final String trimmed = line.trim();

			if (trimmed.isEmpty() || trimmed.startsWith("#"))
				continue;

			final String[] parts = trimmed.split("\\s+", 2);

			if (parts.length != 2)
				throw new IllegalStateException("Malformed migrations.list line: " + line);

			final int version = Integer.parseInt(parts[0]);
			final String file = parts[1];

			if (!migrations.isEmpty() && version <= migrations.get(migrations.size() - 1).version())
				throw new IllegalStateException("migrations.list must be strictly ascending, offending line: " + line);

			migrations.add(new Migration(version, describe(file), readResource(file)));
		}

		return migrations;
	}

	/** {@code V1__init.sql} becomes {@code init}. */
	private static String describe(String file) {
		final int separator = file.indexOf("__");
		final String name = separator >= 0 ? file.substring(separator + 2) : file;

		return (name.endsWith(".sql") ? name.substring(0, name.length() - 4) : name).replace('_', ' ');
	}

	private static String readResource(String name) {
		try (InputStream stream = Migrator.class.getClassLoader().getResourceAsStream(RESOURCE_DIR + name)) {
			if (stream == null)
				throw new IllegalStateException("Missing bundled resource " + RESOURCE_DIR + name);

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				final StringBuilder content = new StringBuilder();
				String line;

				while ((line = reader.readLine()) != null)
					content.append(line).append('\n');

				return content.toString();
			}

		} catch (final IOException ex) {
			throw new IllegalStateException("Could not read " + RESOURCE_DIR + name, ex);
		}
	}

	record Migration(int version, String description, String sql) {
	}
}
