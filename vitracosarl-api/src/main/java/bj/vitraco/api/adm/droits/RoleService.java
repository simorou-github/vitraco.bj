package bj.vitraco.api.adm.droits;

import bj.vitraco.api.adm.auth.SessionsUtilisateurs;
import bj.vitraco.api.adm.droits.RoleDto.PermissionDto;
import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.notif.DemandeNotification;
import bj.vitraco.api.notif.Notifications;
import bj.vitraco.api.common.ErreurValidationException;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Rôles et droits par action (EF-ADM-03). Toute modification est tracée avec ses valeurs avant et après. */
@Service
public class RoleService {

	private final RoleRepository roles;
	private final PermissionRepository permissions;
	private final UtilisateurRepository utilisateurs;
	private final SessionsUtilisateurs sessions;
	private final JournalAudit journal;
	private final Notifications notifications;

	RoleService(RoleRepository roles, PermissionRepository permissions, UtilisateurRepository utilisateurs,
				SessionsUtilisateurs sessions, JournalAudit journal, Notifications notifications) {
		this.roles = roles;
		this.permissions = permissions;
		this.utilisateurs = utilisateurs;
		this.sessions = sessions;
		this.journal = journal;
		this.notifications = notifications;
	}

	@Transactional(readOnly = true)
	public Page<RoleDto> lister(Pageable pagination) {
		return roles.findAll(pagination).map(this::versDto);
	}

	@Transactional(readOnly = true)
	public RoleDto detail(Long id) {
		return versDto(trouver(id));
	}

	@Transactional(readOnly = true)
	public List<PermissionDto> toutesLesPermissions() {
		return permissions.findAllByOrderByModuleAscActionAsc().stream().map(PermissionDto::de).toList();
	}

	@Transactional
	public RoleDto creer(RoleRequete requete) {
		if (requete.code() == null) {
			throw new ErreurValidationException("code", "Le code est requis.");
		}
		if (roles.existsByCode(requete.code())) {
			throw new ErreurValidationException("code", "Ce code de rôle est déjà utilisé.");
		}
		Role role = new Role(requete.code(), requete.libelle().trim(), vide(requete.description()), false);
		role.getPermissions().addAll(permissionsDe(requete.permissions()));
		role.setPerimetreGlobal(Boolean.TRUE.equals(requete.perimetreGlobal()));
		role.setDoubleAuthentificationRequise(Boolean.TRUE.equals(requete.doubleAuthentificationRequise()));
		ActeurAuthentifie.courant().flatMap(a -> utilisateurs.findById(a.id())).ifPresent(role::setCreePar);
		roles.saveAndFlush(role);

		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "role.cree", "Role", String.valueOf(role.getId()))
				.avecValeurs(null, resume(role)));
		return versDto(role);
	}

	@Transactional
	public RoleDto modifier(Long id, RoleRequete requete) {
		Role role = trouver(id);
		Map<String, Object> avant = resume(role);

		role.setLibelle(requete.libelle().trim());
		role.setDescription(vide(requete.description()));
		if (requete.perimetreGlobal() != null) {
			role.setPerimetreGlobal(requete.perimetreGlobal());
		}
		if (requete.doubleAuthentificationRequise() != null) {
			role.setDoubleAuthentificationRequise(requete.doubleAuthentificationRequise());
		}
		Set<Permission> nouvelles = permissionsDe(requete.permissions());
		boolean droitsChanges = !nouvelles.equals(role.getPermissions());
		Set<String> anciens = role.getPermissions().stream().map(Permission::getCode).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
		role.getPermissions().clear();
		role.getPermissions().addAll(nouvelles);
		roles.saveAndFlush(role);

		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, droitsChanges ? "role.droits_modifies" : "role.modifie", "Role", String.valueOf(id))
				.avecValeurs(avant, resume(role)));
		if (droitsChanges) {
			Set<String> apres = role.getPermissions().stream().map(Permission::getCode).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
			Set<String> ajoutes = new java.util.TreeSet<>(apres);
			ajoutes.removeAll(anciens);
			Set<String> retires = new java.util.TreeSet<>(anciens);
			retires.removeAll(apres);
			String auteur = ActeurAuthentifie.courant().map(ActeurAuthentifie::libelle).orElse("Le système");
			notifications.notifierRole("direction", DemandeNotification.de("droits.modifies", "Droits du rôle modifiés : " + role.getLibelle(),
					auteur + " a modifié les droits du rôle « " + role.getLibelle() + " ».\nAjoutés : "
							+ (ajoutes.isEmpty() ? "aucun" : String.join(", ", ajoutes)) + ".\nRetirés : "
							+ (retires.isEmpty() ? "aucun" : String.join(", ", retires)) + ".", "/utilisateurs/roles/" + id));
			// Les droits sont figés dans la session : on la ferme pour qu'ils s'appliquent à la prochaine connexion.
			sessions.fermerApresValidation(utilisateurs.emailsDuRole(id));
		}
		return versDto(role);
	}

	@Transactional
	public void supprimer(Long id) {
		Role role = trouver(id);
		if (role.isSysteme()) {
			throw new ErreurMetierException("ROLE_SYSTEME", "Un rôle livré par défaut ne peut pas être supprimé.");
		}
		if (utilisateurs.countByRolesId(id) > 0) {
			throw new ErreurMetierException("ROLE_UTILISE", "Ce rôle est attribué à des utilisateurs et ne peut pas être supprimé.");
		}
		Map<String, Object> avant = resume(role);
		roles.delete(role);
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "role.supprime", "Role", String.valueOf(id)).avecValeurs(avant, null));
	}

	private Role trouver(Long id) {
		return roles.findById(id)
				.orElseThrow(() -> new ErreurMetierException("ROLE_INTROUVABLE", "Rôle introuvable.", HttpStatus.NOT_FOUND));
	}

	private Set<Permission> permissionsDe(List<String> codes) {
		Set<String> demandes = new HashSet<>(codes);
		List<Permission> trouvees = permissions.findByCodeIn(demandes);
		Set<String> connues = trouvees.stream().map(Permission::getCode).collect(Collectors.toSet());
		demandes.stream().filter(c -> !connues.contains(c)).findFirst().ifPresent(c -> {
			throw new ErreurValidationException("permissions", "Permission inconnue : " + c + ".");
		});
		return new HashSet<>(trouvees);
	}

	private RoleDto versDto(Role role) {
		return RoleDto.de(role, utilisateurs.countByRolesId(role.getId()));
	}

	private static Map<String, Object> resume(Role role) {
		return Map.of(
				"code", role.getCode(),
				"libelle", role.getLibelle(),
				"description", role.getDescription() == null ? "" : role.getDescription(),
				"perimetre_global", role.isPerimetreGlobal(),
				"double_authentification_requise", role.isDoubleAuthentificationRequise(),
				"permissions", role.getPermissions().stream().map(Permission::getCode).sorted().toList());
	}

	private static String vide(String valeur) {
		return valeur == null || valeur.isBlank() ? null : valeur.trim();
	}

}
