package bj.vitraco.api.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** Personne connectée, vue par les modules qui tracent ou signent une opération (journal, créé par…). */
public interface ActeurAuthentifie {

	Long id();

	String libelle();

	static Optional<ActeurAuthentifie> courant() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		return auth != null && auth.getPrincipal() instanceof ActeurAuthentifie a ? Optional.of(a) : Optional.empty();
	}

}
