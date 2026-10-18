package bj.vitraco.api.cat.chassis;

/**
 * Demande de création d'un châssis par un autre module : l'import du colisage (EN_TRANSIT_FOURNISSEUR, module APP) ou
 * la réception d'une moto non annoncée (DISPONIBLE, ou BLOQUEE si elle arrive endommagée, module REC). Il n'existe pas de saisie directe d'un châssis.
 */
public record CreationChassis(
		String numeroSaisi,
		Long modeleId,
		StatutChassis statutInitial,
		Long emplacementId,
		Long arrivageId,
		Long receptionId,
		Long coutRevient,
		String numeroMoteur,
		String couleur,
		String documentType,
		Long documentId,
		String motif) {

	/** Sans motif : le cas courant (colisage, réception conforme). */
	public CreationChassis(String numeroSaisi, Long modeleId, StatutChassis statutInitial, Long emplacementId, Long arrivageId, Long receptionId,
						   Long coutRevient, String numeroMoteur, String couleur, String documentType, Long documentId) {
		this(numeroSaisi, modeleId, statutInitial, emplacementId, arrivageId, receptionId, coutRevient, numeroMoteur, couleur, documentType, documentId, null);
	}

}
