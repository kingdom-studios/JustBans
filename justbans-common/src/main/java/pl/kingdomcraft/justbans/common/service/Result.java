package pl.kingdomcraft.justbans.common.service;

/**
 * Outcome of a service call. Expected failures (unknown reason, already banned, ...) are results;
 * unexpected ones (SQL errors) complete the future exceptionally.
 */
public record Result<T>(T value, ErrorCode error, String message) {

	public static <T> Result<T> ok(T value) {
		return new Result<>(value, null, null);
	}

	public static <T> Result<T> error(ErrorCode code, String message) {
		return new Result<>(null, code, message);
	}

	public boolean isOk() {
		return this.error == null;
	}
}
