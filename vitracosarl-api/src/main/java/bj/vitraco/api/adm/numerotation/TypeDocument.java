package bj.vitraco.api.adm.numerotation;

/** Types de documents numérotés automatiquement, avec leur préfixe (EF-ADM-07). */
public enum TypeDocument {

	COMMANDE_FOURNISSEUR("PO"),
	RECEPTION("BR"),
	TRANSFERT("TR"),
	COMMANDE_CLIENT("CMD"),
	LIVRAISON("BL"),
	FACTURE_VENTE("FV"),
	TICKET("TK"),
	AVOIR("FA"),
	RECU_REGLEMENT("RC"),
	ATTRIBUTION_DEPOT("AD"),
	INVENTAIRE("INV"),
	AJUSTEMENT("AJ"),
	ANOMALIE("ANO"),
	DEPENSE("DEP"),
	CLIENT("CLI");

	private final String prefixe;

	TypeDocument(String prefixe) {
		this.prefixe = prefixe;
	}

	public String prefixe() {
		return prefixe;
	}

}
