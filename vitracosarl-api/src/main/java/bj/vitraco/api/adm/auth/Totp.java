package bj.vitraco.api.adm.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * Mots de passe à usage unique basés sur le temps (RFC 6238, HMAC-SHA1, 6 chiffres, pas de 30 s) :
 * le format que lisent Google Authenticator, Microsoft Authenticator, Authy, FreeOTP…
 */
public final class Totp {

	static final int PAS_SECONDES = 30;
	static final int CHIFFRES = 6;
	/** Tolérance d'horloge : un pas avant et un pas après le pas courant. */
	private static final int DERIVE = 1;
	private static final String ALPHABET_BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
	private static final SecureRandom ALEA = new SecureRandom();

	private Totp() {
	}

	/** Secret de 160 bits (la taille recommandée par la RFC 4226), en Base32 pour le QR code. */
	static String genererSecret() {
		byte[] octets = new byte[20];
		ALEA.nextBytes(octets);
		return versBase32(octets);
	}

	public static long pasCourant(Instant instant) {
		return Math.floorDiv(instant.getEpochSecond(), PAS_SECONDES);
	}

	/** Code attendu pour un pas donné. */
	public static String code(String secretBase32, long pas) {
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(depuisBase32(secretBase32), "HmacSHA1"));
			byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(pas).array());
			int decalage = hash[hash.length - 1] & 0x0F;
			int binaire = ((hash[decalage] & 0x7F) << 24) | ((hash[decalage + 1] & 0xFF) << 16)
					| ((hash[decalage + 2] & 0xFF) << 8) | (hash[decalage + 3] & 0xFF);
			int modulo = (int) Math.pow(10, CHIFFRES);
			return String.format("%0" + CHIFFRES + "d", binaire % modulo);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA1 indisponible", e);
		}
	}

	/**
	 * Pas qui correspond au code saisi, s'il est valable et postérieur au dernier pas déjà accepté :
	 * un code intercepté ne peut donc pas être rejoué dans sa fenêtre de validité.
	 */
	static OptionalLong verifier(String secretBase32, String saisie, Instant maintenant, long dernierPasAccepte) {
		if (saisie == null || !saisie.matches("\\d{" + CHIFFRES + "}")) {
			return OptionalLong.empty();
		}
		long courant = pasCourant(maintenant);
		OptionalLong trouve = OptionalLong.empty();
		// Tous les pas sont comparés, sans sortie anticipée, pour ne pas révéler lequel a correspondu.
		for (long pas = courant - DERIVE; pas <= courant + DERIVE; pas++) {
			boolean egal = MessageDigest.isEqual(code(secretBase32, pas).getBytes(), saisie.getBytes());
			if (egal && pas > dernierPasAccepte) {
				trouve = OptionalLong.of(pas);
			}
		}
		return trouve;
	}

	/** Lien lu par les applications d'authentification (affiché en QR code). */
	static String uriConfiguration(String emetteur, String compte, String secretBase32) {
		String etiquette = encoder(emetteur) + ":" + encoder(compte);
		return "otpauth://totp/" + etiquette + "?secret=" + secretBase32 + "&issuer=" + encoder(emetteur)
				+ "&algorithm=SHA1&digits=" + CHIFFRES + "&period=" + PAS_SECONDES;
	}

	private static String encoder(String valeur) {
		return java.net.URLEncoder.encode(valeur, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
	}

	static String versBase32(byte[] octets) {
		StringBuilder sortie = new StringBuilder();
		int tampon = 0;
		int bits = 0;
		for (byte b : octets) {
			tampon = (tampon << 8) | (b & 0xFF);
			bits += 8;
			while (bits >= 5) {
				sortie.append(ALPHABET_BASE32.charAt((tampon >> (bits - 5)) & 31));
				bits -= 5;
			}
		}
		if (bits > 0) {
			sortie.append(ALPHABET_BASE32.charAt((tampon << (5 - bits)) & 31));
		}
		return sortie.toString();
	}

	static byte[] depuisBase32(String texte) {
		String propre = texte.replace("=", "").replace(" ", "").toUpperCase();
		ByteBuffer sortie = ByteBuffer.allocate(propre.length() * 5 / 8);
		int tampon = 0;
		int bits = 0;
		for (char c : propre.toCharArray()) {
			int valeur = ALPHABET_BASE32.indexOf(c);
			if (valeur < 0) {
				throw new IllegalArgumentException("Caractère Base32 invalide");
			}
			tampon = (tampon << 5) | valeur;
			bits += 5;
			if (bits >= 8) {
				sortie.put((byte) ((tampon >> (bits - 8)) & 0xFF));
				bits -= 8;
			}
		}
		return sortie.array();
	}

}
