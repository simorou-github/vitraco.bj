package bj.vitraco.api.adm.auth;

import bj.vitraco.api.adm.utilisateurs.Utilisateur;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/** Émet et consomme les jetons à usage unique des liens d'activation et de réinitialisation. */
@Service
public class ServiceJetons {

	private final SecureRandom aleatoire = new SecureRandom();
	private final JetonCompteRepository jetons;
	private final Clock horloge;

	ServiceJetons(JetonCompteRepository jetons, Clock horloge) {
		this.jetons = jetons;
		this.horloge = horloge;
	}

	/** Renvoie le jeton en clair, à mettre dans le lien ; seule son empreinte est stockée. */
	@Transactional(propagation = Propagation.MANDATORY)
	public String emettre(Utilisateur utilisateur, TypeJeton type, Duration validite) {
		Instant maintenant = Instant.now(horloge);
		jetons.invaliderLesAnciens(utilisateur.getId(), type, maintenant);

		byte[] octets = new byte[32];
		aleatoire.nextBytes(octets);
		String clair = Base64.getUrlEncoder().withoutPadding().encodeToString(octets);

		jetons.save(new JetonCompte(utilisateur, type, empreinte(clair), maintenant.plus(validite)));
		return clair;
	}

	/** Marque le jeton utilisé et renvoie son propriétaire, ou vide si le jeton est inconnu, expiré ou déjà utilisé. */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<Utilisateur> consommer(String clair, TypeJeton type) {
		Instant maintenant = Instant.now(horloge);
		return jetons.findByEmpreinteAndType(empreinte(clair), type)
				.filter(j -> j.estValide(maintenant))
				.map(j -> {
					j.marquerUtilise(maintenant);
					return j.getUtilisateur();
				});
	}

	private static String empreinte(String clair) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(clair.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
