package pl.kingdomcraft.justbans.common.messaging;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs and verifies {@link Envelope}s with HMAC-SHA256 keyed by the shared {@code web-secret}.
 * Signatures are standard Base64 (with padding) of the 32-byte MAC. Thread-safe.
 */
public final class HmacSigner {

	private static final String ALGORITHM = "HmacSHA256";

	/** Secrets shorter than this are rejected, because a short secret can be brute-forced offline from one captured message. */
	public static final int MIN_SECRET_LENGTH = 16;

	private final SecretKeySpec key;

	public HmacSigner(String secret) {
		if (secret == null || secret.length() < MIN_SECRET_LENGTH)
			throw new IllegalArgumentException("web-secret must be at least " + MIN_SECRET_LENGTH + " characters long");

		this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
	}

	public String sign(String text) {
		try {
			final Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(this.key);

			return Base64.getEncoder().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));

		} catch (final NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
		}
	}

	public Envelope sign(Envelope envelope) {
		return envelope.withSignature(sign(envelope.signingString()));
	}

	/** Constant-time check of the envelope's signature. An unsigned envelope never verifies. */
	public boolean verify(Envelope envelope) {
		if (envelope.sig() == null)
			return false;

		final byte[] expected = sign(envelope.signingString()).getBytes(StandardCharsets.UTF_8);
		final byte[] actual = envelope.sig().getBytes(StandardCharsets.UTF_8);

		return MessageDigest.isEqual(expected, actual);
	}

	/** Whether the envelope's timestamp is within {@code maxSkew} of the clock, in either direction. */
	public static boolean isFresh(Envelope envelope, Clock clock, Duration maxSkew) {
		return Math.abs(clock.millis() - envelope.ts()) <= maxSkew.toMillis();
	}
}
