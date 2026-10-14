package pl.kingdomcraft.justbans.velocity.util;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Routes {@code java.util.logging} (used by justbans-common) into Velocity's SLF4J logger. */
public final class Slf4jHandler extends Handler {

	private final org.slf4j.Logger target;

	private Slf4jHandler(org.slf4j.Logger target) {
		this.target = target;
	}

	/** A JUL logger that writes to {@code target} and nowhere else. */
	public static Logger bridge(String name, org.slf4j.Logger target) {
		final Logger logger = Logger.getLogger(name);

		logger.setUseParentHandlers(false);
		logger.setLevel(Level.ALL);

		for (final Handler existing : logger.getHandlers())
			logger.removeHandler(existing);

		logger.addHandler(new Slf4jHandler(target));

		return logger;
	}

	@Override
	public void publish(LogRecord record) {
		final int level = record.getLevel().intValue();
		final String message = record.getMessage() == null ? "" : new java.util.logging.SimpleFormatter().formatMessage(record);
		final Throwable thrown = record.getThrown();

		if (level >= Level.SEVERE.intValue())
			this.target.error(message, thrown);
		else if (level >= Level.WARNING.intValue())
			this.target.warn(message, thrown);
		else if (level >= Level.INFO.intValue())
			this.target.info(message, thrown);
		else
			this.target.debug(message, thrown);
	}

	@Override
	public void flush() {
	}

	@Override
	public void close() {
	}
}
