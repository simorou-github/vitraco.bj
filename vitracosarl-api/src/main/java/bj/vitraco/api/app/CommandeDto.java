package bj.vitraco.api.app;

import bj.vitraco.api.adm.utilisateurs.ActeurDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Les prix et les montants ne sont envoyés qu'à qui les saisit ou a la permission « Voir prix d'achat » (EF-CAT-03).
 * Le coût de revient provisoire cumule la marchandise et les frais d'importation non annulés de tous les arrivages ; sa
 * répartition par châssis (méthode à fixer avec VITRACO) relève de la clôture du dossier. Les quantités, elles, restent visibles du magasin, qui prépare la réception.
 */
public record CommandeDto(
		Long id,
		String numero,
		Fournisseur fournisseur,
		LocalDate dateCommande,
		String devise,
		BigDecimal tauxChange,
		Incoterm incoterm,
		BasePrix basePrix,
		StatutCommande statut,
		String statutLibelle,
		String referenceProforma,
		String observation,
		List<Ligne> lignes,
		long totalCommande,
		long totalRecu,
		boolean prixVisibles,
		BigDecimal totalDevise,
		Long totalFcfa,
		Long fraisFcfa,
		Long coutRevientProvisoireFcfa,
		Long coutMoyenMotoFcfa,
		Integer fraisSansPiece,
		int nbArrivages,
		StatutCommande prochainStatut,
		StatutCommande statutPrecedent,
		boolean annulable,
		boolean modifiable,
		List<Etape> historique,
		Instant creeLe,
		ActeurDto creePar) {

	public record Fournisseur(Long id, String raisonSociale, String pays) {
	}

	/** Reliquat = commandé − reçu ; négatif quand un surplus a été accepté (EF-APP-08). */
	public record Ligne(Long id, Long modeleId, String modele, String codeArticle, int quantite, int quantiteRecue, int reliquat,
						BigDecimal prixUnitaire, BigDecimal montantDevise, Long montantFcfa) {
	}

	public record Etape(String type, StatutCommande statutAvant, StatutCommande statutApres, String motif, String auteur, Instant date) {
	}

}
