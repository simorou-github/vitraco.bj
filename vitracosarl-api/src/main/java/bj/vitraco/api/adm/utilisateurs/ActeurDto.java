package bj.vitraco.api.adm.utilisateurs;

/** Personne désignée par une trace (« créé par »). */
public record ActeurDto(Long id, String nom, String prenom) {

	public static ActeurDto de(Utilisateur u) {
		return u == null ? null : new ActeurDto(u.getId(), u.getNom(), u.getPrenom());
	}

}
