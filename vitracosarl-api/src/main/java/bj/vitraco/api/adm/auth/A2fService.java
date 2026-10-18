package bj.vitraco.api.adm.auth;

import bj.vitraco.api.adm.utilisateurs.Utilisateur;
import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Double authentification par application (TOTP) avec codes de secours (EF-ADM-10). Le secret est
 * chiffré en base ; un code TOTP ne sert qu'une fois ; un code de secours ne sert qu'une fois.
 */
@Service
public class A2fService {

	private static final int NB_CODES_SECOURS = 10;
	private static final String ALPHABET_SECOURS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final SecureRandom ALEA = new SecureRandom();

	/** Ce que l'application d'authentification doit recevoir : le secret à saisir ou à scanner. */
	public record Configuration(String secret, String otpauthUri) {
	}

	private final UtilisateurRepository utilisateurs;
	private final ChiffrementSecrets chiffrement;
	private final SecuriteProprietes regles;
	private final JdbcClient jdbc;
	private final JournalAudit journal;
	private final Clock horloge;

	A2fService(UtilisateurRepository utilisateurs, ChiffrementSecrets chiffrement, SecuriteProprietes regles, JdbcClient jdbc,
			   JournalAudit journal, Clock horloge) {
		this.utilisateurs = utilisateurs;
		this.chiffrement = chiffrement;
		this.regles = regles;
		this.jdbc = jdbc;
		this.journal = journal;
		this.horloge = horloge;
	}

	/** Prépare l'enrôlement : un secret est généré une fois puis réutilisé tant que l'activation n'est pas faite. */
	@Transactional
	public Configuration configurer(Long utilisateurId) {
		Utilisateur u = trouver(utilisateurId);
		if (u.a2fActive()) {
			throw new ErreurMetierException("A2F_DEJA_ACTIVE", "La double authentification est déjà activée.", HttpStatus.UNPROCESSABLE_ENTITY);
		}
		String secret;
		if (u.getA2fSecretChiffre() == null) {
			secret = Totp.genererSecret();
			u.setA2fSecretChiffre(chiffrement.chiffrer(secret));
		} else {
			secret = chiffrement.dechiffrer(u.getA2fSecretChiffre());
		}
		return new Configuration(secret, Totp.uriConfiguration(regles.a2fEmetteur(), u.getEmail(), secret));
	}

	/** Active le second facteur après un premier code valide ; renvoie les codes de secours, affichés une seule fois. */
	@Transactional
	public List<String> activer(Long utilisateurId, String code) {
		Utilisateur u = trouver(utilisateurId);
		if (u.a2fActive()) {
			throw new ErreurMetierException("A2F_DEJA_ACTIVE", "La double authentification est déjà activée.", HttpStatus.UNPROCESSABLE_ENTITY);
		}
		if (u.getA2fSecretChiffre() == null) {
			throw new ErreurMetierException("A2F_NON_CONFIGUREE", "Commencez par scanner le code dans votre application.", HttpStatus.UNPROCESSABLE_ENTITY);
		}
		OptionalLong pas = Totp.verifier(chiffrement.dechiffrer(u.getA2fSecretChiffre()), code, Instant.now(horloge), -1);
		if (pas.isEmpty()) {
			throw codeInvalide();
		}
		u.setA2fActiveLe(Instant.now(horloge));
		u.setA2fDernierPas(pas.getAsLong());
		List<String> codes = regenererCodesSecours(u.getId());
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "auth.a2f_activee", "Utilisateur", String.valueOf(u.getId()))
				.avecActeur(u.getId(), libelle(u)));
		return codes;
	}

	/**
	 * Vérifie un code TOTP (jamais deux fois le même) ou un code de secours (à usage unique).
	 * L'appelant gère les échecs répétés : ce service ne verrouille pas le compte.
	 */
	@Transactional
	public boolean codeValide(Utilisateur u, String saisie) {
		if (!u.a2fActive() || saisie == null) {
			return false;
		}
		String normalise = saisie.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
		if (normalise.matches("\\d{" + Totp.CHIFFRES + "}")) {
			OptionalLong pas = Totp.verifier(chiffrement.dechiffrer(u.getA2fSecretChiffre()), normalise, Instant.now(horloge), u.getA2fDernierPas());
			pas.ifPresent(u::setA2fDernierPas);
			return pas.isPresent();
		}
		boolean utilise = jdbc.sql("""
						UPDATE codes_secours SET utilise_le = now()
						WHERE utilisateur_id = :u AND empreinte = :h AND utilise_le IS NULL""")
				.param("u", u.getId()).param("h", empreinte(normalise)).update() == 1;
		if (utilise) {
			journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "auth.a2f_code_secours_utilise", "Utilisateur", String.valueOf(u.getId()))
					.avecActeur(u.getId(), libelle(u)).avecValeurs(null, Map.of("restants", codesSecoursRestants(u.getId()))));
		}
		return utilise;
	}

	/** Remplace les codes de secours : les anciens, utilisés ou non, cessent de valoir. */
	@Transactional
	public List<String> regenererCodesSecours(Long utilisateurId) {
		jdbc.sql("DELETE FROM codes_secours WHERE utilisateur_id = :u").param("u", utilisateurId).update();
		List<String> codes = new ArrayList<>();
		for (int i = 0; i < NB_CODES_SECOURS; i++) {
			String brut = aleatoire(10);
			jdbc.sql("INSERT INTO codes_secours (utilisateur_id, empreinte) VALUES (:u, :h)")
					.param("u", utilisateurId).param("h", empreinte(brut)).update();
			codes.add(brut.substring(0, 5) + "-" + brut.substring(5));
		}
		return codes;
	}

	@Transactional(readOnly = true)
	public long codesSecoursRestants(Long utilisateurId) {
		return jdbc.sql("SELECT count(*) FROM codes_secours WHERE utilisateur_id = :u AND utilise_le IS NULL")
				.param("u", utilisateurId).query(Long.class).single();
	}

	/** Efface le second facteur : l'utilisateur devra le réenrôler (téléphone perdu, ou rôle qui cesse de l'exiger). */
	@Transactional
	public void effacer(Utilisateur u) {
		u.setA2fSecretChiffre(null);
		u.setA2fActiveLe(null);
		u.setA2fDernierPas(null);
		jdbc.sql("DELETE FROM codes_secours WHERE utilisateur_id = :u").param("u", u.getId()).update();
	}

	private Utilisateur trouver(Long id) {
		return utilisateurs.findById(id).orElseThrow();
	}

	static ErreurMetierException codeInvalide() {
		return new ErreurMetierException("CODE_A2F_INVALIDE", "Code incorrect ou expiré. Vérifiez l'heure de votre téléphone et réessayez.",
				HttpStatus.UNPROCESSABLE_ENTITY);
	}

	private static String libelle(Utilisateur u) {
		return u.getPrenom() == null || u.getPrenom().isBlank() ? u.getNom() : u.getPrenom() + " " + u.getNom();
	}

	private static String aleatoire(int longueur) {
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < longueur; i++) {
			s.append(ALPHABET_SECOURS.charAt(ALEA.nextInt(ALPHABET_SECOURS.length())));
		}
		return s.toString();
	}

	private static String empreinte(String valeur) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(valeur.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
