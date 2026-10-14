package pl.kingdomcraft.justbans.common.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import pl.kingdomcraft.justbans.common.database.Sql;
import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.ActorType;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.common.domain.ScopeType;

/**
 * Stateless JDBC access to {@code jb_punishments}. "Active" is always computed from
 * {@code revoked_at} and {@code expires_at} against the supplied clock instant.
 */
public final class PunishmentRepository {

	private static final String ACTIVE = "revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)";

	/**
	 * Inserts the row and returns it with its generated id.
	 *
	 * @param requestId idempotency key from a web command, or null
	 */
	public Punishment insert(Connection connection, Punishment p, UUID requestId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO jb_punishments (public_id, type, scope_type, scope_target, target_uuid, target_name, "
						+ "actor_type, actor_uuid, actor_ref, actor_name, reason_id, reason_text, note, created_at, expires_at, "
						+ "origin_server, silent, ladder_step, request_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				Statement.RETURN_GENERATED_KEYS)) {
			statement.setString(1, p.publicId());
			statement.setString(2, p.type().name());
			statement.setString(3, p.scope().type().name());
			statement.setString(4, p.scope().target());
			Sql.setUuid(statement, 5, p.targetUuid());
			statement.setString(6, p.targetName());
			statement.setString(7, p.actor().type().name());
			Sql.setUuid(statement, 8, p.actor().uuid());
			Sql.setString(statement, 9, p.actor().ref());
			statement.setString(10, p.actor().name());
			Sql.setString(statement, 11, p.reasonId());
			statement.setString(12, p.reasonText());
			Sql.setString(statement, 13, p.note());
			Sql.setInstant(statement, 14, p.createdAt());
			Sql.setInstant(statement, 15, p.expiresAt());
			Sql.setString(statement, 16, p.originServer());
			statement.setBoolean(17, p.silent());
			Sql.setInt(statement, 18, p.ladderStep());
			Sql.setUuid(statement, 19, requestId);
			statement.executeUpdate();

			try (ResultSet keys = statement.getGeneratedKeys()) {
				keys.next();

				return p.withId(keys.getLong(1));
			}
		}
	}

	public Optional<Punishment> findById(Connection connection, long id) throws SQLException {
		return findOne(connection, "id = ?", statement -> statement.setLong(1, id));
	}

	public Optional<Punishment> findByPublicId(Connection connection, String publicId) throws SQLException {
		return findOne(connection, "public_id = ?", statement -> statement.setString(1, publicId));
	}

	public Optional<Punishment> findByRequestId(Connection connection, UUID requestId) throws SQLException {
		return findOne(connection, "request_id = ?", statement -> Sql.setUuid(statement, 1, requestId));
	}

	public boolean publicIdExists(Connection connection, String publicId) throws SQLException {
		return findByPublicId(connection, publicId).isPresent();
	}

	/** All active bans of the player, in any scope. This is the join-time query. */
	public List<Punishment> findActiveBans(Connection connection, UUID target, Instant now) throws SQLException {
		return findMany(connection, "target_uuid = ? AND type = 'BAN' AND " + ACTIVE + " ORDER BY id",
				statement -> {
					Sql.setUuid(statement, 1, target);
					Sql.setInstant(statement, 2, now);
				});
	}

	/** The active ban in exactly this scope, if any. */
	public Optional<Punishment> findActiveBan(Connection connection, UUID target, Scope scope, Instant now) throws SQLException {
		return findOne(connection, "target_uuid = ? AND type = 'BAN' AND scope_type = ? AND scope_target = ? AND " + ACTIVE + " ORDER BY id DESC",
				statement -> {
					Sql.setUuid(statement, 1, target);
					statement.setString(2, scope.type().name());
					statement.setString(3, scope.target());
					Sql.setInstant(statement, 4, now);
				});
	}

	/** Active warns, oldest first. */
	public List<Punishment> findActiveWarns(Connection connection, UUID target, Instant now) throws SQLException {
		return findMany(connection, "target_uuid = ? AND type = 'WARN' AND " + ACTIVE + " ORDER BY id",
				statement -> {
					Sql.setUuid(statement, 1, target);
					Sql.setInstant(statement, 2, now);
				});
	}

	/** How many non-revoked punishments with this reason the player already has (ladder position). */
	public int countPriorOffences(Connection connection, UUID target, String reasonId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT COUNT(*) FROM jb_punishments WHERE target_uuid = ? AND reason_id = ? AND revoked_at IS NULL")) {
			Sql.setUuid(statement, 1, target);
			statement.setString(2, reasonId);

			try (ResultSet result = statement.executeQuery()) {
				result.next();

				return result.getInt(1);
			}
		}
	}

	/** Full history, newest first. */
	public List<Punishment> history(Connection connection, UUID target, int offset, int limit) throws SQLException {
		return findMany(connection, "target_uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?",
				statement -> {
					Sql.setUuid(statement, 1, target);
					statement.setInt(2, limit);
					statement.setInt(3, offset);
				});
	}

	/**
	 * Marks the punishment revoked if it still is not.
	 *
	 * @return false if it was already revoked (or does not exist)
	 */
	public boolean revoke(Connection connection, long id, Instant at, Actor by, String reason) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"UPDATE jb_punishments SET revoked_at = ?, revoked_by_type = ?, revoked_by_uuid = ?, revoked_by_name = ?, revoke_reason = ? "
						+ "WHERE id = ? AND revoked_at IS NULL")) {
			Sql.setInstant(statement, 1, at);
			statement.setString(2, by.type().name());
			Sql.setUuid(statement, 3, by.uuid());
			statement.setString(4, by.name());
			Sql.setString(statement, 5, reason);
			statement.setLong(6, id);

			return statement.executeUpdate() == 1;
		}
	}

	// ------------------------------------------------------------------------------------------

	@FunctionalInterface
	private interface Binder {
		void bind(PreparedStatement statement) throws SQLException;
	}

	private Optional<Punishment> findOne(Connection connection, String where, Binder binder) throws SQLException {
		final List<Punishment> found = findMany(connection, where + " LIMIT 1", binder);

		return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
	}

	private List<Punishment> findMany(Connection connection, String where, Binder binder) throws SQLException {
		final List<Punishment> list = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM jb_punishments WHERE " + where)) {
			binder.bind(statement);

			try (ResultSet result = statement.executeQuery()) {
				while (result.next())
					list.add(map(result));
			}
		}

		return list;
	}

	private static Punishment map(ResultSet r) throws SQLException {
		final Actor actor = new Actor(ActorType.valueOf(r.getString("actor_type")), Sql.uuid(r, "actor_uuid"), r.getString("actor_ref"), r.getString("actor_name"));

		Punishment.Revocation revocation = null;
		final Instant revokedAt = Sql.instant(r, "revoked_at");

		if (revokedAt != null) {
			final Actor by = new Actor(ActorType.valueOf(r.getString("revoked_by_type")), Sql.uuid(r, "revoked_by_uuid"), null, r.getString("revoked_by_name"));
			revocation = new Punishment.Revocation(revokedAt, by, r.getString("revoke_reason"));
		}

		return new Punishment(r.getLong("id"), r.getString("public_id"), PunishmentType.valueOf(r.getString("type")),
				new Scope(ScopeType.valueOf(r.getString("scope_type")), r.getString("scope_target")),
				Sql.uuid(r, "target_uuid"), r.getString("target_name"), actor, r.getString("reason_id"), r.getString("reason_text"),
				r.getString("note"), Sql.instant(r, "created_at"), Sql.instant(r, "expires_at"), r.getString("origin_server"),
				r.getBoolean("silent"), Sql.integer(r, "ladder_step"), revocation);
	}
}
