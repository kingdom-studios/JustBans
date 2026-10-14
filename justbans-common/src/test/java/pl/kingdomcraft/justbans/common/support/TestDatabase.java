package pl.kingdomcraft.justbans.common.support;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MariaDBContainer;

import pl.kingdomcraft.justbans.common.database.Database;
import pl.kingdomcraft.justbans.common.database.DatabaseConfig;
import pl.kingdomcraft.justbans.common.database.Migrator;

/**
 * Gives each test class its own empty, migrated MariaDB schema.
 *
 * <p>Uses a Testcontainers {@code mariadb:11.4} container when Docker is available. Set
 * {@code JUSTBANS_TEST_DB=host:port:user:password} to run against an existing server instead (the user needs
 * CREATE/DROP DATABASE rights). With neither, the tests are skipped.
 */
public final class TestDatabase implements AutoCloseable {

	private static MariaDBContainer<?> container;

	private final String schema = "jb_test_" + UUID.randomUUID().toString().replace("-", "");
	private final Database admin;
	private final Database database;

	private TestDatabase(String host, int port, String user, String password) throws Exception {
		this.admin = new Database(new DatabaseConfig(host, port, "", user, password, 2, 10_000, Map.of()));

		try (Connection connection = this.admin.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE " + this.schema + " CHARACTER SET utf8mb4");
		}

		this.database = new Database(new DatabaseConfig(host, port, this.schema, user, password, 8, 10_000, Map.of()));
		new Migrator(this.database, Logger.getLogger("test")).migrate();
	}

	public static TestDatabase create() throws Exception {
		final String external = System.getenv("JUSTBANS_TEST_DB");

		if (external != null && !external.isBlank()) {
			final String[] parts = external.split(":", 4);

			return new TestDatabase(parts[0], Integer.parseInt(parts[1]), parts[2], parts.length > 3 ? parts[3] : "");
		}

		Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available and JUSTBANS_TEST_DB is not set");

		synchronized (TestDatabase.class) {
			if (container == null) {
				container = new MariaDBContainer<>("mariadb:11.4").withUsername("root").withPassword("test");
				container.start();
			}
		}

		return new TestDatabase(container.getHost(), container.getMappedPort(3306), "root", "test");
	}

	public Database database() {
		return this.database;
	}

	@Override
	public void close() throws Exception {
		this.database.close();

		try (Connection connection = this.admin.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + this.schema);
		}

		this.admin.close();
	}
}
