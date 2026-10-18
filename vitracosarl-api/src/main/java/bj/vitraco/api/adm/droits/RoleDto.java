package bj.vitraco.api.adm.droits;

import bj.vitraco.api.adm.utilisateurs.ActeurDto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public record RoleDto(Long id, String code, String libelle, String description, boolean systeme, boolean perimetreGlobal, boolean doubleAuthentificationRequise,
					  List<PermissionDto> permissions, long nbUtilisateurs, Instant creeLe, ActeurDto creePar) {

	public record PermissionDto(Long id, String code, String libelle, String module, String action) {

		public static PermissionDto de(Permission p) {
			return new PermissionDto(p.getId(), p.getCode(), p.getLibelle(), p.getModule(), p.getAction());
		}

	}

	public static RoleDto de(Role r, long nbUtilisateurs) {
		return new RoleDto(r.getId(), r.getCode(), r.getLibelle(), r.getDescription(), r.isSysteme(), r.isPerimetreGlobal(), r.isDoubleAuthentificationRequise(),
				r.getPermissions().stream().sorted(Comparator.comparing(Permission::getCode)).map(PermissionDto::de).toList(),
				nbUtilisateurs, r.getCreeLe(), ActeurDto.de(r.getCreePar()));
	}

}
