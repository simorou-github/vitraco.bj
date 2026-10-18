package bj.vitraco.api.adm.auth;

import bj.vitraco.api.adm.utilisateurs.UtilisateurDto;
import bj.vitraco.api.adm.utilisateurs.UtilisateurService;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.ReponseApi;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

	record DemandeConnexion(@NotBlank String email, @NotBlank String motDePasse) {
	}

	record DemandeEmail(@NotBlank String email) {
	}

	record DemandeJeton(@NotBlank String token, @NotBlank String motDePasse) {
	}

	record DemandeCode(@NotBlank String code) {
	}

	record DemandeDesactivationA2f(@NotBlank String motDePasse, @NotBlank String code) {
	}

	/**
	 * Mot de passe validé, connexion inachevée : l'identité attend son second facteur dans la
	 * session, sans aucun droit tant qu'il n'est pas fourni. L'attente expire.
	 */
	record AttenteA2f(Long utilisateurId, ResultatConnexion.Issue etape, Instant expire) implements Serializable {
	}

	private static final String ATTRIBUT_A2F = "VITRACO_A2F";

	private final AuthService auth;
	private final UtilisateurService utilisateurs;
	private final SecurityContextRepository contextes;
	private final A2fService a2f;
	private final SecuriteProprietes regles;

	AuthController(AuthService auth, UtilisateurService utilisateurs, SecurityContextRepository contextes, A2fService a2f,
				   SecuriteProprietes regles) {
		this.auth = auth;
		this.utilisateurs = utilisateurs;
		this.contextes = contextes;
		this.a2f = a2f;
		this.regles = regles;
	}

	/** Dépose le cookie CSRF avant la première requête d'écriture (le filtre CSRF s'en charge). */
	@GetMapping("/csrf")
	ResponseEntity<Void> csrf() {
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/login")
	ResponseEntity<ReponseApi<?>> connexion(@Valid @RequestBody DemandeConnexion demande, HttpServletRequest requete, HttpServletResponse reponse) {
		ResultatConnexion resultat = auth.authentifier(demande.email(), demande.motDePasse());

		switch (resultat.issue()) {
			case IDENTIFIANTS_INVALIDES -> throw new ErreurMetierException("IDENTIFIANTS_INVALIDES",
					"Adresse e-mail ou mot de passe incorrect.", HttpStatus.UNAUTHORIZED);
			case COMPTE_VERROUILLE -> throw verrouille();
			case COMPTE_SUSPENDU -> throw suspendu();
			case A2F_VERIFICATION_REQUISE, A2F_CONFIGURATION_REQUISE -> {
				// 202 : la connexion n'est pas terminée, la suite se fait par les routes /2fa.
				mettreEnAttente(resultat, requete);
				String etape = resultat.issue() == ResultatConnexion.Issue.A2F_VERIFICATION_REQUISE ? "VERIFICATION" : "CONFIGURATION";
				return ResponseEntity.status(HttpStatus.ACCEPTED).body(ReponseApi.de(Map.of("etape", etape)));
			}
			case SUCCES, CODE_A2F_INVALIDE -> {
			}
		}

		ouvrirSession(resultat, requete, reponse);
		return ResponseEntity.ok(ReponseApi.de(utilisateurs.detail(resultat.utilisateurId())));
	}

	/** Second temps de la connexion : le code de l'application d'authentification, ou un code de secours. */
	@PostMapping("/2fa/verifier")
	ReponseApi<UtilisateurDto> verifierA2f(@Valid @RequestBody DemandeCode demande, HttpServletRequest requete, HttpServletResponse reponse) {
		AttenteA2f attente = attente(requete, ResultatConnexion.Issue.A2F_VERIFICATION_REQUISE);
		ResultatConnexion resultat = auth.verifierSecondFacteur(attente.utilisateurId(), demande.code());

		switch (resultat.issue()) {
			case CODE_A2F_INVALIDE -> throw new ErreurMetierException("CODE_A2F_INVALIDE",
					"Code incorrect ou expiré. Vérifiez l'heure de votre téléphone ou utilisez un code de secours.", HttpStatus.UNAUTHORIZED);
			case COMPTE_VERROUILLE -> {
				requete.getSession().removeAttribute(ATTRIBUT_A2F);
				throw verrouille();
			}
			case COMPTE_SUSPENDU -> {
				requete.getSession().removeAttribute(ATTRIBUT_A2F);
				throw suspendu();
			}
			default -> {
			}
		}
		requete.getSession().removeAttribute(ATTRIBUT_A2F);
		ouvrirSession(resultat, requete, reponse);
		return ReponseApi.de(utilisateurs.detail(resultat.utilisateurId()));
	}

	/** Secret et lien à scanner. Accessible pendant la connexion d'un utilisateur à configurer, ou connecté. */
	@GetMapping("/2fa/configuration")
	ReponseApi<A2fService.Configuration> configurationA2f(HttpServletRequest requete) {
		return ReponseApi.de(a2f.configurer(utilisateurPourConfiguration(requete)));
	}

	@PostMapping("/2fa/activer")
	ReponseApi<Map<String, Object>> activerA2f(@Valid @RequestBody DemandeCode demande, HttpServletRequest requete, HttpServletResponse reponse) {
		AttenteA2f attente = attenteEventuelle(requete, ResultatConnexion.Issue.A2F_CONFIGURATION_REQUISE);
		if (attente != null) {
			AuthService.Activation activation = auth.activerSecondFacteurEtConnecter(attente.utilisateurId(), demande.code());
			requete.getSession().removeAttribute(ATTRIBUT_A2F);
			ouvrirSession(activation.connexion(), requete, reponse);
			return ReponseApi.de(Map.of("codes_secours", activation.codesSecours(),
					"utilisateur", utilisateurs.detail(attente.utilisateurId())));
		}
		Long id = connecte();
		List<String> codes = a2f.activer(id, demande.code());
		return ReponseApi.de(Map.of("codes_secours", codes, "utilisateur", utilisateurs.detail(id)));
	}

	@PostMapping("/2fa/desactiver")
	ResponseEntity<Void> desactiverA2f(@Valid @RequestBody DemandeDesactivationA2f demande) {
		auth.desactiverSecondFacteur(connecte(), demande.motDePasse(), demande.code());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/2fa/codes-secours")
	ReponseApi<Map<String, List<String>>> regenererCodesSecours(@Valid @RequestBody DemandeCode demande) {
		return ReponseApi.de(Map.of("codes_secours", auth.regenererCodesSecours(connecte(), demande.code())));
	}

	@GetMapping("/2fa/etat")
	ReponseApi<Map<String, Object>> etatA2f() {
		Long id = connecte();
		UtilisateurDto u = utilisateurs.detail(id);
		return ReponseApi.de(Map.of("active", u.a2fActive(), "requise", u.a2fRequise(),
				"codes_secours_restants", u.a2fActive() ? a2f.codesSecoursRestants(id) : 0L));
	}

	private void ouvrirSession(ResultatConnexion resultat, HttpServletRequest requete, HttpServletResponse reponse) {
		if (requete.getSession(false) != null) {
			requete.changeSessionId(); // contre la fixation de session
		}
		SecurityContext contexte = SecurityContextHolder.createEmptyContext();
		contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
				resultat.identite(), null, resultat.identite().getAuthorities()));
		SecurityContextHolder.setContext(contexte);
		contextes.saveContext(contexte, requete, reponse);
	}

	private void mettreEnAttente(ResultatConnexion resultat, HttpServletRequest requete) {
		if (requete.getSession(false) != null) {
			requete.changeSessionId();
		}
		HttpSession session = requete.getSession(true);
		session.setAttribute(ATTRIBUT_A2F, new AttenteA2f(resultat.utilisateurId(), resultat.issue(),
				Instant.now().plus(Duration.ofMinutes(regles.a2fAttenteMinutes()))));
	}

	private AttenteA2f attenteEventuelle(HttpServletRequest requete, ResultatConnexion.Issue etape) {
		HttpSession session = requete.getSession(false);
		if (session != null && session.getAttribute(ATTRIBUT_A2F) instanceof AttenteA2f a
				&& a.etape() == etape && a.expire().isAfter(Instant.now())) {
			return a;
		}
		return null;
	}

	private AttenteA2f attente(HttpServletRequest requete, ResultatConnexion.Issue etape) {
		AttenteA2f a = attenteEventuelle(requete, etape);
		if (a == null) {
			throw new ErreurMetierException("A2F_NON_DEMANDEE", "Votre connexion a expiré. Reconnectez-vous.", HttpStatus.UNAUTHORIZED);
		}
		return a;
	}

	private Long utilisateurPourConfiguration(HttpServletRequest requete) {
		AttenteA2f a = attenteEventuelle(requete, ResultatConnexion.Issue.A2F_CONFIGURATION_REQUISE);
		return a != null ? a.utilisateurId() : connecte();
	}

	private static Long connecte() {
		return ActeurAuthentifie.courant().map(ActeurAuthentifie::id)
				.orElseThrow(() -> new ErreurMetierException("NON_AUTHENTIFIE", "Connexion requise.", HttpStatus.UNAUTHORIZED));
	}

	private static ErreurMetierException verrouille() {
		return new ErreurMetierException("COMPTE_VERROUILLE", "Compte verrouillé après plusieurs échecs. Réessayez plus tard.", HttpStatus.LOCKED);
	}

	private static ErreurMetierException suspendu() {
		return new ErreurMetierException("COMPTE_SUSPENDU", "Ce compte est suspendu. Contactez l'administrateur.", HttpStatus.FORBIDDEN);
	}

	@GetMapping("/me")
	ReponseApi<UtilisateurDto> moi() {
		Long id = ActeurAuthentifie.courant().map(ActeurAuthentifie::id)
				.orElseThrow(() -> new ErreurMetierException("NON_AUTHENTIFIE", "Connexion requise.", HttpStatus.UNAUTHORIZED));
		return ReponseApi.de(utilisateurs.detail(id));
	}

	@PostMapping("/logout")
	ResponseEntity<Void> deconnexion(HttpServletRequest requete, HttpServletResponse reponse) {
		new SecurityContextLogoutHandler().logout(requete, reponse, SecurityContextHolder.getContext().getAuthentication());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/activation")
	ResponseEntity<Void> activation(@Valid @RequestBody DemandeJeton demande) {
		auth.activer(demande.token(), demande.motDePasse());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/mot-de-passe/oublie")
	ResponseEntity<Void> motDePasseOublie(@Valid @RequestBody DemandeEmail demande) {
		auth.demanderReinitialisation(demande.email());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/mot-de-passe/reinitialiser")
	ResponseEntity<Void> reinitialiser(@Valid @RequestBody DemandeJeton demande) {
		auth.reinitialiser(demande.token(), demande.motDePasse());
		return ResponseEntity.noContent().build();
	}

}
