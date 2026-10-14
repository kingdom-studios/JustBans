package pl.kingdomcraft.justbans.common.service;

/** Stable error codes shared by in-game messages and the web protocol (docs/DEVELOPMENT_PLAN.md section 4.4). */
public enum ErrorCode {
	UNKNOWN_PLAYER,
	UNKNOWN_REASON,
	REASON_TYPE_NOT_ALLOWED,
	ALREADY_BANNED,
	/** No active ban in the requested scope to pardon. */
	NOT_BANNED,
	UNKNOWN_PUNISHMENT,
	/** The punishment exists but is expired, revoked or a kick. */
	NOT_ACTIVE,
	TARGET_EXEMPT,
	INVALID_DURATION,
	INVALID_REQUEST,
	UNKNOWN_SERVER,
	BAD_SIGNATURE,
	EXPIRED_REQUEST,
	INTERNAL
}
