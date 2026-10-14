package pl.kingdomcraft.justbans.common.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import pl.kingdomcraft.justbans.common.database.Sql;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.json.ReasonJson;

/** Stateless JDBC access to {@code jb_reasons}. */
public final class ReasonRepository {

	public void upsert(Connection connection, ReasonDefinition reason, Instant now) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO jb_reasons (id, display_name, allowed_types, definition, sort_order, enabled, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) "
						+ "ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), allowed_types = VALUES(allowed_types), "
						+ "definition = VALUES(definition), sort_order = VALUES(sort_order), enabled = VALUES(enabled), updated_at = VALUES(updated_at)")) {
			statement.setString(1, reason.id());
			statement.setString(2, reason.displayName());
			statement.setString(3, reason.allowedKinds().stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
			statement.setString(4, ReasonJson.toJson(reason));
			statement.setInt(5, reason.sortOrder());
			statement.setBoolean(6, reason.enabled());
			Sql.setInstant(statement, 7, now);
			statement.executeUpdate();
		}
	}

	/**
	 * Makes the table match the configuration: upserts every given reason and disables the rest
	 * (rows are kept so history still resolves).
	 */
	public void sync(Connection connection, Collection<ReasonDefinition> reasons, Instant now) throws SQLException {
		final Set<String> keep = new HashSet<>();

		for (final ReasonDefinition reason : reasons) {
			upsert(connection, reason, now);
			keep.add(reason.id());
		}

		for (final ReasonDefinition existing : findAll(connection, true))
			if (existing.enabled() && !keep.contains(existing.id()))
				upsert(connection, withEnabled(existing, false), now);
	}

	public Optional<ReasonDefinition> find(Connection connection, String id) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM jb_reasons WHERE id = ?")) {
			statement.setString(1, id);

			try (ResultSet result = statement.executeQuery()) {
				return result.next() ? Optional.of(map(result)) : Optional.empty();
			}
		}
	}

	public List<ReasonDefinition> findAll(Connection connection, boolean includeDisabled) throws SQLException {
		final List<ReasonDefinition> reasons = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT * FROM jb_reasons" + (includeDisabled ? "" : " WHERE enabled = TRUE") + " ORDER BY sort_order, id");
				ResultSet result = statement.executeQuery()) {
			while (result.next())
				reasons.add(map(result));
		}

		return reasons;
	}

	private static ReasonDefinition withEnabled(ReasonDefinition r, boolean enabled) {
		return new ReasonDefinition(r.id(), r.displayName(), r.allowedKinds(), r.defaultDuration(), r.ladder(), r.icon(), r.templates(), r.sortOrder(), enabled);
	}

	private static ReasonDefinition map(ResultSet result) throws SQLException {
		return ReasonJson.fromJson(result.getString("id"), result.getString("display_name"), result.getInt("sort_order"),
				result.getBoolean("enabled"), result.getString("definition"));
	}
}
