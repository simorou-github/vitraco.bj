package bj.vitraco.api.adm.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

	/** « 12345678901234567890 » en Base32 : le secret des vecteurs de test de la RFC 6238. */
	private static final String SECRET_RFC = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

	@Test
	void lesVecteursDeLaRfc6238SontReproduits() {
		// La RFC donne 8 chiffres ; nous en affichons 6, soit les 6 derniers.
		assertThat(Totp.code(SECRET_RFC, 59 / 30)).isEqualTo("287082");
		assertThat(Totp.code(SECRET_RFC, 1111111109L / 30)).isEqualTo("081804");
		assertThat(Totp.code(SECRET_RFC, 1111111111L / 30)).isEqualTo("050471");
		assertThat(Totp.code(SECRET_RFC, 1234567890L / 30)).isEqualTo("005924");
		assertThat(Totp.code(SECRET_RFC, 2000000000L / 30)).isEqualTo("279037");
		assertThat(Totp.code(SECRET_RFC, 20000000000L / 30)).isEqualTo("353130");
	}

	@Test
	void leBase32SAllerRetourneSansPerte() {
		String secret = Totp.genererSecret();
		assertThat(secret).hasSize(32).matches("[A-Z2-7]+");
		assertThat(Totp.versBase32(Totp.depuisBase32(secret))).isEqualTo(secret);
		assertThat(Totp.depuisBase32(SECRET_RFC)).isEqualTo("12345678901234567890".getBytes());
	}

	@Test
	void uneDeriveDHorlogeDUnPasEstTolereeMaisPasDeuxPas() {
		Instant maintenant = Instant.ofEpochSecond(1_700_000_000L);
		long pas = Totp.pasCourant(maintenant);
		assertThat(Totp.verifier(SECRET_RFC, Totp.code(SECRET_RFC, pas), maintenant, -1)).hasValue(pas);
		assertThat(Totp.verifier(SECRET_RFC, Totp.code(SECRET_RFC, pas - 1), maintenant, -1)).hasValue(pas - 1);
		assertThat(Totp.verifier(SECRET_RFC, Totp.code(SECRET_RFC, pas + 1), maintenant, -1)).hasValue(pas + 1);
		assertThat(Totp.verifier(SECRET_RFC, Totp.code(SECRET_RFC, pas - 2), maintenant, -1)).isEmpty();
		assertThat(Totp.verifier(SECRET_RFC, Totp.code(SECRET_RFC, pas + 2), maintenant, -1)).isEmpty();
	}

	@Test
	void unCodeDejaAccepteNePeutPasEtreRejoue() {
		Instant maintenant = Instant.ofEpochSecond(1_700_000_000L);
		long pas = Totp.pasCourant(maintenant);
		String code = Totp.code(SECRET_RFC, pas);
		assertThat(Totp.verifier(SECRET_RFC, code, maintenant, pas - 1)).hasValue(pas);
		assertThat(Totp.verifier(SECRET_RFC, code, maintenant, pas)).isEmpty();
	}

	@Test
	void uneSaisieMalFormeeEstRefuseeSansErreur() {
		Instant maintenant = Instant.now();
		for (String saisie : new String[]{null, "", "12345", "1234567", "abcdef", "12 345"}) {
			assertThat(Totp.verifier(SECRET_RFC, saisie, maintenant, -1)).isEqualTo(OptionalLong.empty());
		}
	}

	@Test
	void lUriDeConfigurationEstLisibleParLesApplications() {
		String uri = Totp.uriConfiguration("VITRACO Moto", "marie@vitraco.bj", SECRET_RFC);
		assertThat(uri).startsWith("otpauth://totp/VITRACO%20Moto:marie%40vitraco.bj?secret=" + SECRET_RFC)
				.contains("issuer=VITRACO%20Moto").contains("digits=6").contains("period=30");
	}

}
