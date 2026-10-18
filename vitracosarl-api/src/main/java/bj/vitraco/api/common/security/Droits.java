package bj.vitraco.api.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Permissions de la personne connectée, pour les règles métier qui dépendent d'un droit particulier. */
public final class Droits {

	private Droits() {
	}

	public static boolean aPermission(String permission) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(permission));
	}

}
