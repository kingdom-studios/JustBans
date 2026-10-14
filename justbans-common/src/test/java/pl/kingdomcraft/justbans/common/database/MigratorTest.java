package pl.kingdomcraft.justbans.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class MigratorTest {

	@Test
	void splitsStatementsAndSkipsComments() {
		final List<String> statements = Migrator.splitStatements("-- header\nCREATE TABLE a (\n  id INT\n);\n\nCREATE TABLE b (id INT);\n");

		assertEquals(2, statements.size());
		assertTrue(statements.get(0).startsWith("CREATE TABLE a"));
		assertFalse(statements.get(0).endsWith(";"));
	}

	@Test
	void bundledMigrationsAreValidAndAscending() {
		final List<Migrator.Migration> migrations = Migrator.loadMigrations();

		assertFalse(migrations.isEmpty());
		assertEquals(1, migrations.get(0).version());
		assertEquals("init", migrations.get(0).description());
		assertTrue(Migrator.splitStatements(migrations.get(0).sql()).size() >= 7);
	}
}
