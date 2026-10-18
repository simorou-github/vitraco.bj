package bj.vitraco.api.adm.emplacements;

/** Référence courte à un emplacement, pour les listes et les fiches qui le mentionnent. */
public record EmplacementRef(Long id, String code, String nom, TypeEmplacement type) {

	public static EmplacementRef de(Emplacement e) {
		return e == null ? null : new EmplacementRef(e.getId(), e.getCode(), e.getNom(), e.getType());
	}

}
