package bj.vitraco.api.app;

/** Les frais d'un arrivage (EF-APP-03). */
public enum TypeFrais {
	FRET("Fret"),
	ASSURANCE("Assurance"),
	DOUANE("Douane"),
	DROITS_TAXES("Droits et taxes"),
	TRANSIT("Transit"),
	PORT("Port"),
	AUTRE("Autre");

	private final String libelle;

	TypeFrais(String libelle) {
		this.libelle = libelle;
	}

	public String libelle() {
		return libelle;
	}

}
