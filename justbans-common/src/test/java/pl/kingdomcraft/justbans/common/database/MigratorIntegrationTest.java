package pl.kingdomcraft.justbans.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.support.TestDatabase;

class MigratorIntegrationTest {

	private static TestDatabase db;

	@BeforeAll
	static void setUp() throws Exception {
		db = TestDatabase.create();
	}

	@AfterAll
	static void tearDown() throws Exception {
		if (db != null)
			db.close();
	}

	@Test
	void v1CreatesEveryTable() throws Exception {
		final Set<String> tables = new HashSet<>();

		try (Connection connection = db.database().getConnection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SHOW TABLES")) {
			while (result.next())
				tables.add(result.getString(1));
		}

		for (final String expected : new String[] { "jb_schema_version", "jb_players", "jb_player_names", "jb_servers", "jb_punishments", "jb_events", "jb_reasons", "jb_command_log" })
			assertTrue(tables.contains(expected), "missing table " + expected);
	}

	@Test
	void migrationIsIdempotent() throws Exception {
		final Migrator migrator = new Migrator(db.database(), Logger.getLogger("test"));

		assertEquals(2, migrator.migrate());
		assertEquals(2, migrator.migrate());
		assertEquals(2L, count("SELECT COUNT(*) FROM jb_schema_version"));
	}

	@Test
	void concurrentMigrationsApplyOnce() throws Exception {
		final Migrator migrator = new Migrator(db.database(), Logger.getLogger("test"));
		final CompletableFuture<Integer> a = db.database().supplyAsync(migrator::migrate);
		final CompletableFuture<Integer> b = db.database().supplyAsync(migrator::migrate);

		assertEquals(2, a.get());
		assertEquals(2, b.get());
		assertEquals(2L, count("SELECT COUNT(*) FROM jb_schema_version"));
	}

	@Test
	void refusesToRunAgainstNewerSchema() throws Exception {
		try (Connection connection = db.database().getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO jb_schema_version (version, description) VALUES (99, 'from the future')");
		}

		try {
			assertThrows(IllegalStateException.class, () -> new Migrator(db.database(), Logger.getLogger("test")).migrate());
		} finally {
			try (Connection connection = db.database().getConnection(); Statement statement = connection.createStatement()) {
				statement.execute("DELETE FROM jb_schema_version WHERE version = 99");
			}
		}
	}

	private static long count(String sql) throws Exception {
		try (Connection connection = db.database().getConnection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
			result.next();

			return result.getLong(1);
		}
	}
}
