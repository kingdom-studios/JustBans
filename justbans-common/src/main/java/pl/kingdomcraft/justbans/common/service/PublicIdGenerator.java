package pl.kingdomcraft.justbans.common.service;

import java.security.SecureRandom;

/** Short, unambiguous ids shown to players ({@code K7Q2M9XA}); no 0/O/1/I. */
final class PublicIdGenerator {

	private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final SecureRandom RANDOM = new SecureRandom();

	private PublicIdGenerator() {
	}

	/** Whether the text has the shape of a public id (8 characters of the id alphabet), in upper case. */
	static boolean looksLikePublicId(String text) {
		if (text.length() != 8)
			return false;

		for (int i = 0; i < text.length(); i++)
			if (ALPHABET.indexOf(text.charAt(i)) < 0)
				return false;

		return true;
	}

	static String next() {
		final char[] id = new char[8];

		for (int i = 0; i < id.length; i++)
			id[i] = ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()));

		return new String(id);
	}
}
