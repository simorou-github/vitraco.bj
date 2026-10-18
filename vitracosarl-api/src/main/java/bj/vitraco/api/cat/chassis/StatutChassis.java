package bj.vitraco.api.cat.chassis;

/** Les 12 états du cycle de vie du châssis (SRS 3.4). Seul le service du châssis change un statut. */
public enum StatutChassis {
	EN_TRANSIT_FOURNISSEUR("En transit fournisseur"),
	A_RECEPTIONNER("À réceptionner"),
	DISPONIBLE("Disponible"),
	RESERVEE("Réservée"),
	EN_PREPARATION("En préparation"),
	LIVREE("Livrée"),
	EN_TRANSIT_INTERNE("En transit interne"),
	DEPOT_VENTE("Dépôt-vente"),
	VENDUE_DEPOT_VENTE("Vendue en dépôt-vente"),
	RETOURNEE("Retournée"),
	BLOQUEE("Bloquée"),
	SORTIE_PAR_AJUSTEMENT("Sortie par ajustement");

	private final String libelle;

	StatutChassis(String libelle) {
		this.libelle = libelle;
	}

	public String libelle() {
		return libelle;
	}

	/** Pas encore reçue : la moto n'est dans aucun emplacement et n'entre pas dans le stock. */
	public boolean enCoursImportation() {
		return this == EN_TRANSIT_FOURNISSEUR || this == A_RECEPTIONNER;
	}

}
