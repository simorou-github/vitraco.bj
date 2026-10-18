package bj.vitraco.api.rec;

import java.time.Instant;
import java.util.List;

/**
 * Une réception d'arrivage. En brouillon, le rapprochement se calcule en direct (attendu, reçu, écart par modèle, motos
 * manquantes) ; une fois validée, il est figé pour que le PV reste le même (EF-REC-04, EF-REC-08).
 */
public record ReceptionDto(
		Long id,
		String numero,
		String statut,
		Arrivage arrivage,
		Emplacement emplacement,
		String observation,
		List<ResumeModele> resume,
		List<Ligne> lignes,
		List<String> manquants,
		int nbAttendus,
		int nbRecus,
		int nbBloques,
		int nbInattendus,
		boolean validable,
		String creePar,
		Instant creeLe,
		String validePar,
		Instant valideLe) {

	public record Arrivage(Long id, String numeroBl, Long commandeId, String commandeNumero, String fournisseur) {
	}

	public record Emplacement(Long id, String code, String nom) {
	}

	/** Écart = reçu − attendu : négatif pour une moto manquante, positif pour un surplus. */
	public record ResumeModele(Long modeleId, String codeArticle, String libelle, int attendu, int recu, int ecart) {
	}

	public record Ligne(Long id, String numero, Long modeleId, String codeArticle, String modele, String nature, boolean endommage, String motif,
						String auteur, Instant date) {
	}

	/** Ligne de la liste des réceptions. */
	public record Liste(Long id, String numero, String statut, String numeroBl, String commandeNumero, String emplacement, int nbLignes, Instant creeLe,
						Instant valideLe) {
	}

	/** Un arrivage qui attend sa réception au magasin. */
	public record ArrivageAReceptionner(Long arrivageId, String numeroBl, Long commandeId, String commandeNumero, String fournisseur, int restant,
										boolean avecColisage, Long receptionEnCoursId, String receptionEnCoursNumero) {
	}

}
