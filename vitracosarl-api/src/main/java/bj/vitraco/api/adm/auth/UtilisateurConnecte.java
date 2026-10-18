package bj.vitraco.api.adm.auth;

import bj.vitraco.api.common.security.ActeurAuthentifie;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;

/**
 * Identité conservée en session. Les permissions y sont figées à la connexion : toute
 * modification de droits ou suspension ferme donc les sessions concernées.
 */
public final class UtilisateurConnecte implements UserDetails, ActeurAuthentifie {

	private static final long serialVersionUID = 1L;

	private final Long id;
	private final String email;
	private final String libelle;
	private final Set<SimpleGrantedAuthority> autorites;

	public UtilisateurConnecte(Long id, String email, String libelle, Set<SimpleGrantedAuthority> autorites) {
		this.id = id;
		this.email = email;
		this.libelle = libelle;
		this.autorites = autorites;
	}

	@Override
	public Long id() {
		return id;
	}

	@Override
	public String libelle() {
		return libelle;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return autorites;
	}

	@Override
	public String getPassword() {
		return null;
	}

	@Override
	public String getUsername() {
		return email;
	}

}
