package pl.kingdomcraft.justbans.velocity.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.Scope;


class ActiveBanCacheTest {

	private final UUID player = UUID.randomUUID();
	private final AtomicInteger loads = new AtomicInteger();

	@Test
	void loadsOnceThenServesFromCache() throws Exception {
		final List<Punishment> bans = List.of(TestBans.p(PunishmentType.BAN, Scope.network(), null, false));
		final ActiveBanCache cache = new ActiveBanCache(uuid -> {
			loads.incrementAndGet();
			return bans;
		}, Duration.ofMinutes(1));

		assertEquals(bans, cache.get(player));
		assertSame(cache.get(player), cache.get(player));
		assertEquals(1, loads.get());
	}

	@Test
	void cachesEmptyResultsToo() throws Exception {
		final ActiveBanCache cache = new ActiveBanCache(uuid -> {
			loads.incrementAndGet();
			return List.of();
		}, Duration.ofMinutes(1));

		cache.get(player);
		cache.get(player);

		assertEquals(1, loads.get(), "a clean player must not hit the database on every server switch");
	}

	@Test
	void invalidateForcesAReload() throws Exception {
		final ActiveBanCache cache = new ActiveBanCache(uuid -> {
			loads.incrementAndGet();
			return List.of();
		}, Duration.ofMinutes(1));

		cache.get(player);
		cache.invalidate(player);
		assertNull(cache.getIfPresent(player));
		cache.get(player);

		assertEquals(2, loads.get());
	}

	@Test
	void invalidateAllClearsEveryone() throws Exception {
		final ActiveBanCache cache = new ActiveBanCache(uuid -> List.of(), Duration.ofMinutes(1));
		final UUID other = UUID.randomUUID();

		cache.get(player);
		cache.get(other);
		cache.invalidateAll();

		assertNull(cache.getIfPresent(player));
		assertNull(cache.getIfPresent(other));
	}

	@Test
	void sourceFailuresPropagateAndAreNotCached() throws Exception {
		final AtomicInteger calls = new AtomicInteger();
		final ActiveBanCache cache = new ActiveBanCache(uuid -> {
			if (calls.incrementAndGet() == 1)
				throw new SQLException("database down");

			return List.of();
		}, Duration.ofMinutes(1));

		assertThrows(SQLException.class, () -> cache.get(player));
		assertEquals(List.of(), cache.get(player), "the next call retries instead of serving a cached failure");
		assertEquals(2, calls.get());
	}

	@Test
	void entriesExpireOnTheirOwn() throws Exception {
		final ActiveBanCache cache = new ActiveBanCache(uuid -> {
			loads.incrementAndGet();
			return List.of();
		}, Duration.ofMillis(50));

		cache.get(player);
		Thread.sleep(150);
		cache.get(player);

		assertEquals(2, loads.get());
	}
}
