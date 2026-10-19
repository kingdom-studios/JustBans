package pl.kingdomcraft.justbans.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.support.TestDatabase;

class SqlIntegrationTest {

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

	private static void writeHeartbeat(Connection connection, Instant value) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("REPLACE INTO jb_servers (id, last_heartbeat) VALUES ('s', ?)")) {
			Sql.setInstant(statement, 1, value);
			statement.executeUpdate();
		}
	}

	/** The driver is told to use UTC; a JVM in another time zone must still store and read the same instants. */
	@Test
	void instantsSurviveAJvmInAnotherTimeZone() throws Exception {
		final TimeZone original = TimeZone.getDefault();

		try (Connection connection = db.database().getConnection(); Statement statement = connection.createStatement()) {
			TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));

			final Instant written = Instant.parse("2031-07-01T12:34:56.789Z");

			writeHeartbeat(connection, written);

			try (ResultSet result = statement.executeQuery("SELECT last_heartbeat FROM jb_servers WHERE id = 's'")) {
				result.next();

				assertEquals(written, Sql.instant(result, "last_heartbeat"));
			}

			// Reading back what was written proves nothing if both directions share the same mistake: look at what the server holds.
			try (ResultSet result = statement.executeQuery("SELECT CAST(last_heartbeat AS CHAR) FROM jb_servers WHERE id = 's'")) {
				result.next();

				assertEquals("2031-07-01 12:34:56.789", result.getString(1));
			}
		} finally {
			TimeZone.setDefault(original);
		}
	}

	@Test
	void aNullInstantIsNull() throws Exception {
		try (Connection connection = db.database().getConnection(); Statement statement = connection.createStatement()) {
			writeHeartbeat(connection, null);

			try (ResultSet result = statement.executeQuery("SELECT last_heartbeat FROM jb_servers WHERE id = 's'")) {
				result.next();

				assertNull(Sql.instant(result, "last_heartbeat"));
			}
		}
	}
}
