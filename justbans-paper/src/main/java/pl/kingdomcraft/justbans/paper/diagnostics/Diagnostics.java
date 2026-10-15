package pl.kingdomcraft.justbans.paper.diagnostics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.mineacademy.fo.debug.Debugger;
import org.mineacademy.fo.debug.LagCatcher;

import pl.kingdomcraft.justbans.common.database.Database;

/**
 * Debug sections and lag measurement, on top of Foundation's {@link Debugger} and {@link LagCatcher}.
 *
 * <p>Sections are switched on with the {@code Debug} list in settings.yml:
 * <ul>
 * <li>{@value #DATABASE}: every database call with its duration and thread;</li>
 * <li>{@value #REDIS}: every Redis message sent or received, and lock attempts;</li>
 * <li>{@value #ENFORCE}: login-gate decisions;</li>
 * <li>{@value #SWEEPER}: expiry sweeper rounds.</li>
 * </ul>
 *
 * <p>{@link LagCatcher} keeps its timers in plain unsynchronized maps, so it is only ever used on the main thread here.
 * {@link Debugger#debug} only reads settings and prints, which is safe from any thread.
 */
public final class Diagnostics {

	public static final String DATABASE = "database";
	public static final String REDIS = "redis";
	public static final String ENFORCE = "enforce";
	public static final String SWEEPER = "sweeper";

	private static final Runnable NOTHING = () -> {
	};

	/** At most one main-thread warning per this many milliseconds, so a bug in a hot path cannot flood the console. */
	private static final long WARN_EVERY_MILLIS = 60_000;

	private Diagnostics() {
	}

	public static boolean isDebugged(String section) {
		return Debugger.isDebugged(section);
	}

	public static void debug(String section, String message) {
		Debugger.debug(section, message);
	}

	/**
	 * Runs the work, timing it with {@link LagCatcher} when this is the main thread (so a slow placeholder or menu step shows
	 * up in the console as "{section} took N ms" once it passes {@code Log_Lag_Over_Milis}); on any other thread it just runs.
	 */
	public static <T> T timed(String section, Supplier<T> work) {
		if (!Bukkit.isPrimaryThread())
			return work.get();

		LagCatcher.start(section);

		try {
			return work.get();

		} finally {
			LagCatcher.end(section);
		}
	}

	/**
	 * Watches synchronous JDBC calls. Database work belongs on the database executor, so a call on the main thread is a bug:
	 * it is timed with {@link LagCatcher} and reported once a minute with a stack trace pointing at the caller. With the
	 * {@value #DATABASE} debug section on, every call is logged. With neither it costs one thread check.
	 */
	public static Database.CallObserver databaseObserver(Logger logger) {
		final AtomicLong lastWarning = new AtomicLong();

		return kind -> {
			final boolean main = Bukkit.isPrimaryThread();
			final boolean debug = Debugger.isDebugged(DATABASE);

			if (!main && !debug)
				return NOTHING;

			final String section = "JustBans database " + kind + " on the main thread";
			final long start = System.nanoTime();

			if (main) {
				final long now = System.currentTimeMillis();
				final long previous = lastWarning.get();

				if (now - previous >= WARN_EVERY_MILLIS && lastWarning.compareAndSet(previous, now))
					logger.log(Level.WARNING, "A database " + kind + " is running on the main thread, which freezes the server while it waits. Please report this.", new Throwable("main-thread database call"));

				LagCatcher.start(section);
			}

			return () -> {
				if (main)
					LagCatcher.end(section);

				if (debug)
					Debugger.debug(DATABASE, kind + " took " + String.format("%.2f", (System.nanoTime() - start) / 1_000_000D) + " ms on " + Thread.currentThread().getName());
			};
		};
	}
}
