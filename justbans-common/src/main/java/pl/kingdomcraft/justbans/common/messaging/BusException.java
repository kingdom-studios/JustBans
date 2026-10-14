package pl.kingdomcraft.justbans.common.messaging;

/** Publishing to Redis failed (unavailable, timeout, message too large). The database is unaffected. */
public final class BusException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public BusException(String message, Throwable cause) {
		super(message, cause);
	}

	public BusException(String message) {
		super(message);
	}
}
