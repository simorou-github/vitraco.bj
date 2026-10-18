package bj.vitraco.api.adm.auth;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Chiffre les secrets de double authentification (AES-256-GCM). Un vol de la base seule ne donne
 * donc pas de quoi produire les codes : il faut aussi la clé, qui vit hors de la base.
 */
@Component
class ChiffrementSecrets {

	private static final int TAILLE_IV = 12;
	private static final int TAILLE_TAG_BITS = 128;
	private final SecureRandom alea = new SecureRandom();
	private final SecretKeySpec cle;

	ChiffrementSecrets(SecuriteProprietes regles) {
		byte[] octets;
		try {
			octets = Base64.getDecoder().decode(regles.cleA2f());
		} catch (IllegalArgumentException e) {
			octets = new byte[0];
		}
		if (octets.length != 32) {
			throw new IllegalStateException("vitraco.securite.cle-a2f doit être une clé de 32 octets en Base64 "
					+ "(générez-la avec « openssl rand -base64 32 » et gardez-la hors du dépôt).");
		}
		this.cle = new SecretKeySpec(octets, "AES");
	}

	String chiffrer(String clair) {
		try {
			byte[] iv = new byte[TAILLE_IV];
			alea.nextBytes(iv);
			Cipher chiffre = Cipher.getInstance("AES/GCM/NoPadding");
			chiffre.init(Cipher.ENCRYPT_MODE, cle, new GCMParameterSpec(TAILLE_TAG_BITS, iv));
			byte[] secret = chiffre.doFinal(clair.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + secret.length).put(iv).put(secret).array());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Chiffrement impossible", e);
		}
	}

	String dechiffrer(String chiffre) {
		try {
			ByteBuffer tampon = ByteBuffer.wrap(Base64.getDecoder().decode(chiffre));
			byte[] iv = new byte[TAILLE_IV];
			tampon.get(iv);
			byte[] secret = new byte[tampon.remaining()];
			tampon.get(secret);
			Cipher dechiffre = Cipher.getInstance("AES/GCM/NoPadding");
			dechiffre.init(Cipher.DECRYPT_MODE, cle, new GCMParameterSpec(TAILLE_TAG_BITS, iv));
			return new String(dechiffre.doFinal(secret), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Secret illisible : la clé de chiffrement a-t-elle changé ?", e);
		}
	}

}
