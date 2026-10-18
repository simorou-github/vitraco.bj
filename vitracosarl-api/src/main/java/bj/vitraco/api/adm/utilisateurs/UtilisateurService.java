package bj.vitraco.api.adm.utilisateurs;

import bj.vitraco.api.adm.auth.A2fService;
import bj.vitraco.api.adm.auth.AuthService;
import bj.vitraco.api.adm.auth.SessionsUtilisateurs;
import bj.vitraco.api.adm.droits.Role;
import bj.vitraco.api.adm.droits.RoleRepository;
import bj.vitraco.api.adm.emplacements.Emplacement;
import bj.vitraco.api.adm.emplacements.EmplacementRepository;
import bj.vitraco.api.adm.emplacements.TypeEmplacement;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.notif.DemandeNotification;
import bj.vitraco.api.notif.Notifications;
import bj.vitraco.api.common.ErreurValidationException;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Comptes utilisateurs (EF-ADM-02). Un compte ne se supprime jamais (RG-18) : il se
 * suspend, ce qui coupe l'accès tout en gardant l'historique.
 */
@Service
public class UtilisateurService {

	public record Filtre(String recherche, String role, StatutUtilisateur statut) {
	}

	private final UtilisateurRepository utilisateurs;
	private final RoleRepository roles;
	private final EmplacementRepository emplacements;
	private final AuthService auth;
	private final SessionsUtilisateurs sessions;
	private final JournalAudit journal;
	private final A2fService a2f;
	private final Notifications notifications;

	UtilisateurService(UtilisateurRepository utilisateurs, RoleRepository roles, EmplacementRepository emplacements, AuthService auth,
					   SessionsUtilisateurs sessions, JournalAudit journal, A2fService a2f, Notifications notifications) {
		this.utilisateurs = utilisateurs;
		this.roles = roles;
		this.emplacements = emplacements;
		this.auth = auth;
		this.sessions = sessions;
		this.journal = journal;
		this.a2f = a2f;
		this.notifications = notifications;
	}

	@Transactional(readOnly = true)
	public Page<UtilisateurDto> lister(Filtre filtre, Pageable pagination) {
		Specification<Utilisateur> spec = (racine, requete, cb) -> {
			List<Predicate> criteres = new ArrayList<>();
			if (filtre.recherche() != null && !filtre.recherche().isBlank()) {
				String motif = "%" + filtre.recherche().trim().toLowerCase() + "%";
				criteres.add(cb.or(
						cb.like(cb.lower(racine.get("nom")), motif),
						cb.like(cb.lower(cb.coalesce(racine.get("prenom"), "")), motif),
						cb.like(cb.lower(racine.get("email")), motif)));
			}
			if (filtre.statut() != null) {
				criteres.add(cb.equal(racine.get("statut"), filtre.statut()));
			}
			if (filtre.role() != null && !filtre.role().isBlank()) {
				criteres.add(cb.equal(racine.join("roles").get("code"), filtre.role()));
				requete.distinct(true);
			}
			return cb.and(criteres.toArray(new Predicate[0]));
		};
		return utilisateurs.findAll(spec, pagination).map(UtilisateurDto::de);
	}

	@Transactional(readOnly = true)
	public UtilisateurDto detail(Long id) {
		return UtilisateurDto.de(trouver(id));
	}

	@Transactional
	public UtilisateurDto creer(UtilisateurRequete requete) {
		String email = AuthService.normaliser(requete.email());
		verifierEmailLibre(email, null);

		Utilisateur u = new Utilisateur(requete.nom().trim(), vide(requete.prenom()), email);
		u.setTelephone(vide(requete.telephone()));
		u.setAdresse(vide(requete.adresse()));
		u.getRoles().addAll(rolesDe(requete.roles()));
		u.getEmplacements().addAll(emplacementsDe(requete.emplacements()));
		u.setCreePar(acteur());
		utilisateurs.saveAndFlush(u);

		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "utilisateur.cree", "Utilisateur", String.valueOf(u.getId()))
				.avecValeurs(null, resume(u)));
		auth.envoyerLienActivation(u);
		// Créer un compte, c'est accorder des droits : la Direction en est informée (EF-ADM-15).
		notifications.notifierRole("direction", DemandeNotification.de("droits.modifies", "Nouvel utilisateur : " + nomComplet(u),
				auteur() + " a créé le compte de " + nomComplet(u) + " (" + u.getEmail() + ").\nRôles : "
						+ listeOuAucun(codesRoles(u)) + ".\nEmplacements : " + listeOuAucun(codesEmplacements(u)) + ".",
				"/utilisateurs/" + u.getId()));
		return UtilisateurDto.de(u);
	}

	@Transactional
	public UtilisateurDto modifier(Long id, UtilisateurRequete requete) {
		Utilisateur u = trouver(id);
		Map<String, Object> avant = resume(u);
		String ancienEmail = u.getEmail();
		String email = AuthService.normaliser(requete.email());
		verifierEmailLibre(email, id);

		u.setNom(requete.nom().trim());
		u.setPrenom(vide(requete.prenom()));
		u.setEmail(email);
		u.setTelephone(vide(requete.telephone()));
		u.setAdresse(vide(requete.adresse()));
		Set<Role> nouveauxRoles = rolesDe(requete.roles());
		boolean rolesChanges = !nouveauxRoles.equals(u.getRoles());
		List<String> anciensRoles = codesRoles(u);
		List<String> anciensEmplacements = codesEmplacements(u);
		u.getRoles().clear();
		u.getRoles().addAll(nouveauxRoles);
		Set<Emplacement> nouveauxEmplacements = emplacementsDe(requete.emplacements());
		u.getEmplacements().clear();
		u.getEmplacements().addAll(nouveauxEmplacements);
		u.setModifiePar(acteur());
		utilisateurs.saveAndFlush(u);
		// Un changement de rôles ou d'emplacements modifie ce que la personne peut faire et voir.
		boolean droitsChanges = rolesChanges || !anciensEmplacements.equals(codesEmplacements(u));

		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, droitsChanges ? "utilisateur.droits_modifies" : "utilisateur.modifie",
				"Utilisateur", String.valueOf(id)).avecValeurs(avant, resume(u)));
		if (droitsChanges) {
			notifications.notifierRole("direction", DemandeNotification.de("droits.modifies", "Droits modifiés : " + nomComplet(u),
					auteur() + " a modifié les droits de " + nomComplet(u) + ".\nRôles : " + listeOuAucun(anciensRoles) + " → "
							+ listeOuAucun(codesRoles(u)) + ".\nEmplacements : " + listeOuAucun(anciensEmplacements) + " → "
							+ listeOuAucun(codesEmplacements(u)) + ".", "/utilisateurs/" + u.getId()));
		}
		if (rolesChanges || !ancienEmail.equals(email)) {
			sessions.fermerApresValidation(ancienEmail);
		}
		return UtilisateurDto.de(u);
	}

	@Transactional
	public UtilisateurDto suspendre(Long id) {
		if (ActeurAuthentifie.courant().map(a -> a.id().equals(id)).orElse(false)) {
			throw new ErreurMetierException("SUSPENSION_PROPRE_COMPTE", "Vous ne pouvez pas suspendre votre propre compte.");
		}
		Utilisateur u = trouver(id);
		if (u.getStatut() == StatutUtilisateur.SUSPENDU) {
			throw new ErreurMetierException("DEJA_SUSPENDU", "Ce compte est déjà suspendu.");
		}
		StatutUtilisateur avant = u.getStatut();
		u.setStatut(StatutUtilisateur.SUSPENDU);
		u.setModifiePar(acteur());
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "utilisateur.suspendu", "Utilisateur", String.valueOf(id))
				.avecValeurs(Map.of("statut", avant), Map.of("statut", u.getStatut())));
		sessions.fermerApresValidation(u.getEmail());
		return UtilisateurDto.de(u);
	}

	/** Lève une suspension ou un verrouillage. Un compte jamais activé retourne en attente d'activation. */
	@Transactional
	public UtilisateurDto reactiver(Long id) {
		Utilisateur u = trouver(id);
		StatutUtilisateur avant = u.getStatut();
		u.setStatut(u.getMotDePasseHash() == null ? StatutUtilisateur.EN_ATTENTE_ACTIVATION : StatutUtilisateur.ACTIF);
		u.setTentativesEchec(0);
		u.setVerrouilleJusqua(null);
		u.setModifiePar(acteur());
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "utilisateur.reactive", "Utilisateur", String.valueOf(id))
				.avecValeurs(Map.of("statut", avant), Map.of("statut", u.getStatut())));
		return UtilisateurDto.de(u);
	}

	@Transactional
	public void renvoyerActivation(Long id) {
		Utilisateur u = trouver(id);
		if (u.getStatut() != StatutUtilisateur.EN_ATTENTE_ACTIVATION) {
			throw new ErreurMetierException("DEJA_ACTIVE", "Ce compte est déjà activé.");
		}
		auth.envoyerLienActivation(u);
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "utilisateur.activation_renvoyee", "Utilisateur", String.valueOf(id)));
	}

	/**
	 * Téléphone perdu ou changé : l'administrateur efface le second facteur, l'utilisateur en
	 * configure un nouveau à sa prochaine connexion. On ne réinitialise pas le sien (sinon un
	 * administrateur pourrait retirer sa propre protection sans que personne ne le voie).
	 */
	@Transactional
	public void reinitialiserA2f(Long id) {
		Utilisateur u = trouver(id);
		if (acteur() != null && acteur().getId().equals(id)) {
			throw new ErreurMetierException("A2F_AUTO_REINITIALISATION",
					"Vous ne pouvez pas réinitialiser votre propre double authentification : demandez-le à un autre administrateur.");
		}
		if (!u.a2fActive() && u.getA2fSecretChiffre() == null) {
			throw new ErreurMetierException("A2F_NON_ACTIVE", "Cet utilisateur n'a pas de double authentification à réinitialiser.");
		}
		a2f.effacer(u);
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "utilisateur.a2f_reinitialisee", "Utilisateur", String.valueOf(id)));
		sessions.fermerApresValidation(u.getEmail());
		notifications.notifierUtilisateur(id, DemandeNotification.de("a2f.reinitialisee", "Votre double authentification a été réinitialisée",
				auteur() + " a réinitialisé votre double authentification. Configurez-la de nouveau à votre prochaine connexion. "
						+ "Si vous n'êtes pas à l'origine de cette demande, prévenez l'administrateur.", "/mon-compte"));
		notifications.notifierRole("direction", DemandeNotification.de("droits.modifies", "Double authentification réinitialisée : " + nomComplet(u),
				auteur() + " a réinitialisé la double authentification de " + nomComplet(u) + ".", "/utilisateurs/" + id));
	}

	private Utilisateur trouver(Long id) {
		return utilisateurs.findById(id)
				.orElseThrow(() -> new ErreurMetierException("UTILISATEUR_INTROUVABLE", "Utilisateur introuvable.", HttpStatus.NOT_FOUND));
	}

	private void verifierEmailLibre(String email, Long exclu) {
		if (utilisateurs.emailUtilise(email, exclu)) {
			throw new ErreurValidationException("email", "Cette adresse e-mail est déjà utilisée.");
		}
	}

	private Set<Role> rolesDe(List<String> codes) {
		List<Role> trouves = roles.findByCodeIn(codes);
		Set<String> connus = trouves.stream().map(Role::getCode).collect(Collectors.toSet());
		codes.stream().filter(c -> !connus.contains(c)).findFirst().ifPresent(c -> {
			throw new ErreurValidationException("roles", "Rôle inconnu : " + c + ".");
		});
		return Set.copyOf(trouves);
	}

	/** Seuls un magasin ou une boutique actifs s'attribuent à un utilisateur. */
	private Set<Emplacement> emplacementsDe(List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return Set.of();
		}
		List<Emplacement> trouves = emplacements.findByIdIn(ids);
		if (trouves.size() != Set.copyOf(ids).size()) {
			throw new ErreurValidationException("emplacements", "Emplacement inconnu.");
		}
		trouves.stream()
				.filter(e -> !e.isActif() || (e.getType() != TypeEmplacement.MAGASIN && e.getType() != TypeEmplacement.BOUTIQUE))
				.findFirst().ifPresent(e -> {
					throw new ErreurValidationException("emplacements",
							"L'emplacement " + e.getCode() + " ne peut pas être attribué (désactivé ou ni magasin ni boutique).");
				});
		return Set.copyOf(trouves);
	}

	private Utilisateur acteur() {
		return ActeurAuthentifie.courant().flatMap(a -> utilisateurs.findById(a.id())).orElse(null);
	}

	private String auteur() {
		Utilisateur a = acteur();
		return a == null ? "Le système" : nomComplet(a);
	}

	private static String nomComplet(Utilisateur u) {
		return u.getPrenom() == null || u.getPrenom().isBlank() ? u.getNom() : u.getPrenom() + " " + u.getNom();
	}

	private static List<String> codesRoles(Utilisateur u) {
		return u.getRoles().stream().map(Role::getCode).sorted().toList();
	}

	private static List<String> codesEmplacements(Utilisateur u) {
		return u.getEmplacements().stream().map(Emplacement::getCode).sorted().toList();
	}

	private static String listeOuAucun(List<String> valeurs) {
		return valeurs.isEmpty() ? "aucun" : String.join(", ", valeurs);
	}

	private static String vide(String valeur) {
		return valeur == null || valeur.isBlank() ? null : valeur.trim();
	}

	/** Valeurs tracées au journal. Jamais de mot de passe ni d'empreinte. */
	private static Map<String, Object> resume(Utilisateur u) {
		Map<String, Object> valeurs = new LinkedHashMap<>();
		valeurs.put("nom", u.getNom());
		valeurs.put("prenom", u.getPrenom());
		valeurs.put("email", u.getEmail());
		valeurs.put("telephone", u.getTelephone());
		valeurs.put("adresse", u.getAdresse());
		valeurs.put("statut", u.getStatut());
		valeurs.put("roles", u.getRoles().stream().map(Role::getCode).sorted().toList());
		valeurs.put("emplacements", u.getEmplacements().stream().map(Emplacement::getCode).sorted().toList());
		return valeurs;
	}

}
