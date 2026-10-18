package bj.vitraco.api.app;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Le dossier d'un arrivage. L'état est dérivé des dates et du dédouanement. Les frais ne sont envoyés qu'à qui saisit les
 * commandes ou a « Voir prix d'achat » (EF-CAT-03) : ce sont des éléments du coût de revient.
 */
public record ArrivageDto(
		Long id,
		Long commandeId,
		String commandeNumero,
		String fournisseur,
		String numeroBl,
		String conteneur,
		String port,
		LocalDate dateDepartPrevue,
		LocalDate dateDepartReelle,
		LocalDate dateArriveePrevue,
		LocalDate dateArriveeReelle,
		StatutDouane statutDouane,
		String statutDouaneLibelle,
		String etat,
		String etatLibelle,
		long retardJours,
		String observation,
		long nbChassis,
		List<ChassisAnnonce> chassis,
		boolean fraisVisibles,
		List<Frais> frais,
		Long totalFraisFcfa,
		Integer fraisSansPiece,
		List<Import> imports,
		Instant creeLe) {

	public record ChassisAnnonce(Long id, String numero, String codeArticle, String statut, String statutLibelle) {
	}

	public record Frais(Long id, TypeFrais type, String typeLibelle, String libelle, BigDecimal montant, String devise, BigDecimal tauxChange,
						long montantFcfa, String referencePiece, boolean sansPiece, LocalDate dateFrais, boolean annule, String motifAnnulation) {
	}

	public record Import(Long id, String nomFichier, int nbLignes, int nbImportees, int nbRejetees, List<Rejet> rejets, String auteur, Instant date) {
	}

	/** Ligne du fichier refusée, avec sa raison : numéro de ligne tel qu'on le voit dans le tableur. */
	public record Rejet(int ligne, String numero, String motif) {
	}

}
