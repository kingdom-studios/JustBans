package pl.kingdomcraft.justbans.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.support.TestDatabase;

/** A database server that closes connections behind the pool's back (wait_timeout, a restart) must not surface as errors. */
class DatabasePoolHealthIntegrationTest {

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

	private static long ping() throws Exception {
		return db.database().query(connection -> {
			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT 1")) {
				result.next();

				return result.getLong(1);
			}
		});
	}

	/** Kills every connection to the test schema except the killer's own, as an idle timeout on the server would. */
	private static int killPoolConnections() throws Exception {
		final List<Long> ids = new ArrayList<>();

		try (Connection admin = db.adminConnection(); PreparedStatement statement = admin.prepareStatement("SELECT ID FROM information_schema.PROCESSLIST WHERE DB = ? AND ID <> CONNECTION_ID()")) {
			statement.setString(1, db.schema());

			try (ResultSet result = statement.executeQuery()) {
				while (result.next())
					ids.add(result.getLong(1));
			}

			for (final long id : ids)
				try (Statement kill = admin.createStatement()) {
					kill.execute("KILL " + id);
				}
		}

		return ids.size();
	}

	@Test
	void queriesKeepWorkingAfterTheServerClosedAllPooledConnections() throws Exception {
		// fill the pool with several connections
		final List<CompletableFuture<Long>> warm = new ArrayList<>();

		for (int i = 0; i < 6; i++)
			warm.add(db.database().supplyAsync(() -> db.database().query(connection -> {
				try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT SLEEP(0.2) + 1")) {
					result.next();

					return result.getLong(1);
				}
			})));

		for (final CompletableFuture<Long> future : warm)
			assertEquals(1, future.get());

		// HikariCP only validates a connection that has been idle for more than half a second (aliveBypassWindowMs); a connection
		// that sat unused for hours, as in production, is always validated, found dead and replaced.
		Thread.sleep(800);

		final int killed = killPoolConnections();
		assertTrue(killed >= 2, "the test needs pooled connections to kill, found " + killed);

		Thread.sleep(800);

		for (int i = 0; i < 5; i++)
			assertEquals(1, ping());
	}
}
