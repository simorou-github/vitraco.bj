package bj.vitraco.api.adm.auth;

import bj.vitraco.api.adm.droits.Permission;
import bj.vitraco.api.adm.droits.Role;
import bj.vitraco.api.adm.utilisateurs.StatutUtilisateur;
import bj.vitraco.api.adm.utilisateurs.Utilisateur;
import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.notif.DemandeNotification;
import bj.vitraco.api.notif.Notifications;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Set;

/** Connexion, verrouillage après échecs répétés, activation de compte et réinitialisation du mot de passe. */
@Service
public class AuthService {

	private final UtilisateurRepository utilisateurs;
	private final PasswordEncoder encodeur;
	private final ServiceJetons jetons;
	private final EnvoiLienCompte envoi;
	private final SessionsUtilisateurs sessions;
	private final JournalAudit journal;
	private final SecuriteProprietes regles;
	private final Clock horloge;
	private final A2fService a2f;
	private final Notifications notifications;
	/** Empreinte factice : un e-mail inconnu coûte le même temps de calcul qu'un mot de passe faux. */
	private final String empreinteFactice;

	AuthService(UtilisateurRepository utilisateurs, PasswordEncoder encodeur, ServiceJetons jetons, EnvoiLienCompte envoi,
				SessionsUtilisateurs sessions, JournalAudit journal, SecuriteProprietes regles, Clock horloge, A2fService a2f,
				Notifications notifications) {
		this.utilisateurs = utilisateurs;
		this.encodeur = encodeur;
		this.jetons = jetons;
		this.envoi = envoi;
		this.sessions = sessions;
		this.journal = journal;
		this.regles = regles;
		this.horloge = horloge;
		this.a2f = a2f;
		this.notifications = notifications;
		this.empreinteFactice = encodeur.encode("mot-de-passe-factice");
	}

	/**
	 * Ne lève pas d'exception pour un échec : le compteur d'échecs et la trace doivent être
	 * validés même quand la connexion est refusée.
	 */
	@Transactional
	public ResultatConnexion authentifier(String email, String motDePasse) {
		Instant maintenant = Instant.now(horloge);
		Utilisateur u = utilisateurs.findByEmail(normaliser(email)).orElse(null);

		if (u == null || u.getMotDePasseHash() == null) {
			encodeur.matches(motDePasse, empreinteFactice);
			journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "auth.echec", "Utilisateur", u == null ? null : String.valueOf(u.getId()))
					.avecValeurs(null, Map.of("email", normaliser(email), "cause", u == null ? "inconnu" : "non_active")));
			return ResultatConnexion.echec(ResultatConnexion.Issue.IDENTIFIANTS_INVALIDES);
		}

		if (u.estVerrouille(maintenant)) {
			return ResultatConnexion.echec(ResultatConnexion.Issue.COMPTE_VERROUILLE);
		}

		if (!encodeur.matches(motDePasse, u.getMotDePasseHash())) {
			enregistrerEchec(u, maintenant, "mot_de_passe");
			return ResultatConnexion.echec(ResultatConnexion.Issue.IDENTIFIANTS_INVALIDES);
		}

		if (u.getStatut() == StatutUtilisateur.SUSPENDU) {
			return ResultatConnexion.echec(ResultatConnexion.Issue.COMPTE_SUSPENDU);
		}

		// Mot de passe juste mais connexion inachevée : on ne remet pas à zéro le compteur d'échecs,
		// sans quoi le second facteur pourrait être deviné sans limite en se reconnectant sans cesse.
		if (u.a2fActive()) {
			return ResultatConnexion.enAttente(ResultatConnexion.Issue.A2F_VERIFICATION_REQUISE, u.getId());
		}
		if (u.a2fRequise()) {
			return ResultatConnexion.enAttente(ResultatConnexion.Issue.A2F_CONFIGURATION_REQUISE, u.getId());
		}
		return terminerConnexion(u, maintenant);
	}

	/** Second temps de la connexion : le code TOTP, ou un code de secours. */
	@Transactional
	public ResultatConnexion verifierSecondFacteur(Long utilisateurId, String code) {
		Instant maintenant = Instant.now(horloge);
		Utilisateur u = utilisateurs.findById(utilisateurId).orElseThrow();
		if (u.estVerrouille(maintenant)) {
			return ResultatConnexion.echec(ResultatConnexion.Issue.COMPTE_VERROUILLE);
		}
		if (u.getStatut() == StatutUtilisateur.SUSPENDU) {
			return ResultatConnexion.echec(ResultatConnexion.Issue.COMPTE_SUSPENDU);
		}
		if (!a2f.codeValide(u, code)) {
			enregistrerEchec(u, maintenant, "a2f");
			return ResultatConnexion.echec(u.estVerrouille(maintenant)
					? ResultatConnexion.Issue.COMPTE_VERROUILLE : ResultatConnexion.Issue.CODE_A2F_INVALIDE);
		}
		return terminerConnexion(u, maintenant);
	}

	/**
	 * Active le second facteur pendant la connexion d'un utilisateur dont le rôle l'exige, puis
	 * termine cette connexion. Renvoie aussi les codes de secours, à afficher une seule fois.
	 */
	@Transactional
	public Activation activerSecondFacteurEtConnecter(Long utilisateurId, String code) {
		List<String> codes = a2f.activer(utilisateurId, code);
		Utilisateur u = utilisateurs.findById(utilisateurId).orElseThrow();
		return new Activation(codes, terminerConnexion(u, Instant.now(horloge)));
	}

	public record Activation(List<String> codesSecours, ResultatConnexion connexion) {
	}

	/** Un utilisateur connecté retire son second facteur, sauf si son rôle l'exige. */
	@Transactional
	public void desactiverSecondFacteur(Long utilisateurId, String motDePasse, String code) {
		Utilisateur u = utilisateurs.findById(utilisateurId).orElseThrow();
		if (!u.a2fActive()) {
			throw new ErreurMetierException("A2F_NON_ACTIVE", "La double authentification n'est pas activée.", HttpStatus.UNPROCESSABLE_ENTITY);
		}
		if (u.a2fRequise()) {
			throw new ErreurMetierException("A2F_OBLIGATOIRE", "Votre rôle exige la double authentification : vous ne pouvez pas la retirer.",
					HttpStatus.UNPROCESSABLE_ENTITY);
		}
		if (!encodeur.matches(motDePasse, u.getMotDePasseHash())) {
			throw new ErreurMetierException("MOT_DE_PASSE_INCORRECT", "Mot de passe incorrect.", HttpStatus.UNPROCESSABLE_ENTITY);
		}
		if (!a2f.codeValide(u, code)) {
			throw A2fService.codeInvalide();
		}
		a2f.effacer(u);
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "auth.a2f_desactivee", "Utilisateur", String.valueOf(u.getId())));
	}

	/** Nouveaux codes de secours, sur présentation d'un code valide ; les anciens cessent de valoir. */
	@Transactional
	public List<String> regenererCodesSecours(Long utilisateurId, String code) {
		Utilisateur u = utilisateurs.findById(utilisateurId).orElseThrow();
		if (!u.a2fActive()) {
			throw new ErreurMetierException("A2F_NON_ACTIVE", "La double authentification n'est pas activée.", HttpStatus.UNPROCESSABLE_ENTITY);
		}
		if (!a2f.codeValide(u, code)) {
			throw A2fService.codeInvalide();
		}
		List<String> codes = a2f.regenererCodesSecours(u.getId());
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "auth.a2f_codes_regeneres", "Utilisateur", String.valueOf(u.getId())));
		return codes;
	}

	private ResultatConnexion terminerConnexion(Utilisateur u, Instant maintenant) {
		u.setTentativesEchec(0);
		u.setVerrouilleJusqua(null);
		u.setDerniereConnexion(maintenant);
		UtilisateurConnecte identite = identiteDe(u);
		// La session n'existe pas encore : l'acteur de la connexion est la personne qui se connecte.
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "auth.connexion", "Utilisateur", String.valueOf(u.getId()))
				.avecActeur(u.getId(), identite.libelle()));
		return new ResultatConnexion(ResultatConnexion.Issue.SUCCES, identite, u.getId());
	}

	private void enregistrerEchec(Utilisateur u, Instant maintenant, String cause) {
		int echecs = u.getTentativesEchec() + 1;
		u.setTentativesEchec(echecs);
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "auth.echec", "Utilisateur", String.valueOf(u.getId()))
				.avecValeurs(null, Map.of("email", u.getEmail(), "cause", cause, "echecs", echecs)));

		if (echecs >= regles.tentativesMax()) {
			u.setVerrouilleJusqua(maintenant.plus(Duration.ofMinutes(regles.verrouillageMinutes())));
			u.setTentativesEchec(0);
			journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "auth.verrouillage", "Utilisateur", String.valueOf(u.getId()))
					.avecValeurs(null, Map.of("jusqua", u.getVerrouilleJusqua().toString())));
			notifications.notifierRole("admin", DemandeNotification.de("compte.verrouille", "Compte verrouillé",
					"Le compte de " + nomComplet(u) + " (" + u.getEmail() + ") est verrouillé pendant " + regles.verrouillageMinutes()
							+ " minutes après " + regles.tentativesMax() + " échecs de connexion.", "/utilisateurs/" + u.getId()));
		}
	}

	/** L'identité est reconstruite à partir des rôles actuels, avec les permissions de ces rôles. */
	@Transactional(readOnly = true)
	public UtilisateurConnecte identiteDe(Long utilisateurId) {
		return identiteDe(utilisateurs.findById(utilisateurId).orElseThrow());
	}

	private UtilisateurConnecte identiteDe(Utilisateur u) {
		Set<SimpleGrantedAuthority> autorites = new HashSet<>();
		for (Role role : u.getRoles()) {
			autorites.add(new SimpleGrantedAuthority("ROLE_" + role.getCode()));
			for (Permission p : role.getPermissions()) {
				autorites.add(new SimpleGrantedAuthority(p.getCode()));
			}
		}
		String libelle = u.getPrenom() == null || u.getPrenom().isBlank() ? u.getNom() : u.getPrenom() + " " + u.getNom();
		return new UtilisateurConnecte(u.getId(), u.getEmail(), libelle, autorites);
	}

	@Transactional
	public void activer(String jeton, String motDePasse) {
		PolitiqueMotDePasse.exiger(motDePasse, regles.motDePasseLongueurMin(), "mot_de_passe");
		Utilisateur u = jetons.consommer(jeton, TypeJeton.ACTIVATION).orElseThrow(AuthService::lienInvalide);

		u.setMotDePasseHash(encodeur.encode(motDePasse));
		u.setStatut(StatutUtilisateur.ACTIF);
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "utilisateur.active", "Utilisateur", String.valueOf(u.getId())));
	}

	/** Répond toujours de la même façon : on ne révèle pas si l'adresse correspond à un compte. */
	@Transactional
	public void demanderReinitialisation(String email) {
		utilisateurs.findByEmail(normaliser(email))
				.filter(u -> u.getStatut() == StatutUtilisateur.ACTIF)
				.ifPresent(u -> {
					String jeton = jetons.emettre(u, TypeJeton.REINITIALISATION, Duration.ofMinutes(regles.reinitialisationValiditeMinutes()));
					envoi.envoyerReinitialisation(destinataire(u), regles.lienBaseUrl() + "/password/change?token=" + jeton,
							Duration.ofMinutes(regles.reinitialisationValiditeMinutes()));
					journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "auth.reinitialisation_demandee", "Utilisateur", String.valueOf(u.getId())));
				});
	}

	@Transactional
	public void reinitialiser(String jeton, String motDePasse) {
		PolitiqueMotDePasse.exiger(motDePasse, regles.motDePasseLongueurMin(), "mot_de_passe");
		Utilisateur u = jetons.consommer(jeton, TypeJeton.REINITIALISATION).orElseThrow(AuthService::lienInvalide);

		u.setMotDePasseHash(encodeur.encode(motDePasse));
		u.setTentativesEchec(0);
		u.setVerrouilleJusqua(null);
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "auth.mot_de_passe_reinitialise", "Utilisateur", String.valueOf(u.getId())));
		sessions.fermerApresValidation(u.getEmail());
	}

	/** Lien d'activation à (re)générer pour un compte créé ou relancé par l'administrateur. */
	@Transactional
	public void envoyerLienActivation(Utilisateur u) {
		String jeton = jetons.emettre(u, TypeJeton.ACTIVATION, Duration.ofHours(regles.activationValiditeHeures()));
		envoi.envoyerActivation(destinataire(u), regles.lienBaseUrl() + "/activation?token=" + jeton,
				Duration.ofHours(regles.activationValiditeHeures()));
	}

	private static DestinataireCompte destinataire(Utilisateur u) {
		return new DestinataireCompte(u.getEmail(), nomComplet(u));
	}

	private static String nomComplet(Utilisateur u) {
		return u.getPrenom() == null || u.getPrenom().isBlank() ? u.getNom() : u.getPrenom() + " " + u.getNom();
	}

	public static String normaliser(String email) {
		return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
	}

	private static ErreurMetierException lienInvalide() {
		return new ErreurMetierException("LIEN_INVALIDE", "Ce lien est invalide ou a expiré.", HttpStatus.UNPROCESSABLE_ENTITY);
	}

}
