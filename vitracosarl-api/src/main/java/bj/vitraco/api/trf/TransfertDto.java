package bj.vitraco.api.trf;

import java.time.Instant;
import java.util.List;

/** Un transfert entre deux emplacements VITRACO (SRS 5.7) : création sans mouvement, expédition = sortie, réception = entrée. */
public record TransfertDto(
		Long id,
		String numero,
		String statut,
		String statutLibelle,
		Emplacement source,
		Emplacement destination,
		String observation,
		String retourDe,
		List<Ligne> lignes,
		int nbChassis,
		int nbRecus,
		List<String> manquants,
		String creePar,
		Instant creeLe,
		String expediePar,
		Instant expedieLe,
		Instant receptionneLe,
		String annulePar,
		Instant annuleLe,
		String motifAnnulation,
		Instant ecartSignaleLe,
		/** Expédié depuis plus longtemps que le délai paramétré sans être réceptionné en entier. */
		boolean enRetard,
		long joursEnTransit,
		String anomalie,
		Droits droits) {

	public record Emplacement(Long id, String code, String nom) {
	}

	public record Ligne(Long id, Long chassisId, String numero, String modele, String codeArticle, boolean recu, Instant recuLe, String recuPar) {
	}

	/** Ce que la personne connectée peut faire de ce transfert, dans l'état où il se trouve. */
	public record Droits(boolean modifier, boolean expedier, boolean receptionner, boolean annuler, boolean bon) {
	}

	public record Liste(Long id, String numero, String statut, String statutLibelle, String source, String destination, int nbChassis, int nbRecus,
						Instant creeLe, Instant expedieLe, boolean enRetard) {
	}

	/** Un numéro refusé à la réception : pas dans ce transfert, déjà reçu ou inconnu. Il est signalé, jamais accepté. */
	public record Refus(String numero, String motif) {
	}

	public record ResultatReception(TransfertDto transfert, int recus, List<Refus> refuses) {
	}

	/** Un châssis que l'on peut mettre dans un transfert depuis cette source. */
	public record Transferable(Long id, String numero, String modele, String codeArticle, Long dateEntreeJours) {
	}

}
