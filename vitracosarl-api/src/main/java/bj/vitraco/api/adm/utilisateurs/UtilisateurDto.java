package bj.vitraco.api.adm.utilisateurs;

import bj.vitraco.api.adm.droits.Permission;
import bj.vitraco.api.adm.droits.Role;
import bj.vitraco.api.adm.emplacements.Emplacement;
import bj.vitraco.api.adm.emplacements.EmplacementRef;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public record UtilisateurDto(
		Long id,
		String nom,
		String prenom,
		String email,
		String telephone,
		String adresse,
		StatutUtilisateur statut,
		boolean verrouille,
		List<String> roles,
		List<String> permissions,
		boolean perimetreGlobal,
		boolean a2fActive,
		boolean a2fRequise,
		List<EmplacementRef> emplacements,
		Instant derniereConnexion,
		Instant creeLe,
		ActeurDto creePar) {

	public static UtilisateurDto de(Utilisateur u) {
		return new UtilisateurDto(u.getId(), u.getNom(), u.getPrenom(), u.getEmail(), u.getTelephone(), u.getAdresse(),
				u.getStatut(), u.estVerrouille(Instant.now()),
				u.getRoles().stream().map(Role::getCode).sorted().toList(),
				permissionsDe(u),
				u.aPerimetreGlobal(),
				u.a2fActive(),
				u.a2fRequise(),
				u.getEmplacements().stream().sorted(Comparator.comparing(Emplacement::getCode)).map(EmplacementRef::de).toList(),
				u.getDerniereConnexion(), u.getCreeLe(), ActeurDto.de(u.getCreePar()));
	}

	public static List<String> permissionsDe(Utilisateur u) {
		return u.getRoles().stream()
				.flatMap(r -> r.getPermissions().stream())
				.map(Permission::getCode)
				.distinct()
				.sorted(Comparator.naturalOrder())
				.toList();
	}

}
